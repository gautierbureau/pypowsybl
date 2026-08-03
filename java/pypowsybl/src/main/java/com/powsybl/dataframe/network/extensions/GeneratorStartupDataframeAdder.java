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
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.extensions.GeneratorStartupAdder;

import java.util.Collections;
import java.util.List;

/**
 * @author Claude Code
 */
public class GeneratorStartupDataframeAdder extends AbstractSimpleAdder {

    private static final List<SeriesMetadata> METADATA = List.of(
            SeriesMetadata.stringIndex("id"),
            SeriesMetadata.doubles("planned_active_power_setpoint"),
            SeriesMetadata.doubles("startup_cost"),
            SeriesMetadata.doubles("marginal_cost"),
            SeriesMetadata.doubles("planned_outage_rate"),
            SeriesMetadata.doubles("forced_outage_rate")
            );

    @Override
    public List<List<SeriesMetadata>> getMetadata() {
        return Collections.singletonList(METADATA);
    }

    private static class GeneratorStartupSeries {

        private final StringSeries id;
        private final DoubleSeries plannedActivePowerSetpoint;
        private final DoubleSeries startupCost;
        private final DoubleSeries marginalCost;
        private final DoubleSeries plannedOutageRate;
        private final DoubleSeries forcedOutageRate;

        GeneratorStartupSeries(UpdatingDataframe dataframe) {
            this.id = dataframe.getStrings("id");
            this.plannedActivePowerSetpoint = dataframe.getDoubles("planned_active_power_setpoint");
            this.startupCost = dataframe.getDoubles("startup_cost");
            this.marginalCost = dataframe.getDoubles("marginal_cost");
            this.plannedOutageRate = dataframe.getDoubles("planned_outage_rate");
            this.forcedOutageRate = dataframe.getDoubles("forced_outage_rate");
        }

        void create(Network network, int row) {
            String generatorId = this.id.get(row);
            Generator generator = network.getGenerator(generatorId);
            if (generator == null) {
                throw new PowsyblException("Invalid generator id : could not find " + generatorId);
            }
            var adder = generator.newExtension(GeneratorStartupAdder.class);
            SeriesUtils.applyIfPresent(plannedActivePowerSetpoint, row, adder::withPlannedActivePowerSetpoint);
            SeriesUtils.applyIfPresent(startupCost, row, adder::withStartupCost);
            SeriesUtils.applyIfPresent(marginalCost, row, adder::withMarginalCost);
            SeriesUtils.applyIfPresent(plannedOutageRate, row, adder::withPlannedOutageRate);
            SeriesUtils.applyIfPresent(forcedOutageRate, row, adder::withForcedOutageRate);
            adder.add();
        }
    }

    @Override
    public void addElements(Network network, UpdatingDataframe dataframe) {
        GeneratorStartupSeries series = new GeneratorStartupSeries(dataframe);
        for (int row = 0; row < dataframe.getRowCount(); row++) {
            series.create(network, row);
        }
    }
}
