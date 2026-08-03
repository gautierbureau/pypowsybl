/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.dataframe.network.extensions;

import com.powsybl.dataframe.SeriesMetadata;
import com.powsybl.dataframe.network.adders.AbstractSimpleAdder;
import com.powsybl.dataframe.network.adders.SeriesUtils;
import com.powsybl.dataframe.update.StringSeries;
import com.powsybl.dataframe.update.UpdatingDataframe;
import com.powsybl.iidm.network.Identifiable;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.extensions.OperatingStatus;
import com.powsybl.iidm.network.extensions.OperatingStatusAdder;

import java.util.Collections;
import java.util.List;

/**
 * @author Claude Code
 */
public class OperatingStatusDataframeAdder extends AbstractSimpleAdder {

    private static final List<SeriesMetadata> METADATA = List.of(
            SeriesMetadata.stringIndex("id"),
            SeriesMetadata.strings("status")
            );

    @Override
    public List<List<SeriesMetadata>> getMetadata() {
        return Collections.singletonList(METADATA);
    }

    private static class OperatingStatusSeries {

        private final StringSeries id;
        private final StringSeries status;

        OperatingStatusSeries(UpdatingDataframe dataframe) {
            this.id = dataframe.getStrings("id");
            // the status has no default value, the extension cannot be created without it
            this.status = SeriesUtils.getRequiredStrings(dataframe, "status");
        }

        void create(Network network, int row) {
            Identifiable<?> identifiable =
                    OperatingStatusDataframeProvider.getAllowedIdentifiableOrThrow(network, this.id.get(row));
            var adder = identifiable.newExtension(OperatingStatusAdder.class);
            SeriesUtils.applyIfPresent(status, row, OperatingStatus.Status.class, adder::withStatus);
            adder.add();
        }
    }

    @Override
    public void addElements(Network network, UpdatingDataframe dataframe) {
        OperatingStatusSeries series = new OperatingStatusSeries(dataframe);
        for (int row = 0; row < dataframe.getRowCount(); row++) {
            series.create(network, row);
        }
    }
}
