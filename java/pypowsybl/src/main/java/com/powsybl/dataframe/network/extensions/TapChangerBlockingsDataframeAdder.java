/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.dataframe.network.extensions;

import com.powsybl.commons.PowsyblException;
import com.powsybl.dataframe.SeriesMetadata;
import com.powsybl.dataframe.network.adders.AbstractSimpleAdder;
import com.powsybl.dataframe.update.StringSeries;
import com.powsybl.dataframe.update.UpdatingDataframe;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.extensions.MeasurementPoint;
import com.powsybl.iidm.network.extensions.MeasurementPointAdder;
import com.powsybl.iidm.network.extensions.TapChangerBlockingAdder;
import com.powsybl.iidm.network.extensions.TapChangerBlockingsAdder;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the tap changer blockings extension from the three dataframes the provider lays it out as:
 * the blockings, the measurement points carrying the name of the blocking they belong to, and the
 * control voltage levels the same way.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public class TapChangerBlockingsDataframeAdder extends AbstractSimpleAdder {

    private static final List<SeriesMetadata> BLOCKINGS_METADATA = List.of(
            SeriesMetadata.stringIndex("name"));

    private static final List<SeriesMetadata> MEASUREMENT_POINTS_METADATA = List.of(
            SeriesMetadata.stringIndex("id"),
            SeriesMetadata.strings("tcb_name"),
            SeriesMetadata.strings("buses"),
            SeriesMetadata.strings("busbar_section_ids"));

    private static final List<SeriesMetadata> CONTROL_VOLTAGE_LEVELS_METADATA = List.of(
            SeriesMetadata.stringIndex("id"),
            SeriesMetadata.strings("tcb_name"));

    @Override
    public List<List<SeriesMetadata>> getMetadata() {
        return List.of(BLOCKINGS_METADATA, MEASUREMENT_POINTS_METADATA, CONTROL_VOLTAGE_LEVELS_METADATA);
    }

    private static final class TapChangerBlockingsSeries {

        private final int blockingCount;
        private final StringSeries blockingName;

        private final int pointCount;
        private final StringSeries pointId;
        private final StringSeries pointTcbName;
        private final StringSeries pointBuses;
        private final StringSeries pointBusbarSectionIds;

        private final int controlCount;
        private final StringSeries controlId;
        private final StringSeries controlTcbName;

        TapChangerBlockingsSeries(UpdatingDataframe blockingsDf, UpdatingDataframe pointsDf, UpdatingDataframe controlsDf) {
            this.blockingCount = blockingsDf.getRowCount();
            this.blockingName = blockingsDf.getStrings("name");

            this.pointCount = pointsDf.getRowCount();
            this.pointId = pointsDf.getStrings("id");
            this.pointTcbName = pointsDf.getStrings("tcb_name");
            this.pointBuses = pointsDf.getStrings("buses");
            this.pointBusbarSectionIds = pointsDf.getStrings("busbar_section_ids");

            this.controlCount = controlsDf.getRowCount();
            this.controlId = controlsDf.getStrings("id");
            this.controlTcbName = controlsDf.getStrings("tcb_name");
        }

        private static List<MeasurementPoint.BusRef> parseBuses(String buses) {
            List<MeasurementPoint.BusRef> refs = new ArrayList<>();
            if (buses == null || buses.isEmpty()) {
                return refs;
            }
            for (String bus : buses.split(",")) {
                if (bus.isEmpty()) {
                    continue;
                }
                int sep = bus.indexOf(':');
                if (sep < 0) {
                    throw new PowsyblException("Measurement point bus '" + bus + "' is not of the form 'voltage_level_id:bus_id'");
                }
                refs.add(new MeasurementPoint.BusRef(bus.substring(0, sep), bus.substring(sep + 1)));
            }
            return refs;
        }

        private static List<String> parseBusbarSectionIds(String ids) {
            List<String> result = new ArrayList<>();
            if (ids == null || ids.isEmpty()) {
                return result;
            }
            for (String id : ids.split(",")) {
                if (!id.isEmpty()) {
                    result.add(id);
                }
            }
            return result;
        }

        void create(Network network) {
            TapChangerBlockingsAdder adder = network.newExtension(TapChangerBlockingsAdder.class);
            for (int blocking = 0; blocking < blockingCount; blocking++) {
                String name = blockingName.get(blocking);
                TapChangerBlockingAdder tcbAdder = adder.newTapChangerBlocking().withName(name);

                for (int point = 0; point < pointCount; point++) {
                    if (pointTcbName.get(point).equals(name)) {
                        MeasurementPointAdder<TapChangerBlockingAdder> pointAdder = tcbAdder.newMeasurementPoint()
                                .withId(pointId.get(point));
                        List<MeasurementPoint.BusRef> buses = parseBuses(pointBuses != null ? pointBuses.get(point) : null);
                        if (!buses.isEmpty()) {
                            pointAdder.withBuses(buses);
                        }
                        List<String> busbarSectionIds = parseBusbarSectionIds(pointBusbarSectionIds != null ? pointBusbarSectionIds.get(point) : null);
                        if (!busbarSectionIds.isEmpty()) {
                            pointAdder.withBusbarSectionIds(busbarSectionIds);
                        }
                        pointAdder.add();
                    }
                }

                for (int control = 0; control < controlCount; control++) {
                    if (controlTcbName.get(control).equals(name)) {
                        tcbAdder.newControlVoltageLevel()
                                .withId(controlId.get(control))
                                .add();
                    }
                }

                tcbAdder.add();
            }
            adder.add();
        }
    }

    @Override
    public void addElements(Network network, List<UpdatingDataframe> dataframes) {
        if (dataframes.size() != 3) {
            throw new PowsyblException("Three dataframes are expected to describe the tap changer blockings, found : " + dataframes.size());
        }
        new TapChangerBlockingsSeries(dataframes.get(0), dataframes.get(1), dataframes.get(2)).create(network);
    }
}
