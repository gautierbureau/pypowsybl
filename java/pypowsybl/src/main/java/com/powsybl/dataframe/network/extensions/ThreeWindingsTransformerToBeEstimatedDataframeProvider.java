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
import com.powsybl.iidm.network.ThreeSides;
import com.powsybl.iidm.network.ThreeWindingsTransformer;
import com.powsybl.iidm.network.extensions.ThreeWindingsTransformerToBeEstimated;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * The three legs are flattened into one row, each status being suffixed by the leg number, as the three windings
 * transformer dataframe itself does for its own per leg attributes.
 *
 * @author Claude Code
 */
@AutoService(NetworkExtensionDataframeProvider.class)
public class ThreeWindingsTransformerToBeEstimatedDataframeProvider extends AbstractSingleDataframeNetworkExtension {

    @Override
    public String getExtensionName() {
        return ThreeWindingsTransformerToBeEstimated.NAME;
    }

    @Override
    public ExtensionInformation getExtensionInformation() {
        return new ExtensionInformation(ThreeWindingsTransformerToBeEstimated.NAME,
                "Tells whether the tap changers of each leg of a three windings transformer should be estimated by a state estimation",
                "index : id (str), " +
                        "ratio_tap_changer1_status (bool), " +
                        "ratio_tap_changer2_status (bool), " +
                        "ratio_tap_changer3_status (bool), " +
                        "phase_tap_changer1_status (bool), " +
                        "phase_tap_changer2_status (bool), " +
                        "phase_tap_changer3_status (bool)");
    }

    private Stream<ThreeWindingsTransformerToBeEstimated> itemsStream(Network network) {
        return network.getThreeWindingsTransformerStream()
                .map(twt -> (ThreeWindingsTransformerToBeEstimated) twt.getExtension(ThreeWindingsTransformerToBeEstimated.class))
                .filter(Objects::nonNull);
    }

    private ThreeWindingsTransformerToBeEstimated getOrThrow(Network network, String id) {
        ThreeWindingsTransformer twt = network.getThreeWindingsTransformer(id);
        if (twt == null) {
            throw new PowsyblException("Three windings transformer '" + id + "' not found");
        }
        ThreeWindingsTransformerToBeEstimated extension = twt.getExtension(ThreeWindingsTransformerToBeEstimated.class);
        if (extension == null) {
            throw new PowsyblException("Three windings transformer '" + id + "' has no ThreeWindingsTransformerToBeEstimated extension");
        }
        return extension;
    }

    @Override
    public NetworkDataframeMapper createMapper() {
        NetworkDataframeMapperBuilder<ThreeWindingsTransformerToBeEstimated> builder =
                NetworkDataframeMapperBuilder.<ThreeWindingsTransformerToBeEstimated>ofStream(this::itemsStream, this::getOrThrow)
                        .stringsIndex("id", ext -> ext.getExtendable().getId());
        for (ThreeSides side : ThreeSides.values()) {
            builder.booleans("ratio_tap_changer" + side.getNum() + "_status",
                    ext -> ext.shouldEstimateRatioTapChanger(side),
                    (ext, value) -> ext.shouldEstimateRatioTapChanger(value, side));
        }
        for (ThreeSides side : ThreeSides.values()) {
            builder.booleans("phase_tap_changer" + side.getNum() + "_status",
                    ext -> ext.shouldEstimatePhaseTapChanger(side),
                    (ext, value) -> ext.shouldEstimatePhaseTapChanger(value, side));
        }
        return builder.build();
    }

    @Override
    public void removeExtensions(Network network, List<String> ids) {
        ids.stream().filter(Objects::nonNull)
                .map(network::getThreeWindingsTransformer)
                .filter(Objects::nonNull)
                .forEach(twt -> twt.removeExtension(ThreeWindingsTransformerToBeEstimated.class));
    }

    @Override
    public NetworkElementAdder createAdder() {
        return new ThreeWindingsTransformerToBeEstimatedDataframeAdder();
    }

}
