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
import com.powsybl.iidm.network.ThreeWindingsTransformer;
import com.powsybl.iidm.network.extensions.ThreeWindingsTransformerFortescueAdder;
import com.powsybl.iidm.network.extensions.WindingConnectionType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * @author Claude Code
 */
public class ThreeWindingsTransformerFortescueDataframeAdder extends AbstractSimpleAdder {

    private static final List<SeriesMetadata> METADATA = buildMetadata();

    private static List<SeriesMetadata> buildMetadata() {
        List<SeriesMetadata> metadata = new ArrayList<>();
        metadata.add(SeriesMetadata.stringIndex("id"));
        for (int leg = 1; leg <= 3; leg++) {
            metadata.add(SeriesMetadata.doubles("rz" + leg));
            metadata.add(SeriesMetadata.doubles("xz" + leg));
            metadata.add(SeriesMetadata.booleans("free_fluxes" + leg));
            metadata.add(SeriesMetadata.strings("connection_type" + leg));
            metadata.add(SeriesMetadata.doubles("grounding_r" + leg));
            metadata.add(SeriesMetadata.doubles("grounding_x" + leg));
        }
        return List.copyOf(metadata);
    }

    @Override
    public List<List<SeriesMetadata>> getMetadata() {
        return Collections.singletonList(METADATA);
    }

    private static class LegSeries {

        private final DoubleSeries rz;
        private final DoubleSeries xz;
        private final IntSeries freeFluxes;
        private final StringSeries connectionType;
        private final DoubleSeries groundingR;
        private final DoubleSeries groundingX;

        LegSeries(UpdatingDataframe dataframe, int legNumber) {
            this.rz = dataframe.getDoubles("rz" + legNumber);
            this.xz = dataframe.getDoubles("xz" + legNumber);
            this.freeFluxes = dataframe.getInts("free_fluxes" + legNumber);
            this.connectionType = dataframe.getStrings("connection_type" + legNumber);
            this.groundingR = dataframe.getDoubles("grounding_r" + legNumber);
            this.groundingX = dataframe.getDoubles("grounding_x" + legNumber);
        }

        void apply(ThreeWindingsTransformerFortescueAdder.LegFortescueAdder adder, int row) {
            SeriesUtils.applyIfPresent(rz, row, adder::withRz);
            SeriesUtils.applyIfPresent(xz, row, adder::withXz);
            SeriesUtils.applyBooleanIfPresent(freeFluxes, row, adder::withFreeFluxes);
            SeriesUtils.applyIfPresent(connectionType, row, WindingConnectionType.class, adder::withConnectionType);
            SeriesUtils.applyIfPresent(groundingR, row, adder::withGroundingR);
            SeriesUtils.applyIfPresent(groundingX, row, adder::withGroundingX);
        }
    }

    private static class ThreeWindingsTransformerFortescueSeries {

        private final StringSeries id;
        private final LegSeries leg1;
        private final LegSeries leg2;
        private final LegSeries leg3;

        ThreeWindingsTransformerFortescueSeries(UpdatingDataframe dataframe) {
            this.id = dataframe.getStrings("id");
            this.leg1 = new LegSeries(dataframe, 1);
            this.leg2 = new LegSeries(dataframe, 2);
            this.leg3 = new LegSeries(dataframe, 3);
        }

        void create(Network network, int row) {
            String twtId = this.id.get(row);
            ThreeWindingsTransformer twt = network.getThreeWindingsTransformer(twtId);
            if (twt == null) {
                throw new PowsyblException("Invalid three windings transformer id : could not find " + twtId);
            }
            var adder = twt.newExtension(ThreeWindingsTransformerFortescueAdder.class);
            applyLeg(adder, leg1, row, ThreeWindingsTransformerFortescueAdder::leg1);
            applyLeg(adder, leg2, row, ThreeWindingsTransformerFortescueAdder::leg2);
            applyLeg(adder, leg3, row, ThreeWindingsTransformerFortescueAdder::leg3);
            adder.add();
        }

        private static void applyLeg(ThreeWindingsTransformerFortescueAdder adder, LegSeries legSeries, int row,
                                     Function<ThreeWindingsTransformerFortescueAdder,
                                             ThreeWindingsTransformerFortescueAdder.LegFortescueAdder> leg) {
            legSeries.apply(leg.apply(adder), row);
        }
    }

    @Override
    public void addElements(Network network, UpdatingDataframe dataframe) {
        ThreeWindingsTransformerFortescueSeries series = new ThreeWindingsTransformerFortescueSeries(dataframe);
        for (int row = 0; row < dataframe.getRowCount(); row++) {
            series.create(network, row);
        }
    }
}
