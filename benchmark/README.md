# Parallel load flow scaling benchmarks

Two independent scripts measuring how parallel load flows on network variants scale with
the number of workers, on the same scenario:

* `loadflow_async_scaling.py` — asyncio: `run_ac_async` awaited from `W` coroutines,
* `loadflow_threadpool_scaling.py` — thread pool: the blocking `run_ac` called from `W`
  threads of a `ThreadPoolExecutor` (it releases the GIL for the whole native call).

## Scenario

A fixed total number of AC load flows (`--total-lf`) is spread over `W` workers. Each
worker owns one variant cloned from `InitialState` and loops on:

1. select its variant as working variant,
2. `network.update_generators(...)` to shift the active power setpoint (`p0` / `target_p`)
   of `--gens` generators by a bounded random amount,
3. run an AC load flow on that variant.

In the asyncio script, steps 1 and 2 contain no `await`, so the pair is atomic with respect
to the other coroutines and no worker can steal the working variant. In the thread pool
script each thread selects its variant once when it starts: the working variant is thread
local, so threads never share one.

Two options are required:

* the network must be loaded with `allow_variant_multi_thread_access=True`, otherwise
  variant selection is process-wide instead of thread local;
* `networkCacheEnabled=true` on OpenLoadFlow, so that a load flow following a small
  `target_p` change reuses the network state built for the previous run on the same
  variant.

## Usage

```bash
python benchmark/loadflow_async_scaling.py                          # ieee300, 1..16 workers
python benchmark/loadflow_threadpool_scaling.py --total-lf 512 --csv out.csv
python benchmark/loadflow_async_scaling.py --no-network-cache       # cache effect
python benchmark/loadflow_threadpool_scaling.py --gens 30 --shift 0.05
```

Columns: `LF/s` throughput, `speedup` against one worker, `effic.` = speedup / workers,
`cores` = process CPU time / wall time (how many cores are really kept busy, powsybl
threads included), `loop` (asyncio script only) = event loop thread CPU time / wall time,
i.e. the serial part of the scenario.

## Results

ieee300 (300 buses, 411 branches, 69 generators), 10 generators shifted per load flow,
256 load flows per configuration, `networkCacheEnabled=true`, fastest of 3 repetitions, on
a **4 vCPU** Linux container. Builds are GraalVM native images (Oracle GraalVM 21, G1) of
this repository: `main` (8c957d1) and the same commit plus the concurrency fix of
[PR #8](https://github.com/gautierbureau/pypowsybl/pull/8) (thread stays attached to the
isolate for its lifetime).

### Thread pool

On `main` the thread pool scenario **cannot run at all**: the second call made by a pool
thread fails with

```
pypowsybl._pypowsybl.PyPowsyblError: Variant index not set for current thread System-4
```

because the isolate thread is detached at the end of every call, which drops the thread
local working variant set by `set_working_variant`. With the fix:

| workers | wall (s) | LF/s | speedup | effic. | cores | mean lat (ms) | p95 (ms) |
|--------:|---------:|-----:|--------:|-------:|------:|--------------:|---------:|
| sequential | 1.412 | 181 | – | – | 1.00 | 4.97 | 6.32 |
| 1  | 1.371 | 187 | 1.00 | 1.00 | 1.00 | 4.85 | 5.97 |
| 2  | 0.760 | 337 | 1.80 | 0.90 | 1.96 | 5.37 | 6.43 |
| 3  | 0.553 | 463 | 2.48 | 0.83 | 2.69 | 5.71 | 8.09 |
| 4  | 0.482 | 532 | 2.85 | 0.71 | 3.26 | 6.90 | 11.58 |
| 6  | 0.508 | 504 | 2.70 | 0.45 | 3.25 | 10.28 | 21.22 |
| 8  | 0.566 | 453 | 2.42 | 0.30 | 3.18 | 13.04 | 28.00 |
| 12 | 0.564 | 454 | 2.43 | 0.20 | 3.18 | 20.75 | 37.48 |
| 16 | 0.576 | 444 | 2.38 | 0.15 | 3.30 | 27.02 | 44.46 |

### asyncio

Unaffected by the fix (`run_ac_async` passes the variant id to the Java side, which selects
it on its own worker thread), and measured on both builds as a control:

| workers | LF/s (main) | LF/s (fix) | speedup (fix) | effic. | cores | loop | mean lat (ms) |
|--------:|------------:|-----------:|--------------:|-------:|------:|-----:|--------------:|
| sync `run_ac` | 185 | 185 | – | – | 1.01 | 0.20 | 4.92 |
| 1  | 185 | 181 | 1.00 | 1.00 | 1.01 | 0.21 | 5.01 |
| 2  | 325 | 324 | 1.79 | 0.90 | 1.90 | 0.35 | 5.68 |
| 3  | 426 | 467 | 2.58 | 0.86 | 2.81 | 0.49 | 5.84 |
| 4  | 495 | 526 | 2.91 | 0.73 | 3.30 | 0.55 | 6.92 |
| 6  | 519 | 501 | 2.77 | 0.46 | 3.46 | 0.56 | 11.29 |
| 8  | 487 | 484 | 2.68 | 0.33 | 3.43 | 0.53 | 15.59 |
| 12 | 466 | 442 | 2.45 | 0.20 | 3.39 | 0.48 | 25.77 |
| 16 | 424 | 405 | 2.24 | 0.14 | 3.37 | 0.42 | 37.59 |

Observations:

* both flavours scale the same way: close to linear up to the number of cores (0.90
  efficiency at 2 workers, 0.83–0.86 at 3), a plateau at ~2.9x on 4 vCPUs, then a slow
  decay past 8 workers where oversubscription only inflates latency. The best operating
  point is `workers ≈ number of cores`;
* peak throughput is within noise of each other (532 LF/s thread pool, 526 LF/s asyncio),
  but the thread pool keeps latency lower when oversubscribed (27 ms vs 38 ms mean at 16
  workers) because tasks queue on the pool instead of all being submitted at once;
* the asyncio event loop burns ~0.5 core at the plateau, i.e. ~1.1 ms of serial Python work
  per load flow (variant switch, dataframe update, future plumbing) against a ~5.5 ms
  cached load flow. That ~20% serial fraction caps the asyncio speedup around 5x even with
  more cores. The thread pool has no such central thread, so it should keep scaling further
  on a bigger machine;
* `networkCacheEnabled=true` roughly doubles throughput at every worker count (measured on
  the asyncio flavour: 478 vs 261 LF/s at 4 workers) since only the incremental `target_p`
  change is re-solved;
* with `allow_variant_multi_thread_access=False`, the asyncio flavour still works with one
  worker but 2+ concurrent load flows fail with `java.util.NoSuchElementException` /
  `java.lang.IndexOutOfBoundsException` from the variant manager.

Caveat: 4 vCPUs is a thin basis for a scaling study — the interesting region (8–64 workers)
cannot be observed here. Both scripts take `--workers` / `--total-lf` / `--csv`, so
re-running them on a larger box is the natural next step.
