# Copyright (c) 2026, RTE (http://www.rte-france.com)
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
# SPDX-License-Identifier: MPL-2.0
"""
Take the model a mapping chose for one machine and put another in its place.

A mapping gives every machine a model on its own, but a study may want a particular one somewhere
it knows better. ``update_dynamic_model`` replaces the model of a single equipment, leaving the
rest of the mapping as it was. Here the machine the mapping put on a model carrying a transformer
and auxiliaries is moved to the plain one, without touching the others.

The override is named right after the mapping, before any network is bound: like the mapping
itself it waits, and is settled when the models are resolved by ``get_models`` or the run. Saying
nothing of its parameters keeps the set the mapping wrote, so only the model changes.

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

    # what the mapping chose for B3-G on its own
    chosen = mapping.get_models(network).loc["B3-G", "model"]
    print(f"mapping chose {chosen} for B3-G")

    # put the plain model there instead, keeping the parameter set the mapping wrote for it
    mapping.update_dynamic_model(
        category_name="SynchronousGenerator",
        static_id="B3-G",
        parameter_set_id="DynaWaltz_B3-G",
        model_name="GeneratorSynchronousFourWindingsGoverPropVRPropInt")

    models = mapping.get_models(network)
    print(f"B3-G now on   {models.loc['B3-G', 'model']}")
    print("\nevery machine and its model:")
    print(models[models["static_id"].str.endswith("-G")][["model", "parameter_set_id"]].to_string())

    result = dyn.Simulation().run(network, mapping, dyn.EventMapping())
    print(f"\nsimulation status: {result.status().name}")


if __name__ == "__main__":
    main()
