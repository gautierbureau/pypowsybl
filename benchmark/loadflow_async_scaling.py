#!/usr/bin/env python3
# Copyright (c) 2025, RTE (http://www.rte-france.com)
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
# SPDX-License-Identifier: MPL-2.0
#
"""
Scaling benchmark of the asyncio load flow API (``pypowsybl.loadflow.run_ac_async``).

Scenario
--------
A fixed total amount of work (``--total-lf`` AC load flows) is spread over ``W``
asyncio workers. Each worker owns its own network variant and, for each of its
tasks:

  1. selects its variant as working variant,
  2. shifts the active power setpoint (``p0``, i.e. ``target_p``) of ``--gens`` generators,
  3. submits an asynchronous AC load flow on that variant and awaits it.

Steps 1 and 2 contain no ``await``, so they are atomic with respect to the other
coroutines: the (thread local) working variant cannot be stolen by another worker
in between. The load flow itself runs on a powsybl computation-manager thread, so
several load flows on different variants are really computed in parallel while the
Python event loop stays free.

Two options are mandatory for this to work:

  * the network must be loaded with ``allow_variant_multi_thread_access=True``
    (variant selection becomes thread local),
  * ``networkCacheEnabled=true`` on OpenLoadFlow, so that a load flow following a
    small ``p0`` change reuses the network state built for the previous run on the
    same variant instead of rebuilding it from scratch.

The same total work is repeated for each worker count, which gives throughput,
speedup and parallel efficiency curves. CPU time (all threads, including the JVM /
native-image ones) is sampled as well: ``cpu_time / wall_time`` is the number of
cores actually kept busy, which tells apart "the event loop is the bottleneck"
from "there is nothing left to parallelize".

Examples
--------
    python loadflow_async_scaling.py
    python loadflow_async_scaling.py --network ieee300 --workers 1,2,4,8,16 --total-lf 512
    python loadflow_async_scaling.py --no-network-cache --csv no_cache.csv
"""
import argparse
import asyncio
import os
import resource
import statistics
import sys
import time
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Sequence

import numpy as np

import pypowsybl as pp
import pypowsybl.loadflow as lf
import pypowsybl.network as pn

CREATORS = {
    'ieee9': pn.create_ieee9,
    'ieee14': pn.create_ieee14,
    'ieee30': pn.create_ieee30,
    'ieee57': pn.create_ieee57,
    'ieee118': pn.create_ieee118,
    'ieee300': pn.create_ieee300,
}


@dataclass
class Measure:
    """Result of one (worker count) configuration."""
    workers: int
    wall: float
    cpu: float
    load_flows: int
    diverged: int
    loop_cpu: float = 0.0
    latencies: List[float] = field(default_factory=list)

    @property
    def throughput(self) -> float:
        return self.load_flows / self.wall

    @property
    def cores_busy(self) -> float:
        """Number of cores kept busy by the whole process (event loop + powsybl threads)."""
        return self.cpu / self.wall

    @property
    def loop_busy(self) -> float:
        """Fraction of the wall time the event loop thread is burning CPU: the serial part."""
        return self.loop_cpu / self.wall


def load_network(name: str, multi_thread: bool) -> pp.network.Network:
    if name in CREATORS:
        return CREATORS[name](allow_variant_multi_thread_access=multi_thread)
    return pn.load(name, allow_variant_multi_thread_access=multi_thread)


def build_parameters(network_cache: bool, distributed_slack: bool) -> lf.Parameters:
    return lf.Parameters(distributed_slack=distributed_slack,
                         provider_parameters={'networkCacheEnabled': 'true' if network_cache else 'false'})


def pick_generators(network: pp.network.Network, count: int) -> 'tuple[List[str], np.ndarray]':
    """The `count` biggest connected generators, with their base target_p."""
    gens = network.get_generators(attributes=['target_p', 'min_p', 'max_p', 'bus_id'])
    gens = gens[(gens['bus_id'] != '') & (gens['target_p'] > 0)]
    gens = gens.sort_values('target_p', ascending=False).head(count)
    if len(gens) == 0:
        raise ValueError('no connected generator with a positive target_p found')
    return list(gens.index), gens['target_p'].to_numpy(dtype=float)


def shifted_target_p(base_p: np.ndarray, rng: np.random.Generator, amplitude: float) -> np.ndarray:
    """A bounded, always different, target_p vector: base * (1 + U(-a, a))."""
    return base_p * (1.0 + rng.uniform(-amplitude, amplitude, size=base_p.shape))


def cpu_seconds() -> float:
    """CPU time of the whole process, i.e. Python threads + powsybl worker threads."""
    usage = resource.getrusage(resource.RUSAGE_SELF)
    return usage.ru_utime + usage.ru_stime


async def _worker(network: pp.network.Network, variant: str, task_count: int, gen_ids: List[str],
                  base_p: np.ndarray, amplitude: float, parameters: lf.Parameters, seed: int,
                  latencies: List[float]) -> int:
    """One asyncio worker, working on its own variant."""
    rng = np.random.default_rng(seed)
    diverged = 0
    for _ in range(task_count):
        # No await between the variant selection and the update: the pair is atomic
        # for the other coroutines of this event loop.
        network.set_working_variant(variant)
        network.update_generators(id=gen_ids, target_p=shifted_target_p(base_p, rng, amplitude))
        start = time.perf_counter()
        results = await lf.run_ac_async(network, variant, parameters)
        latencies.append(time.perf_counter() - start)
        if results[0].status != pp.loadflow.ComponentStatus.CONVERGED:
            diverged += 1
    return diverged


async def _run_async_case(network: pp.network.Network, workers: int, total_lf: int, gen_ids: List[str],
                          base_p: np.ndarray, amplitude: float, parameters: lf.Parameters) -> Measure:
    variants = [f'w{i}' for i in range(workers)]
    for variant in variants:
        network.clone_variant('InitialState', variant)

    # Same total work whatever the worker count.
    per_worker = [total_lf // workers + (1 if i < total_lf % workers else 0) for i in range(workers)]
    latencies: List[float] = []

    cpu0, loop0, wall0 = cpu_seconds(), time.thread_time(), time.perf_counter()
    diverged = await asyncio.gather(*[
        _worker(network, variants[i], per_worker[i], gen_ids, base_p, amplitude, parameters, 1234 + i, latencies)
        for i in range(workers)])
    wall, cpu, loop_cpu = time.perf_counter() - wall0, cpu_seconds() - cpu0, time.thread_time() - loop0

    for variant in variants:
        network.remove_variant(variant)
    network.set_working_variant('InitialState')
    return Measure(workers=workers, wall=wall, cpu=cpu, load_flows=sum(per_worker),
                   diverged=sum(diverged), loop_cpu=loop_cpu, latencies=latencies)


def run_async_case(network: pp.network.Network, workers: int, total_lf: int, gen_ids: List[str],
                   base_p: np.ndarray, amplitude: float, parameters: lf.Parameters) -> Measure:
    return asyncio.run(_run_async_case(network, workers, total_lf, gen_ids, base_p, amplitude, parameters))


def run_sync_case(network: pp.network.Network, total_lf: int, gen_ids: List[str], base_p: np.ndarray,
                  amplitude: float, parameters: lf.Parameters) -> Measure:
    """Blocking `run_ac` reference, on a single variant."""
    network.clone_variant('InitialState', 'sync')
    network.set_working_variant('sync')
    rng = np.random.default_rng(1234)
    latencies: List[float] = []
    diverged = 0

    cpu0, loop0, wall0 = cpu_seconds(), time.thread_time(), time.perf_counter()
    for _ in range(total_lf):
        network.update_generators(id=gen_ids, target_p=shifted_target_p(base_p, rng, amplitude))
        start = time.perf_counter()
        results = lf.run_ac(network, parameters)
        latencies.append(time.perf_counter() - start)
        if results[0].status != pp.loadflow.ComponentStatus.CONVERGED:
            diverged += 1
    wall, cpu, loop_cpu = time.perf_counter() - wall0, cpu_seconds() - cpu0, time.thread_time() - loop0

    network.set_working_variant('InitialState')
    network.remove_variant('sync')
    return Measure(workers=0, wall=wall, cpu=cpu, load_flows=total_lf, diverged=diverged, loop_cpu=loop_cpu,
                   latencies=latencies)


def best_of(measures: Sequence[Measure]) -> Measure:
    """Keep the fastest repetition, the least polluted by JIT / GC / noisy neighbours."""
    return min(measures, key=lambda m: m.wall)


def print_table(reference: Optional[Measure], measures: List[Measure]) -> None:
    header = f"{'workers':>8} {'wall (s)':>10} {'LF/s':>10} {'speedup':>9} {'effic.':>8} {'cores':>7} " \
             f"{'loop':>6} {'mean lat (ms)':>14} {'p95 lat (ms)':>13} {'diverged':>9}"
    print(header)
    print('-' * len(header))
    if reference is not None:
        print(f"{'sync':>8} {reference.wall:10.3f} {reference.throughput:10.1f} {'-':>9} {'-':>8} "
              f"{reference.cores_busy:7.2f} {reference.loop_busy:6.2f} "
              f"{1e3 * statistics.fmean(reference.latencies):14.2f} "
              f"{1e3 * np.percentile(reference.latencies, 95):13.2f} {reference.diverged:9d}")
    base = measures[0].throughput if measures else 1.0
    for m in measures:
        speedup = m.throughput / base
        print(f"{m.workers:8d} {m.wall:10.3f} {m.throughput:10.1f} {speedup:9.2f} "
              f"{speedup / m.workers:8.2f} {m.cores_busy:7.2f} {m.loop_busy:6.2f} "
              f"{1e3 * statistics.fmean(m.latencies):14.2f} {1e3 * np.percentile(m.latencies, 95):13.2f} "
              f"{m.diverged:9d}")


def write_csv(path: str, reference: Optional[Measure], measures: List[Measure], context: Dict[str, object]) -> None:
    base = measures[0].throughput if measures else 1.0
    prefix_names = ','.join(context.keys())
    prefix_values = ','.join(str(v) for v in context.values())
    with open(path, 'w', encoding='utf-8') as file:
        file.write(f'{prefix_names},mode,workers,wall_s,cpu_s,load_flows,throughput_lf_s,speedup,'
                   'efficiency,cores_busy,loop_busy,mean_latency_ms,p95_latency_ms,diverged\n')
        rows = ([('sync', reference)] if reference is not None else []) + [('async', m) for m in measures]
        for mode, m in rows:
            speedup = m.throughput / base
            file.write(f'{prefix_values},{mode},{m.workers},{m.wall:.4f},{m.cpu:.4f},{m.load_flows},'
                       f'{m.throughput:.3f},{speedup:.3f},{speedup / max(m.workers, 1):.3f},{m.cores_busy:.3f},'
                       f'{m.loop_busy:.3f},'
                       f'{1e3 * statistics.fmean(m.latencies):.3f},{1e3 * np.percentile(m.latencies, 95):.3f},'
                       f'{m.diverged}\n')
    print(f'\nresults written to {path}')


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--network', default='ieee300',
                        help='one of %s, or a network file path (default: ieee300)' % ', '.join(CREATORS))
    parser.add_argument('--workers', default='1,2,3,4,6,8,12,16',
                        help='comma separated worker counts to benchmark')
    parser.add_argument('--total-lf', type=int, default=256,
                        help='total number of load flows per configuration (constant work, default: 256)')
    parser.add_argument('--gens', type=int, default=10, help='number of generators shifted before each load flow')
    parser.add_argument('--shift', type=float, default=0.02, help='relative p0 shift amplitude (default: 2%%)')
    parser.add_argument('--repeat', type=int, default=3, help='repetitions per configuration, fastest is kept')
    parser.add_argument('--warmup', type=int, default=32, help='number of warm-up load flows')
    parser.add_argument('--no-network-cache', action='store_true', help='disable OpenLoadFlow networkCacheEnabled')
    parser.add_argument('--no-multi-thread-variant', action='store_true',
                        help='load the network without allow_variant_multi_thread_access (expected to fail)')
    parser.add_argument('--distributed-slack', action='store_true', help='enable slack distribution')
    parser.add_argument('--no-sync-reference', action='store_true', help='skip the blocking run_ac reference')
    parser.add_argument('--csv', help='write the results to this CSV file')
    args = parser.parse_args(argv)

    worker_counts = [int(w) for w in args.workers.split(',') if w]
    parameters = build_parameters(not args.no_network_cache, args.distributed_slack)
    network = load_network(args.network, not args.no_multi_thread_variant)
    gen_ids, base_p = pick_generators(network, args.gens)

    print(f'pypowsybl {pp.__version__} | provider {lf.get_default_provider()} | {os.cpu_count()} CPUs')
    print(f'network {args.network}: {len(network.get_buses())} buses, {len(network.get_branches())} branches, '
          f'{len(network.get_generators())} generators')
    print(f'{args.total_lf} load flows per configuration, {args.gens} generators shifted by +/-{100 * args.shift:.1f}%, '
          f'networkCacheEnabled={not args.no_network_cache}, '
          f'allow_variant_multi_thread_access={not args.no_multi_thread_variant}, '
          f'repeat={args.repeat}\n')

    if args.warmup > 0:
        run_sync_case(network, args.warmup, gen_ids, base_p, args.shift, parameters)

    reference = None
    if not args.no_sync_reference:
        reference = best_of([run_sync_case(network, args.total_lf, gen_ids, base_p, args.shift, parameters)
                             for _ in range(args.repeat)])

    measures = []
    for workers in worker_counts:
        measures.append(best_of([run_async_case(network, workers, args.total_lf, gen_ids, base_p, args.shift,
                                                parameters)
                                 for _ in range(args.repeat)]))

    print_table(reference, measures)
    if args.csv:
        write_csv(args.csv, reference, measures,
                  {'network': args.network, 'total_lf': args.total_lf, 'gens': args.gens,
                   'network_cache': not args.no_network_cache, 'cpus': os.cpu_count()})
    return 0


if __name__ == '__main__':
    sys.exit(main())
