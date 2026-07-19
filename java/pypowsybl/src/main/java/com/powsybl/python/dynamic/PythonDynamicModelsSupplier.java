/**
 * Copyright (c) 2020-2023, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.python.dynamic;

import com.powsybl.commons.report.ReportNode;
import com.powsybl.dynamicsimulation.DynamicModel;
import com.powsybl.dynamicsimulation.DynamicModelsSupplier;
import com.powsybl.dynawo.DynawoSimulationParameters;
import com.powsybl.dynawo.models.EquipmentBlackBoxModel;
import com.powsybl.iidm.network.Network;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * @author Nicolas Pierre {@literal <nicolas.pierre@artelys.com>}
 * @author Laurent Issertial {@literal <laurent.issertial at rte-france.com>}
 */
public class PythonDynamicModelsSupplier implements DynamicModelsSupplier {

    /**
     * What becomes of an equipment described twice.
     */
    public enum Mode {
        /**
         * The description already in place stands, so that a mapping can be completed with the
         * equipments it left out without disturbing those it covered.
         */
        KEEP_FIRST,
        /**
         * The new description stands, so that a mapping can be adjusted where it does not suit.
         */
        KEEP_LAST
    }

    private record Entry(BiFunction<Network, ReportNode, DynamicModel> modelFunction, Mode mode) {
    }

    private final List<Entry> entries = new ArrayList<>();

    /**
     * Settings a mapping generated along with its models, the parameters of each model among them.
     * They travel with the models because they describe them: a set is written for one model of one
     * equipment and means nothing without it.
     */
    private DynawoSimulationParameters mappingParameters;

    /**
     * Mode the models added next are given. The adders building models from a dataframe call
     * {@link #addModel(BiFunction)} without knowing which of the two the caller asked for, so the
     * caller says it here beforehand.
     */
    private Mode defaultMode = Mode.KEEP_FIRST;

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }

    @Override
    public List<DynamicModel> get(Network network, ReportNode reportNode) {
        ReportNode supplierReportNode = SupplierReport.createDynawoModelsSupplierReportNode(reportNode);
        Map<String, DynamicModel> describedEquipments = new LinkedHashMap<>();
        List<DynamicModel> others = new ArrayList<>();
        for (Entry entry : entries) {
            DynamicModel model = entry.modelFunction().apply(network, supplierReportNode);
            if (model == null) {
                continue;
            }
            describedEquipment(model).ifPresentOrElse(equipment -> {
                if (entry.mode() == Mode.KEEP_LAST) {
                    describedEquipments.put(equipment, model);
                } else {
                    describedEquipments.putIfAbsent(equipment, model);
                }
            }, () -> others.add(model));
        }
        List<DynamicModel> models = new ArrayList<>(describedEquipments.values());
        models.addAll(others);
        return models;
    }

    /**
     * The equipment a model describes, when it describes one: a model standing on its own, an
     * automation system for instance, is never a second description of anything.
     */
    public static Optional<String> describedEquipment(DynamicModel model) {
        return model instanceof EquipmentBlackBoxModel equipmentModel
                ? Optional.of(equipmentModel.getEquipment().getId())
                : Optional.empty();
    }

    public void setDefaultMode(Mode defaultMode) {
        this.defaultMode = Objects.requireNonNull(defaultMode);
    }

    public void addModel(BiFunction<Network, ReportNode, DynamicModel> modelFunction) {
        addModel(modelFunction, defaultMode);
    }

    public void addModel(BiFunction<Network, ReportNode, DynamicModel> modelFunction, Mode mode) {
        entries.add(new Entry(modelFunction, Objects.requireNonNull(mode)));
    }

    public void setMappingParameters(DynawoSimulationParameters mappingParameters) {
        this.mappingParameters = mappingParameters;
    }

    public Optional<DynawoSimulationParameters> getMappingParameters() {
        return Optional.ofNullable(mappingParameters);
    }

    /**
     * The settings the simulation will run with, whether a mapping generated them, a study loaded
     * them from a file or the platform configuration declares them. They are read from the
     * configuration the first time they are asked for, so that a value can be looked at or changed
     * without a mapping having been applied.
     */
    public DynawoSimulationParameters getOrCreateMappingParameters() {
        if (mappingParameters == null) {
            mappingParameters = DynawoSimulationParameters.load();
        }
        return mappingParameters;
    }
}
