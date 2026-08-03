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
import com.powsybl.iidm.network.TwoWindingsTransformer;
import com.powsybl.iidm.network.extensions.TwoWindingsTransformerToBeEstimatedAdder;

import java.util.Collections;
import java.util.List;

/**
 * @author Claude Code
 */
public class TwoWindingsTransformerToBeEstimatedDataframeAdder extends AbstractSimpleAdder {

    private static final List<SeriesMetadata> METADATA = List.of(
            SeriesMetadata.stringIndex("id"),
            SeriesMetadata.booleans("ratio_tap_changer_status"),
            SeriesMetadata.booleans("phase_tap_changer_status")
            );

    @Override
    public List<List<SeriesMetadata>> getMetadata() {
        return Collections.singletonList(METADATA);
    }

    private static class TwoWindingsTransformerToBeEstimatedSeries {

        private final StringSeries id;
        private final IntSeries ratioTapChangerStatus;
        private final IntSeries phaseTapChangerStatus;

        TwoWindingsTransformerToBeEstimatedSeries(UpdatingDataframe dataframe) {
            this.id = dataframe.getStrings("id");
            this.ratioTapChangerStatus = dataframe.getInts("ratio_tap_changer_status");
            this.phaseTapChangerStatus = dataframe.getInts("phase_tap_changer_status");
        }

        void create(Network network, int row) {
            String twtId = this.id.get(row);
            TwoWindingsTransformer twt = network.getTwoWindingsTransformer(twtId);
            if (twt == null) {
                throw new PowsyblException("Invalid two windings transformer id : could not find " + twtId);
            }
            var adder = twt.newExtension(TwoWindingsTransformerToBeEstimatedAdder.class);
            SeriesUtils.applyBooleanIfPresent(ratioTapChangerStatus, row, adder::withRatioTapChangerStatus);
            SeriesUtils.applyBooleanIfPresent(phaseTapChangerStatus, row, adder::withPhaseTapChangerStatus);
            adder.add();
        }
    }

    @Override
    public void addElements(Network network, UpdatingDataframe dataframe) {
        TwoWindingsTransformerToBeEstimatedSeries series = new TwoWindingsTransformerToBeEstimatedSeries(dataframe);
        for (int row = 0; row < dataframe.getRowCount(); row++) {
            series.create(network, row);
        }
    }
}
