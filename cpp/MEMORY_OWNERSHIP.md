# Native memory ownership across the Java ⇄ C++ bridge

## Background

pypowsybl runs powsybl-core as a GraalVM native image. C++ (`powsybl-cpp`) calls
into the Java isolate through `@CEntryPoint` functions. Several of those entry
points allocate **unmanaged** native memory on the Java side
(`UnmanagedMemory.malloc/calloc`, `allocArrayPointer`, structs such as `matrix`,
`array`, `*_metadata`) and return a raw pointer to C++.

That memory is **not** reclaimed by the JVM garbage collector. It must be freed
by calling back into a matching Java `free*` `@CEntryPoint`. Every memory leak we
have found in the bridge was a site that returned such a pointer and never wired
up the free.

The goal of the helpers below is to make "forgot to free" **unrepresentable**:
you cannot obtain the raw pointer without naming its deleter in the same
expression.

## The three ownership patterns

| Group | Where the memory lives | How it is freed | Helper |
|-------|------------------------|-----------------|--------|
| 1 | GraalVM isolate (Java `UnmanagedMemory`) | call back into a Java `free*` entry point; lifetime driven by Python (pybind11 holder) or a C++ scope | `callJavaOwned<T>` |
| 2 | GraalVM isolate | same, but consumed and freed inside a single C++ function | `JavaUnique<T, FreeFn>` |
| 3 | C++ heap (`new` in `to_c_struct()`) | free nested `char**` members, then `delete` the struct | `newCOwned<T>` |

`Array<T>` (`SeriesArray`, `PostContingencyResultArray`, …) is intentionally
**out of scope**: its destructors perform nested/iterated frees
(`freeSeriesArray`, `freeContingencyResultArrayPointer`) that a single generic
deleter cannot express. That per-type specialization is justified, not
duplication.

## The helpers (`powsybl-cpp.h`)

```cpp
// (1) JAVA-OWNED, shared. For values whose lifetime is driven by Python
// (pybind11 holders) or that you just want a scope-guarded handle for.
// The raw pointer never escapes — you can't get it without naming the free
// function in the same call, which is exactly what prevents the leaks.
class PowsyblCaller {
public:
    // ... existing callJava overloads ...

    template<typename T, typename F, typename FreeF, typename... ARGS>
    std::shared_ptr<T> callJavaOwned(F f, FreeF freeF, ARGS... args) {
        T* ptr = callJava<T*>(f, args...);
        if (ptr == nullptr) {
            return nullptr;
        }
        // NB: re-fetch the singleton inside the deleter — the free may run much
        // later, on the Python GC thread. Never capture `this`.
        return std::shared_ptr<T>(ptr, [freeF](T* p) {
            try {
                PowsyblCaller::get()->callJava(freeF, p);
            } catch (const std::exception& e) {
                // A throw escaping a shared_ptr deleter == std::terminate.
                // Java-side free already runs under doCatch; log and swallow here.
            }
        });
    }
};

// (2) JAVA-OWNED, unique. Zero-overhead (no control block) for the common case
// where the struct is consumed and freed inside one C++ function.
template<auto FreeFn>
struct JavaDeleter {
    template<typename T>
    void operator()(T* p) const {
        if (p) {
            try { PowsyblCaller::get()->callJava(FreeFn, p); } catch (...) {}
        }
    }
};
template<typename T, auto FreeFn>
using JavaUnique = std::unique_ptr<T, JavaDeleter<FreeFn>>;

// (3) C++-OWNED. For the to_c_struct() builders: the struct is `new`ed on the
// C++ heap and its nested char** members are freed by a per-type deleteMembers
// function, then the struct itself is `delete`d. Different heap from (1)/(2) —
// nothing calls back into Java.
template<typename T, typename DeleteMembersF>
std::shared_ptr<T> newCOwned(DeleteMembersF deleteMembers) {
    T* res = new T();                       // value-initialized (zeroed)
    return std::shared_ptr<T>(res, [deleteMembers](T* ptr) {
        deleteMembers(ptr);                 // free nested C-side allocations
        delete ptr;
    });
}
```

## Migration examples

### Group 1 — `callJavaOwned`

```cpp
// before — getSensitivityMatrix
matrix* m = PowsyblCaller::get()->callJava<matrix*>(::getSensitivityMatrix, ctx, matId, contId);
if (m == nullptr) return nullptr;
return std::shared_ptr<matrix>(m, [](matrix* p){
    PowsyblCaller::get()->callJava(::freeSensitivityMatrix, p);
});

// after
return PowsyblCaller::get()->callJavaOwned<matrix>(
    ::getSensitivityMatrix, ::freeSensitivityMatrix, ctx, matId, contId);
```

```cpp
// before — createLoadFlowParameters
LoadFlowParameters* createLoadFlowParameters() {
    loadflow_parameters* parameters_ptr = PowsyblCaller::get()->callJava<loadflow_parameters*>(::createLoadFlowParameters);
    auto parameters = std::shared_ptr<loadflow_parameters>(parameters_ptr, [](loadflow_parameters* ptr){
        PowsyblCaller::get()->callJava(::freeLoadFlowParameters, ptr);
    });
    return new LoadFlowParameters(parameters.get());
}

// after
LoadFlowParameters* createLoadFlowParameters() {
    auto parameters = PowsyblCaller::get()->callJavaOwned<loadflow_parameters>(
        ::createLoadFlowParameters, ::freeLoadFlowParameters);
    return new LoadFlowParameters(parameters.get());   // freed at scope exit
}
```

### Group 2 — `JavaUnique`

```cpp
// before — getSeriesMetadata
dataframe_metadata* metadata = PowsyblCaller::get()->callJava<dataframe_metadata*>(::getSeriesMetadata, elementType);
std::vector<SeriesMetadata> res = /* ... read metadata ... */;
PowsyblCaller::get()->callJava(::freeDataframeMetadata, metadata);   // easy to forget / not exception-safe
return res;

// after
JavaUnique<dataframe_metadata, ::freeDataframeMetadata> metadata{
    PowsyblCaller::get()->callJava<dataframe_metadata*>(::getSeriesMetadata, elementType) };
return /* ... read metadata.get() ... */;   // freed on return, even if the read throws
```

### Group 3 — `newCOwned`

```cpp
// before
std::shared_ptr<loadflow_parameters> LoadFlowParameters::to_c_struct() const {
    loadflow_parameters* res = new loadflow_parameters();
    load_to_c_struct(*res);                 // if this throws, res leaks
    return std::shared_ptr<loadflow_parameters>(res, [](loadflow_parameters* ptr){
        deleteLoadFlowParameters(ptr);
        delete ptr;
    });
}

// after
std::shared_ptr<loadflow_parameters> LoadFlowParameters::to_c_struct() const {
    auto res = newCOwned<loadflow_parameters>(deleteLoadFlowParameters);
    load_to_c_struct(*res);                 // res now freed even if this throws
    return res;
}
```

## Why do Group 3 even though it never leaked

1. **Exception safety it doesn't currently have.** Every `to_c_struct` today does
   `new T(); fill(*res);` on a *raw* pointer — if `load_to_c_struct` throws
   mid-fill, `res` leaks. Wrapping *before* filling closes that window. The
   `new T()` value-initialization means a partially-filled struct has null
   members, so `deleteMembers` on the throw path is safe (`deleteCharPtrPtr`
   handles null / zero count).
2. **One place for the ownership contract.** `deleteMembers(ptr); delete ptr;` is
   copy-pasted 11 times; centralizing it means the "free members, then the
   struct" ordering can't be gotten wrong in a new builder.

## Design notes / trade-offs

- **Never capture `this` in the shared deleter.** The free can run on the Python
  GC thread long after the call; the deleter re-fetches `PowsyblCaller::get()`.
  Same reasoning for `JavaDeleter`.
- **`template<auto FreeFn>` is C++17** (the project target), so `JavaUnique`
  compiles as-is; the member `operator()` is templated on `T` rather than using a
  C++20 `auto` parameter.
- **shared vs unique.** For the low-frequency parameter/metadata calls the
  control-block cost is negligible, so if you would rather keep a single helper,
  use `callJavaOwned` everywhere and drop `JavaUnique`. Keep `JavaUnique` only
  where the value is genuinely hot or clearly single-scope.
- **Throwing deleters.** All existing hand-written deleters call `callJava`,
  which throws `PyPowsyblError` if the Java side reports an exception. A throw
  propagating out of `shared_ptr`/`unique_ptr` destruction is `std::terminate`.
  The helpers wrap the free in `try/catch` once, for every site.

## Migration inventory

### Group 1 — Java-owned, deleter-style → `callJavaOwned` (16 sites)

`powsybl-cpp.cpp` (12):

| Line | Function | Free fn |
|------|----------|---------|
| 819  | `getNetworkMetadata` | `freeNetworkMetadata` |
| 955  | `createLoadFlowParameters` | `freeLoadFlowParameters` |
| 964  | `createRaoParameters` | `freeRaoParameters` |
| 974  | `createValidationConfig` | `freeValidationConfig` |
| 984  | `createSecurityAnalysisParameters` | `freeSecurityAnalysisParameters` |
| 996  | `createSensitivityAnalysisParametersFromCStruct` | `freeSensitivityAnalysisParameters` |
| 1004 | `createDynamicSimulationParameters` | `freeDynamicSimulationParameters` |
| 1619 | `createFlowDecompositionParameters` | `freeFlowDecompositionParameters` |
| 1745 | `createSldParameters` | `freeSldParameters` |
| 1754 | `createNadParameters` | `freeNadParameters` |
| 1954 | `createScalingParameters` | `freeScalingParameters` |
| 2032 | `createShortCircuitAnalysisParameters` | `freeShortCircuitAnalysisParameters` |

On the `fix_memory_leak_sensi` branch (4 — the leaks already fixed follow the
same idiom and should be migrated when it lands):

| Location | Function | Free fn |
|----------|----------|---------|
| powsybl-cpp.cpp | `getSensitivityMatrix` | `freeSensitivityMatrix` |
| powsybl-cpp.cpp | `getReferenceMatrix` | `freeSensitivityMatrix` |
| powsybl-cpp.cpp | `getPreContingencyResult` | `freePreContingencyResultPointer` |
| bindings.cpp | `loadRaoParametersFromBuffer` | `freeRaoParameters` |

### Group 2 — Java-owned, explicit-free-style → `JavaUnique` (optional cleanup)

| Line | Function | Free fn |
|------|----------|---------|
| 1426 | `getLimitReductionDataframeMetadata` | `freeDataframeMetadata` |
| 1433 | `getSeriesMetadata` | `freeDataframeMetadata` |
| 1444 | `getCreationMetadata` | `freeDataframesMetadata` |
| 1501 | `createLoadFlowParametersFromJson` | `freeLoadFlowParameters` |
| 1544 | `getExtensionSeriesMetadata` | `freeDataframeMetadata` |
| 1554 | `getExtensionsCreationMetadata` | `freeDataframesMetadata` |
| 1865 / 1872 / 1883 / 1893 | network-modification metadata getters | `freeDataframe(s)Metadata` |

### Group 3 — C++-owned `to_c_struct()` → `newCOwned` (11 sites)

`powsybl-cpp.cpp` lines 369, 512, 562, 596, 619, 651, 675, 1729, 1738, 1946,
1994 — each of the form `new T(); load_to_c_struct(*res);` with a
`deleteXxx(ptr); delete ptr;` deleter.

## Rollout

1. ~~Add the three helpers to `powsybl-cpp.h`.~~ done.
2. ~~Migrate one representative of each group as a proof commit~~ done:
   `createLoadFlowParameters` (group 1), `getNetworkDataframeMetadata`
   (group 2), `LoadFlowParameters::to_c_struct` (group 3).
3. Sweep the remaining sites group by group (inventory above).
