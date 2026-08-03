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
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.TwoWindingsTransformer;
import com.powsybl.iidm.network.extensions.TwoWindingsTransformerFortescueAdder;
import com.powsybl.iidm.network.extensions.WindingConnectionType;

import java.util.Collections;
import java.util.List;

/**
 * @author Claude Code
 */
public class TwoWindingsTransformerFortescueDataframeAdder extends AbstractSimpleAdder {

    private static final List<SeriesMetadata> METADATA = List.of(
            SeriesMetadata.stringIndex("id"),
            SeriesMetadata.doubles("rz"),
            SeriesMetadata.doubles("xz"),
            SeriesMetadata.booleans("free_fluxes"),
            SeriesMetadata.doubles("xm"),
            SeriesMetadata.strings("connection_type1"),
            SeriesMetadata.strings("connection_type2"),
            SeriesMetadata.doubles("grounding_r1"),
            SeriesMetadata.doubles("grounding_x1"),
            SeriesMetadata.doubles("grounding_r2"),
            SeriesMetadata.doubles("grounding_x2")
            );

    @Override
    public List<List<SeriesMetadata>> getMetadata() {
        return Collections.singletonList(METADATA);
    }

    private static class TwoWindingsTransformerFortescueSeries {

        private final StringSeries id;
        private final DoubleSeries rz;
        private final DoubleSeries xz;
        private final IntSeries freeFluxes;
        private final DoubleSeries xm;
        private final StringSeries connectionType1;
        private final StringSeries connectionType2;
        private final DoubleSeries groundingR1;
        private final DoubleSeries groundingX1;
        private final DoubleSeries groundingR2;
        private final DoubleSeries groundingX2;

        TwoWindingsTransformerFortescueSeries(UpdatingDataframe dataframe) {
            this.id = dataframe.getStrings("id");
            this.rz = dataframe.getDoubles("rz");
            this.xz = dataframe.getDoubles("xz");
            this.freeFluxes = dataframe.getInts("free_fluxes");
            this.xm = dataframe.getDoubles("xm");
            this.connectionType1 = dataframe.getStrings("connection_type1");
            this.connectionType2 = dataframe.getStrings("connection_type2");
            this.groundingR1 = dataframe.getDoubles("grounding_r1");
            this.groundingX1 = dataframe.getDoubles("grounding_x1");
            this.groundingR2 = dataframe.getDoubles("grounding_r2");
            this.groundingX2 = dataframe.getDoubles("grounding_x2");
        }

        void create(Network network, int row) {
            String twtId = this.id.get(row);
            TwoWindingsTransformer twt = network.getTwoWindingsTransformer(twtId);
            if (twt == null) {
                throw new PowsyblException("Invalid two windings transformer id : could not find " + twtId);
            }
            var adder = twt.newExtension(TwoWindingsTransformerFortescueAdder.class);
            SeriesUtils.applyIfPresent(rz, row, adder::withRz);
            SeriesUtils.applyIfPresent(xz, row, adder::withXz);
            SeriesUtils.applyBooleanIfPresent(freeFluxes, row, adder::withFreeFluxes);
            SeriesUtils.applyIfPresent(xm, row, adder::withXm);
            SeriesUtils.applyIfPresent(connectionType1, row, WindingConnectionType.class, adder::withConnectionType1);
            SeriesUtils.applyIfPresent(connectionType2, row, WindingConnectionType.class, adder::withConnectionType2);
            SeriesUtils.applyIfPresent(groundingR1, row, adder::withGroundingR1);
            SeriesUtils.applyIfPresent(groundingX1, row, adder::withGroundingX1);
            SeriesUtils.applyIfPresent(groundingR2, row, adder::withGroundingR2);
            SeriesUtils.applyIfPresent(groundingX2, row, adder::withGroundingX2);
            adder.add();
        }
    }

    @Override
    public void addElements(Network network, UpdatingDataframe dataframe) {
        TwoWindingsTransformerFortescueSeries series = new TwoWindingsTransformerFortescueSeries(dataframe);
        for (int row = 0; row < dataframe.getRowCount(); row++) {
            series.create(network, row);
        }
    }
}
