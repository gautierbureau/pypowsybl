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
import com.powsybl.iidm.network.extensions.GeneratorFortescue;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * @author Claude Code
 */
@AutoService(NetworkExtensionDataframeProvider.class)
public class GeneratorFortescueDataframeProvider extends AbstractSingleDataframeNetworkExtension {

    @Override
    public String getExtensionName() {
        return GeneratorFortescue.NAME;
    }

    @Override
    public ExtensionInformation getExtensionInformation() {
        return new ExtensionInformation(GeneratorFortescue.NAME,
                "Provides the zero and negative sequence parameters of a generator, used by unbalanced computations",
                "index : id (str), " +
                        "grounded (bool), " +
                        "grounding_r (float), " +
                        "grounding_x (float), " +
                        "rz (float), " +
                        "xz (float), " +
                        "rn (float), " +
                        "xn (float)");
    }

    private Stream<GeneratorFortescue> itemsStream(Network network) {
        return network.getGeneratorStream()
                .map(g -> (GeneratorFortescue) g.getExtension(GeneratorFortescue.class))
                .filter(Objects::nonNull);
    }

    private GeneratorFortescue getOrThrow(Network network, String id) {
        Generator generator = network.getGenerator(id);
        if (generator == null) {
            throw new PowsyblException("Generator '" + id + "' not found");
        }
        GeneratorFortescue extension = generator.getExtension(GeneratorFortescue.class);
        if (extension == null) {
            throw new PowsyblException("Generator '" + id + "' has no GeneratorFortescue extension");
        }
        return extension;
    }

    @Override
    public NetworkDataframeMapper createMapper() {
        return NetworkDataframeMapperBuilder.ofStream(this::itemsStream, this::getOrThrow)
                .stringsIndex("id", ext -> ext.getExtendable().getId())
                .booleans("grounded", GeneratorFortescue::isGrounded, GeneratorFortescue::setGrounded)
                .doubles("grounding_r", (ext, context) -> ext.getGroundingR(), (ext, value, context) -> ext.setGroundingR(value))
                .doubles("grounding_x", (ext, context) -> ext.getGroundingX(), (ext, value, context) -> ext.setGroundingX(value))
                .doubles("rz", (ext, context) -> ext.getRz(), (ext, value, context) -> ext.setRz(value))
                .doubles("xz", (ext, context) -> ext.getXz(), (ext, value, context) -> ext.setXz(value))
                .doubles("rn", (ext, context) -> ext.getRn(), (ext, value, context) -> ext.setRn(value))
                .doubles("xn", (ext, context) -> ext.getXn(), (ext, value, context) -> ext.setXn(value))
                .build();
    }

    @Override
    public void removeExtensions(Network network, List<String> ids) {
        ids.stream().filter(Objects::nonNull)
                .map(network::getGenerator)
                .filter(Objects::nonNull)
                .forEach(g -> g.removeExtension(GeneratorFortescue.class));
    }

    @Override
    public NetworkElementAdder createAdder() {
        return new GeneratorFortescueDataframeAdder();
    }

}
