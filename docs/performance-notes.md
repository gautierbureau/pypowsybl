# Performance notes

Developer-facing inventory of performance opportunities identified across the
Python layer (`pypowsybl/`), the C++/pybind11 binding layer (`cpp/`) and the
Java/GraalVM layer (`java/`).

This is a working roadmap, not user documentation. Items are grouped into
**Implemented** (already on this branch) and **Outstanding** (found but not yet
done). Each outstanding item lists where it lives and a suggested fix so it can
be picked up independently.

All implemented items were validated against a locally built native library
(GraalVM 21 native-image + the C++ extension) with the test suite. Two optional
test modules (`test_pandapower`, `test_opf`) could not run in that environment
for reasons unrelated to these changes (a broken pandapower transitive
dependency and a missing IPOPT solver, respectively).

## Implemented

| Area | Change | Where |
|------|--------|-------|
| grid2op | `ensureTopoVectIsUpToDate()` clears `topoChanges` after applying them (was replaying the whole history every call → O(steps²) per episode) | `java/.../grid2op/Backend.java` |
| grid2op | `runLoadFlow()` no longer runs `checkIsolatedAndDisconnectedInjections()` only to discard the result (a full injection scan per step) | `java/.../grid2op/Backend.java` |
| grid2op | `computeTopoVectPosition()` precomputes a prefix-sum once instead of re-summing per element (O(elements × voltage levels) → O(elements + voltage levels)) | `java/.../grid2op/Backend.java` |
| Dataframes | node-breaker internal connections indexed by position instead of `List.indexOf()` per element (O(n²) → O(n)) | `java/.../network/Dataframes.java` |
| Python | series metadata accessors memoized with `lru_cache` (metadata is static per type but was re-fetched over FFI on every get/update/create) | `pypowsybl/utils/impl/util.py` + call sites in `network.py`, `network_element_modification_util.py` |
| Python | grid2op `Backend.run_pf` caches the default C `LoadFlowParameters` instead of rebuilding it (a native round trip into Java) every step | `pypowsybl/grid2op/impl/backend.py` |
| C++ | log level cached in an atomic; the native `setLogLevel` transition is skipped when unchanged; GIL acquired before reading the logger | `cpp/pypowsybl-cpp/bindings.cpp` |
| C++ | `PowsyblCaller::get()` and `CppToPythonLogger::get()` are Meyers singletons (no per-call mutex lock) | `cpp/powsybl-cpp/powsybl-cpp.cpp`, `cpp/pypowsybl-cpp/pylogging.cpp` |
| C++ (correctness) | `callJava` initializes `exception_handler::message` (was zeroed only as a side effect of the pre-hook's Java call; skipping that call exposed a wild-pointer read for callees not routed through `doCatch`, e.g. `freeString`) | `cpp/powsybl-cpp/powsybl-cpp.h` |
| C++ | `createDataframe` bulk-copies numeric numpy columns with `memcpy` instead of an element-by-element cast to `std::vector` plus a second copy; textual/wrong-type inputs still fall through to the strict cast | `cpp/pypowsybl-cpp/bindings.cpp` |
| C++ (correctness) | zero-copy `Series.data`/`Series.mask` numpy views anchor their `base` to the owning Series object so the `SeriesArray` buffer cannot be freed while a view is alive (`mask` previously used `py::cast` of a raw `int*`, anchoring nothing) | `cpp/pypowsybl-cpp/bindings.cpp` |
| C++/Java | string series reduced from ~3 copies to ~1: `Series.data` builds the Python list of str directly from the `char**` (one PyUnicode per element) instead of via `std::vector<std::string>`; `toVector<std::string>` emplaces in place; `CTypeUtil.toCharPtr`/`toBytePtr` bulk-copy the UTF-8 bytes with `ByteBuffer.put` instead of a byte-at-a-time loop | `cpp/pypowsybl-cpp/bindings.cpp`, `cpp/powsybl-cpp/powsybl-cpp.cpp`, `java/.../commons/CTypeUtil.java` |
| Python | `_create_c_dataframe` passes index arrays as numpy (no `list()` boxing) and detects bool via `dtype`; the kwargs write/read paths (`update_*`, `update_extensions`, `remove_aliases`/`remove_internal_connections`, `get_*(id=...)`) build the C dataframe directly from named arguments via `_create_c_dataframe_from_kwargs`/`_get_c_dataframe`, skipping the intermediate pandas DataFrame (an Index build + block-consolidation copy) | `pypowsybl/utils/impl/dataframes.py`, `pypowsybl/network/impl/network.py` |

## Outstanding

### High / medium value

- **grid2op `get_*_value` copies zero-copy buffers.** The C++ layer returns
  zero-copy memoryviews, but `pypowsybl/grid2op/impl/backend.py` wraps them in
  `np.array(...)` (a copy) on every read; a step reads ~20 value types. Fix:
  `np.asarray` view or cache the view per value type — needs a decision on
  aliasing semantics (the backend buffer is rewritten in place).

- **Default loadflow params rebuilt per call (non-grid2op).**
  `loadflow.run_ac/run_dc` (`pypowsybl/loadflow/impl/loadflow.py`) construct a
  fresh C `LoadFlowParameters` each call; Java-side `loadSpecificParameters`
  (`java/.../loadflow/LoadFlowCUtils.java`) re-runs per call. Same idea as the
  grid2op fix, applied to the general loadflow entry points.

- **`NetworkUtil.getBusViewBus` O(B²) fallback.** For node-breaker voltage
  levels with disconnected sections, the fallback streams all bus-view buses per
  bus (`java/.../network/NetworkUtil.java`, used by `NetworkDataframes.buses(true)`
  and `Dataframes.getBusBreakerViewBuses`). Fix: precompute one
  `busBreakerBusId → busViewBus` map per voltage level.

- **`get_elements` double column slice.** After the native layer already
  filtered columns, `network.py` re-slices `result[attributes]`, copying the
  whole frame again just to enforce order. Fix: skip the copy when columns are
  already in the requested order.

- **Sensitivity matrix post-processing.**
  `pypowsybl/sensitivity/impl/sensitivity_analysis_result.py` runs a Python row
  loop and an unconditional full-copy `df.drop` per contingency even when there
  is nothing to drop. Fix: precompute whether any `TO_REMOVE` rows exist; if
  none, return the frame untouched; otherwise vectorize.

- **Per-element FFI contingency registration.**
  `pypowsybl/security/impl/contingency_container.py` and
  `pypowsybl/flowdecomposition/impl/flowdecomposition.py` call
  `add_contingency` once per element; N-1 screening registers thousands. Needs a
  batched native entry point.

- **GraalVM thread attach/detach per call.** `GraalVmGuard`
  (`cpp/powsybl-cpp/powsybl-cpp.cpp`) attaches and detaches the isolate on every
  call from a non-main thread (e.g. a `ThreadPoolExecutor` running loadflows).
  Fix: cache the attachment in a `thread_local` that detaches at thread exit.

### Lower value

- OPF model build/write-back uses `iterrows()` in ~10 places where `itertuples`
  or vectorization would do, and evaluates `logger.log(TRACE, f"...")` per row
  even when TRACE is disabled (`pypowsybl/opf/impl/model/*.py`).
- `AbstractDataframeMapper` allocates a capturing lambda per row in the update
  loop; `UpdatingDataframe` getters allocate an `Optional` per row
  (`java/.../dataframe/`).
- `DataframeFilter` uses `List.contains` per column for attribute filtering;
  wrap the attributes in a `HashSet`.
- `CTypeUtil.toStringMap` boxes indices through a stream; use a pre-sized
  `HashMap`.
- `Util.createDoubleArray`/`createIntegerArray` and the sensitivity
  `doubleArrToMatrix` write element-by-element and box through `List<Double>`;
  bulk-copy via buffers and take primitive arrays.
- `SimulationResult` (dynamic) converts timestamps element-wise with
  `index.map(lambda x: pd.Timestamp(x))`; use `pd.to_datetime`.
- `NodeBreakerTopology.create_graph` uses `iterrows`; build node/edge lists from
  column arrays like `BusBreakerTopology.create_graph` already does.
- pandapower converter uses `df.apply(..., axis=1)` for ID strings and a
  per-generator FFI loop; vectorize the string build and batch the FFI call.
- `SecurityAnalysisResult.__init__` eagerly materializes the violations
  DataFrame even when the caller only reads `post_contingency_results`; make it
  a lazy property.
- C++ parameter-map loops copy each pair by value at ~8 sites; use
  `const auto&`. `arrayToStringVectorVector` / `convertDataframeMetadata` miss
  `reserve()` / `std::move`.

## Notes

- Fast C++-only rebuild: the native library (`libpypowsybl-java.so`) only needs
  rebuilding when Java changes. For C++-only changes, configure a CMake build
  with `-DBUILD_PYPOWSYBL_JAVA=OFF` pointing at a pre-built native lib
  (`PYPOWSYBL_NATIVE_BUILD_DIR`, or the extracted `dist/binaries.zip`) and
  rebuild just the extension — seconds instead of minutes.
- Performance claims here are from code analysis, not yet from a benchmark
  harness. Quantifying the wins (grid2op stepping loop, large `update_*`, large
  `get_*`) is a natural next step and would help prioritize the outstanding
  items.
