/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.python.dynamic;

import com.powsybl.commons.PowsyblException;
import com.powsybl.dataframe.update.DefaultUpdatingDataframe;
import com.powsybl.dataframe.update.StringSeries;
import com.powsybl.dataframe.update.UpdatingDataframe;
import com.powsybl.dynawo.extensions.api.generator.SynchronousGeneratorProperties.Windings;
import com.powsybl.dynawo.mappings.preassembled.ControlUnitCatalog;
import com.powsybl.dynawo.mappings.preassembled.ControlUnitProvider;
import com.powsybl.dynawo.mappings.preassembled.GeneratorControls;
import com.powsybl.dynawo.mappings.preassembled.GeneratorModelDesigner;
import com.powsybl.dynawo.mappings.preassembled.MachineControlUnit;
import com.powsybl.dynawo.mappings.preassembled.ModelNaming;
import com.powsybl.dynawo.mappings.preassembled.PreassembledModel;
import com.powsybl.dynawo.mappings.preassembled.RegulatorControlUnit;
import com.powsybl.python.dynamic.ControlUnitsDataframes.ControlUnitRow;
import com.powsybl.python.dynamic.ControlUnitsDataframes.MachineQuantityRow;
import com.powsybl.dynawo.mappings.preassembled.UnitConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reads controls described in Python, one row per connection, into what the catalog takes.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class ControlUnitsDataframesTest {

    private static final String GOVERNORS = "Dynawo.Electrical.Controls.Machines.Governors.";
    private static final String REGULATORS = "Dynawo.Electrical.Controls.Machines.VoltageRegulators.";
    private static final String STABILISERS = "Dynawo.Electrical.Controls.Machines.PowerSystemStabilizers.";

    private static final String[] COLUMNS = {"kind", "model", "init_model", "instance_id", "own_var", "target_kind", "target", "init"};

    @AfterEach
    void resetCatalog() {
        ControlUnitCatalog.getInstance().resetToBase();
    }

    /**
     * Each row: name, then the columns above.
     */
    private static UpdatingDataframe dataframe(String[]... rows) {
        DefaultUpdatingDataframe dataframe = new DefaultUpdatingDataframe(rows.length);
        dataframe.addSeries("name", true, column(rows, 0));
        for (int c = 0; c < COLUMNS.length; c++) {
            dataframe.addSeries(COLUMNS[c], false, column(rows, c + 1));
        }
        return dataframe;
    }

    private static StringSeries column(String[][] rows, int index) {
        return i -> rows[i][index];
    }

    private static String[] row(String name, String kind, String model, String initModel, String instanceId,
                                String ownVar, String targetKind, String target, boolean init) {
        return new String[] {name, kind, model, initModel, instanceId, ownVar, targetKind, target, Boolean.toString(init)};
    }

    /**
     * GovCt2, St5c and Pss2a, as Dynawo ships them, under names of their own.
     */
    private static UpdatingDataframe governorRegulatorAndStabiliser() {
        String gov = GOVERNORS + "Standard.Generic.GovCt2";
        String govInit = GOVERNORS + "GovernorPmPGen_INIT";
        String vr = REGULATORS + "Standard.St5c";
        String vrInit = REGULATORS + "Standard.St15c_INIT";
        String pss = STABILISERS + "Standard.Pss2a";
        String pssInit = STABILISERS + "Pss_INIT";
        return dataframe(
                row("MyGov", "GOVERNOR", gov, govInit, "governor", "PGenPu", "MACHINE", "ACTIVE_POWER", false),
                row("MyGov", "GOVERNOR", gov, govInit, "governor", "PmPu", "MACHINE", "MECHANICAL_POWER", false),
                row("MyGov", "GOVERNOR", gov, govInit, "governor", "omegaPu", "MACHINE", "SPEED", false),
                row("MyGov", "GOVERNOR", gov, govInit, "governor", "PGen0Pu", "MACHINE", "INIT_ACTIVE_POWER", true),
                row("MyGov", "GOVERNOR", gov, govInit, "governor", "Pm0Pu", "MACHINE", "INIT_MECHANICAL_POWER", true),
                row("MyVr", "VOLTAGE_REGULATOR", vr, vrInit, "voltageRegulator", "EfdPu", "MACHINE", "FIELD_VOLTAGE", false),
                row("MyVr", "VOLTAGE_REGULATOR", vr, vrInit, "voltageRegulator", "IrPu", "MACHINE", "ROTOR_CURRENT", false),
                row("MyVr", "VOLTAGE_REGULATOR", vr, vrInit, "voltageRegulator", "UsPu", "MACHINE", "STATOR_VOLTAGE", false),
                row("MyVr", "VOLTAGE_REGULATOR", vr, vrInit, "voltageRegulator", "Efd0Pu", "MACHINE", "INIT_FIELD_VOLTAGE", true),
                row("MyVr", "VOLTAGE_REGULATOR", vr, vrInit, "voltageRegulator", "Ir0Pu", "MACHINE", "INIT_ROTOR_CURRENT", true),
                row("MyVr", "VOLTAGE_REGULATOR", vr, vrInit, "voltageRegulator", "Us0Pu", "MACHINE", "INIT_STATOR_VOLTAGE", true),
                row("MyVr", "VOLTAGE_REGULATOR", vr, vrInit, "voltageRegulator", "UPssPu", "REGULATOR_INPUT", "STABILISER", false),
                row("MyPss", "PSS", pss, pssInit, "powerSystemStabilizer", "PGenPu", "MACHINE", "ACTIVE_POWER", false),
                row("MyPss", "PSS", pss, pssInit, "powerSystemStabilizer", "omegaPu", "MACHINE", "SPEED", false),
                row("MyPss", "PSS", pss, pssInit, "powerSystemStabilizer", "PGen0Pu", "MACHINE", "INIT_ACTIVE_POWER", true),
                row("MyPss", "PSS", pss, pssInit, "powerSystemStabilizer", "VPssPu", "REGULATOR_INPUT", "STABILISER", false));
    }

    @Test
    void shouldReadControlsTheCatalogDesignsAModelWith() {
        ControlUnitProvider units = ControlUnitsDataframes.read(governorRegulatorAndStabiliser());
        assertThat(units.getGovernors()).hasSize(1);
        assertThat(units.getVoltageRegulators()).hasSize(1);
        assertThat(units.getRegulatorControls()).hasSize(1);

        ControlUnitCatalog catalog = ControlUnitCatalog.getInstance();
        catalog.register(units);
        PreassembledModel model = new GeneratorModelDesigner(catalog, ModelNaming.DYNAWO_1_7_0)
                .design(new GeneratorControls("MyGov", "MyVr", "MyPss"), Windings.FOUR_WINDINGS, false, false)
                .orElseThrow();

        assertThat(model.getId()).isEqualTo("GeneratorSynchronousFourWindingsMyGovMyVrMyPss");
        assertThat(model.getConnections()).contains(
                new UnitConnection("governor", "omegaPu", "generator", "omegaPu.value", false),
                new UnitConnection("governor", "Pm0Pu", "generator", "Pm0Pu", true),
                new UnitConnection("voltageRegulator", "Us0Pu", "generator", "UStator0Pu", true),
                // the stabiliser meets the input the regulator names for it
                new UnitConnection("powerSystemStabilizer", "VPssPu", "voltageRegulator", "UPssPu", false));
    }

    @Test
    void shouldGiveARegulatorTheExciterItDrives() {
        String exciter = "MyLibrary.Exciters.MyExciter";
        String vr = "MyLibrary.Regulators.MyRegulator";
        ControlUnitProvider units = ControlUnitsDataframes.read(dataframe(
                // the regulator before the exciter it drives
                row("MyRegulator", "VOLTAGE_REGULATOR", vr, "", "voltageRegulator", "UsPu", "MACHINE", "STATOR_VOLTAGE", false),
                row("MyRegulator", "VOLTAGE_REGULATOR", vr, "", "voltageRegulator", "", "EXCITER", "MyExciter", false),
                row("MyExciter", "EXCITER", exciter, "", "exciter", "EfdPu", "MACHINE", "FIELD_VOLTAGE", false),
                row("MyExciter", "EXCITER", exciter, "", "exciter", "VROutPu", "REGULATOR_LINK", "", false)));

        assertThat(units.getVoltageRegulators()).hasSize(1);
        MachineControlUnit regulator = units.getVoltageRegulators().get(0);
        assertThat(regulator.getExciter()).isNotNull();
        assertThat(regulator.getExciter().getName()).isEqualTo(exciter);
        assertThat(regulator.getInitName()).as("no init model given").isNull();
        // an exciter is not a control of its own in the catalog
        assertThat(units.getGovernors()).isEmpty();
    }

    @Test
    void shouldReadAControlMakingNoConnection() {
        ControlUnitProvider units = ControlUnitsDataframes.read(dataframe(
                row("Fixed", "GOVERNOR", "MyLibrary.Fixed", "", "governor", "", "", "", false)));

        assertThat(units.getGovernors()).singleElement()
                .satisfies(governor -> assertThat(governor.getCatalogName()).isEqualTo("Fixed"));
    }

    @Test
    void shouldRefuseAQuantityTheMachineHasNot() {
        UpdatingDataframe dataframe = dataframe(
                row("MyGov", "GOVERNOR", "M", "", "governor", "omegaPu", "MACHINE", "SPEEDD", false));

        assertThatThrownBy(() -> ControlUnitsDataframes.read(dataframe))
                .isInstanceOf(PowsyblException.class)
                .hasMessageContaining("SPEEDD")
                .hasMessageContaining("MyGov");
    }

    @Test
    void shouldRefuseAConnectionTheKindDoesNotMake() {
        UpdatingDataframe dataframe = dataframe(
                row("MyGov", "GOVERNOR", "M", "", "governor", "UPssPu", "REGULATOR_INPUT", "STABILISER", false));

        assertThatThrownBy(() -> ControlUnitsDataframes.read(dataframe))
                .isInstanceOf(PowsyblException.class)
                .hasMessageContaining("REGULATOR_INPUT is not one a GOVERNOR makes");
    }

    @Test
    void shouldRefuseAControlDescribedTwiceDifferently() {
        UpdatingDataframe dataframe = dataframe(
                row("MyGov", "GOVERNOR", "A", "", "governor", "omegaPu", "MACHINE", "SPEED", false),
                row("MyGov", "GOVERNOR", "B", "", "governor", "PmPu", "MACHINE", "MECHANICAL_POWER", false));

        assertThatThrownBy(() -> ControlUnitsDataframes.read(dataframe))
                .isInstanceOf(PowsyblException.class)
                .hasMessageContaining("described twice");
    }

    @Test
    void shouldRefuseAnExciterNotDescribed() {
        UpdatingDataframe dataframe = dataframe(
                row("MyRegulator", "VOLTAGE_REGULATOR", "M", "", "voltageRegulator", "", "EXCITER", "Nowhere", false));

        assertThatThrownBy(() -> ControlUnitsDataframes.read(dataframe))
                .isInstanceOf(PowsyblException.class)
                .hasMessageContaining("Nowhere");
    }

    @Test
    void shouldGiveARegulatorControlTheKindItIsDeclared() {
        ControlUnitProvider units = ControlUnitsDataframes.read(dataframe(
                row("MyUel", "UNDER_EXCITATION_LIMITER", "M", "", "underExcitationLimiter", "UUelPu", "REGULATOR_INPUT", "UNDER_EXCITATION", false)));

        assertThat(units.getRegulatorControls()).singleElement()
                .satisfies(c -> assertThat(c.getKind()).hasValue(RegulatorControlUnit.Kind.UNDER_EXCITATION_LIMITER));
        assertThatThrownBy(() -> ControlUnitsDataframes.read(dataframe(
                row("MyPss", "REGULATOR_CONTROL", "M", "", "pss", "VPssPu", "REGULATOR_INPUT", "STABILISER", false))))
                .isInstanceOf(PowsyblException.class)
                .hasMessageContaining("REGULATOR_CONTROL");
    }

    @Test
    void shouldWriteWhatShipsInTheColumnsItReads() {
        List<ControlUnitRow> rows = ControlUnitsDataframes.rows(ControlUnitCatalog.shipped());

        assertThat(rows).filteredOn(r -> r.name().equals("GovCt2")).containsExactly(
                new ControlUnitRow("GovCt2", "GOVERNOR", GOVERNORS + "Standard.Generic.GovCt2", GOVERNORS + "GovernorPmPGen_INIT",
                        "governor", "PGenPu", "MACHINE", "ACTIVE_POWER", false),
                new ControlUnitRow("GovCt2", "GOVERNOR", GOVERNORS + "Standard.Generic.GovCt2", GOVERNORS + "GovernorPmPGen_INIT",
                        "governor", "PmPu", "MACHINE", "MECHANICAL_POWER", false),
                new ControlUnitRow("GovCt2", "GOVERNOR", GOVERNORS + "Standard.Generic.GovCt2", GOVERNORS + "GovernorPmPGen_INIT",
                        "governor", "omegaPu", "MACHINE", "SPEED", false),
                new ControlUnitRow("GovCt2", "GOVERNOR", GOVERNORS + "Standard.Generic.GovCt2", GOVERNORS + "GovernorPmPGen_INIT",
                        "governor", "PGen0Pu", "MACHINE", "INIT_ACTIVE_POWER", true),
                new ControlUnitRow("GovCt2", "GOVERNOR", GOVERNORS + "Standard.Generic.GovCt2", GOVERNORS + "GovernorPmPGen_INIT",
                        "governor", "Pm0Pu", "MACHINE", "INIT_MECHANICAL_POWER", true));
        assertThat(rows).filteredOn(r -> r.name().equals("Pss2a")).extracting(ControlUnitRow::kind).containsOnly("PSS");
        assertThat(rows).filteredOn(r -> r.name().equals("St4b"))
                .contains(new ControlUnitRow("St4b", "VOLTAGE_REGULATOR", REGULATORS + "Standard.St4b",
                        REGULATORS + "Standard.St4b_INIT", "voltageRegulator", "UPssPu", "REGULATOR_INPUT", "STABILISER", false));
    }

    @Test
    void shouldReadBackWhatItWrote() {
        ControlUnitCatalog shipped = ControlUnitCatalog.shipped();
        List<ControlUnitRow> written = ControlUnitsDataframes.rows(shipped).stream()
                .filter(r -> List.of("GovCt2", "St4b", "Pss2a").contains(r.name()))
                .toList();

        ControlUnitProvider read = ControlUnitsDataframes.read(dataframe(written.stream()
                .map(r -> new String[] {r.name(), r.kind(), r.model(), r.initModel(), r.instanceId(),
                    r.ownVar(), r.targetKind(), r.target(), Boolean.toString(r.init())})
                .toArray(String[][]::new)));

        assertThat(read.getGovernors()).singleElement()
                .satisfies(g -> assertThat(g.getWires()).isEqualTo(shipped.getGovernors().get("GovCt2").getWires()));
        assertThat(read.getVoltageRegulators()).singleElement()
                .satisfies(v -> {
                    assertThat(v.getWires()).isEqualTo(shipped.getVoltageRegulators().get("St4b").getWires());
                    assertThat(v.getInputs()).isEqualTo(shipped.getVoltageRegulators().get("St4b").getInputs());
                });
        assertThat(read.getRegulatorControls()).singleElement()
                .satisfies(p -> {
                    assertThat(p.getKind()).hasValue(RegulatorControlUnit.Kind.PSS);
                    assertThat(p.getWatches()).isEqualTo(shipped.getRegulatorControls().get("Pss2a").getWatches());
                    assertThat(p.getDrives()).isEqualTo(shipped.getRegulatorControls().get("Pss2a").getDrives());
                });
    }

    @Test
    void shouldListWhatAMachineOffers() {
        List<MachineQuantityRow> quantities = ControlUnitsDataframes.machineQuantities(Windings.FOUR_WINDINGS, false);

        assertThat(quantities).contains(
                new MachineQuantityRow("SPEED", false, "omegaPu.value"),
                new MachineQuantityRow("INIT_MECHANICAL_POWER", true, "Pm0Pu"));
        assertThat(quantities).extracting(MachineQuantityRow::quantity).doesNotContain("RUNNING");
    }
}
