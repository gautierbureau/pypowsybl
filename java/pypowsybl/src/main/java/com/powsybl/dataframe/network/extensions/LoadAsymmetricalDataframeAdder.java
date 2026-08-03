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
import com.powsybl.dataframe.update.StringSeries;
import com.powsybl.dataframe.update.UpdatingDataframe;
import com.powsybl.iidm.network.Load;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.extensions.LoadAsymmetricalAdder;
import com.powsybl.iidm.network.extensions.LoadConnectionType;

import java.util.Collections;
import java.util.List;

/**
 * @author Claude Code
 */
public class LoadAsymmetricalDataframeAdder extends AbstractSimpleAdder {

    private static final List<SeriesMetadata> METADATA = List.of(
            SeriesMetadata.stringIndex("id"),
            SeriesMetadata.strings("connection_type"),
            SeriesMetadata.doubles("delta_pa"),
            SeriesMetadata.doubles("delta_pb"),
            SeriesMetadata.doubles("delta_pc"),
            SeriesMetadata.doubles("delta_qa"),
            SeriesMetadata.doubles("delta_qb"),
            SeriesMetadata.doubles("delta_qc")
            );

    @Override
    public List<List<SeriesMetadata>> getMetadata() {
        return Collections.singletonList(METADATA);
    }

    private static class LoadAsymmetricalSeries {

        private final StringSeries id;
        private final StringSeries connectionType;
        private final DoubleSeries deltaPa;
        private final DoubleSeries deltaPb;
        private final DoubleSeries deltaPc;
        private final DoubleSeries deltaQa;
        private final DoubleSeries deltaQb;
        private final DoubleSeries deltaQc;

        LoadAsymmetricalSeries(UpdatingDataframe dataframe) {
            this.id = dataframe.getStrings("id");
            this.connectionType = dataframe.getStrings("connection_type");
            this.deltaPa = dataframe.getDoubles("delta_pa");
            this.deltaPb = dataframe.getDoubles("delta_pb");
            this.deltaPc = dataframe.getDoubles("delta_pc");
            this.deltaQa = dataframe.getDoubles("delta_qa");
            this.deltaQb = dataframe.getDoubles("delta_qb");
            this.deltaQc = dataframe.getDoubles("delta_qc");
        }

        void create(Network network, int row) {
            String loadId = this.id.get(row);
            Load load = network.getLoad(loadId);
            if (load == null) {
                throw new PowsyblException("Invalid load id : could not find " + loadId);
            }
            var adder = load.newExtension(LoadAsymmetricalAdder.class);
            SeriesUtils.applyIfPresent(connectionType, row, LoadConnectionType.class, adder::withConnectionType);
            SeriesUtils.applyIfPresent(deltaPa, row, adder::withDeltaPa);
            SeriesUtils.applyIfPresent(deltaPb, row, adder::withDeltaPb);
            SeriesUtils.applyIfPresent(deltaPc, row, adder::withDeltaPc);
            SeriesUtils.applyIfPresent(deltaQa, row, adder::withDeltaQa);
            SeriesUtils.applyIfPresent(deltaQb, row, adder::withDeltaQb);
            SeriesUtils.applyIfPresent(deltaQc, row, adder::withDeltaQc);
            adder.add();
        }
    }

    @Override
    public void addElements(Network network, UpdatingDataframe dataframe) {
        LoadAsymmetricalSeries series = new LoadAsymmetricalSeries(dataframe);
        for (int row = 0; row < dataframe.getRowCount(); row++) {
            series.create(network, row);
        }
    }
}
