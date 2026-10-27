/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.python.dynamic;

import com.powsybl.commons.PowsyblException;
import com.powsybl.dataframe.DataframeMapper;
import com.powsybl.dataframe.DataframeMapperBuilder;
import com.powsybl.dataframe.SeriesMetadata;
import com.powsybl.dataframe.update.UpdatingDataframe;
import com.powsybl.dynawo.extensions.api.generator.SynchronousGeneratorProperties.Windings;
import com.powsybl.dynawo.mappings.preassembled.ControlUnitCatalog;
import com.powsybl.dynawo.mappings.preassembled.ControlUnitProvider;
import com.powsybl.dynawo.mappings.preassembled.GeneratorSynchronousUnit;
import com.powsybl.dynawo.mappings.preassembled.MachineControlUnit;
import com.powsybl.dynawo.mappings.preassembled.MachineControlUnit.MachineQuantity;
import com.powsybl.dynawo.mappings.preassembled.MachineControlUnit.RegulatorInput;
import com.powsybl.dynawo.mappings.preassembled.ModelNaming;
import com.powsybl.dynawo.mappings.preassembled.RegulatorControlUnit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The machine controls as dataframes, both ways: read from the ones a study describes of its own,
 * so they can be registered in the control catalog and a model built out of them for the machines
 * whose extension names them, and written from the catalog, so what ships can be looked at, and
 * copied into a control of one's own.
 * <p>
 * The dataframe holds one row per connection a control makes, its definition repeated on each, so
 * a control is the rows sharing its name. A control making no connection at all is one row with
 * none.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public final class ControlUnitsDataframes {

    private static final String NAME = "name";
    private static final String KIND = "kind";
    private static final String MODEL = "model";
    private static final String INIT_MODEL = "init_model";
    private static final String INSTANCE_ID = "instance_id";
    private static final String OWN_VAR = "own_var";
    private static final String TARGET_KIND = "target_kind";
    private static final String TARGET = "target";
    private static final String INIT = "init";

    private static final List<SeriesMetadata> METADATA = List.of(
            SeriesMetadata.stringIndex(NAME),
            SeriesMetadata.strings(KIND),
            SeriesMetadata.strings(MODEL),
            SeriesMetadata.strings(INIT_MODEL),
            SeriesMetadata.strings(INSTANCE_ID),
            SeriesMetadata.strings(OWN_VAR),
            SeriesMetadata.strings(TARGET_KIND),
            SeriesMetadata.strings(TARGET),
            SeriesMetadata.strings(INIT));

    /**
     * What part a control plays. An exciter is not a control of its own in the catalog: it comes with
     * the voltage regulator {@linkplain TargetKind#EXCITER driving} it. The others act through the
     * voltage regulator, each the {@link RegulatorControlUnit.Kind} of the same name.
     */
    enum Kind {
        GOVERNOR,
        VOLTAGE_REGULATOR,
        EXCITER,
        PSS(RegulatorControlUnit.Kind.PSS),
        OVER_EXCITATION_LIMITER(RegulatorControlUnit.Kind.OVER_EXCITATION_LIMITER),
        UNDER_EXCITATION_LIMITER(RegulatorControlUnit.Kind.UNDER_EXCITATION_LIMITER),
        STATOR_CURRENT_LIMITER(RegulatorControlUnit.Kind.STATOR_CURRENT_LIMITER),
        REACTIVE_POWER_CONTROL_LOOP(RegulatorControlUnit.Kind.REACTIVE_POWER_CONTROL_LOOP);

        private final RegulatorControlUnit.Kind regulatorControlKind;

        Kind() {
            this(null);
        }

        Kind(RegulatorControlUnit.Kind regulatorControlKind) {
            this.regulatorControlKind = regulatorControlKind;
        }

        static Kind of(RegulatorControlUnit.Kind regulatorControlKind) {
            return valueOf(regulatorControlKind.name());
        }
    }

    /**
     * What a connection meets on the other side.
     */
    enum TargetKind {
        /** a quantity of the machine, the target naming a {@link MachineQuantity} */
        MACHINE,
        /**
         * an input of the voltage regulator, the target naming a {@link RegulatorInput}: the input a
         * regulator offers under its own variable, or the one a regulator control drives
         */
        REGULATOR_INPUT,
        /** a variable a regulator shares with the governor beside it, under the same name */
        GOVERNOR_LINK,
        /** a variable an exciter shares with the regulator driving it, under the same name */
        REGULATOR_LINK,
        /** the exciter a regulator drives, the target naming it */
        EXCITER
    }

    private record Row(int index, String ownVar, TargetKind targetKind, String target, boolean init) {
    }

    private record Definition(Kind kind, String model, String initModel, String instanceId) {
    }

    private record RegisteredControls(List<MachineControlUnit> governors,
                                      List<MachineControlUnit> voltageRegulators,
                                      List<RegulatorControlUnit> regulatorControls) implements ControlUnitProvider {

        @Override
        public List<MachineControlUnit> getGovernors() {
            return governors;
        }

        @Override
        public List<MachineControlUnit> getVoltageRegulators() {
            return voltageRegulators;
        }

        @Override
        public List<RegulatorControlUnit> getRegulatorControls() {
            return regulatorControls;
        }
    }

    private ControlUnitsDataframes() {
    }

    public static List<SeriesMetadata> getMetadata() {
        return METADATA;
    }

    public static ControlUnitProvider read(UpdatingDataframe dataframe) {
        Map<String, Definition> definitions = new LinkedHashMap<>();
        Map<String, List<Row>> rows = new LinkedHashMap<>();
        for (int row = 0; row < dataframe.getRowCount(); row++) {
            String name = required(dataframe, NAME, row, "a control");
            Definition definition = new Definition(
                    parse(Kind.class, required(dataframe, KIND, row, name), KIND, name),
                    required(dataframe, MODEL, row, name),
                    optional(dataframe, INIT_MODEL, row),
                    required(dataframe, INSTANCE_ID, row, name));
            Definition known = definitions.putIfAbsent(name, definition);
            if (known != null && !known.equals(definition)) {
                throw new PowsyblException("The control " + name + " is described twice, as " + known + " and as " + definition);
            }
            List<Row> connections = rows.computeIfAbsent(name, n -> new ArrayList<>());
            String targetKind = optional(dataframe, TARGET_KIND, row);
            if (targetKind != null) {
                TargetKind kind = parse(TargetKind.class, targetKind, TARGET_KIND, name);
                // every connection wires one of the control's own variables, but the one naming the
                // exciter a regulator drives, which brings its own connections with it
                String ownVar = kind == TargetKind.EXCITER ? null : required(dataframe, OWN_VAR, row, name);
                connections.add(new Row(row, ownVar, kind, optional(dataframe, TARGET, row),
                        Boolean.parseBoolean(optional(dataframe, INIT, row))));
            }
        }

        // the exciters first, so a regulator finds the one it drives whatever the order they came in
        Map<String, MachineControlUnit> exciters = new LinkedHashMap<>();
        definitions.forEach((name, definition) -> {
            if (definition.kind() == Kind.EXCITER) {
                exciters.put(name, machineControl(name, definition, rows.get(name), Map.of()));
            }
        });
        List<MachineControlUnit> governors = new ArrayList<>();
        List<MachineControlUnit> voltageRegulators = new ArrayList<>();
        List<RegulatorControlUnit> regulatorControls = new ArrayList<>();
        definitions.forEach((name, definition) -> {
            switch (definition.kind()) {
                case GOVERNOR -> governors.add(machineControl(name, definition, rows.get(name), exciters));
                case VOLTAGE_REGULATOR -> voltageRegulators.add(machineControl(name, definition, rows.get(name), exciters));
                case EXCITER -> {
                    // brought in by the regulator driving it
                }
                default -> regulatorControls.add(regulatorControl(name, definition, rows.get(name)));
            }
        });
        return new RegisteredControls(List.copyOf(governors), List.copyOf(voltageRegulators), List.copyOf(regulatorControls));
    }

    private static MachineControlUnit machineControl(String name, Definition definition, List<Row> rows,
                                                     Map<String, MachineControlUnit> exciters) {
        MachineControlUnit unit = new MachineControlUnit(definition.instanceId(), definition.model(), definition.initModel())
                .named(name);
        for (Row row : rows) {
            switch (row.targetKind()) {
                case MACHINE -> {
                    MachineQuantity quantity = parse(MachineQuantity.class, target(row, name), TARGET, name);
                    if (row.init()) {
                        unit.startingFrom(row.ownVar(), quantity);
                    } else {
                        unit.reading(row.ownVar(), quantity);
                    }
                }
                case REGULATOR_INPUT -> {
                    expect(definition, Kind.VOLTAGE_REGULATOR, row, name);
                    unit.accepting(parse(RegulatorInput.class, target(row, name), TARGET, name), row.ownVar());
                }
                case GOVERNOR_LINK -> {
                    expect(definition, Kind.VOLTAGE_REGULATOR, row, name);
                    unit.linkedToGovernor(row.ownVar());
                }
                case REGULATOR_LINK -> {
                    expect(definition, Kind.EXCITER, row, name);
                    if (row.init()) {
                        unit.linkedToRegulatorAtInit(row.ownVar());
                    } else {
                        unit.linkedToRegulator(row.ownVar());
                    }
                }
                case EXCITER -> {
                    expect(definition, Kind.VOLTAGE_REGULATOR, row, name);
                    MachineControlUnit exciter = exciters.get(target(row, name));
                    if (exciter == null) {
                        throw new PowsyblException("The voltage regulator " + name + " drives the exciter "
                                + row.target() + ", which is not described");
                    }
                    unit.driving(exciter);
                }
            }
        }
        return unit;
    }

    /**
     * One row of the dataframe written from the catalog.
     */
    record ControlUnitRow(String name, String kind, String model, String initModel, String instanceId,
                          String ownVar, String targetKind, String target, boolean init) {
    }

    /**
     * The controls a catalog holds, one row per connection, in the columns {@link #read} takes, so
     * a control written out can be read back once changed. A control whose kind is not known, one
     * a provider declared without it, is written with none, and has to be given one to be read back.
     */
    public static List<ControlUnitRow> rows(ControlUnitCatalog catalog) {
        List<ControlUnitRow> rows = new ArrayList<>();
        Map<String, MachineControlUnit> exciters = new LinkedHashMap<>();
        catalog.getGovernors().forEach((name, unit) -> machineControlRows(rows, name, Kind.GOVERNOR, unit));
        catalog.getVoltageRegulators().forEach((name, unit) -> {
            machineControlRows(rows, name, Kind.VOLTAGE_REGULATOR, unit);
            if (unit.getExciter() != null) {
                exciters.putIfAbsent(unit.getExciter().getCatalogName(), unit.getExciter());
            }
        });
        // each exciter once, however many regulators drive it
        exciters.forEach((name, unit) -> machineControlRows(rows, name, Kind.EXCITER, unit));
        catalog.getRegulatorControls().forEach((name, unit) -> {
            String kind = unit.getKind().map(k -> Kind.of(k).name()).orElse("");
            List<ControlUnitRow> own = new ArrayList<>();
            unit.getWatches().forEach(w -> own.add(row(name, kind, unit.getName(), unit.getInitName(), unit.getId(),
                    w.ownVar(), TargetKind.MACHINE, w.quantity().name(), w.initialisation())));
            unit.getDrives().forEach(d -> own.add(row(name, kind, unit.getName(), unit.getInitName(), unit.getId(),
                    d.ownVar(), TargetKind.REGULATOR_INPUT, d.input().name(), d.initialisation())));
            addOrNone(rows, own, name, kind, unit.getName(), unit.getInitName(), unit.getId());
        });
        return rows;
    }

    private static void machineControlRows(List<ControlUnitRow> rows, String name, Kind kind, MachineControlUnit unit) {
        String k = kind.name();
        List<ControlUnitRow> own = new ArrayList<>();
        unit.getWires().forEach(w -> own.add(row(name, k, unit.getName(), unit.getInitName(), unit.getId(),
                w.ownVar(), TargetKind.MACHINE, w.quantity().name(), w.initialisation())));
        unit.getInputs().forEach((input, var) -> own.add(row(name, k, unit.getName(), unit.getInitName(), unit.getId(),
                var, TargetKind.REGULATOR_INPUT, input.name(), false)));
        unit.getGovernorLinks().forEach(l -> own.add(row(name, k, unit.getName(), unit.getInitName(), unit.getId(),
                l.var(), TargetKind.GOVERNOR_LINK, "", l.initialisation())));
        unit.getRegulatorLinks().forEach(l -> own.add(row(name, k, unit.getName(), unit.getInitName(), unit.getId(),
                l.var(), TargetKind.REGULATOR_LINK, "", l.initialisation())));
        if (unit.getExciter() != null) {
            own.add(row(name, k, unit.getName(), unit.getInitName(), unit.getId(),
                    "", TargetKind.EXCITER, unit.getExciter().getCatalogName(), false));
        }
        addOrNone(rows, own, name, k, unit.getName(), unit.getInitName(), unit.getId());
    }

    private static void addOrNone(List<ControlUnitRow> rows, List<ControlUnitRow> own, String name, String kind,
                                  String model, String initModel, String instanceId) {
        if (own.isEmpty()) {
            rows.add(new ControlUnitRow(name, kind, model, nullToEmpty(initModel), instanceId, "", "", "", false));
        } else {
            rows.addAll(own);
        }
    }

    private static ControlUnitRow row(String name, String kind, String model, String initModel, String instanceId,
                                      String ownVar, TargetKind targetKind, String target, boolean init) {
        return new ControlUnitRow(name, kind, model, nullToEmpty(initModel), instanceId, ownVar, targetKind.name(), target, init);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public static DataframeMapper<Collection<ControlUnitRow>, Void> controlUnitsMapper() {
        return new DataframeMapperBuilder<Collection<ControlUnitRow>, ControlUnitRow, Void>()
                .itemsStreamProvider(Collection::stream)
                .stringsIndex(NAME, ControlUnitRow::name)
                .strings(KIND, ControlUnitRow::kind)
                .strings(MODEL, ControlUnitRow::model)
                .strings(INIT_MODEL, ControlUnitRow::initModel)
                .strings(INSTANCE_ID, ControlUnitRow::instanceId)
                .strings(OWN_VAR, ControlUnitRow::ownVar)
                .strings(TARGET_KIND, ControlUnitRow::targetKind)
                .strings(TARGET, ControlUnitRow::target)
                .booleans(INIT, ControlUnitRow::init)
                .build();
    }

    /**
     * One quantity a machine offers, with the variable holding it.
     */
    record MachineQuantityRow(String quantity, boolean init, String variable) {
    }

    /**
     * The quantities a synchronous machine of that shape offers, named as the mappings build their
     * models: what a control can read or drive while running, and start from at initialisation.
     */
    public static List<MachineQuantityRow> machineQuantities(Windings windings, boolean transformer) {
        return MachineQuantity.offeredBy(new GeneratorSynchronousUnit(windings, transformer, ModelNaming.DYNAWO_1_7_0))
                .entrySet().stream()
                .map(e -> new MachineQuantityRow(e.getKey().name(), e.getKey().isInitialisation(), e.getValue()))
                .toList();
    }

    public static DataframeMapper<Collection<MachineQuantityRow>, Void> machineQuantitiesMapper() {
        return new DataframeMapperBuilder<Collection<MachineQuantityRow>, MachineQuantityRow, Void>()
                .itemsStreamProvider(Collection::stream)
                .stringsIndex("quantity", MachineQuantityRow::quantity)
                .booleans(INIT, MachineQuantityRow::init)
                .strings("variable", MachineQuantityRow::variable)
                .build();
    }

    private static RegulatorControlUnit regulatorControl(String name, Definition definition, List<Row> rows) {
        RegulatorControlUnit unit = new RegulatorControlUnit(definition.kind().regulatorControlKind,
                definition.instanceId(), definition.model(), definition.initModel())
                .named(name);
        for (Row row : rows) {
            switch (row.targetKind()) {
                case MACHINE -> {
                    MachineQuantity quantity = parse(MachineQuantity.class, target(row, name), TARGET, name);
                    if (row.init()) {
                        unit.startingFrom(row.ownVar(), quantity);
                    } else {
                        unit.reading(row.ownVar(), quantity);
                    }
                }
                case REGULATOR_INPUT -> {
                    RegulatorInput input = parse(RegulatorInput.class, target(row, name), TARGET, name);
                    if (row.init()) {
                        unit.startingFrom(row.ownVar(), input);
                    } else {
                        unit.driving(row.ownVar(), input);
                    }
                }
                default -> throw notFor(definition, row, name);
            }
        }
        return unit;
    }

    private static void expect(Definition definition, Kind kind, Row row, String name) {
        if (definition.kind() != kind) {
            throw notFor(definition, row, name);
        }
    }

    private static PowsyblException notFor(Definition definition, Row row, String name) {
        return new PowsyblException("A connection to " + row.targetKind() + " is not one a "
                + definition.kind() + " makes, on the control " + name + " (row " + row.index() + ")");
    }

    private static String target(Row row, String name) {
        if (row.target() == null) {
            throw new PowsyblException("A connection of the control " + name + " (row " + row.index() + ") names no target");
        }
        return row.target();
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String column, String name) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw new PowsyblException("Unknown " + column + " " + value + " for the control " + name
                    + ", expected one of " + List.of(type.getEnumConstants()), e);
        }
    }

    private static String required(UpdatingDataframe dataframe, String column, int row, String of) {
        String value = optional(dataframe, column, row);
        if (value == null) {
            throw new PowsyblException(column + " is missing for " + of + " (row " + row + ")");
        }
        return value;
    }

    private static String optional(UpdatingDataframe dataframe, String column, int row) {
        return dataframe.getStringValue(column, row).filter(Objects::nonNull).filter(s -> !s.isEmpty()).orElse(null);
    }
}
