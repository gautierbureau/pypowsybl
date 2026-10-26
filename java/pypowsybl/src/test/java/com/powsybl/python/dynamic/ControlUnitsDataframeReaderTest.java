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
import com.powsybl.dynawo.mappings.preassembled.UnitConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reads controls described in Python, one row per connection, into what the catalog takes.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class ControlUnitsDataframeReaderTest {

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
                row("MyPss", "REGULATOR_CONTROL", pss, pssInit, "powerSystemStabilizer", "PGenPu", "MACHINE", "ACTIVE_POWER", false),
                row("MyPss", "REGULATOR_CONTROL", pss, pssInit, "powerSystemStabilizer", "omegaPu", "MACHINE", "SPEED", false),
                row("MyPss", "REGULATOR_CONTROL", pss, pssInit, "powerSystemStabilizer", "PGen0Pu", "MACHINE", "INIT_ACTIVE_POWER", true),
                row("MyPss", "REGULATOR_CONTROL", pss, pssInit, "powerSystemStabilizer", "VPssPu", "REGULATOR_INPUT", "STABILISER", false));
    }

    @Test
    void shouldReadControlsTheCatalogDesignsAModelWith() {
        ControlUnitProvider units = ControlUnitsDataframeReader.read(governorRegulatorAndStabiliser());
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
        ControlUnitProvider units = ControlUnitsDataframeReader.read(dataframe(
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
        ControlUnitProvider units = ControlUnitsDataframeReader.read(dataframe(
                row("Fixed", "GOVERNOR", "MyLibrary.Fixed", "", "governor", "", "", "", false)));

        assertThat(units.getGovernors()).singleElement()
                .satisfies(governor -> assertThat(governor.getCatalogName()).isEqualTo("Fixed"));
    }

    @Test
    void shouldRefuseAQuantityTheMachineHasNot() {
        UpdatingDataframe dataframe = dataframe(
                row("MyGov", "GOVERNOR", "M", "", "governor", "omegaPu", "MACHINE", "SPEEDD", false));

        assertThatThrownBy(() -> ControlUnitsDataframeReader.read(dataframe))
                .isInstanceOf(PowsyblException.class)
                .hasMessageContaining("SPEEDD")
                .hasMessageContaining("MyGov");
    }

    @Test
    void shouldRefuseAConnectionTheKindDoesNotMake() {
        UpdatingDataframe dataframe = dataframe(
                row("MyGov", "GOVERNOR", "M", "", "governor", "UPssPu", "REGULATOR_INPUT", "STABILISER", false));

        assertThatThrownBy(() -> ControlUnitsDataframeReader.read(dataframe))
                .isInstanceOf(PowsyblException.class)
                .hasMessageContaining("REGULATOR_INPUT is not one a GOVERNOR makes");
    }

    @Test
    void shouldRefuseAControlDescribedTwiceDifferently() {
        UpdatingDataframe dataframe = dataframe(
                row("MyGov", "GOVERNOR", "A", "", "governor", "omegaPu", "MACHINE", "SPEED", false),
                row("MyGov", "GOVERNOR", "B", "", "governor", "PmPu", "MACHINE", "MECHANICAL_POWER", false));

        assertThatThrownBy(() -> ControlUnitsDataframeReader.read(dataframe))
                .isInstanceOf(PowsyblException.class)
                .hasMessageContaining("described twice");
    }

    @Test
    void shouldRefuseAnExciterNotDescribed() {
        UpdatingDataframe dataframe = dataframe(
                row("MyRegulator", "VOLTAGE_REGULATOR", "M", "", "voltageRegulator", "", "EXCITER", "Nowhere", false));

        assertThatThrownBy(() -> ControlUnitsDataframeReader.read(dataframe))
                .isInstanceOf(PowsyblException.class)
                .hasMessageContaining("Nowhere");
    }
}
