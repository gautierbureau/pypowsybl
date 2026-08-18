/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.dataframe.network.extensions;

import com.google.auto.service.AutoService;
import com.powsybl.dataframe.network.ExtensionInformation;
import com.powsybl.dataframe.network.NetworkDataframeMapper;
import com.powsybl.dataframe.network.NetworkDataframeMapperBuilder;
import com.powsybl.dataframe.network.adders.NetworkElementAdder;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.extensions.ControlVoltageLevel;
import com.powsybl.iidm.network.extensions.MeasurementPoint;
import com.powsybl.iidm.network.extensions.TapChangerBlocking;
import com.powsybl.iidm.network.extensions.TapChangerBlockings;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Exposes the tap changer blockings extension as three linked dataframes: the blockings, the
 * measurement points each watches, and the voltage levels each blocks. The nesting the extension
 * holds does not fit one table, so it is spread the way the secondary voltage control is, a child
 * carrying the name of the blocking it belongs to.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
@AutoService(NetworkExtensionDataframeProvider.class)
public class TapChangerBlockingsDataframeProvider implements NetworkExtensionDataframeProvider {

    static final String BLOCKINGS_TABLE = "blockings";
    static final String MEASUREMENT_POINTS_TABLE = "measurement_points";
    static final String CONTROL_VOLTAGE_LEVELS_TABLE = "control_voltage_levels";

    @Override
    public String getExtensionName() {
        return TapChangerBlockings.NAME;
    }

    @Override
    public ExtensionInformation getExtensionInformation() {
        return new ExtensionInformation(TapChangerBlockings.NAME,
                "Provides information, for dynamic simulation only, about the tap changer blockings: the points each watches and the voltage levels each blocks, in three linked dataframes.",
                "[dataframe \"blockings\"] index : name (str) / "
                        + "[dataframe \"measurement_points\"] index : id (str), tcb_name (str), buses (str, comma-joined \"voltage_level_id:bus_id\"), busbar_section_ids (str, comma-joined) / "
                        + "[dataframe \"control_voltage_levels\"] index : id (str), tcb_name (str)");
    }

    @Override
    public List<String> getExtensionTableNames() {
        return List.of(BLOCKINGS_TABLE, MEASUREMENT_POINTS_TABLE, CONTROL_VOLTAGE_LEVELS_TABLE);
    }

    private Stream<TapChangerBlocking> blockings(Network network) {
        TapChangerBlockings ext = network.getExtension(TapChangerBlockings.class);
        // an absent extension reads back as an empty dataframe rather than an error, the way the
        // other dynamic-simulation extensions do, so a network carrying none is not a failure
        return ext == null ? Stream.empty() : ext.getTapChangerBlockings().stream();
    }

    private Stream<TapChangerBlocking> blockingsStream(Network network) {
        return blockings(network);
    }

    private Stream<MeasurementPointContext> measurementPointsStream(Network network) {
        return blockings(network)
                .flatMap(tcb -> tcb.getMeasurementPoints().stream()
                        .map(point -> new MeasurementPointContext(point, tcb.getName())));
    }

    private Stream<ControlVoltageLevelContext> controlVoltageLevelsStream(Network network) {
        return blockings(network)
                .flatMap(tcb -> tcb.getControlVoltageLevels().stream()
                        .map(cvl -> new ControlVoltageLevelContext(cvl, tcb.getName())));
    }

    private static String joinBuses(MeasurementPoint point) {
        return point.getBuses().stream()
                .map(bus -> bus.voltageLevelId() + ":" + bus.busId())
                .reduce((a, b) -> a + "," + b).orElse("");
    }

    @Override
    public Map<String, NetworkDataframeMapper> createMappers() {
        Map<String, NetworkDataframeMapper> mappers = new HashMap<>();
        mappers.put(BLOCKINGS_TABLE,
                NetworkDataframeMapperBuilder.ofStream(this::blockingsStream)
                        .stringsIndex("name", TapChangerBlocking::getName)
                        .build());
        mappers.put(MEASUREMENT_POINTS_TABLE,
                NetworkDataframeMapperBuilder.ofStream(this::measurementPointsStream)
                        .stringsIndex("id", ctx -> ctx.point().getId())
                        .strings("tcb_name", MeasurementPointContext::tcbName)
                        .strings("buses", ctx -> joinBuses(ctx.point()))
                        .strings("busbar_section_ids", ctx -> String.join(",", ctx.point().getBusbarSectionIds()))
                        .build());
        mappers.put(CONTROL_VOLTAGE_LEVELS_TABLE,
                NetworkDataframeMapperBuilder.ofStream(this::controlVoltageLevelsStream)
                        .stringsIndex("id", ctx -> ctx.controlVoltageLevel().getId())
                        .strings("tcb_name", ControlVoltageLevelContext::tcbName)
                        .build());
        return mappers;
    }

    @Override
    public void removeExtensions(Network network, List<String> ids) {
        network.removeExtension(TapChangerBlockings.class);
    }

    @Override
    public NetworkElementAdder createAdder() {
        return new TapChangerBlockingsDataframeAdder();
    }

    private record MeasurementPointContext(MeasurementPoint point, String tcbName) {
        private MeasurementPointContext {
            Objects.requireNonNull(point);
            Objects.requireNonNull(tcbName);
        }
    }

    private record ControlVoltageLevelContext(ControlVoltageLevel controlVoltageLevel, String tcbName) {
        private ControlVoltageLevelContext {
            Objects.requireNonNull(controlVoltageLevel);
            Objects.requireNonNull(tcbName);
        }
    }
}
