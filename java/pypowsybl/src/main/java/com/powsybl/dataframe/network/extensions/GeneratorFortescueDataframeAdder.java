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
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.extensions.GeneratorFortescueAdder;

import java.util.Collections;
import java.util.List;

/**
 * @author Claude Code
 */
public class GeneratorFortescueDataframeAdder extends AbstractSimpleAdder {

    private static final List<SeriesMetadata> METADATA = List.of(
            SeriesMetadata.stringIndex("id"),
            SeriesMetadata.booleans("grounded"),
            SeriesMetadata.doubles("grounding_r"),
            SeriesMetadata.doubles("grounding_x"),
            SeriesMetadata.doubles("rz"),
            SeriesMetadata.doubles("xz"),
            SeriesMetadata.doubles("rn"),
            SeriesMetadata.doubles("xn")
            );

    @Override
    public List<List<SeriesMetadata>> getMetadata() {
        return Collections.singletonList(METADATA);
    }

    private static class GeneratorFortescueSeries {

        private final StringSeries id;
        private final IntSeries grounded;
        private final DoubleSeries groundingR;
        private final DoubleSeries groundingX;
        private final DoubleSeries rz;
        private final DoubleSeries xz;
        private final DoubleSeries rn;
        private final DoubleSeries xn;

        GeneratorFortescueSeries(UpdatingDataframe dataframe) {
            this.id = dataframe.getStrings("id");
            this.grounded = dataframe.getInts("grounded");
            this.groundingR = dataframe.getDoubles("grounding_r");
            this.groundingX = dataframe.getDoubles("grounding_x");
            this.rz = dataframe.getDoubles("rz");
            this.xz = dataframe.getDoubles("xz");
            this.rn = dataframe.getDoubles("rn");
            this.xn = dataframe.getDoubles("xn");
        }

        void create(Network network, int row) {
            String generatorId = this.id.get(row);
            Generator generator = network.getGenerator(generatorId);
            if (generator == null) {
                throw new PowsyblException("Invalid generator id : could not find " + generatorId);
            }
            var adder = generator.newExtension(GeneratorFortescueAdder.class);
            SeriesUtils.applyBooleanIfPresent(grounded, row, adder::withGrounded);
            SeriesUtils.applyIfPresent(groundingR, row, adder::withGroundingR);
            SeriesUtils.applyIfPresent(groundingX, row, adder::withGroundingX);
            SeriesUtils.applyIfPresent(rz, row, adder::withRz);
            SeriesUtils.applyIfPresent(xz, row, adder::withXz);
            SeriesUtils.applyIfPresent(rn, row, adder::withRn);
            SeriesUtils.applyIfPresent(xn, row, adder::withXn);
            adder.add();
        }
    }

    @Override
    public void addElements(Network network, UpdatingDataframe dataframe) {
        GeneratorFortescueSeries series = new GeneratorFortescueSeries(dataframe);
        for (int row = 0; row < dataframe.getRowCount(); row++) {
            series.create(network, row);
        }
    }
}
