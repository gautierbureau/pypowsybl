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
import com.powsybl.iidm.network.extensions.TwoWindingsTransformerToBeEstimated;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * @author Claude Code
 */
@AutoService(NetworkExtensionDataframeProvider.class)
public class TwoWindingsTransformerToBeEstimatedDataframeProvider extends AbstractSingleDataframeNetworkExtension {

    @Override
    public String getExtensionName() {
        return TwoWindingsTransformerToBeEstimated.NAME;
    }

    @Override
    public ExtensionInformation getExtensionInformation() {
        return new ExtensionInformation(TwoWindingsTransformerToBeEstimated.NAME,
                "Tells whether the tap changers of a two windings transformer should be estimated by a state estimation",
                "index : id (str), " +
                        "ratio_tap_changer_status (bool), " +
                        "phase_tap_changer_status (bool)");
    }

    private Stream<TwoWindingsTransformerToBeEstimated> itemsStream(Network network) {
        return network.getTwoWindingsTransformerStream()
                .map(twt -> (TwoWindingsTransformerToBeEstimated) twt.getExtension(TwoWindingsTransformerToBeEstimated.class))
                .filter(Objects::nonNull);
    }

    private TwoWindingsTransformerToBeEstimated getOrThrow(Network network, String id) {
        TwoWindingsTransformer twt = network.getTwoWindingsTransformer(id);
        if (twt == null) {
            throw new PowsyblException("Two windings transformer '" + id + "' not found");
        }
        TwoWindingsTransformerToBeEstimated extension = twt.getExtension(TwoWindingsTransformerToBeEstimated.class);
        if (extension == null) {
            throw new PowsyblException("Two windings transformer '" + id + "' has no TwoWindingsTransformerToBeEstimated extension");
        }
        return extension;
    }

    @Override
    public NetworkDataframeMapper createMapper() {
        return NetworkDataframeMapperBuilder.ofStream(this::itemsStream, this::getOrThrow)
                .stringsIndex("id", ext -> ext.getExtendable().getId())
                .booleans("ratio_tap_changer_status",
                        ext -> ext.shouldEstimateRatioTapChanger(),
                        (ext, value) -> ext.shouldEstimateRatioTapChanger(value))
                .booleans("phase_tap_changer_status",
                        ext -> ext.shouldEstimatePhaseTapChanger(),
                        (ext, value) -> ext.shouldEstimatePhaseTapChanger(value))
                .build();
    }

    @Override
    public void removeExtensions(Network network, List<String> ids) {
        ids.stream().filter(Objects::nonNull)
                .map(network::getTwoWindingsTransformer)
                .filter(Objects::nonNull)
                .forEach(twt -> twt.removeExtension(TwoWindingsTransformerToBeEstimated.class));
    }

    @Override
    public NetworkElementAdder createAdder() {
        return new TwoWindingsTransformerToBeEstimatedDataframeAdder();
    }

}
