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
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.TwoWindingsTransformer;
import com.powsybl.iidm.network.extensions.TwoWindingsTransformerFortescue;
import com.powsybl.iidm.network.extensions.WindingConnectionType;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * @author Claude Code
 */
@AutoService(NetworkExtensionDataframeProvider.class)
public class TwoWindingsTransformerFortescueDataframeProvider extends AbstractSingleDataframeNetworkExtension {

    @Override
    public String getExtensionName() {
        return TwoWindingsTransformerFortescue.NAME;
    }

    @Override
    public ExtensionInformation getExtensionInformation() {
        return new ExtensionInformation(TwoWindingsTransformerFortescue.NAME,
                "Provides the zero sequence parameters and the winding connection types of a two windings transformer, used by unbalanced computations",
                "index : id (str), " +
                        "rz (float), " +
                        "xz (float), " +
                        "free_fluxes (bool), " +
                        "xm (float), " +
                        "connection_type1 (str), " +
                        "connection_type2 (str), " +
                        "grounding_r1 (float), " +
                        "grounding_x1 (float), " +
                        "grounding_r2 (float), " +
                        "grounding_x2 (float)");
    }

    private Stream<TwoWindingsTransformerFortescue> itemsStream(Network network) {
        return network.getTwoWindingsTransformerStream()
                .map(twt -> (TwoWindingsTransformerFortescue) twt.getExtension(TwoWindingsTransformerFortescue.class))
                .filter(Objects::nonNull);
    }

    private TwoWindingsTransformerFortescue getOrThrow(Network network, String id) {
        TwoWindingsTransformer twt = network.getTwoWindingsTransformer(id);
        if (twt == null) {
            throw new PowsyblException("Two windings transformer '" + id + "' not found");
        }
        TwoWindingsTransformerFortescue extension = twt.getExtension(TwoWindingsTransformerFortescue.class);
        if (extension == null) {
            throw new PowsyblException("Two windings transformer '" + id + "' has no TwoWindingsTransformerFortescue extension");
        }
        return extension;
    }

    @Override
    public NetworkDataframeMapper createMapper() {
        return NetworkDataframeMapperBuilder.ofStream(this::itemsStream, this::getOrThrow)
                .stringsIndex("id", ext -> ext.getExtendable().getId())
                .doubles("rz", (ext, context) -> ext.getRz(), (ext, value, context) -> ext.setRz(value))
                .doubles("xz", (ext, context) -> ext.getXz(), (ext, value, context) -> ext.setXz(value))
                .booleans("free_fluxes", TwoWindingsTransformerFortescue::isFreeFluxes, TwoWindingsTransformerFortescue::setFreeFluxes)
                .doubles("xm", (ext, context) -> ext.getXm(), (ext, value, context) -> ext.setXm(value))
                .enums("connection_type1", WindingConnectionType.class,
                        TwoWindingsTransformerFortescue::getConnectionType1,
                        TwoWindingsTransformerFortescue::setConnectionType1)
                .enums("connection_type2", WindingConnectionType.class,
                        TwoWindingsTransformerFortescue::getConnectionType2,
                        TwoWindingsTransformerFortescue::setConnectionType2)
                .doubles("grounding_r1", (ext, context) -> ext.getGroundingR1(), (ext, value, context) -> ext.setGroundingR1(value))
                .doubles("grounding_x1", (ext, context) -> ext.getGroundingX1(), (ext, value, context) -> ext.setGroundingX1(value))
                .doubles("grounding_r2", (ext, context) -> ext.getGroundingR2(), (ext, value, context) -> ext.setGroundingR2(value))
                .doubles("grounding_x2", (ext, context) -> ext.getGroundingX2(), (ext, value, context) -> ext.setGroundingX2(value))
                .build();
    }

    @Override
    public void removeExtensions(Network network, List<String> ids) {
        ids.stream().filter(Objects::nonNull)
                .map(network::getTwoWindingsTransformer)
                .filter(Objects::nonNull)
                .forEach(twt -> twt.removeExtension(TwoWindingsTransformerFortescue.class));
    }

    @Override
    public NetworkElementAdder createAdder() {
        return new TwoWindingsTransformerFortescueDataframeAdder();
    }

}
