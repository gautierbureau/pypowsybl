/**
 * Copyright (c) 2020-2022, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.dataframe.dynamic;

import com.powsybl.dataframe.DataframeMapper;
import com.powsybl.dataframe.DataframeMapperBuilder;
import com.powsybl.dataframe.dynamic.adders.DynamicMappingAdder;
import com.powsybl.dataframe.dynamic.adders.EventMappingAdder;
import com.powsybl.dynamicsimulation.TimelineEvent;
import com.powsybl.dynawo.builders.ModelInfo;
import com.powsybl.dynawo.models.BlackBoxModel;
import com.powsybl.dynawo.parameters.Parameter;
import com.powsybl.dynawo.parameters.ParametersSet;
import com.powsybl.python.dynamic.PythonDynamicModelsSupplier;
import org.apache.commons.lang3.tuple.Pair;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * @author Nicolas Pierre {@literal <nicolas.pierre@artelys.com>}
 */
public final class DynamicSimulationDataframeMappersUtils {

    private static final String NAMED_INDEX_NAME = "name";
    private static final String DESCRIPTION_NAME = "description";

    private DynamicSimulationDataframeMappersUtils() {
    }

    public static DataframeMapper<Map<String, Double>, Void> fsvDataFrameMapper() {
        return new DataframeMapperBuilder<Map<String, Double>, Map.Entry<String, Double>, Void>()
                .itemsStreamProvider(m -> m.entrySet().stream())
                .stringsIndex("variables", Map.Entry::getKey)
                .doubles("values", Map.Entry::getValue)
                .build();
    }

    public static DataframeMapper<List<TimelineEvent>, Void> timelineEventDataFrameMapper() {
        AtomicInteger index = new AtomicInteger();
        return new DataframeMapperBuilder<List<TimelineEvent>, TimelineEvent, Void>()
                .itemsProvider(Function.identity())
                .intsIndex("index", e -> index.getAndIncrement())
                .doubles("time", TimelineEvent::time)
                .strings("model", TimelineEvent::modelName)
                .strings("message", TimelineEvent::message)
                .build();
    }

    public static DataframeMapper<Collection<DynamicMappingAdder>, Void> categoriesDataFrameMapper() {
        return new DataframeMapperBuilder<Collection<DynamicMappingAdder>, CategoryInformation, Void>()
                .itemsStreamProvider(a -> a.stream()
                        .map(DynamicMappingAdder::getCategoryInformation))
                .stringsIndex(NAMED_INDEX_NAME, CategoryInformation::name)
                .strings(DESCRIPTION_NAME, CategoryInformation::description)
                .strings("attribute", CategoryInformation::attribute)
                .build();
    }

    /**
     * What a mapping made of a network: the model standing for each equipment and the parameter
     * set valuing it, which is what one looks at to check a mapping did what was meant.
     */
    public static DataframeMapper<Collection<BlackBoxModel>, Void> mappedModelsDataFrameMapper() {
        return new DataframeMapperBuilder<Collection<BlackBoxModel>, BlackBoxModel, Void>()
                .itemsStreamProvider(Collection::stream)
                .stringsIndex("dynamic_model_id", BlackBoxModel::getDynamicModelId)
                .strings("static_id", m -> PythonDynamicModelsSupplier.describedEquipment(m).orElse(""))
                .strings("model", BlackBoxModel::getLib)
                .strings("parameter_set_id", BlackBoxModel::getParameterSetId)
                .build();
    }

    /**
     * The parameters a mapping generated, one row per parameter of each set. The ones read from the
     * network are left out: they hold no value to look at or change, only the name of what they
     * follow.
     */
    public static DataframeMapper<Collection<ParametersSet>, Void> mappedParametersDataFrameMapper() {
        return new DataframeMapperBuilder<Collection<ParametersSet>, Pair<String, Parameter>, Void>()
                .itemsStreamProvider(sets -> sets.stream()
                        .flatMap(set -> set.getParameters().values().stream().map(p -> Pair.of(set.getId(), p))))
                .stringsIndex("parameter_set_id", Pair::getKey)
                .strings("name", p -> p.getValue().name())
                .strings("type", p -> p.getValue().type().name())
                .strings("value", p -> p.getValue().value())
                .build();
    }

    public static DataframeMapper<Collection<ModelInfo>, Void> supportedModelsDataFrameMapper() {
        return new DataframeMapperBuilder<Collection<ModelInfo>, ModelInfo, Void>()
                .itemsStreamProvider(Collection::stream)
                .stringsIndex(NAMED_INDEX_NAME, ModelInfo::name)
                .strings(DESCRIPTION_NAME, ModelInfo::doc)
                .build();
    }

    public static DataframeMapper<Collection<DynamicMappingAdder>, Void> allSupportedModelsDataFrameMapper() {
        return new DataframeMapperBuilder<Collection<DynamicMappingAdder>, Pair<String, ModelInfo>, Void>()
                .itemsStreamProvider(a -> a.stream()
                        .flatMap(adder -> {
                            String cat = adder.getCategory();
                            return adder.getSupportedModels().stream().map(m -> Pair.of(cat, m));
                        }))
                .stringsIndex(NAMED_INDEX_NAME, p -> p.getValue().name())
                .strings(DESCRIPTION_NAME, p -> p.getValue().doc())
                .strings("category", Pair::getKey)
                .build();
    }

    public static DataframeMapper<Collection<EventMappingAdder>, Void> eventInformationDataFrameMapper() {
        return new DataframeMapperBuilder<Collection<EventMappingAdder>, EventInformation, Void>()
                .itemsStreamProvider(a -> a.stream()
                        .map(EventMappingAdder::getEventInformation))
                .stringsIndex(NAMED_INDEX_NAME, EventInformation::name)
                .strings(DESCRIPTION_NAME, EventInformation::description)
                .strings("attribute", EventInformation::attribute)
                .build();
    }
}
