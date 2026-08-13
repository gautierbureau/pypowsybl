# Async load flow scaling benchmark

`loadflow_async_scaling.py` measures how the asyncio load flow API
(`pypowsybl.loadflow.run_ac_async`) scales with the number of concurrent workers, each
worker running load flows on its own network variant.

## Scenario

A fixed total number of AC load flows (`--total-lf`) is spread over `W` asyncio workers.
Each worker owns one variant cloned from `InitialState` and loops on:

1. `network.set_working_variant(its variant)`,
2. `network.update_generators(...)` to shift the active power setpoint (`p0` /`target_p`)
   of `--gens` generators by a bounded random amount,
3. `await run_ac_async(network, its variant)`.

Steps 1 and 2 contain no `await`, so the pair is atomic with respect to the other
coroutines and no worker can steal the working variant in between. The load flow itself
is computed on a powsybl computation manager thread, so several variants are solved in
parallel while the Python event loop stays free.

Two options are required:

* the network must be loaded with `allow_variant_multi_thread_access=True`, otherwise
  variant selection is process-wide instead of thread local;
* `networkCacheEnabled=true` on OpenLoadFlow, so that a load flow following a small
  `target_p` change reuses the network state built for the previous run on the same
  variant.

## Usage

```bash
python benchmark/loadflow_async_scaling.py                       # ieee300, 1..16 workers
python benchmark/loadflow_async_scaling.py --network ieee118 --total-lf 512 --csv out.csv
python benchmark/loadflow_async_scaling.py --no-network-cache    # cache effect
python benchmark/loadflow_async_scaling.py --gens 30 --shift 0.05
```

The same total work is run for every worker count, so the columns read as: `LF/s`
throughput, `speedup` against one worker, `effic.` = speedup / workers, `cores` =
process CPU time / wall time (how many cores are really kept busy, powsybl threads
included), `loop` = event loop thread CPU time / wall time (the serial part of the
scenario).

## Results

pypowsybl 1.16.1, OpenLoadFlow, ieee300 (300 buses, 411 branches, 69 generators),
10 generators shifted per load flow, 256 load flows per configuration, fastest of 3
repetitions, on a **4 vCPU** Linux container.

`networkCacheEnabled=true`:

| workers | wall (s) | LF/s | speedup | effic. | cores | loop | mean lat (ms) | p95 (ms) |
|--------:|---------:|-----:|--------:|-------:|------:|-----:|--------------:|---------:|
| sync `run_ac` | 1.416 | 181 | – | – | 1.00 | 0.22 | 5.00 | 6.53 |
| 1  | 1.451 | 176 | 1.00 | 1.00 | 1.01 | 0.21 | 5.14 | 6.85 |
| 2  | 0.823 | 311 | 1.76 | 0.88 | 1.97 | 0.37 | 5.91 | 8.74 |
| 3  | 0.575 | 445 | 2.52 | 0.84 | 2.81 | 0.52 | 6.12 | 9.96 |
| 4  | 0.536 | 478 | 2.71 | 0.68 | 3.24 | 0.52 | 7.51 | 10.76 |
| 6  | 0.547 | 468 | 2.65 | 0.44 | 3.53 | 0.61 | 12.01 | 18.06 |
| 8  | 0.569 | 450 | 2.55 | 0.32 | 3.41 | 0.49 | 16.85 | 27.51 |
| 12 | 0.609 | 420 | 2.38 | 0.20 | 3.38 | 0.45 | 27.25 | 49.83 |
| 16 | 0.639 | 401 | 2.27 | 0.14 | 3.39 | 0.44 | 38.01 | 65.10 |

`networkCacheEnabled=false` (128 load flows per configuration):

| workers | LF/s | speedup | effic. | cores |
|--------:|-----:|--------:|-------:|------:|
| sync | 88 | – | – | 1.00 |
| 1  | 84  | 1.00 | 1.00 | 1.01 |
| 2  | 148 | 1.77 | 0.88 | 1.97 |
| 4  | 261 | 3.12 | 0.78 | 3.19 |
| 8  | 247 | 2.95 | 0.37 | 3.14 |
| 16 | 244 | 2.92 | 0.18 | 3.17 |

Observations:

* scaling is close to linear up to the number of physical cores (0.88 efficiency at 2
  workers, 0.84 at 3), then flattens at ~2.7–3.1x on 4 vCPUs and slowly *degrades* beyond
  8 workers, where oversubscription only inflates latency (5 ms → 38 ms mean at 16
  workers) without adding throughput;
* the best operating point is `workers ≈ number of cores`; running the same sweep pinned
  to 2 CPUs (`taskset -c 0,1`) moves the plateau to 2 workers, confirming the limit is the
  available cores, not an internal queue;
* `networkCacheEnabled=true` roughly doubles throughput at every worker count (478 vs 261
  LF/s at 4 workers) since only the incremental `target_p` change is re-solved;
* the event loop thread burns ~0.5 core at the plateau, i.e. ~1.1 ms of serial Python work
  per load flow (variant switch, dataframe update, future plumbing). Against a ~5.5 ms
  cached load flow that is a ~20% serial fraction, which caps the speedup around 5x even
  on a machine with more cores. Shifting a single generator instead of ten only removes
  0.35 ms of it: most of that serial time is the async round trip itself, not the update;
* with `allow_variant_multi_thread_access=False`, one worker still works, but two or more
  concurrent load flows fail with `java.util.NoSuchElementException` /
  `java.lang.IndexOutOfBoundsException` from the variant manager.
