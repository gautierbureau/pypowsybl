/**
 * Copyright (c) 2024, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.python.dynamic;

import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.parameters.Parameter;
import com.powsybl.dataframe.update.UpdatingDataframe;
import com.powsybl.dynamicsimulation.DynamicSimulationParameters;
import com.powsybl.dynamicsimulation.DynamicSimulationProvider;
import com.powsybl.dynawo.DynawoSimulationParameters;
import com.powsybl.dynawo.builders.ModelConfig;
import com.powsybl.dynawo.builders.VersionInterval;
import com.powsybl.dynawo.commons.DynawoVersion;
import com.powsybl.python.commons.CTypeUtil;
import com.powsybl.python.commons.PyPowsyblApiHeader.DynamicSimulationParametersPointer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.powsybl.python.commons.PyPowsyblConfiguration.getDefaultDynamicSimulationProvider;
import static com.powsybl.python.commons.PyPowsyblConfiguration.isReadConfig;

/**
 * @author Laurent Issertial {@literal <laurent.issertial at rte-france.com>}
 */
public final class DynamicSimulationParametersCUtils {

    private static final String CATEGORY = "category";
    private static final String LIB = "lib";
    private static final String DOC = "doc";
    private static final String ALIAS = "alias";
    private static final String INTERNAL_MODEL_PREFIX = "internal_model_prefix";
    private static final String PROPERTIES = "properties";
    private static final String MIN_VERSION = "min_version";
    private static final String MAX_VERSION = "max_version";
    private static final String END_CAUSE = "end_cause";

    private DynamicSimulationParametersCUtils() {
    }

    public static DynamicSimulationProvider getDynamicSimulationProvider() {
        return DynamicSimulationProvider.findAll().stream()
                .filter(provider -> provider.getName().equals(getDefaultDynamicSimulationProvider()))
                .findFirst()
                .orElseThrow(() -> new PowsyblException("No dynamic simulation provider for name '" + getDefaultDynamicSimulationProvider() + "'"));
    }

    public static DynamicSimulationParameters createDynamicSimulationParameters() {
        return isReadConfig() ? DynamicSimulationParameters.load() : new DynamicSimulationParameters();
    }

    public static List<Parameter> getSpecificParametersInfo() {
        return getDynamicSimulationProvider().getSpecificParameters();
    }

    public static DynamicSimulationParameters createDynamicSimulationParameters(DynamicSimulationParametersPointer parametersPointer) {
        DynamicSimulationParameters parameters = createDynamicSimulationParameters()
                .setStartTime(parametersPointer.getStartTime())
                .setStopTime(parametersPointer.getStopTime());
        DynawoSimulationParameters specificParameters = createSpecificDynamicSimulationParameters(parameters);
        Map<String, String> specificParametersMap = getSpecificParameters(parametersPointer);
        if (!specificParametersMap.isEmpty()) {
            specificParameters.update(specificParametersMap);
        }
        return parameters;
    }

    public static void copyToCDynamicSimulationParameters(DynamicSimulationParametersPointer cParameters) {
        copyToCDynamicSimulationParameters(createDynamicSimulationParameters(), cParameters);
    }

    public static void copyToCDynamicSimulationParameters(DynamicSimulationParameters parameters, DynamicSimulationParametersPointer cParameters) {
        cParameters.setStartTime(parameters.getStartTime());
        cParameters.setStopTime(parameters.getStopTime());
        cParameters.getProviderParameters().setProviderParametersValuesCount(0);
        cParameters.getProviderParameters().setProviderParametersKeysCount(0);
    }

    private static DynawoSimulationParameters createSpecificDynamicSimulationParameters(DynamicSimulationParameters parameters) {
        DynawoSimulationParameters specificParameters = parameters.getExtension(DynawoSimulationParameters.class);
        if (specificParameters == null) {
            specificParameters = isReadConfig() ? DynawoSimulationParameters.load() : new DynawoSimulationParameters();
            parameters.addExtension(DynawoSimulationParameters.class, specificParameters);
        }
        return specificParameters;
    }

    private static Map<String, String> getSpecificParameters(DynamicSimulationParametersPointer parametersPointer) {
        return CTypeUtil.toStringMap(parametersPointer.getProviderParameters().getProviderParametersKeys(),
                parametersPointer.getProviderParameters().getProviderParametersKeysCount(),
                parametersPointer.getProviderParameters().getProviderParametersValues(),
                parametersPointer.getProviderParameters().getProviderParametersValuesCount());
    }

    /**
     * Reads a dataframe of additional dynamic model definitions (one row per model) into
     * the {@code Map<category, List<ModelConfig>>} expected by
     * {@link DynawoSimulationParameters#setAdditionalModels}.
     */
    public static Map<String, List<ModelConfig>> readAdditionalModels(UpdatingDataframe dataframe) {
        Map<String, List<ModelConfig>> additionalModels = new LinkedHashMap<>();
        for (int row = 0; row < dataframe.getRowCount(); row++) {
            String category = dataframe.getStringValue(CATEGORY, row)
                    .orElseThrow(() -> new PowsyblException("category is missing for an additional model"));
            additionalModels.computeIfAbsent(category, k -> new ArrayList<>()).add(createModelConfig(dataframe, row));
        }
        return additionalModels;
    }

    private static ModelConfig createModelConfig(UpdatingDataframe dataframe, int row) {
        String lib = dataframe.getStringValue(LIB, row)
                .orElseThrow(() -> new PowsyblException("lib is missing for an additional model"));
        String alias = dataframe.getStringValue(ALIAS, row).filter(s -> !s.isEmpty()).orElse(null);
        String internalModelPrefix = dataframe.getStringValue(INTERNAL_MODEL_PREFIX, row).filter(s -> !s.isEmpty()).orElse(null);
        String doc = dataframe.getStringValue(DOC, row).orElse("");
        List<String> properties = dataframe.getStringValue(PROPERTIES, row)
                .filter(s -> !s.isEmpty())
                .map(s -> Arrays.asList(s.split(",")))
                .orElse(Collections.emptyList());
        return new ModelConfig(lib, alias, internalModelPrefix, properties, doc, createVersionInterval(dataframe, row));
    }

    private static VersionInterval createVersionInterval(UpdatingDataframe dataframe, int row) {
        String minVersion = dataframe.getStringValue(MIN_VERSION, row).filter(s -> !s.isEmpty()).orElse(null);
        String maxVersion = dataframe.getStringValue(MAX_VERSION, row).filter(s -> !s.isEmpty()).orElse(null);
        String endCause = dataframe.getStringValue(END_CAUSE, row).filter(s -> !s.isEmpty()).orElse(null);
        if (minVersion == null && maxVersion == null && endCause == null) {
            return VersionInterval.createDefaultVersion();
        }
        DynawoVersion min = minVersion != null ? DynawoVersion.createFromString(minVersion) : VersionInterval.MODEL_DEFAULT_MIN_VERSION;
        DynawoVersion max = maxVersion != null ? DynawoVersion.createFromString(maxVersion) : null;
        return new VersionInterval(min, max, endCause);
    }

    /**
     * Applies programmatic additional models onto the Dynawo extension of the given parameters.
     */
    public static void applyAdditionalModels(DynamicSimulationParameters parameters, Map<String, List<ModelConfig>> additionalModels) {
        if (additionalModels.isEmpty()) {
            return;
        }
        DynawoSimulationParameters dynawoParameters = parameters.getExtension(DynawoSimulationParameters.class);
        if (dynawoParameters != null) {
            dynawoParameters.setAdditionalModels(additionalModels);
        }
    }
}
