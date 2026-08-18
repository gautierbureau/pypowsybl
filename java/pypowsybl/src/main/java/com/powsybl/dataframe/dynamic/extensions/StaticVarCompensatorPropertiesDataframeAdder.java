/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.dataframe.dynamic.extensions;

import com.powsybl.commons.PowsyblException;
import com.powsybl.dataframe.SeriesMetadata;
import com.powsybl.dataframe.network.adders.AbstractSimpleAdder;
import com.powsybl.dataframe.network.adders.SeriesUtils;
import com.powsybl.dataframe.update.StringSeries;
import com.powsybl.dataframe.update.UpdatingDataframe;
import com.powsybl.dynawo.extensions.api.svarc.StaticVarCompensatorPropertiesAdder;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.StaticVarCompensator;

import java.util.Collections;
import java.util.List;

/**
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public class StaticVarCompensatorPropertiesDataframeAdder extends AbstractSimpleAdder {

    private static final List<SeriesMetadata> METADATA = List.of(
            SeriesMetadata.stringIndex("id"),
            SeriesMetadata.strings("constructor")
            );

    @Override
    public List<List<SeriesMetadata>> getMetadata() {
        return Collections.singletonList(METADATA);
    }

    private static class StaticVarCompensatorPropertiesSeries {

        private final StringSeries id;
        private final StringSeries constructor;

        StaticVarCompensatorPropertiesSeries(UpdatingDataframe dataframe) {
            this.id = dataframe.getStrings("id");
            // the constructor has no default value, the extension cannot be created without it
            this.constructor = SeriesUtils.getRequiredStrings(dataframe, "constructor");
        }

        void create(Network network, int row) {
            String svcId = this.id.get(row);
            StaticVarCompensator staticVarCompensator = network.getStaticVarCompensator(svcId);
            if (staticVarCompensator == null) {
                throw new PowsyblException("Static var compensator '" + svcId + "' does not exist.");
            }
            var adder = staticVarCompensator.newExtension(StaticVarCompensatorPropertiesAdder.class);
            SeriesUtils.applyIfPresent(constructor, row, adder::withConstructor);
            adder.add();
        }
    }

    @Override
    public void addElements(Network network, UpdatingDataframe dataframe) {
        StaticVarCompensatorPropertiesSeries series = new StaticVarCompensatorPropertiesSeries(dataframe);
        for (int row = 0; row < dataframe.getRowCount(); row++) {
            series.create(network, row);
        }
    }
}
