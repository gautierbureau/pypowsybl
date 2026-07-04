# Sensitivity Analysis Performance Work

This directory holds the running notes and upstream patches produced while
optimizing the sensitivity-analysis path in pypowsybl. The `.patch` files
under `powsybl-core/` and `powsybl-open-loadflow/` are `git format-patch`
outputs meant to be submitted upstream as PRs; the rest of this file
documents (a) the optimizations that landed on the pypowsybl branch
directly, (b) what is queued as an upstream patch, and (c) analysis of
opportunities that were investigated but not yet implemented, with the
trade-offs.

Motivating workload: **PEGASE ~13.6 k-bus network, ~2 M sensitivity
factors, DC sensitivity analysis, base case + contingencies**.

---

## Landed on `claude/sensitivity-analysis-optimization-uvp5ka`

### 1. Extract `MatrixFactorReader` and hoist per-row work

*File:* `java/pypowsybl/src/main/java/com/powsybl/python/sensitivity/SensitivityAnalysisContext.java`

The inline `SensitivityFactorReader factorReader = handler -> { ... }`
lambda in `run()` did two things worth separating:

1. Expand the pypowsybl "matrix" representation
   (`branchIds × variableIds × contingencies`) into a stream of individual
   factors delivered to the provider via `handler.onFactor(...)`.
2. For each `(rowVariableId, columnFunctionId, contingencyContext)` triple,
   resolve the `SensitivityVariableType` — either fixed on the matrix, or
   inferred via `network.getIdentifiable(variableId)`, or looked up in the
   variable-set map.

The per-row lookups were being performed inside the columns loop, so for a
matrix registered with `SensitivityVariableType.AUTO_DETECT` (the common
Python-side default) `network.getIdentifiable(variableId)` was called
`rows × columns` times per matrix instead of `rows`. For 2 M factors this
was millions of redundant `HashMap` lookups + `instanceof` chains,
compounded on the AC path (see "AC double-read" below).

Refactor:
- Introduced a private static nested class `MatrixFactorReader` with a
  docstring explaining why pypowsybl streams from matrices rather than
  materializing `SensitivityFactor` objects up front.
- Hoisted `variableType` / `variableSet` resolution and `matrix.getFunctionType()`
  out of the columns loop.
- Extracted `buildContingencyContexts` and `resolveVariableTypeFromNetwork`
  as separate static helpers for clarity.

### 2. `TreeMap<Integer, MatrixInfo>` → sorted `int[]` + `MatrixInfo[]`

*Same file, in the writer definition inside `run()`.*

`SensitivityResultWriter.writeSensitivityValue` is called
`F × (K + 1)` times where `F` is the factor count and `K` the contingency
count — the true hottest path on the read-back side of a run
(20-40 M calls on the target workload). The previous implementation did
`factorIndexMatrixMap.floorEntry(factorContext).getValue()` on every call,
which

- autoboxes `int factorContext` to `Integer` (fresh allocation on every
  call for `factorContext > 127`), and
- navigates a red-black tree even for a single-node tree.

Replaced with a sorted `int[] offsetTable` + parallel `MatrixInfo[] matricesArr`
(both built in the same pass that computes `baseCaseValueSize` and
`totalColumnsCount`, folding two previous helpers into one loop). The
single-matrix case — most pypowsybl callers add exactly one factor
matrix — becomes a direct index-0 read. Multi-matrix uses
`Arrays.binarySearch` over `int[]`. Both paths are allocation-free.

### 3. `createDoubleMatrix`: drop intermediate `double[]`, bulk copy, `malloc` not `calloc`

*File:* `java/pypowsybl/src/main/java/com/powsybl/python/sensitivity/SensitivityAnalysisResultContext.java`

Original:

```java
double[] values = new double[matRow * matCol];
System.arraycopy(sources, srcPos, values, 0, values.length);
CDoublePointer valuePtr = UnmanagedMemory.calloc(cellCount * SizeOf.get(CDoublePointer.class));
for (int i = 0; i < cellCount; i++) {
    valuePtr.addressOf(i).write(values[i]);
}
```

Three things wrong: a wasted intermediate `double[] values`, `calloc`
zero-initializing memory we immediately overwrite, and a per-cell native
`addressOf(i).write(v)` loop.

Replaced with a single bulk copy through a native-backed `DoubleBuffer`:

```java
CDoublePointer valuePtr = UnmanagedMemory.malloc(cellCount * SizeOf.get(CDoublePointer.class));
ByteBuffer buffer = CTypeConversion.asByteBuffer(valuePtr, cellCount * Double.BYTES)
    .order(ByteOrder.nativeOrder());
buffer.asDoubleBuffer().put(sources, srcPos, cellCount);
```

`DoubleBuffer.put(double[], int, int)` on a native-backed buffer with
matching byte order compiles to a single `Unsafe.copyMemory` — one
memcpy instead of `cellCount` intrinsic calls.

### 4. Memory leak fix: free the result matrix values buffer

*Files:* `SensitivityAnalysisCFunctions.java`, `powsybl-cpp.h`,
`powsybl-cpp.cpp`, `bindings.cpp`.

Every `get_sensitivity_matrix` / `get_reference_matrix` call allocated a
fresh `matrix` struct + `double[cells]` values buffer via
`UnmanagedMemory.malloc`, passed the pointer to Python via pybind11, and
never freed it. There was no counterpart to
`freeSensitivityAnalysisParameters` / `freeDataframeMetadata` /
`freeArrayPointer`. On a workflow that pulls one matrix per contingency
across a large study, the leak grew unboundedly and would OOM.

Fix:
- Added `@CEntryPoint(name = "freeSensitivityMatrix")` that frees both
  the values buffer and the matrix struct via `UnmanagedMemory.free`.
- Changed `getSensitivityMatrix` / `getReferenceMatrix` (in
  `powsybl-cpp.cpp`) to return `std::shared_ptr<matrix>` with a custom
  deleter calling `freeSensitivityMatrix`, mirroring
  `getNetworkMetadata` / `freeNetworkMetadata`.
- Registered the pybind11 `Matrix` class with `std::shared_ptr<matrix>`
  as its holder — same pattern already used for `NetworkMetadata`,
  `Dataframe`, `ArrayStruct`. The shared_ptr deleter fires when the
  Python-side reference drops.

Verified end-to-end after building GraalVM 21 + native-image + the C++
+ pybind11 layers: all 18 sensitivity Python tests plus a 500-iteration
"pull and drop" smoke test pass without crash or leak.

### 5. Vectorize `process_ptdf`

*File:* `pypowsybl/sensitivity/impl/sensitivity_analysis_result.py`

The PTDF post-processing (subtracting each `TO_REMOVE` row from its
preceding `"a -> b"` row, then dropping `TO_REMOVE` rows) was doing
`df.iloc[i-1] = df.iloc[i-1] - df.iloc[i]` in a Python for-loop. Every
iteration goes through pandas' iloc read/write machinery.

Vectorized: gather TO_REMOVE row positions into an `np.intp` array, do
one 2D ndarray subtraction indexing with the array, then wrap once.
Early-exit when there are no TO_REMOVE rows (the vast majority of
workloads that don't use zone-transfer variables).

---

## Upstream patches (in `powsybl-core/` and `powsybl-open-loadflow/`)

### `powsybl-core/0001-Build-SensitivityAnalysisResult-lookup-indexes-lazily.patch`

Provided by the pypowsybl maintainers. Makes `SensitivityAnalysisResult`
build its three per-value lookup indexes lazily on first key-based
access, so callers that only iterate `getValues()` /
`getPreContingencyValues()` pay nothing. On the same 2 M-factor
benchmark, cuts end-to-end sensitivity analysis time by ~39%.

pypowsybl itself uses the streaming `SensitivityResultWriter` API and
never constructs a `SensitivityAnalysisResult`, so it does not benefit
directly — but this patch is a strict improvement for other callers.

### `powsybl-open-loadflow/0001-Buffer-factor-reader-once-on-AC-sensitivity-analysis.patch`

`AcSensitivityAnalysis.analyse` reads the `SensitivityFactorReader`
twice: once in `getVariableTargetVoltageInfo` (to decide whether to
enable transformer voltage control before the LfNetwork is built), and
once per worker inside `analyzeContingencySet` →
`readAndCheckFactors`.

The multi-thread branch already wraps the reader in a
`BufferedFactorReader` before dispatching to workers, but leaves the
earlier `getVariableTargetVoltageInfo` call on the raw reader. For a
streaming reader whose per-factor cost is non-trivial — precisely
pypowsybl's `MatrixFactorReader`, which expands a matrix and does
per-row `network.getIdentifiable` on the fly — the raw first pass
doubles the per-factor cost on AC (single-thread) or does an
`extra + threadCount` full expansion on AC (multi-thread).

The patch moves buffering one step earlier so a single
`BufferedFactorReader` instance feeds both `getVariableTargetVoltageInfo`
and `analyzeContingencySet` in both branches. The memory cost of
holding `SensitivityFactor` objects is the one the multi-thread path
already accepts.

DC is unaffected (single read).

---

## Investigated but not yet applied: peak-memory-during-`run`

`SensitivityAnalysisContext.run` allocates on the Java heap:

```
baseCaseValues                : double[F]     =  8·F bytes
valuesByContingencyIndex      : double[K][F]  =  8·K·F bytes  ← dominant
baseCaseReferences            : double[C]     =  8·C bytes
referencesByContingencyIndex  : double[K][C]  =  8·K·C bytes
```

For 2 M factors × 100 contingencies, `valuesByContingencyIndex` is
**~1.6 GB**, held live through `run()` and afterwards until the result
context is garbage-collected. `get_sensitivity_matrix` from Python
adds another one-matrix-worth of transient native memory per pull.

### Where the waste comes from

**A. Matrices registered with `NONE` or `SPECIFIC` contingency contexts
still consume slots in every `valuesByContingencyIndex[c]`.** Those
cells are never written; they sit at zero, wasting `K · rowCount ·
columnCount · 8` bytes per non-`ALL` matrix. Mixed workloads (users
often combine `add_precontingency_...` (NONE) + `add_..._factor_matrix`
(ALL) + `add_postcontingency_..._factor_matrix` (SPECIFIC) in one
analysis) are the most affected.

**B. Contingencies the provider fully skips (unconverged, etc.) still
hold a full `double[F]` slot.** Rare on DC (deterministic), sometimes
seen on AC.

### Options

**Option 1 — per-matrix, per-relevant-contingency storage.** Biggest
potential saving on mixed workloads: peak drops from `K · F · 8` to
`sum_over_matrices(relevantContingencies(m) · cellsIn(m) · 8)`.
Requires the writer to map
`(factorContext, contingencyIndex) → (matrix, per-matrix offset,
per-matrix contingency slot)` — the `factorContext → matrix` step
already exists (the binary-search table added in optimization 2 above);
`ALL` matrices use `contingencyIndex` directly; `SPECIFIC` matrices
need an extra `int[K] contingencyIndex → per-matrix slot` per matrix;
`NONE` matrices don't allocate a contingency dimension at all. Also
requires updating
`SensitivityAnalysisResultContext.createSensitivityMatrix` to read from
per-matrix storage instead of a flat array. Real refactor.

**Option 2 — lazy inner-array allocation.** `double[][] valuesByContingencyIndex
= new double[K][]` outer only; allocate inner on first write. Adds a
null-check per write on the hot path. Only saves memory when the
provider fully skips a contingency (source B above). Small win, low
risk.

**Option 3 — native-heap storage from the start with zero-copy Python
reads.** Allocate values as `CDoublePointer` in `UnmanagedMemory`.
Writer uses `Pointer.writeDouble(int, double)`. `get_sensitivity_matrix`
returns a `MatrixPointer` offset into the existing native buffer — no
`createDoubleMatrix` copy at all. Peak memory during `run` is
unchanged (still `K · F · 8`), but

- eliminates the extra transient native buffer allocation and memcpy
  on every `get_sensitivity_matrix` pull (saves ~`K · one-matrix`
  bytes-transferred across a full pull cycle);
- gives Python deterministic release control — pull, use, drop, GC
  fires the shared_ptr deleter, native memory returns to the isolate
  immediately (no waiting on Java GC).

Requires a new `freeSensitivityAnalysisResultContext` C entry point
hooked to the JavaHandle destruction so the native buffers are freed
when the Python-side result context object is GC'd. Writes to native
memory via `Pointer.writeDouble` are typically 1-3× slower than Java
array stores — for 200 M writes, several hundred ms extra. That extra
write cost is expected to be dwarfed by the ~1.6 GB saved memcpy on
the read side.

**Option 4 — user-driven incremental release.** `result.release_contingency('cont-1')`
Python API that nulls the corresponding entry in `valuesByContingencyId`
so Java GC can reclaim it. Doesn't reduce peak during `run` itself,
only after. Requires user opt-in.

### Recommendation given the target workload

Assuming the PEGASE 2 M-factor case is a single matrix with
`ContingencyContext.ALL`:

- Option 1 gives **no** savings (a single ALL matrix already fills
  every slot).
- Option 2 gives **no** savings (every contingency writes).
- Option 3 doesn't shrink `K · F · 8` during `run`, but eliminates the
  transient copy during Python pull and gives deterministic release
  → biggest concrete win for this workload.
- Option 4 doesn't reduce peak-during-run.

For **mixed workloads** (multiple matrices with different contingency
contexts), Option 1 is the biggest win — reduction proportional to the
fraction of `NONE`/`SPECIFIC` matrices.

Neither is trivial. Both should be gated on a real profile of the
target workload before committing to the design change.

---

## Investigated and declined

- **`SensitivityAnalysis.find(provider)` per `run`.** `ServiceLoader`
  caches internally; not worth touching.
- **Sparse per-cell storage (e.g. `Int2DoubleOpenHashMap`).**
  Attractive for post-contingency values where most cells are
  threshold-filtered zeros, but each hash entry is ~40 bytes vs 8
  bytes for dense storage — regresses dense workloads (DC PTDF is
  typically dense). Would need a real profile with cell-density data
  before committing.
- **`float32` instead of `double`.** Halves memory but loses precision
  — not acceptable for scientific work.
- **PGO (`--pgo`) for `native-image`.** Build-system change, not code.
  Recommended by `native-image` itself; separate discussion.

---

## Verification

- Full Java test suite (`./mvnw -pl pypowsybl test`): 162 pass, 2
  skipped (unrelated dataframe skips), 0 failures.
- Full Python test suite (`pytest tests/`): 531 pass, 3 skipped, 13
  failures — all 13 are `tests/test_opf.py` failing with
  `RuntimeError: IPOPT library is not loaded`. This is a missing
  native solver in the test environment, not related to the changes
  on this branch (verified by re-running one OPF test with the tree
  in its original state — same failure).
- End-to-end smoke test: DC sensitivity on IEEE-14 returns correct
  DataFrames; 500 iterations of pulling and dropping sensitivity +
  reference matrices complete without crash, confirming the leak fix
  in production.
