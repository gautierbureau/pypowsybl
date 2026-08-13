# Copyright (c) 2026, RTE (http://www.rte-france.com)
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
# SPDX-License-Identifier: MPL-2.0
#
"""Demonstrate the leaked IIDM NetworkListener in OLF's NetworkCache.

NetworkCache.AbstractEntry's constructor calls network.addListener(this); its
close() never calls network.removeListener(this). An entry is dropped and
recreated whenever LfInput.hasChanged() reports a parameter change, so a
long-lived network that sees alternating loadflow parameters accumulates one
permanent IIDM listener per run.

Symptoms expected if the leak is real:
  - RSS grows linearly and never plateaus
  - each network update gets slower, because NetworkListenerList notifies an
    ever-growing CopyOnWriteArrayList
"""
import gc
import sys
import time

import psutil
import pypowsybl as pp

pp.set_config_read(False)
PROC = psutil.Process()


def rss_mb():
    return PROC.memory_info().rss / 1024 / 1024


def run(iterations, alternate):
    net = pp.network.create_ieee300()
    loads = net.get_loads()
    lid = loads.index[0]
    base = float(loads.loc[lid, 'p0'])

    # two parameter sets differing only in a flag; alternating them makes
    # OpenLoadFlowParameters.equals() false -> entry evicted and recreated
    variants = [
        pp.loadflow.Parameters(distributed_slack=True,
                               provider_parameters={'networkCacheEnabled': 'true'}),
        pp.loadflow.Parameters(distributed_slack=False,
                               provider_parameters={'networkCacheEnabled': 'true'}),
    ]

    samples = []
    t0 = time.time()
    update_cost = []
    for i in range(iterations):
        params = variants[i % 2] if alternate else variants[0]

        tu = time.perf_counter()
        net.update_loads(id=lid, p0=base * (1.0 + 0.001 * (i % 50)))
        update_cost.append(time.perf_counter() - tu)

        pp.loadflow.run_ac(net, parameters=params)

        if i % 25 == 0 or i == iterations - 1:
            gc.collect()
            samples.append((i, rss_mb()))
            if i % 250 == 0 or i == iterations - 1:
                recent = sum(update_cost[-25:]) / len(update_cost[-25:]) * 1e3
                print(f'  iter {i:6d}  rss {samples[-1][1]:8.1f} MB  '
                      f'update {recent:7.3f} ms  {time.time() - t0:6.1f}s', flush=True)

    n = len(samples)
    warm = samples[max(1, n // 5):]
    mx = sum(s[0] for s in warm) / len(warm)
    my = sum(s[1] for s in warm) / len(warm)
    den = sum((x - mx) ** 2 for x, _ in warm)
    slope = (sum((x - mx) * (y - my) for x, y in warm) / den * 1024) if den else 0.0

    first25 = sum(update_cost[:25]) / 25 * 1e3
    last25 = sum(update_cost[-25:]) / 25 * 1e3
    print(f'\n=== alternate_parameters={alternate} ({iterations} iterations) ===')
    print(f'  RSS {samples[0][1]:.1f} -> {samples[-1][1]:.1f} MB')
    print(f'  slope (post-warm)   : {slope:8.2f} kB/iteration')
    print(f'  update_loads first25: {first25:8.3f} ms')
    print(f'  update_loads last25 : {last25:8.3f} ms  ({last25 / first25:.1f}x)')


if __name__ == '__main__':
    run(int(sys.argv[1]) if len(sys.argv) > 1 else 2000,
        sys.argv[2].lower() == 'true' if len(sys.argv) > 2 else True)
