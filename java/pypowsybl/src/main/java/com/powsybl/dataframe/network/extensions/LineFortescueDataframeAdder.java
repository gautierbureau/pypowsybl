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
import com.powsybl.dataframe.update.DoubleSeries;
import com.powsybl.dataframe.update.IntSeries;
import com.powsybl.dataframe.update.StringSeries;
import com.powsybl.dataframe.update.UpdatingDataframe;
import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.extensions.LineFortescueAdder;

import java.util.Collections;
import java.util.List;

/**
 * @author Claude Code
 */
public class LineFortescueDataframeAdder extends AbstractSimpleAdder {

    private static final List<SeriesMetadata> METADATA = List.of(
            SeriesMetadata.stringIndex("id"),
            SeriesMetadata.doubles("rz"),
            SeriesMetadata.doubles("xz"),
            SeriesMetadata.doubles("g1z"),
            SeriesMetadata.doubles("b1z"),
            SeriesMetadata.doubles("g2z"),
            SeriesMetadata.doubles("b2z"),
            SeriesMetadata.booleans("open_phase_a"),
            SeriesMetadata.booleans("open_phase_b"),
            SeriesMetadata.booleans("open_phase_c")
            );

    @Override
    public List<List<SeriesMetadata>> getMetadata() {
        return Collections.singletonList(METADATA);
    }

    private static class LineFortescueSeries {

        private final StringSeries id;
        private final DoubleSeries rz;
        private final DoubleSeries xz;
        private final DoubleSeries g1z;
        private final DoubleSeries b1z;
        private final DoubleSeries g2z;
        private final DoubleSeries b2z;
        private final IntSeries openPhaseA;
        private final IntSeries openPhaseB;
        private final IntSeries openPhaseC;

        LineFortescueSeries(UpdatingDataframe dataframe) {
            this.id = dataframe.getStrings("id");
            this.rz = dataframe.getDoubles("rz");
            this.xz = dataframe.getDoubles("xz");
            this.g1z = dataframe.getDoubles("g1z");
            this.b1z = dataframe.getDoubles("b1z");
            this.g2z = dataframe.getDoubles("g2z");
            this.b2z = dataframe.getDoubles("b2z");
            this.openPhaseA = dataframe.getInts("open_phase_a");
            this.openPhaseB = dataframe.getInts("open_phase_b");
            this.openPhaseC = dataframe.getInts("open_phase_c");
        }

        void create(Network network, int row) {
            String lineId = this.id.get(row);
            Line line = network.getLine(lineId);
            if (line == null) {
                throw new PowsyblException("Invalid line id : could not find " + lineId);
            }
            var adder = line.newExtension(LineFortescueAdder.class);
            SeriesUtils.applyIfPresent(rz, row, adder::withRz);
            SeriesUtils.applyIfPresent(xz, row, adder::withXz);
            SeriesUtils.applyIfPresent(g1z, row, adder::withG1z);
            SeriesUtils.applyIfPresent(b1z, row, adder::withB1z);
            SeriesUtils.applyIfPresent(g2z, row, adder::withG2z);
            SeriesUtils.applyIfPresent(b2z, row, adder::withB2z);
            SeriesUtils.applyBooleanIfPresent(openPhaseA, row, adder::withOpenPhaseA);
            SeriesUtils.applyBooleanIfPresent(openPhaseB, row, adder::withOpenPhaseB);
            SeriesUtils.applyBooleanIfPresent(openPhaseC, row, adder::withOpenPhaseC);
            adder.add();
        }
    }

    @Override
    public void addElements(Network network, UpdatingDataframe dataframe) {
        LineFortescueSeries series = new LineFortescueSeries(dataframe);
        for (int row = 0; row < dataframe.getRowCount(); row++) {
            series.create(network, row);
        }
    }
}
