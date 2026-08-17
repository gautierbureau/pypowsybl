/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.dataframe.dynamic.extensions;

import com.google.auto.service.AutoService;
import com.powsybl.commons.PowsyblException;
import com.powsybl.dataframe.network.ExtensionInformation;
import com.powsybl.dataframe.network.NetworkDataframeMapper;
import com.powsybl.dataframe.network.NetworkDataframeMapperBuilder;
import com.powsybl.dataframe.network.adders.NetworkElementAdder;
import com.powsybl.dataframe.network.extensions.AbstractSingleDataframeNetworkExtension;
import com.powsybl.dataframe.network.extensions.NetworkExtensionDataframeProvider;
import com.powsybl.dynawo.extensions.api.svarc.StaticVarCompensatorProperties;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.StaticVarCompensator;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
@AutoService(NetworkExtensionDataframeProvider.class)
public class StaticVarCompensatorPropertiesDataframeProvider extends AbstractSingleDataframeNetworkExtension {

    @Override
    public String getExtensionName() {
        return StaticVarCompensatorProperties.NAME;
    }

    @Override
    public ExtensionInformation getExtensionInformation() {
        return new ExtensionInformation(StaticVarCompensatorProperties.NAME,
                "Provides information, for dynamic simulation only, about the model a static var compensator is built on",
                "index : id (str), " +
                        "constructor (str)");
    }

    private Stream<StaticVarCompensatorProperties> itemsStream(Network network) {
        return network.getStaticVarCompensatorStream()
                .map(svc -> (StaticVarCompensatorProperties) svc.getExtension(StaticVarCompensatorProperties.class))
                .filter(Objects::nonNull);
    }

    private StaticVarCompensatorProperties getOrThrow(Network network, String id) {
        StaticVarCompensator staticVarCompensator = network.getStaticVarCompensator(id);
        if (staticVarCompensator == null) {
            throw new PowsyblException("Static var compensator '" + id + "' does not exist.");
        }
        StaticVarCompensatorProperties properties = staticVarCompensator.getExtension(StaticVarCompensatorProperties.class);
        if (properties == null) {
            throw new PowsyblException("Static var compensator '" + id + "' has no StaticVarCompensatorProperties extension");
        }
        return properties;
    }

    @Override
    public NetworkDataframeMapper createMapper() {
        return NetworkDataframeMapperBuilder.ofStream(this::itemsStream, this::getOrThrow)
                .stringsIndex("id", ext -> ext.getExtendable().getId())
                .strings("constructor",
                        StaticVarCompensatorProperties::getConstructor,
                        StaticVarCompensatorProperties::setConstructor)
                .build();
    }

    @Override
    public void removeExtensions(Network network, List<String> ids) {
        ids.stream().filter(Objects::nonNull)
                .map(network::getStaticVarCompensator)
                .filter(Objects::nonNull)
                .forEach(svc -> svc.removeExtension(StaticVarCompensatorProperties.class));
    }

    @Override
    public NetworkElementAdder createAdder() {
        return new StaticVarCompensatorPropertiesDataframeAdder();
    }

}
