# Loadflow memory soak — IEEE300 + OpenLoadFlow

Investigation of whether a long-lived pypowsybl script running many loadflows
with `networkCacheEnabled` (OpenLoadFlow fast restart) grows memory without
bound.

Reproduce with [`memory_soak_loadflow.py`](memory_soak_loadflow.py).

## Setup

- pypowsybl 1.16.1 (released wheel), OpenLoadFlow default provider
- `pypowsybl.network.create_ieee300()`
- one load's `p0` perturbed before every run so each iteration is real work and
  exercises the cache-update path
- RSS sampled every 25 iterations after an explicit `gc.collect()`
- linear slope computed over post-warmup samples (first 20% dropped)

## Results

### Default (unbounded) heap

| Scenario | Iterations | RSS start → end | Peak | Post-warmup slope |
|---|---|---|---|---|
| one network, cache **on** | 20 000 | 193 → 463 MB | 463 MB | **1.50 kB/it** |
| one network, cache off | 10 000 | 192 → 443 MB | 443 MB | 1.00 kB/it |
| fresh network/it, cache **on** | 1 500 | 193 → **2512 MB** | 2512 MB | **858 kB/it** |
| fresh network/it, cache off | 1 500 | 192 → 521 MB | 521 MB | 0.28 kB/it |

### Bounded heap (`GRAALVM_OPTIONS="-Xmx1G"`)

| Scenario | Iterations | RSS start → end | Peak | Post-warmup slope |
|---|---|---|---|---|
| one network, cache **on** | 60 000 | 193 → 514 MB | 514 MB | **1.45 kB/it** |
| one network, cache off | 30 000 | 192 → 441 MB | 441 MB | 0.08 kB/it |
| fresh network/it, cache **on** | 4 000 | 193 → 893 MB | 1256 MB | −61 kB/it (sawtooth) |

## Conclusions

**1. Creating a new network per iteration with the cache on is not a true leak.**
Under the default heap it looks alarming — 2.5 GB after 1500 loadflows, ~1.5 MB
retained per network, while the identical loop with the cache off stays pinned at
521 MB. But under `-Xmx1G` the same loop plateaus around 1.25 GB and then *drops*
back to 911 MB when a full GC runs, giving a negative post-warmup slope. The
cache entries are reclaimable under memory pressure. What the default
configuration does is let the native-image heap expand opportunistically, so a
long-lived script on a memory-constrained host can still be killed by the RSS
growth even though nothing is permanently leaked.

**2. Reusing one network with the cache on shows a small but genuine linear leak.**
This is the result that survives the bounded-heap control:

- cache on:  **1.45 kB/iteration**, perfectly linear over 60 000 iterations
  (1.50 kB/it over the first half, 1.36 kB/it over the second — no decay, no
  plateau)
- cache off: **0.08 kB/iteration**, flat

That is roughly an **18× difference**, and unlike heap expansion it does not
decay as the run gets longer. Extrapolated, a service doing 1 M cached loadflows
on a single network would accumulate on the order of 1.4 GB. It is slow enough
to be invisible in tests and unit benchmarks, and only shows up in genuinely
long-lived processes — which matches the symptom being investigated.

Note the unbounded-heap control (`nocache`, 1.00 kB/it) is misleading: it looks
comparable to the cached run only because heap expansion dominates the signal
there. Bounding the heap separates the two cleanly.

## Recommendation

For long-lived scripts, either bound the Java heap explicitly
(`GRAALVM_OPTIONS="-Xmx…"`) so the cache stays reclaimable, or leave
`networkCacheEnabled` off when the same network is reused for a very large
number of runs. The per-iteration retention in case 2 is worth chasing upstream
in `powsybl-open-loadflow`'s `NetworkCache`.
