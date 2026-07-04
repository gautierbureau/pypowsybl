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
| Python | attribute-filtered `get_*` only re-slices `result[attributes]` (a full copy) when the column order actually differs from what was requested | `pypowsybl/network/impl/network.py` |
| Python | `get_sensitivity_matrix`: fast path returns the matrix untouched when there are no zone-transfer (`TO_REMOVE`) rows, skipping a per-row Python loop and the unconditional full-matrix `df.drop` copy (called once per contingency) | `pypowsybl/sensitivity/impl/sensitivity_analysis_result.py` |
| Python | `SecurityAnalysisResult.limit_violations` built lazily on first access instead of eagerly in `__init__` (FFI + DataFrame) | `pypowsybl/security/impl/security_analysis_result.py` |
| Python | dynamic `SimulationResult` uses vectorized `pd.to_datetime` instead of a per-timestep `pd.Timestamp` map + 3-step index rebuild; `NodeBreakerTopology.create_graph` builds node/edge lists from column arrays via `zip` instead of `iterrows` | `pypowsybl/dynamic/impl/simulation_result.py`, `pypowsybl/network/impl/node_breaker_topology.py` |
| Python (OPF) | model build/write-back loops use `itertuples` instead of `iterrows` (no per-row Series); every per-row `logger.log(TRACE_LEVEL, f"...")` is now guarded by `logger.isEnabledFor(TRACE_LEVEL)` so the f-string is not built when TRACE is disabled | `pypowsybl/opf/impl/model/*.py`, `pypowsybl/opf/impl/bounds/*.py`, `pypowsybl/opf/impl/constraints/*.py` |
| Java | `get_bus_breaker_topology` precomputes the bus/breaker-bus-id -> bus-view-bus map once per voltage level (O(n)) instead of the per-bus `getBusViewBus` fallback scan (O(n^2) on node-breaker voltage levels with disconnected sections) | `java/.../network/NetworkUtil.java`, `java/.../network/Dataframes.java` |
| Java | `DataframeFilter` backs its input attributes with a `HashSet` (O(1) per-column filter check instead of `List.contains`); `AbstractDataframeMapper` update loop uses an indexed inner loop instead of `updaters.forEach(lambda)` (no capturing-lambda allocation per row); `CTypeUtil.toStringMap` fills a pre-sized `HashMap`; `doubleArrToMatrix` bulk-copies via a native-order `DoubleBuffer` | `java/.../dataframe/*.java`, `java/.../commons/CTypeUtil.java`, `java/.../sensitivity/SensitivityAnalysisResultContext.java` |
| Java+C+++Python | `ContingencyContainer.add_single_element_contingencies` registers all N-1 contingencies in a single native call (new `addSingleElementContingencies` entry point taking two parallel string arrays) instead of one `add_contingency` FFI call per element; `SecurityAnalysis`/`SensitivityAnalysis` inherit it | `java/.../security/SecurityAnalysisCFunctions.java`, `cpp/powsybl-cpp/*`, `cpp/pypowsybl-cpp/bindings.cpp`, `pypowsybl/security/impl/contingency_container.py` |
| C++ (concurrency) | `GraalVmGuard` keeps each worker thread attached to the isolate for its lifetime (thread_local, detach at thread exit) instead of attach/detach per call; dropped the redundant per-call `loggerMutex_` (GIL already serializes `logger_`); released the GIL for `run_loadflow_validation`, `run_voltage_initializer`, `update_network_from_binary_buffers` (long native calls that were blocking all Python threads); `run_loadflow_async` no longer releases the GIL (its `Py_INCREF` must run under the GIL) | `cpp/powsybl-cpp/powsybl-cpp.{cpp,h}`, `cpp/pypowsybl-cpp/pylogging.{cpp,h}`, `cpp/pypowsybl-cpp/bindings.cpp` |
| C++ (cleanups) | parameter-map loops iterate by `const auto&` instead of copying each `std::pair` per iteration (~8 sites); `arrayToStringVectorVector` reserves and `std::move`s its sublists; `convertDataframeMetadata` reserves and `emplace_back`s | `cpp/pypowsybl-cpp/bindings.cpp`, `cpp/powsybl-cpp/powsybl-cpp.cpp` |

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

- **`NetworkUtil.getBusViewBus` O(B²) fallback in `buses(bus_breaker_view=True)`.**
  The `get_bus_breaker_topology` caller is fixed (precomputed map), but the
  network-wide `NetworkDataframes.buses(true)` `bus_id` column
  (`java/.../network/NetworkDataframes.java`) still resolves each bus via
  `getBusViewBus`. Its connected-terminal fast path covers most buses, so the
  fallback only fires for buses with no connected terminal; fixing it cleanly
  needs a per-fetch precompute hook in the mapper (the column accessor is
  stateless).

- **Per-element FFI contingency registration in flow decomposition.**
  `ContingencyContainer.add_single_element_contingencies` is now batched, but
  `pypowsybl/flowdecomposition/impl/flowdecomposition.py` still calls
  `add_contingency_for_flow_decomposition` once per element. Needs a parallel
  batched entry point in the flow-decomposition C functions.

### Lower value

These were assessed and intentionally deferred - the gain does not justify the
churn or they cannot be validated here:

- `UpdatingDataframe.getStringValue`/`getIntValue` allocate an `Optional` per row.
  Removing that would change the interface and its ~40 diverse callers
  (`.orElse`, `.orElseThrow`, `.stream()`, ...) for a tiny short-lived
  allocation - high churn, low gain.
- `Util.createDoubleArray`/`createIntegerArray` box through `List<Double>` /
  `List<Integer>`. Only 4 call sites exist; 2 are trivial constants
  (`NetworkModificationsCFunctions` passes `Arrays.asList(min, max)` and
  `Collections.emptyList()`). The 2 real callers receive their `List<Double>`
  directly from powsybl-core APIs - `ProportionalScalable.computePercentages`
  and the GLSK importer's `getInjectionFactorForCountryTimeinterval` - so the
  boxing is inherent to those upstream signatures. A primitive `double[]`
  signature here would not remove it: the caller would just unbox the
  core-provided list itself, on small, cold arrays.
- pandapower converter uses `df.apply(..., axis=1)` for ID strings and a
  per-generator FFI loop; a Python-only change, but pandapower cannot be
  imported in this environment (a broken transitive Rust extension), so it
  cannot be validated here.

## Concurrency notes

A concurrency/lock-contention audit of the call path produced the C++ fixes
above. Remaining concurrency items, none of which are code bugs on the default
single-threaded usage:

- **Same-`Network` multithread contract (correctness footgun, docs-worthy).**
  `allow_variant_multi_thread_access` defaults to `False`. Two threads calling
  `run_loadflow`/`update_*`/reads on the **same** `Network` handle race on the
  shared working variant and its mutable state. The safe pattern for parallel
  computation is either one `Network` per thread/process (what grid2op does via
  pickling), or load with `allow_variant_multi_thread_access=True` and have each
  thread `clone_variant` + `set_working_variant` to its own variant before
  computing (that flag makes the *working-variant index* thread-local; it does
  not make concurrent mutation of the *same* variant safe). Worth documenting
  prominently in the network API.
- **grid2op `Backend` `_default_c_lf_params`** is mutated in place (`p.dc = dc`);
  safe for the intended one-`Backend`-per-thread/process usage, a race only if a
  single `Backend` is driven from multiple threads with different `dc`.
- **`PyPowsyblConfiguration` static fields** (`java/.../commons/`) are plain
  (non-`volatile`) statics; set once at startup in practice, but make them
  `volatile` if runtime reconfiguration from other threads is ever supported.
- **`CommonObjects.getComputationManager()`** shares one `LocalComputationManager`
  (and its ForkJoinPool) across concurrent analyses. Thread-safe, but a shared
  bounded pool caps throughput when many analyses offload work; a per-analysis
  computation manager would lift that ceiling.

Already verified thread-safe (do not touch): the two Meyers singletons, the
`lastPushedLogLevel` atomic (benign relaxed race), `GraalVmGuard` nested-call
detection, GIL release on all the major computations, the static
`DataframeMapper` registries (built once, read-only), and the `functools.lru_cache`
metadata memoization (CPython locks it internally).

## Build & runtime tuning (GraalVM native-image)

These are build- and deploy-time knobs, orthogonal to the code changes above -
they speed up (or slow down) the whole native library without touching the code.
Config lives in `cpp/pypowsybl-java/CMakeLists.txt`.

- **Optimization level.** `-Ob` (quick build, less optimized runtime) is applied
  only for `CMAKE_BUILD_TYPE=Debug`. Release builds (and the shipped wheels) use
  native-image's default `-O2` (full optimization) - so nothing is accidentally
  under-optimized. Do not benchmark a Debug build.
- **`-march`.** The build uses `-march=compatibility` so one wheel runs on any
  x86-64 CPU. For a known-hardware / on-prem build, `-march=native` lets
  native-image emit host-specific SIMD/AVX - a free speedup on numeric paths, at
  the cost of a non-portable binary. Build-time only.
- **Profile-Guided Optimization (PGO).** Not currently used. Oracle GraalVM can
  instrument the image, run a representative workload to collect a profile, then
  rebuild with `--pgo=<profile>`, typically yielding a further ~10-30% runtime
  improvement. It requires the two-pass profiling workflow and Oracle GraalVM
  (not GraalVM CE). This is likely the single biggest untapped runtime lever, but
  it is a distribution/build-pipeline decision, not a code change.
- **Garbage collector.** `--gc=G1` on Linux x86-64, `serial` elsewhere (G1 is
  only supported there). G1 is the throughput-oriented choice for large heaps.
- **Runtime heap/GC** can be tuned per-process via the `GRAALVM_OPTIONS`
  environment variable (e.g. `GRAALVM_OPTIONS="-Xmx4G"`), see
  `docs/user_guide/advanced_parameters.rst`. Matters for very large networks /
  memory pressure.

## Notes

- Fast C++-only rebuild: the native library (`libpypowsybl-java.so`) only needs
  rebuilding when Java changes. For C++-only changes, configure a CMake build
  with `-DBUILD_PYPOWSYBL_JAVA=OFF` pointing at a pre-built native lib
  (`PYPOWSYBL_NATIVE_BUILD_DIR`, or the extracted `dist/binaries.zip`) and
  rebuild just the extension — seconds instead of minutes.
- The implemented items were measured on the PEGASE 13k MATPOWER network
  (`case13659pegase`: 13,659 buses, 4,092 generators, 14,738 lines) against the
  pre-branch baseline, both full Release native builds. Representative medians:
  repeated small `update_*` in a loop ~11.5x faster (metadata cache), bulk
  kwargs `update_*` ~1.4x, string-heavy reads (`get_buses`/`get_2wt`/`get_lines`)
  ~1.2-1.3x; numeric-only reads gain ~1.05x. All measured operations improved,
  no regressions.
