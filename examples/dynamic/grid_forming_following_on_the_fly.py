# Copyright (c) 2026, RTE (http://www.rte-france.com)
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
# SPDX-License-Identifier: MPL-2.0
"""
Add grid-forming / grid-following machines to a study entirely from Python.

A recipe (here ``RteDynaSwing``) maps the machines a network carries. On top of it, a study can bring
models the platform does not ship and give equipments those models, without editing any Java or JSON:

  1. describe two extra models -- ``DynGridFormingVirtualSynchronousMachine`` and ``DynGridFollowing`` --
     as :class:`~pypowsybl.dynamic.ModelConfig` and register them on the mapping with
     ``add_model_configs``. Each is registered under the existing ``BASE_GENERATOR`` category but
     carries its OWN variables and connection point through ``var_mapping`` / ``var_prefix``, so it
     wires like a dedicated model rather than a plain base generator.
  2. say which machines are grid forming or grid following through the ``synchronizedGeneratorProperties``
     extension, set from Python.
  3. create the models' parameter sets from Python (a ``.par`` loaded into the mapping).
  4. express the choice of model in Python -- forming to the virtual synchronous machine, following to
     the grid-following model on the set its energy source calls for -- and add it with
     ``add_dynamic_model``.

The additional models are registered when the mapping resolves, so they show up in ``get_models`` (used
here) and equally in a run.

Run it on the IEEE118 the RTE mapping targets, for instance the ``ieee118_full.xiidm`` of this repository:
``grid_forming_following_on_the_fly.py <path to ieee118_full.xiidm>``.

Needs a Dynawo installation. Point ``dynawo.home_dir`` at it in ``~/.itools/config.yml`` or set
``DYNAWO__HOME_DIR`` in the environment. Running the simulation (rather than only reading ``get_models``)
additionally needs a Dynawo whose catalog carries the grid-forming / grid-following Modelica models.
"""
import sys
import tempfile

import pandas as pd

import pypowsybl as pp
import pypowsybl.dynamic as dyn

# The parameter sets the two models read, one per energy source for the grid-following model. Their
# ``reference`` entries follow the machine's own IIDM data (p_pu, q_pu, ...); the rest are fixed.
PARAMETERS = """<?xml version="1.0" encoding="UTF-8"?>
<parametersSet xmlns="http://www.rte-france.com/dynawo">
  <set id="GFM">
    <reference name="GFM_P0Pu" origData="IIDM" origName="p_pu" type="DOUBLE"/>
    <reference name="GFM_Q0Pu" origData="IIDM" origName="q_pu" type="DOUBLE"/>
    <reference name="GFM_U0Pu" origData="IIDM" origName="v_pu" type="DOUBLE"/>
    <reference name="GFM_UPhase0" origData="IIDM" origName="angle_pu" type="DOUBLE"/>
    <reference name="GFM_SNom" origData="IIDM" origName="pMax" type="DOUBLE"/>
    <par name="GFM_CFilterPu" type="DOUBLE" value="1e-05"/>
    <par name="GFM_H" type="DOUBLE" value="3"/>
    <par name="GFM_IMaxVI" type="DOUBLE" value="1.2"/>
    <par name="GFM_Kfd" type="DOUBLE" value="0.8"/>
    <par name="GFM_Kff" type="DOUBLE" value="0"/>
    <par name="GFM_Kfq" type="DOUBLE" value="0"/>
    <par name="GFM_Kic" type="DOUBLE" value="15"/>
    <par name="GFM_KpVI" type="DOUBLE" value="0.6"/>
    <par name="GFM_Kpc" type="DOUBLE" value="0.477465"/>
    <par name="GFM_LFilterPu" type="DOUBLE" value="0.15"/>
    <par name="GFM_LTransformerPu" type="DOUBLE" value="0.16"/>
    <par name="GFM_Mq" type="DOUBLE" value="0.2"/>
    <par name="GFM_OmegaSetPu" type="DOUBLE" value="1"/>
    <par name="GFM_RFilterPu" type="DOUBLE" value="0.015"/>
    <par name="GFM_RTransformerPu" type="DOUBLE" value="0.006"/>
    <par name="GFM_Wf" type="DOUBLE" value="31.4159"/>
    <par name="GFM_Wff" type="DOUBLE" value="60"/>
    <par name="GFM_XRratio" type="DOUBLE" value="10"/>
    <par name="GFM_XVI" type="DOUBLE" value="0.0"/>
    <par name="GFM_kVSM" type="DOUBLE" value="80"/>
    <par name="GFM_tVSC" type="DOUBLE" value="0.0004"/>
  </set>
  <set id="GFL_Battery">{gfl}</set>
  <set id="GFL_Wind">{gfl}</set>
  <set id="GFL_Solar">{gfl}</set>
</parametersSet>
""".format(gfl="""
    <reference name="GFL_P0Pu" origData="IIDM" origName="p_pu" type="DOUBLE"/>
    <reference name="GFL_Q0Pu" origData="IIDM" origName="q_pu" type="DOUBLE"/>
    <reference name="GFL_U0Pu" origData="IIDM" origName="v_pu" type="DOUBLE"/>
    <reference name="GFL_UPhase0" origData="IIDM" origName="angle_pu" type="DOUBLE"/>
    <reference name="GFL_SNom" origData="IIDM" origName="pMax" type="DOUBLE"/>
    <par type="DOUBLE" name="GFL_CFilterPu" value="0.00001"/>
    <par type="DOUBLE" name="GFL_Ki" value="10"/>
    <par type="DOUBLE" name="GFL_Kic" value="7"/>
    <par type="DOUBLE" name="GFL_Kid" value="50"/>
    <par type="DOUBLE" name="GFL_Kiq" value="50"/>
    <par type="DOUBLE" name="GFL_Kp" value="2"/>
    <par type="DOUBLE" name="GFL_Kpc" value="0.5"/>
    <par type="DOUBLE" name="GFL_Kpd" value="0.11"/>
    <par type="DOUBLE" name="GFL_Kpq" value="0.11"/>
    <par type="DOUBLE" name="GFL_LFilterPu" value="0.1"/>
    <par type="DOUBLE" name="GFL_LTransformerPu" value="0.05"/>
    <par type="DOUBLE" name="GFL_OmegaMaxPu" value="1.1"/>
    <par type="DOUBLE" name="GFL_OmegaMinPu" value="0.9"/>
    <par type="DOUBLE" name="GFL_RFilterPu" value="0.003"/>
    <par type="DOUBLE" name="GFL_RTransformerPu" value="0.002"/>
    <par type="DOUBLE" name="GFL_tPFilt" value="0.01"/>
    <par type="DOUBLE" name="GFL_tQFilt" value="0.01"/>
    <par type="DOUBLE" name="GFL_tVSC" value="0.0004"/>
  """)

# one machine per kind: the forming one, and a following one per energy source. The id of the battery
# machine carries ``_BAT``, which the rule keys on since a battery has no dedicated energy source.
# Each tuple is (machine id, energy source).
NEW_MACHINES = [
    ("GEN_GFM", "OTHER"),
    ("GEN_GFL_BAT", "OTHER"),
    ("GEN_GFL_WIND", "WIND"),
    ("GEN_GFL_SOLAR", "SOLAR"),
]

FORMING_MODEL = "DynGridFormingVirtualSynchronousMachine"
FOLLOWING_MODEL = "DynGridFollowing"


def network_with_new_machines(ieee118_xiidm: str) -> pp.network.Network:
    """The IEEE118 with the four grid-forming / grid-following machines added.

    Each machine is added on a fresh bus-breaker voltage level, which keeps this example independent
    of the base network's topology (the IEEE118 here is node-breaker).
    """
    network = pp.network.load(ieee118_xiidm)
    for machine_id, energy_source in NEW_MACHINES:
        network.create_substations(id=f"S_{machine_id}")
        network.create_voltage_levels(id=f"VL_{machine_id}", substation_id=f"S_{machine_id}",
                                      topology_kind="BUS_BREAKER", nominal_v=400.0)
        network.create_buses(id=f"B_{machine_id}", voltage_level_id=f"VL_{machine_id}")
        network.create_generators(id=machine_id, voltage_level_id=f"VL_{machine_id}", bus_id=f"B_{machine_id}",
                                  max_p=100.0, min_p=0.0, target_p=50.0, target_v=400.0,
                                  voltage_regulator_on=True, energy_source=energy_source)
    return network


def following_set(machine_id: str, energy_source: str) -> str:
    """The parameter set a grid-following machine reads, by what it stands for."""
    if "_BAT" in machine_id:
        return "GFL_Battery"
    if energy_source == "WIND":
        return "GFL_Wind"
    if energy_source == "SOLAR":
        return "GFL_Solar"
    return "GFL_Battery"


def main(ieee118_xiidm: str) -> None:
    network = network_with_new_machines(ieee118_xiidm)

    # Set the RTE extensions up from their databases, so a study can read or tweak what the machines
    # and automatons are described as before the mapping runs. This is optional -- the mapping fills
    # the same set in on its own when it resolves -- but shown here for completeness. Either the whole
    # set in one call:
    dyn.add_dynamic_simulation_extensions(network, "RteDynaSwing")
    # or one kind at a time; the extension is already there from the call above, so the provider
    # leaves it be (create if absent, use if present):
    dyn.add_extensions(network, "synchronousGeneratorProperties", "RteSynchronousGenerators")

    mapping = dyn.ModelMapping()
    mapping.create_mapping("RteDynaSwing")

    # 1) bring the two models in on the fly, each with its own variables and terminal connection point
    mapping.add_model_configs([
        dyn.ModelConfig(category="BASE_GENERATOR", lib=FORMING_MODEL, properties=["SYNCHRONIZED"],
                        var_mapping=[("GFM_Measurements_PFilterPu", "p"),
                                     ("GFM_Measurements_QFilterPu", "q"),
                                     ("GFM_state", "state")],
                        var_prefix={"terminal": "GFM_terminal"}),
        dyn.ModelConfig(category="BASE_GENERATOR", lib=FOLLOWING_MODEL, properties=["SYNCHRONIZED"],
                        var_mapping=[("GFL_Measurements_PFilterPu", "p"),
                                     ("GFL_Measurements_QFilterPu", "q"),
                                     ("GFL_state", "state")],
                        var_prefix={"terminal": "GFL_terminal"}),
    ])

    # 2) say which machine is forming and which are following, from Python
    network.create_extensions("synchronizedGeneratorProperties", pd.DataFrame.from_records(index="id", data=[
        {"id": machine_id, "type": "FORMING" if machine_id == "GEN_GFM" else "FOLLOWING", "rpcl2": False}
        for machine_id, _ in NEW_MACHINES]))

    # 3) create the parameter sets from Python
    with tempfile.NamedTemporaryFile("w", suffix=".par", delete=False) as parameters_file:
        parameters_file.write(PARAMETERS)
    mapping.load_parameters(parameters_file.name)

    # 4) express the rule and add the model to each machine
    energy_sources = network.get_generators(attributes=["energy_source"])
    for machine_id, row in network.get_extensions("synchronizedGeneratorProperties").iterrows():
        if row["type"] == "FORMING":
            mapping.add_dynamic_model("SimplifiedGenerator", static_id=machine_id,
                                      parameter_set_id="GFM", model_name=FORMING_MODEL)
        else:
            mapping.add_dynamic_model("SimplifiedGenerator", static_id=machine_id,
                                      parameter_set_id=following_set(machine_id, energy_sources.loc[machine_id, "energy_source"]),
                                      model_name=FOLLOWING_MODEL)

    # the additional models are registered as the mapping resolves, here at get_models
    models = mapping.get_models(network).reset_index()
    new = models[models["static_id"].str.startswith("GEN_GF")]
    print("grid-forming / grid-following machines resolved from Python:")
    print(new[["static_id", "model", "parameter_set_id"]].sort_values("static_id").to_string(index=False))


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("usage: grid_forming_following_on_the_fly.py <path to ieee118_full.xiidm>")
        raise SystemExit(2)
    main(sys.argv[1])
