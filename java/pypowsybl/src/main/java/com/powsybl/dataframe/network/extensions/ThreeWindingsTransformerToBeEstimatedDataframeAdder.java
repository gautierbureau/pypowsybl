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
import com.powsybl.dataframe.network.adders.SeriesUtils;
import com.powsybl.dataframe.update.IntSeries;
import com.powsybl.dataframe.update.StringSeries;
import com.powsybl.dataframe.update.UpdatingDataframe;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.ThreeSides;
import com.powsybl.iidm.network.ThreeWindingsTransformer;
import com.powsybl.iidm.network.extensions.ThreeWindingsTransformerToBeEstimatedAdder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * @author Claude Code
 */
public class ThreeWindingsTransformerToBeEstimatedDataframeAdder extends AbstractSimpleAdder {

    private static final List<SeriesMetadata> METADATA = buildMetadata();

    private static List<SeriesMetadata> buildMetadata() {
        List<SeriesMetadata> metadata = new ArrayList<>();
        metadata.add(SeriesMetadata.stringIndex("id"));
        for (ThreeSides side : ThreeSides.values()) {
            metadata.add(SeriesMetadata.booleans("ratio_tap_changer" + side.getNum() + "_status"));
        }
        for (ThreeSides side : ThreeSides.values()) {
            metadata.add(SeriesMetadata.booleans("phase_tap_changer" + side.getNum() + "_status"));
        }
        return List.copyOf(metadata);
    }

    @Override
    public List<List<SeriesMetadata>> getMetadata() {
        return Collections.singletonList(METADATA);
    }

    private static class ThreeWindingsTransformerToBeEstimatedSeries {

        private final StringSeries id;
        private final Map<ThreeSides, IntSeries> ratioTapChangerStatus = new EnumMap<>(ThreeSides.class);
        private final Map<ThreeSides, IntSeries> phaseTapChangerStatus = new EnumMap<>(ThreeSides.class);

        ThreeWindingsTransformerToBeEstimatedSeries(UpdatingDataframe dataframe) {
            this.id = dataframe.getStrings("id");
            for (ThreeSides side : ThreeSides.values()) {
                ratioTapChangerStatus.put(side, dataframe.getInts("ratio_tap_changer" + side.getNum() + "_status"));
                phaseTapChangerStatus.put(side, dataframe.getInts("phase_tap_changer" + side.getNum() + "_status"));
            }
        }

        void create(Network network, int row) {
            String twtId = this.id.get(row);
            ThreeWindingsTransformer twt = network.getThreeWindingsTransformer(twtId);
            if (twt == null) {
                throw new PowsyblException("Invalid three windings transformer id : could not find " + twtId);
            }
            var adder = twt.newExtension(ThreeWindingsTransformerToBeEstimatedAdder.class);
            for (ThreeSides side : ThreeSides.values()) {
                SeriesUtils.applyBooleanIfPresent(ratioTapChangerStatus.get(side), row,
                        value -> adder.withRatioTapChangerStatus(side, value));
                SeriesUtils.applyBooleanIfPresent(phaseTapChangerStatus.get(side), row,
                        value -> adder.withPhaseTapChangerStatus(side, value));
            }
            adder.add();
        }
    }

    @Override
    public void addElements(Network network, UpdatingDataframe dataframe) {
        ThreeWindingsTransformerToBeEstimatedSeries series = new ThreeWindingsTransformerToBeEstimatedSeries(dataframe);
        for (int row = 0; row < dataframe.getRowCount(); row++) {
            series.create(network, row);
        }
    }
}
