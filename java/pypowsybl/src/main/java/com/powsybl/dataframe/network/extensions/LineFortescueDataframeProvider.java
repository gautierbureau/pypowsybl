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
import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.extensions.LineFortescue;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * @author Claude Code
 */
@AutoService(NetworkExtensionDataframeProvider.class)
public class LineFortescueDataframeProvider extends AbstractSingleDataframeNetworkExtension {

    @Override
    public String getExtensionName() {
        return LineFortescue.NAME;
    }

    @Override
    public ExtensionInformation getExtensionInformation() {
        return new ExtensionInformation(LineFortescue.NAME,
                "Provides the zero sequence parameters and the open phases of a line, used by unbalanced computations",
                "index : id (str), " +
                        "rz (float), " +
                        "xz (float), " +
                        "g1z (float), " +
                        "b1z (float), " +
                        "g2z (float), " +
                        "b2z (float), " +
                        "open_phase_a (bool), " +
                        "open_phase_b (bool), " +
                        "open_phase_c (bool)");
    }

    private Stream<LineFortescue> itemsStream(Network network) {
        return network.getLineStream()
                .map(l -> (LineFortescue) l.getExtension(LineFortescue.class))
                .filter(Objects::nonNull);
    }

    private LineFortescue getOrThrow(Network network, String id) {
        Line line = network.getLine(id);
        if (line == null) {
            throw new PowsyblException("Line '" + id + "' not found");
        }
        LineFortescue extension = line.getExtension(LineFortescue.class);
        if (extension == null) {
            throw new PowsyblException("Line '" + id + "' has no LineFortescue extension");
        }
        return extension;
    }

    @Override
    public NetworkDataframeMapper createMapper() {
        return NetworkDataframeMapperBuilder.ofStream(this::itemsStream, this::getOrThrow)
                .stringsIndex("id", ext -> ext.getExtendable().getId())
                .doubles("rz", (ext, context) -> ext.getRz(), (ext, value, context) -> ext.setRz(value))
                .doubles("xz", (ext, context) -> ext.getXz(), (ext, value, context) -> ext.setXz(value))
                .doubles("g1z", (ext, context) -> ext.getG1z(), (ext, value, context) -> ext.setG1z(value))
                .doubles("b1z", (ext, context) -> ext.getB1z(), (ext, value, context) -> ext.setB1z(value))
                .doubles("g2z", (ext, context) -> ext.getG2z(), (ext, value, context) -> ext.setG2z(value))
                .doubles("b2z", (ext, context) -> ext.getB2z(), (ext, value, context) -> ext.setB2z(value))
                .booleans("open_phase_a", LineFortescue::isOpenPhaseA, LineFortescue::setOpenPhaseA)
                .booleans("open_phase_b", LineFortescue::isOpenPhaseB, LineFortescue::setOpenPhaseB)
                .booleans("open_phase_c", LineFortescue::isOpenPhaseC, LineFortescue::setOpenPhaseC)
                .build();
    }

    @Override
    public void removeExtensions(Network network, List<String> ids) {
        ids.stream().filter(Objects::nonNull)
                .map(network::getLine)
                .filter(Objects::nonNull)
                .forEach(l -> l.removeExtension(LineFortescue.class));
    }

    @Override
    public NetworkElementAdder createAdder() {
        return new LineFortescueDataframeAdder();
    }

}
