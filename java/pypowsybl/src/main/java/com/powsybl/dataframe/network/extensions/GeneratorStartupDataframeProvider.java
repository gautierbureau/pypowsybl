/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.dataframe.network.extensions;

import com.google.auto.service.AutoService;
import com.powsybl.commons.PowsyblException;
import com.powsybl.dataframe.network.ExtensionInformation;
import com.powsybl.dataframe.network.NetworkDataframeMapper;
import com.powsybl.dataframe.network.NetworkDataframeMapperBuilder;
import com.powsybl.dataframe.network.adders.NetworkElementAdder;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.extensions.GeneratorStartup;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * @author Claude Code
 */
@AutoService(NetworkExtensionDataframeProvider.class)
public class GeneratorStartupDataframeProvider extends AbstractSingleDataframeNetworkExtension {

    @Override
    public String getExtensionName() {
        return GeneratorStartup.NAME;
    }

    @Override
    public ExtensionInformation getExtensionInformation() {
        return new ExtensionInformation(GeneratorStartup.NAME,
                "Provides the planned active power setpoint, the costs and the outage rates of a generator, used by unit commitment",
                "index : id (str), " +
                        "planned_active_power_setpoint (float), " +
                        "startup_cost (float), " +
                        "marginal_cost (float), " +
                        "planned_outage_rate (float), " +
                        "forced_outage_rate (float)");
    }

    private Stream<GeneratorStartup> itemsStream(Network network) {
        return network.getGeneratorStream()
                .map(g -> (GeneratorStartup) g.getExtension(GeneratorStartup.class))
                .filter(Objects::nonNull);
    }

    private GeneratorStartup getOrThrow(Network network, String id) {
        Generator generator = network.getGenerator(id);
        if (generator == null) {
            throw new PowsyblException("Generator '" + id + "' not found");
        }
        GeneratorStartup extension = generator.getExtension(GeneratorStartup.class);
        if (extension == null) {
            throw new PowsyblException("Generator '" + id + "' has no GeneratorStartup extension");
        }
        return extension;
    }

    @Override
    public NetworkDataframeMapper createMapper() {
        return NetworkDataframeMapperBuilder.ofStream(this::itemsStream, this::getOrThrow)
                .stringsIndex("id", ext -> ext.getExtendable().getId())
                .doubles("planned_active_power_setpoint",
                        (ext, context) -> ext.getPlannedActivePowerSetpoint(),
                        (ext, value, context) -> ext.setPlannedActivePowerSetpoint(value))
                .doubles("startup_cost", (ext, context) -> ext.getStartupCost(), (ext, value, context) -> ext.setStartupCost(value))
                .doubles("marginal_cost", (ext, context) -> ext.getMarginalCost(), (ext, value, context) -> ext.setMarginalCost(value))
                .doubles("planned_outage_rate", (ext, context) -> ext.getPlannedOutageRate(), (ext, value, context) -> ext.setPlannedOutageRate(value))
                .doubles("forced_outage_rate", (ext, context) -> ext.getForcedOutageRate(), (ext, value, context) -> ext.setForcedOutageRate(value))
                .build();
    }

    @Override
    public void removeExtensions(Network network, List<String> ids) {
        ids.stream().filter(Objects::nonNull)
                .map(network::getGenerator)
                .filter(Objects::nonNull)
                .forEach(g -> g.removeExtension(GeneratorStartup.class));
    }

    @Override
    public NetworkElementAdder createAdder() {
        return new GeneratorStartupDataframeAdder();
    }

}
