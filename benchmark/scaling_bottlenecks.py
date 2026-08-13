#!/usr/bin/env python3
# Copyright (c) 2025, RTE (http://www.rte-france.com)
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
# SPDX-License-Identifier: MPL-2.0
#
"""
Decompose the thread scaling of the load flow scenario, to locate what limits it.

`loadflow_threadpool_scaling.py` measures how the whole scenario scales; this script
measures each of its ingredients separately, so the limiting one can be identified:

  1. a trivial java call: the per-call binding machinery alone (mutexes, GIL round trips),
  2. `update_generators` alone: the pandas -> C dataframe conversion, done under the GIL,
  3. `run_ac` alone with nothing changed: cache lookup plus load flow, no update,
  4. the full scenario, for reference,
  5. single thread cost as a function of the number of cached variants (the OpenLoadFlow
     network cache scans its entries linearly, under one process-wide lock),
  6. the price of `allow_variant_multi_thread_access` (thread local variant index),
  7. how many threads actually compute load flows, whatever the number of workers.

Point 7 is the one that caps everything else: OpenLoadFlow submits every run with
`CompletableFuture.supplyAsync` without an executor, so all load flows - blocking `run_ac`
included, since `LoadFlow.Runner.run` is `runAsync().join()` - execute on
`ForkJoinPool.commonPool()`, whose parallelism defaults to `availableProcessors - 1`. The
pool can only be resized process-wide, before the isolate starts:

    GRAALVM_OPTIONS="-Djava.util.concurrent.ForkJoinPool.common.parallelism=16" python ...
"""
import glob
import os
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from typing import Callable, List

import numpy as np

import pypowsybl as pp
import pypowsybl.loadflow as lf
import pypowsybl.network as pn

PARAMETERS = lf.Parameters(provider_parameters={'networkCacheEnabled': 'true'})
THREADS = [1, 2, 4]
GENERATOR_COUNT = 10


def make_network(multi_thread: bool = True) -> 'tuple[pp.network.Network, List[str], np.ndarray]':
    network = pn.create_ieee300(allow_variant_multi_thread_access=multi_thread)
    gens = network.get_generators(attributes=['target_p', 'bus_id'])
    gens = gens[(gens['bus_id'] != '') & (gens['target_p'] > 0)]
    gens = gens.sort_values('target_p', ascending=False).head(GENERATOR_COUNT)
    return network, list(gens.index), gens['target_p'].to_numpy(dtype=float)


def scale(name: str, make_worker: Callable[[int], Callable[[], None]], iters: int) -> None:
    """Run `iters` iterations per thread and report throughput against the 1 thread case."""
    print(f'\n{name}')
    base = None
    for threads in THREADS:
        def run(index: int) -> None:
            work = make_worker(index)
            for _ in range(iters):
                work()
        with ThreadPoolExecutor(max_workers=threads) as pool:
            start = time.perf_counter()
            list(pool.map(run, range(threads)))
            wall = time.perf_counter() - start
        ops = threads * iters / wall
        base = base or ops
        print(f'  {threads} threads: {ops:9.0f} ops/s   speedup {ops / base:4.2f}   '
              f'{1e6 * wall / (threads * iters):7.1f} us/op')


def phase_scaling() -> None:
    network, gen_ids, base_p = make_network()
    for i in range(max(THREADS)):
        network.clone_variant('InitialState', f'v{i}')

    scale('1. trivial java call (loadflow.get_default_provider)', lambda i: lf.get_default_provider, 2000)

    def make_update(index: int) -> Callable[[], None]:
        network.set_working_variant(f'v{index}')
        rng = np.random.default_rng(index)
        return lambda: network.update_generators(
            id=gen_ids, target_p=base_p * (1 + rng.uniform(-0.02, 0.02, GENERATOR_COUNT)))
    scale('2. update_generators only', make_update, 1000)

    def make_loadflow(index: int) -> Callable[[], None]:
        network.set_working_variant(f'v{index}')
        lf.run_ac(network, PARAMETERS)  # warm this variant's cache entry
        return lambda: lf.run_ac(network, PARAMETERS)
    scale('3. run_ac only, nothing changed (cache hit)', make_loadflow, 200)

    def make_full(index: int) -> Callable[[], None]:
        network.set_working_variant(f'v{index}')
        rng = np.random.default_rng(index)
        lf.run_ac(network, PARAMETERS)

        def work() -> None:
            network.update_generators(id=gen_ids,
                                      target_p=base_p * (1 + rng.uniform(-0.02, 0.02, GENERATOR_COUNT)))
            lf.run_ac(network, PARAMETERS)
        return work
    scale('4. update + run_ac (the benchmark scenario)', make_full, 200)


def cached_variant_cost() -> None:
    print('\n5. single thread cost vs number of cached variants')
    network, gen_ids, base_p = make_network()
    rng = np.random.default_rng(0)
    for count in (1, 4, 16, 32):
        for i in range(count):
            variant = f'c{i}'
            network.clone_variant('InitialState', variant)
            network.set_working_variant(variant)
            lf.run_ac(network, PARAMETERS)  # one cache entry and one network listener each
        network.set_working_variant('c0')
        for _ in range(20):
            network.update_generators(id=gen_ids, target_p=base_p)
            lf.run_ac(network, PARAMETERS)
        iters = 200
        start = time.perf_counter()
        for _ in range(iters):
            network.update_generators(id=gen_ids, target_p=base_p * (1 + rng.uniform(-0.02, 0.02, GENERATOR_COUNT)))
        update = (time.perf_counter() - start) / iters
        start = time.perf_counter()
        for _ in range(iters):
            network.update_generators(id=gen_ids, target_p=base_p * (1 + rng.uniform(-0.02, 0.02, GENERATOR_COUNT)))
            lf.run_ac(network, PARAMETERS)
        both = (time.perf_counter() - start) / iters
        print(f'  {count:3d} cached variants: update {1e3 * update:6.3f} ms, update+lf {1e3 * both:6.3f} ms')


def multi_thread_variant_cost() -> None:
    print('\n6. single thread, allow_variant_multi_thread_access on/off')
    rng = np.random.default_rng(0)
    for multi_thread in (True, False):
        network, gen_ids, base_p = make_network(multi_thread)
        network.clone_variant('InitialState', 'x')
        network.set_working_variant('x')
        for _ in range(20):
            lf.run_ac(network, PARAMETERS)
        iters = 300
        start = time.perf_counter()
        for _ in range(iters):
            network.update_generators(id=gen_ids, target_p=base_p * (1 + rng.uniform(-0.02, 0.02, GENERATOR_COUNT)))
            lf.run_ac(network, PARAMETERS)
        print(f'  allow_variant_multi_thread_access={multi_thread}: '
              f'{1e3 * (time.perf_counter() - start) / iters:6.3f} ms per update+lf')


def compute_thread_count(workers: int = 8) -> None:
    """Count the threads that really run the load flows while `workers` python threads submit."""
    print(f'\n7. threads actually computing, with {workers} python workers on {os.cpu_count()} CPUs')
    network, gen_ids, base_p = make_network()
    for i in range(workers):
        network.clone_variant('InitialState', f'w{i}')

    def work(index: int) -> None:
        network.set_working_variant(f'w{index}')
        rng = np.random.default_rng(index)
        for _ in range(150):
            network.update_generators(id=gen_ids,
                                      target_p=base_p * (1 + rng.uniform(-0.02, 0.02, GENERATOR_COUNT)))
            lf.run_ac(network, PARAMETERS)

    def snapshot() -> None:
        time.sleep(1.5)
        names: dict = {}
        for path in glob.glob('/proc/self/task/*/comm'):
            try:
                name = open(path, encoding='utf-8').read().strip()
            except OSError:
                continue
            names[name] = names.get(name, 0) + 1
        pool_workers = sum(count for name, count in names.items() if 'onPool-worker' in name)
        print(f'  ForkJoinPool.commonPool workers: {pool_workers} '
              f'(GRAALVM_OPTIONS={os.environ.get("GRAALVM_OPTIONS", "unset")})')

    threading.Thread(target=snapshot).start()
    with ThreadPoolExecutor(max_workers=workers) as pool:
        list(pool.map(work, range(workers)))


def main() -> int:
    print(f'pypowsybl {pp.__version__} | provider {lf.get_default_provider()} | {os.cpu_count()} CPUs')
    phase_scaling()
    cached_variant_cost()
    multi_thread_variant_cost()
    compute_thread_count()
    return 0


if __name__ == '__main__':
    sys.exit(main())
