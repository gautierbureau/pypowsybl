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
import com.powsybl.iidm.network.Load;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.extensions.LoadAsymmetrical;
import com.powsybl.iidm.network.extensions.LoadConnectionType;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * @author Claude Code
 */
@AutoService(NetworkExtensionDataframeProvider.class)
public class LoadAsymmetricalDataframeProvider extends AbstractSingleDataframeNetworkExtension {

    @Override
    public String getExtensionName() {
        return LoadAsymmetrical.NAME;
    }

    @Override
    public ExtensionInformation getExtensionInformation() {
        return new ExtensionInformation(LoadAsymmetrical.NAME,
                "Provides the connection type and the per phase power deltas of a load, used by unbalanced computations",
                "index : id (str), " +
                        "connection_type (str), " +
                        "delta_pa (float), " +
                        "delta_pb (float), " +
                        "delta_pc (float), " +
                        "delta_qa (float), " +
                        "delta_qb (float), " +
                        "delta_qc (float)");
    }

    private Stream<LoadAsymmetrical> itemsStream(Network network) {
        return network.getLoadStream()
                .map(l -> (LoadAsymmetrical) l.getExtension(LoadAsymmetrical.class))
                .filter(Objects::nonNull);
    }

    private LoadAsymmetrical getOrThrow(Network network, String id) {
        Load load = network.getLoad(id);
        if (load == null) {
            throw new PowsyblException("Load '" + id + "' not found");
        }
        LoadAsymmetrical extension = load.getExtension(LoadAsymmetrical.class);
        if (extension == null) {
            throw new PowsyblException("Load '" + id + "' has no LoadAsymmetrical extension");
        }
        return extension;
    }

    @Override
    public NetworkDataframeMapper createMapper() {
        return NetworkDataframeMapperBuilder.ofStream(this::itemsStream, this::getOrThrow)
                .stringsIndex("id", ext -> ext.getExtendable().getId())
                .enums("connection_type", LoadConnectionType.class,
                        LoadAsymmetrical::getConnectionType,
                        (ext, value) -> ext.setConnectionType(value))
                .doubles("delta_pa", (ext, context) -> ext.getDeltaPa(), (ext, value, context) -> ext.setDeltaPa(value))
                .doubles("delta_pb", (ext, context) -> ext.getDeltaPb(), (ext, value, context) -> ext.setDeltaPb(value))
                .doubles("delta_pc", (ext, context) -> ext.getDeltaPc(), (ext, value, context) -> ext.setDeltaPc(value))
                .doubles("delta_qa", (ext, context) -> ext.getDeltaQa(), (ext, value, context) -> ext.setDeltaQa(value))
                .doubles("delta_qb", (ext, context) -> ext.getDeltaQb(), (ext, value, context) -> ext.setDeltaQb(value))
                .doubles("delta_qc", (ext, context) -> ext.getDeltaQc(), (ext, value, context) -> ext.setDeltaQc(value))
                .build();
    }

    @Override
    public void removeExtensions(Network network, List<String> ids) {
        ids.stream().filter(Objects::nonNull)
                .map(network::getLoad)
                .filter(Objects::nonNull)
                .forEach(l -> l.removeExtension(LoadAsymmetrical.class));
    }

    @Override
    public NetworkElementAdder createAdder() {
        return new LoadAsymmetricalDataframeAdder();
    }

}
