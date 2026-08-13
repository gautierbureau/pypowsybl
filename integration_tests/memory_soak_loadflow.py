# Copyright (c) 2026, RTE (http://www.rte-france.com)
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
# SPDX-License-Identifier: MPL-2.0
#
"""Memory soak test: repeated OpenLoadFlow runs on IEEE300.

Manual diagnostic script (not collected by pytest): it probes whether a
long-lived script running many loadflows grows its resident set without bound,
in particular with the OpenLoadFlow ``networkCacheEnabled`` (fast restart)
option.

Scenarios
    cache           one network kept alive, N loadflows, networkCacheEnabled=true
    nocache         same, networkCacheEnabled=false                     (control)
    newnet          a fresh IEEE300 per iteration, cache enabled, network dropped
    newnet_nocache  same as newnet, cache disabled                      (control)

Usage::

    python memory_soak_loadflow.py <scenario> <iterations> [csv_out]

Interpreting the result
    Raw RSS growth is NOT sufficient evidence of a leak: the GraalVM native
    image grows its heap opportunistically when no memory pressure exists, so
    RSS climbs for a while and then plateaus. Re-run under a bounded heap to
    separate lazy GC from genuine retention::

        GRAALVM_OPTIONS="-Xmx1G" python memory_soak_loadflow.py cache 60000

    A leak keeps a constant post-warmup slope under a bounded heap; mere heap
    expansion decays towards zero or shows a sawtooth once GC kicks in.
"""
import gc
import sys
import time

import psutil

import pypowsybl as pp

pp.set_config_read(False)
PROC = psutil.Process()


def rss_mb() -> float:
    return PROC.memory_info().rss / 1024 / 1024


def make_params(cache: bool) -> pp.loadflow.Parameters:
    return pp.loadflow.Parameters(
        provider_parameters={'networkCacheEnabled': 'true' if cache else 'false'})


def slope_kb_per_iter(samples) -> float:
    """Least-squares slope over (iteration, rss_mb) samples, in kB/iteration."""
    n = len(samples)
    if n < 2:
        return 0.0
    mx = sum(s[0] for s in samples) / n
    my = sum(s[1] for s in samples) / n
    den = sum((x - mx) ** 2 for x, _ in samples)
    if not den:
        return 0.0
    return sum((x - mx) * (y - my) for x, y in samples) / den * 1024


def run(scenario: str, iterations: int, csv_out: str = None):
    cache = not scenario.endswith('nocache')
    fresh_network = scenario.startswith('newnet')
    params = make_params(cache)

    net = None if fresh_network else pp.network.create_ieee300()
    if net is not None:
        load_id = net.get_loads().index[0]
        base_p0 = float(net.get_loads().loc[load_id, 'p0'])

    samples = []
    baseline = None
    t0 = time.time()
    for i in range(iterations):
        if fresh_network:
            net = pp.network.create_ieee300()
            load_id = net.get_loads().index[0]
            base_p0 = float(net.get_loads().loc[load_id, 'p0'])

        # perturb the network so each run is real work and exercises the
        # cache-update path rather than replaying an identical state
        net.update_loads(id=load_id, p0=base_p0 * (1.0 + 0.001 * (i % 50)))
        res = pp.loadflow.run_ac(net, parameters=params)
        if res[0].status_text != 'Converged':
            print(f'  !! iteration {i}: {res[0].status_text}', flush=True)

        if fresh_network:
            # there is no explicit close() in the API: the Java handle is
            # released when the Python object is collected
            del net
            net = None

        if i % 25 == 0 or i == iterations - 1:
            gc.collect()
            m = rss_mb()
            samples.append((i, m))
            if baseline is None:
                baseline = m
            if i % 250 == 0 or i == iterations - 1:
                print(f'  iter {i:6d}  rss {m:8.1f} MB  (+{m - baseline:7.1f})  '
                      f'{time.time() - t0:6.1f}s', flush=True)

    # drop the first 20% of samples: native image heap warmup, JIT, caches filling
    warm = samples[max(1, len(samples) // 5):]
    print(f'\n=== {scenario} ({iterations} iterations) ===')
    print(f'  RSS first sample : {samples[0][1]:8.1f} MB')
    print(f'  RSS last sample  : {samples[-1][1]:8.1f} MB')
    print(f'  RSS peak         : {max(s[1] for s in samples):8.1f} MB')
    print(f'  growth total     : {samples[-1][1] - samples[0][1]:8.1f} MB')
    print(f'  slope (all)      : {slope_kb_per_iter(samples):8.2f} kB/iteration')
    print(f'  slope (post-warm): {slope_kb_per_iter(warm):8.2f} kB/iteration')
    print(f'  wall time        : {time.time() - t0:8.1f} s')

    if csv_out:
        with open(csv_out, 'w') as f:
            f.write('iteration,rss_mb\n')
            for i, m in samples:
                f.write(f'{i},{m:.3f}\n')
    return samples


if __name__ == '__main__':
    run(sys.argv[1] if len(sys.argv) > 1 else 'cache',
        int(sys.argv[2]) if len(sys.argv) > 2 else 2000,
        sys.argv[3] if len(sys.argv) > 3 else None)
