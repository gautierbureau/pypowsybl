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
import com.powsybl.iidm.network.ThreeWindingsTransformer;
import com.powsybl.iidm.network.extensions.LegFortescue;
import com.powsybl.iidm.network.extensions.ThreeWindingsTransformerFortescue;
import com.powsybl.iidm.network.extensions.WindingConnectionType;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * The three legs of the extension are flattened into one row, each attribute being suffixed by the leg number,
 * as the three windings transformer dataframe itself does for its own per leg attributes.
 *
 * @author Claude Code
 */
@AutoService(NetworkExtensionDataframeProvider.class)
public class ThreeWindingsTransformerFortescueDataframeProvider extends AbstractSingleDataframeNetworkExtension {

    @Override
    public String getExtensionName() {
        return ThreeWindingsTransformerFortescue.NAME;
    }

    @Override
    public ExtensionInformation getExtensionInformation() {
        return new ExtensionInformation(ThreeWindingsTransformerFortescue.NAME,
                "Provides the zero sequence parameters and the winding connection type of each leg of a three windings transformer, used by unbalanced computations",
                "index : id (str), " +
                        "rz1 (float), xz1 (float), free_fluxes1 (bool), connection_type1 (str), grounding_r1 (float), grounding_x1 (float), " +
                        "rz2 (float), xz2 (float), free_fluxes2 (bool), connection_type2 (str), grounding_r2 (float), grounding_x2 (float), " +
                        "rz3 (float), xz3 (float), free_fluxes3 (bool), connection_type3 (str), grounding_r3 (float), grounding_x3 (float)");
    }

    private Stream<ThreeWindingsTransformerFortescue> itemsStream(Network network) {
        return network.getThreeWindingsTransformerStream()
                .map(twt -> (ThreeWindingsTransformerFortescue) twt.getExtension(ThreeWindingsTransformerFortescue.class))
                .filter(Objects::nonNull);
    }

    private ThreeWindingsTransformerFortescue getOrThrow(Network network, String id) {
        ThreeWindingsTransformer twt = network.getThreeWindingsTransformer(id);
        if (twt == null) {
            throw new PowsyblException("Three windings transformer '" + id + "' not found");
        }
        ThreeWindingsTransformerFortescue extension = twt.getExtension(ThreeWindingsTransformerFortescue.class);
        if (extension == null) {
            throw new PowsyblException("Three windings transformer '" + id + "' has no ThreeWindingsTransformerFortescue extension");
        }
        return extension;
    }

    @Override
    public NetworkDataframeMapper createMapper() {
        NetworkDataframeMapperBuilder<ThreeWindingsTransformerFortescue> builder =
                NetworkDataframeMapperBuilder.<ThreeWindingsTransformerFortescue>ofStream(this::itemsStream, this::getOrThrow)
                        .stringsIndex("id", ext -> ext.getExtendable().getId());
        addLegSeries(builder, 1, ThreeWindingsTransformerFortescue::getLeg1);
        addLegSeries(builder, 2, ThreeWindingsTransformerFortescue::getLeg2);
        addLegSeries(builder, 3, ThreeWindingsTransformerFortescue::getLeg3);
        return builder.build();
    }

    private static void addLegSeries(NetworkDataframeMapperBuilder<ThreeWindingsTransformerFortescue> builder,
                                     int legNumber,
                                     Function<ThreeWindingsTransformerFortescue, LegFortescue> leg) {
        builder.doubles("rz" + legNumber,
                        (ext, context) -> leg.apply(ext).getRz(),
                        (ext, value, context) -> leg.apply(ext).setRz(value))
                .doubles("xz" + legNumber,
                        (ext, context) -> leg.apply(ext).getXz(),
                        (ext, value, context) -> leg.apply(ext).setXz(value))
                .booleans("free_fluxes" + legNumber,
                        ext -> leg.apply(ext).isFreeFluxes(),
                        (ext, value) -> leg.apply(ext).setFreeFluxes(value))
                .enums("connection_type" + legNumber, WindingConnectionType.class,
                        ext -> leg.apply(ext).getConnectionType(),
                        (ext, value) -> leg.apply(ext).setConnectionType(value))
                .doubles("grounding_r" + legNumber,
                        (ext, context) -> leg.apply(ext).getGroundingR(),
                        (ext, value, context) -> leg.apply(ext).setGroundingR(value))
                .doubles("grounding_x" + legNumber,
                        (ext, context) -> leg.apply(ext).getGroundingX(),
                        (ext, value, context) -> leg.apply(ext).setGroundingX(value));
    }

    @Override
    public void removeExtensions(Network network, List<String> ids) {
        ids.stream().filter(Objects::nonNull)
                .map(network::getThreeWindingsTransformer)
                .filter(Objects::nonNull)
                .forEach(twt -> twt.removeExtension(ThreeWindingsTransformerFortescue.class));
    }

    @Override
    public NetworkElementAdder createAdder() {
        return new ThreeWindingsTransformerFortescueDataframeAdder();
    }

}
