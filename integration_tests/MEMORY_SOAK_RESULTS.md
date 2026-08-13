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

## Root cause found upstream: leaked `NetworkListener`

Read against `powsybl-open-loadflow` @ `fe0bd3d` and `powsybl-core` @ `0c26bac`.

`NetworkCache.AbstractEntry` registers itself as an IIDM `NetworkListener` in its
constructor (`NetworkCache.java:263`):

```java
protected AbstractEntry(Network network, I input) {
    ...
    network.addListener(this);
}
```

but `AbstractEntry.close()` (`NetworkCache.java:841-848`) releases the LfNetwork
values and the variant cleaner and **never calls `network.removeListener(this)`**.
No call to IIDM's `Network.removeListener` exists anywhere in open-loadflow.

An entry is closed and recreated whenever `LfInput.hasChanged()` reports a
difference, and that comparison is all-or-nothing over the whole parameter set:

```java
public String hasChanged(LfInput other) {
    // TODO to refine later by comparing in detail parameters that have changed
    return OpenLoadFlowParameters.equals(parameters, other.parameters) ? null : "parameters";
}
```

So **any** loadflow parameter difference between two runs on the same network
evicts the entry, creates a new one, and adds one more permanent listener. On
`powsybl-core` side these are held strongly in a `CopyOnWriteArrayList`
(`NetworkListenerList.java:29`), so nothing ever reclaims them while the network
is alive.

### Measured impact

Alternating a single parameter (`distributed_slack`) between runs on one
long-lived IEEE300 network, under `-Xmx1G`:

| | 2000 iterations | RSS slope | Wall time |
|---|---|---|---|
| alternating parameters | 200 entries created / 199 evicted per 200 runs | 2.16 kB/it | **151.6 s** |
| fixed parameters (control) | 1 entry created, reused | 1.68 kB/it | **12.1 s** |

Logging confirms the accumulation directly: 200 iterations produce 200
"Network cache created" and 199 "Network cache evicted" messages, i.e. ~200
leaked listeners.

The dominant symptom is CPU, not memory. Every write-back a loadflow performs on
the network (bus V/angle, branch P/Q) notifies the whole listener list, so the
cost per run grows linearly with the number of leaked listeners. Time per 250
iterations climbed 7.2 → 10.8 → 13.8 → 17.1 → 20.4 → 24.1 → 27.6 → 30.6 s while
the control stayed flat at ~1.5 s — overall O(n²), already 12.5× slower after
only 2000 runs and still diverging.

Note this is a *different* code path from the 1.45 kB/it drift in case 2 above:
with fixed parameters the entry is created once and reused (verified: 2 created,
38 reused over 20 runs), so that residual drift has another cause and is still
open.

Reproduce with [`memory_soak_listener_leak.py`](memory_soak_listener_leak.py).

## Recommendation

Upstream fix in open-loadflow: have `AbstractEntry.close()` call
`network.removeListener(this)` on the still-reachable network. Refining
`LfInput.hasChanged()` (the existing TODO) would additionally avoid needless
evictions, but the missing deregistration is the leak and should be fixed
regardless.

Workarounds for long-lived pypowsybl scripts until then:

- keep loadflow parameters **identical** across runs on a given network, so the
  cache entry is reused rather than recreated — this is what makes the
  difference between the 12 s and 151 s runs above;
- bound the Java heap explicitly (`GRAALVM_OPTIONS="-Xmx…"`) so cached entries
  for dead networks stay reclaimable;
- leave `networkCacheEnabled` off when parameters must vary between runs.
