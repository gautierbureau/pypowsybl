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
import com.powsybl.iidm.network.Battery;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.extensions.BatteryShortCircuit;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * @author Claude Code
 */
@AutoService(NetworkExtensionDataframeProvider.class)
public class BatteryShortCircuitDataframeProvider extends AbstractSingleDataframeNetworkExtension {

    @Override
    public String getExtensionName() {
        return BatteryShortCircuit.NAME;
    }

    @Override
    public ExtensionInformation getExtensionInformation() {
        return new ExtensionInformation(BatteryShortCircuit.NAME,
                "it contains the transitory reactance of a battery needed to compute short circuit. A subtransitory reactance can also be contained",
                "index : id (str), " +
                        "direct_sub_trans_x (float), " +
                        "direct_trans_x (float), " +
                        "step_up_transformer_x (float)");
    }

    private Stream<BatteryShortCircuit> itemsStream(Network network) {
        return network.getBatteryStream()
                .map(b -> (BatteryShortCircuit) b.getExtension(BatteryShortCircuit.class))
                .filter(Objects::nonNull);
    }

    private BatteryShortCircuit getOrThrow(Network network, String id) {
        Battery battery = network.getBattery(id);
        if (battery == null) {
            throw new PowsyblException("Battery '" + id + "' not found");
        }
        BatteryShortCircuit extension = battery.getExtension(BatteryShortCircuit.class);
        if (extension == null) {
            throw new PowsyblException("Battery '" + id + "' has no BatteryShortCircuit extension");
        }
        return extension;
    }

    @Override
    public NetworkDataframeMapper createMapper() {
        return NetworkDataframeMapperBuilder.ofStream(this::itemsStream, this::getOrThrow)
                .stringsIndex("id", ext -> ext.getExtendable().getId())
                .doubles("direct_sub_trans_x", (ext, context) -> ext.getDirectSubtransX(), (ext, value, context) -> ext.setDirectSubtransX(value))
                .doubles("direct_trans_x", (ext, context) -> ext.getDirectTransX(), (ext, value, context) -> ext.setDirectTransX(value))
                .doubles("step_up_transformer_x", (ext, context) -> ext.getStepUpTransformerX(), (ext, value, context) -> ext.setStepUpTransformerX(value))
                .build();
    }

    @Override
    public void removeExtensions(Network network, List<String> ids) {
        ids.stream().filter(Objects::nonNull)
                .map(network::getBattery)
                .filter(Objects::nonNull)
                .forEach(b -> b.removeExtension(BatteryShortCircuit.class));
    }

    @Override
    public NetworkElementAdder createAdder() {
        return new BatteryShortCircuitDataframeAdder();
    }

}
