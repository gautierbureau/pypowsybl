# Copyright (c) 2026, RTE (http://www.rte-france.com)
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
# SPDX-License-Identifier: MPL-2.0
"""
Run the Nordic 32 test system, whose controls come from the system, not a rule.

Nordic 32 is a voltage stability study: its machines run a Nordic governor and regulator, or hold
their mechanical power constant, and which is which is a fact of the system rather than something
deduced from an energy source. So the controls are set with the ``Nordic32`` provider, machine by
machine, before the mapping chooses a model for them.

There is no detailed counterpart of these controls, so the study is DynaWaltz only. The Nordic
machines are not taken to sit behind a transformer, so ``tso_voltage_min`` is set above their
voltage, which lands them on the plain models the reference uses rather than the transformer ones.

This example goes as far as the model each machine resolves to, which is what the controls decide.
Running the system needs the parameter values the Nordic models expect, which the reference ships
as a hand written ``Nordic.par`` and the generic parameter generator does not yet produce for these
models; that is a step of its own, not shown here.

Load the Nordic network from a Dynawo distribution, for instance
``<dynawo>/examples/DynaWaltz/Nordic/Nordic.xiidm``, and point ``dynawo.home_dir`` at that Dynawo.
"""
import sys

import pypowsybl as pp
import pypowsybl.dynamic as dyn
import pypowsybl.loadflow as lf


def main(nordic_xiidm: str) -> None:
    network = pp.network.load(nordic_xiidm)
    lf.run_ac(network)

    # what the providers offer, name and description
    print(dyn.get_generator_properties_providers())

    # the controls of the system, machine by machine, before any model is chosen
    dyn.set_generator_properties(network, "Nordic32")

    # read them back off the network to see what was written
    properties = network.get_extensions("synchronousGeneratorProperties")
    print("\ncontrols set on the machines:")
    print(properties[["governor", "voltageRegulator", "numberOfWindings"]].to_string())

    mapping = dyn.ModelMapping()
    # a voltage above the Nordic machines, so none is taken behind a transformer
    mapping.create_mapping("UniversalDynaWaltz", tso_voltage_min=1000)

    print("\nthe model each machine resolved to, from the controls set above:")
    models = mapping.get_models(network)
    generators = models[models["static_id"].str.match(r"g\d+")]
    print(generators["model"].to_string())


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("usage: nordic32_energy_source_free.py <path to Nordic.xiidm>")
        raise SystemExit(2)
    main(sys.argv[1])
