# What limits thread scaling of parallel load flows

Investigation of how parallel load flows on network variants scale in pypowsybl, what caps
them, and where the caps live (pypowsybl C++/Python, powsybl-core, powsybl-open-loadflow).

Everything below is measured on this box unless stated otherwise:

| | |
|---|---|
| machine | 4 vCPU Linux container, 15 GB RAM |
| builds | GraalVM native images (Oracle GraalVM 21.0.12, G1) of this repository |
| baseline | `main` @ 8c957d1 |
| fix build | `main` + the concurrency fix of [PR #8](https://github.com/gautierbureau/pypowsybl/pull/8) |
| gil build | fix build + the GIL releases of [PR #15](https://github.com/gautierbureau/pypowsybl/pull/15) |
| network | ieee300 (300 buses, 411 branches, 69 generators) |
| scenario | per worker: own variant, shift `target_p` of 10 generators, run AC load flow |
| provider | OpenLoadFlow, `networkCacheEnabled=true` |

Scripts: `loadflow_async_scaling.py`, `loadflow_threadpool_scaling.py` (end-to-end scaling),
`scaling_bottlenecks.py` (phase-by-phase decomposition).

## Summary

Both parallelism flavours behave the same and plateau at ~2.9x on 4 vCPUs:

| workers | asyncio LF/s | thread pool LF/s | speedup | cores busy |
|--------:|-------------:|-----------------:|--------:|-----------:|
| 1  | 181 | 187 | 1.00 | 1.0 |
| 2  | 324 | 337 | ~1.8 | 2.0 |
| 4  | 526 | 532 | ~2.9 | 3.3 |
| 8  | 484 | 453 | ~2.6 | 3.2 |
| 16 | 405 | 444 | ~2.3 | 3.3 |

Ranked causes, most to least costly:

1. every load flow runs on `ForkJoinPool.commonPool()`, capped at `availableProcessors - 1`,
   because pypowsybl builds its computation manager with `LocalComputationManager.getDefault()`,
2. `update_generators` is ~90% Python/pandas under the GIL and *anti-scales* (0.54x on 4 threads),
3. the OpenLoadFlow network cache scans its entries linearly under one process-wide lock,
4. the pypowsybl per-call machinery takes two global mutexes and two GIL acquisitions per call,
5. variant creation is unsynchronised, `O(network size)`, and holds the GIL.

Measured non-issues: `allow_variant_multi_thread_access` (no measurable cost), network
listener notification (flat with the number of cached variants).

## 1. Every load flow runs on the common ForkJoinPool

OpenLoadFlow does the right thing: `OpenLoadFlowProvider.run` submits with
`CompletableFuture.supplyAsync(supplier, computationManager.getExecutor())`
(`OpenLoadFlowProvider.java:310`), and the blocking path goes through it as well, since
`LoadFlow.Runner.run` (`LoadFlow.java:140`) is `runAsync(...).join()`.

The cap comes from the executor it is handed. `CommonObjects.getComputationManager()` uses
`LocalComputationManager.getDefault()`, and `LocalComputationManager`'s default executor is
`ForkJoinPool.commonPool()` (`LocalComputationManager.java:105`) - whose parallelism is
`availableProcessors - 1`. The `available-core` property of the `local-computation-manager`
module does not help: it only sizes a semaphore used for external process execution, not the
executor.

Measured (`scaling_bottlenecks.py`, item 7): with **8 Python worker threads on 4 CPUs the
process has exactly 3 `commonPool-worker` threads** — `availableProcessors - 1`. That is the
~2.9x plateau and the `cores busy ≈ 3.3`. Extra workers only queue, which is why latency
inflates (5 ms at 1 worker, 27–38 ms at 16) while throughput does not move.

Consequences beyond the thread count:

* the pool is shared with every `parallelStream()` in the process, so unrelated user code
  steals load flow capacity and vice versa,
* an application cannot size or isolate the pool per analysis.

The only knob today is process-wide and must be set before the isolate starts:

```bash
GRAALVM_OPTIONS="-Djava.util.concurrent.ForkJoinPool.common.parallelism=16" python …
```

Verified working (8 `commonPool-worker` threads appear), but it changes nothing on 4 CPUs
(516 vs 502 LF/s at 4 workers, within noise). The fix is in pypowsybl: build the computation
manager with a dedicated pool, sized from `available-core` and defaulting to all cores.

## 2. `update_generators` is GIL-bound and anti-scales

`scaling_bottlenecks.py`, item 2, shifting 10 generators:

| threads | throughput | per call |
|--------:|-----------:|---------:|
| 1 | 3293 ops/s | 304 µs |
| 2 | 2621 ops/s | 382 µs |
| 4 | 1789 ops/s | 559 µs |

More threads is *worse* than one. `cProfile` of the single-thread path shows where the
~300 µs go for ten values:

| step | cost |
|---|---:|
| `update_network_elements_with_series` (the actual Java update) | 18 µs |
| `get_network_elements_dataframe_metadata` (native call, every update, static data) | 30 µs |
| `_adapt_kwargs` → build a pandas `DataFrame` (`Index.__new__`, `sanitize_array`, dtypes) | ~150 µs |
| `_create_c_dataframe` → tear the DataFrame back into Python lists | ~100 µs |

Everything except the first line is Python bytecode holding the GIL
(`pypowsybl/utils/impl/dataframes.py:34` and `:76`), so N threads serialize on it. It is
~5% of this scenario (a 5.9 ms solve dominates), but it is the whole story for fine-grained
loops — grid2op steps, small networks, many small updates.

Fixes worth having: memoize the series metadata (fork PR #10 does exactly that), and add a
kwargs fast path that goes from ids + numpy arrays straight to the C dataframe without
building a pandas `DataFrame` first.

## 3. The OpenLoadFlow network cache: one global lock, linear scan

`NetworkCache.java` holds a single static `AC_LF_INSTANCE` with one `ReentrantLock`, and
`get()` runs `evictDeadEntries()` (iterates all entries) then `findEntry()` (a stream scan
comparing network reference and working variant id) **inside the lock, on every load flow**.
Each worker needs its own variant, so the number of entries — and the length of the
serialized section — grows linearly with the number of workers.

Measured single-thread cost of one `update + run_ac`:

| cached variants | 1 | 4 | 16 | 32 |
|---|---:|---:|---:|---:|
| ms per iteration | 5.83 | 5.35 | 6.24 | 7.58 |

And a no-op `run_ac` (cache hit, nothing changed) stops scaling as entries accumulate:

| cache entries | 1 thread | 4 threads | speedup |
|---|---:|---:|---:|
| 4  | 1140 ops/s | 1888 ops/s | 1.66 |
| 32 | 1457 ops/s |  955 ops/s | **0.66** |

An entry map keyed by (network, variant) instead of a scanned list, and a per-network lock
instead of the static one, would remove both effects.

## 4. Per-call binding machinery: two mutexes and two GIL acquisitions

`scaling_bottlenecks.py`, item 1: a trivial Java call costs 1.1 µs and scales only 1.33x on
4 threads, i.e. the binding layer saturates around 1.2 M calls/s process-wide. Per call, in
`cpp/`:

* `PowsyblCaller::get()` takes a **global `std::mutex`** just to return a singleton
  (`powsybl-cpp.cpp:67`), and `CppToPythonLogger::get()` takes a second one;
* the pre-call hook `setLogLevelFromPythonLogger` (`bindings.cpp:1540`) **acquires the GIL
  and makes an extra Java call** (`setLogLevel`) on every call — and it always runs, because
  `pypowsybl/__init__.py:62` installs a logger at import;
* the post-call hook **acquires the GIL again**, unconditionally, only to check
  `PyErr_Occurred()` (`bindings.cpp:315`).

So a call marked `gil_scoped_release` still takes the GIL twice. Function-local statics
would remove both mutexes; the log level only needs to be pushed when it changed (an atomic
compare); the error check does not need a full re-acquire in the common case.

Latent bug in the same place: `getLogger()` returns a `py::object` **by value**
(`bindings.cpp:1541`), i.e. a refcount increment *before* the `gil_scoped_acquire` on the
next line — for GIL-released calls that is a refcount mutation without the GIL, and the
matching decref happens after the acquire has gone out of scope.

## 5. Variant creation is unsynchronised and holds the GIL

`VariantManagerImpl` (powsybl-core) has **no locking at all**: `cloneVariant` mutates
`id2index` / `variantArraySize` and calls `extendVariantArraySize` on every stateful object.
So variants can only be created before the workers start, and each clone is O(#objects).
On the pypowsybl side `clone_variant`, `remove_variant`, `set_working_variant`,
`get_variant_ids` and `get_working_variant_id` (`bindings.cpp:1108-1112`) are bound
**without** `gil_scoped_release`, so the whole Java call blocks every other Python thread:

| call | 1 thread | 4 threads | speedup |
|---|---:|---:|---:|
| `clone_variant` (ieee300) | 1181 ops/s (0.85 ms) | 896 ops/s | 0.76 |
| `set_working_variant` | 1.09 M ops/s | 1.16 M ops/s | 1.06 |

`set_working_variant` is cheap enough not to matter; `clone_variant` at 0.85 ms of
GIL-holding work is a real stall for workloads that create variants on the fly. The
unsynchronised manager is what the powsybl-core PRs
[#34](https://github.com/powsybl/powsybl-core/pull/34) and
[#50](https://github.com/powsybl/powsybl-core/pull/50) address.

## 6. Effect of the GIL-release branch (PR #15)

PR #15 adds `py::call_guard<py::gil_scoped_release>()` to `create_element`,
`create_network_modification`, `split_or_merge_transformers`, `merge` and the single-line /
network-area diagram entry points. Measured on the gil build:

| workload | PR #8 only | PR #8 + PR #15 |
|---|---:|---:|
| `get_network_area_diagram`, 4 threads vs 1 | **1.01x** | **2.66x** |
| load flow scenario, 4 workers | 532 LF/s (2.85x) | 498 LF/s (3.04x) |

So: **it does not improve this scenario** — `update_network_elements_with_series` and
`run_loadflow` already release the GIL, and the scenario calls none of the functions it
touches. It is a large win for the workloads it does cover: diagram export goes from *no*
threading at all to 2.66x on 4 threads, and the same applies to network building and
topology modification.

The gap it leaves is the variant API of section 5 — adding the same guard to
`clone_variant` / `remove_variant` would be the natural follow-up, and is the one that would
touch a variant-per-worker scenario.

## What to fix, in order

1. **pypowsybl java**: give `CommonObjects` a dedicated `ForkJoinPool` instead of the
   default `LocalComputationManager`, which runs everything on the common pool. Decides
   whether 32 workers can ever use 32 cores, and stops load flows from competing with the
   parallel streams of the calling application.
2. **powsybl-open-loadflow**: key `NetworkCache` entries by (network, variant) and drop the
   process-wide lock to a per-network one.
3. **pypowsybl Python**: memoize the dataframe metadata and add a pandas-free kwargs path in
   `update_*`.
4. **pypowsybl C++**: remove the two per-call singleton mutexes, push the log level only when
   it changes, and fix the unguarded refcount in `setLogLevelFromPythonLogger`.
5. **pypowsybl C++**: release the GIL on the variant calls (PR #15 style).
6. **powsybl-core**: make variant creation thread safe (core PRs #34 / #50).

## Caveat

4 vCPUs is a thin basis for a scaling study: the region where these effects separate
(8–64 workers) cannot be observed here, and on this box the CPU count and the common pool
cap (3) nearly coincide. All three scripts take `--workers` / `--total-lf` / `--csv`, so
re-running them on a larger machine is the natural next step — item 1 in particular should
become dramatic there.
