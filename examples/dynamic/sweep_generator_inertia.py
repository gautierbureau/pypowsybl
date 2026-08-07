# Copyright (c) 2026, RTE (http://www.rte-france.com)
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
# SPDX-License-Identifier: MPL-2.0
"""
Sweep a generator's inertia and watch its speed swing change.

One mapping is created once, then a value in it is changed and the simulation run again for each
inertia in the sweep. The mapping is a recipe: it is created without a network, and the parameter
sets it generates do not exist until it is first applied. So the first ``update_parameter_value``
is held and applied when the mapping resolves; the rest change a set that is already there. Either
way the value is in place by the time the models are built, so the loop reads as if the parameters
were always there.

Needs an open source Dynawo installation. Point ``dynawo.home_dir`` at it in ``~/.itools/config.yml``,
or set ``DYNAWO__HOME_DIR`` in the environment.
"""
import pandas as pd

import pypowsybl as pp
import pypowsybl.dynamic as dyn
import pypowsybl.loadflow as lf


def ieee14_with_sources() -> pp.network.Network:
    """IEEE14 with an energy source on each machine, which is what the controls are read from."""
    network = pp.network.create_ieee14()
    network.update_generators(pd.DataFrame(
        index=["B1-G", "B2-G", "B3-G", "B6-G", "B8-G"],
        data={"energy_source": ["NUCLEAR", "THERMAL", "THERMAL", "HYDRO", "HYDRO"]}))
    lf.run_ac(network)
    return network


def main() -> None:
    network = ieee14_with_sources()

    mapping = dyn.ModelMapping()
    mapping.create_mapping("UniversalDynaWaltz")

    variables = dyn.OutputVariableMapping()
    variables.add_dynamic_model_curves("B1-G", ["generator_omegaPu"])

    swings = {}
    for inertia in [3.0, 4.0, 5.0, 6.0, 8.0]:
        # the first pass writes the set this names, so this change is held until then; the rest
        # find it already there
        mapping.update_parameter_value("DynaWaltz_B1-G", "generator_H", inertia)

        result = dyn.Simulation().run(network, mapping, dyn.EventMapping(), variables)
        curves = result.curves()
        if result.status().name != "SUCCESS" or curves.empty:
            print(f"H = {inertia:>4}  ->  {result.status().name}: {result.status_text()}")
            continue
        # how far the machine's speed strayed from nominal over the run, in per unit
        omega = curves.iloc[:, 0]
        swings[inertia] = float((omega - 1.0).abs().max())
        print(f"H = {inertia:>4}  ->  status {result.status().name:<8}  "
              f"peak speed deviation {swings[inertia]:.3e} pu")

    if swings:
        print("\nthe speed swing changes with the inertia the loop set:")
        print(pd.Series(swings, name="peak_speed_deviation_pu").rename_axis("generator_H"))


if __name__ == "__main__":
    main()
