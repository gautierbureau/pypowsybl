/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.dataframe.network.extensions;

import com.powsybl.dataframe.DataframeFilter;
import com.powsybl.dataframe.impl.DefaultDataframeHandler;
import com.powsybl.dataframe.impl.Series;
import com.powsybl.dataframe.network.NetworkDataframeContext;
import com.powsybl.dataframe.network.NetworkDataframeMapper;
import com.powsybl.dataframe.network.NetworkDataframes;
import com.powsybl.dataframe.network.adders.NetworkElementAdders;
import com.powsybl.dataframe.update.DefaultUpdatingDataframe;
import com.powsybl.dataframe.update.TestIntSeries;
import com.powsybl.dataframe.update.TestStringSeries;
import com.powsybl.dataframe.update.UpdatingDataframe;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.extensions.ThreeWindingsTransformerToBeEstimated;
import com.powsybl.iidm.network.extensions.TwoWindingsTransformerToBeEstimated;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.iidm.network.test.ThreeWindingsTransformerNetworkFactory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Claude Code
 */
class ToBeEstimatedExtensionsTest {

    @Test
    void twoWindingsTransformerToBeEstimated() {
        Network network = EurostagTutorialExample1Factory.create();
        assertNull(network.getTwoWindingsTransformer("NHV2_NLOAD")
                .getExtension(TwoWindingsTransformerToBeEstimated.class));

        DefaultUpdatingDataframe dataframe = new DefaultUpdatingDataframe(1);
        dataframe.addSeries("id", true, new TestStringSeries("NHV2_NLOAD"));
        dataframe.addSeries("ratio_tap_changer_status", false, new TestIntSeries(1));
        dataframe.addSeries("phase_tap_changer_status", false, new TestIntSeries(0));
        NetworkElementAdders.addExtensions(TwoWindingsTransformerToBeEstimated.NAME, network, singletonList(dataframe));

        TwoWindingsTransformerToBeEstimated extension = network.getTwoWindingsTransformer("NHV2_NLOAD")
                .getExtension(TwoWindingsTransformerToBeEstimated.class);
        assertNotNull(extension);
        assertTrue(extension.shouldEstimateRatioTapChanger());
        assertThat(extension.shouldEstimatePhaseTapChanger()).isFalse();

        List<Series> series = createExtensionDataFrame(TwoWindingsTransformerToBeEstimated.NAME, network);
        assertThat(series).extracting(Series::getName)
                .containsExactly("id", "ratio_tap_changer_status", "phase_tap_changer_status");
        assertThat(series.get(0).getStrings()).containsExactly("NHV2_NLOAD");
        assertThat(series.get(1).getBooleans()).containsExactly(true);
        assertThat(series.get(2).getBooleans()).containsExactly(false);

        DefaultUpdatingDataframe update = new DefaultUpdatingDataframe(1);
        update.addSeries("id", true, new TestStringSeries("NHV2_NLOAD"));
        update.addSeries("phase_tap_changer_status", false, new TestIntSeries(1));
        updateExtension(TwoWindingsTransformerToBeEstimated.NAME, network, update);
        assertTrue(network.getTwoWindingsTransformer("NHV2_NLOAD")
                .getExtension(TwoWindingsTransformerToBeEstimated.class).shouldEstimatePhaseTapChanger());

        NetworkExtensions.removeExtensions(network, TwoWindingsTransformerToBeEstimated.NAME, List.of("NHV2_NLOAD"));
        assertNull(network.getTwoWindingsTransformer("NHV2_NLOAD")
                .getExtension(TwoWindingsTransformerToBeEstimated.class));
    }

    @Test
    void threeWindingsTransformerToBeEstimated() {
        Network network = ThreeWindingsTransformerNetworkFactory.create();
        assertNull(network.getThreeWindingsTransformer("3WT")
                .getExtension(ThreeWindingsTransformerToBeEstimated.class));

        DefaultUpdatingDataframe dataframe = new DefaultUpdatingDataframe(1);
        dataframe.addSeries("id", true, new TestStringSeries("3WT"));
        dataframe.addSeries("ratio_tap_changer1_status", false, new TestIntSeries(1));
        dataframe.addSeries("ratio_tap_changer2_status", false, new TestIntSeries(0));
        dataframe.addSeries("ratio_tap_changer3_status", false, new TestIntSeries(1));
        dataframe.addSeries("phase_tap_changer1_status", false, new TestIntSeries(0));
        dataframe.addSeries("phase_tap_changer2_status", false, new TestIntSeries(1));
        dataframe.addSeries("phase_tap_changer3_status", false, new TestIntSeries(0));
        NetworkElementAdders.addExtensions(ThreeWindingsTransformerToBeEstimated.NAME, network, singletonList(dataframe));

        ThreeWindingsTransformerToBeEstimated extension = network.getThreeWindingsTransformer("3WT")
                .getExtension(ThreeWindingsTransformerToBeEstimated.class);
        assertNotNull(extension);
        assertTrue(extension.shouldEstimateRatioTapChanger1());
        assertThat(extension.shouldEstimateRatioTapChanger2()).isFalse();
        assertTrue(extension.shouldEstimateRatioTapChanger3());
        assertThat(extension.shouldEstimatePhaseTapChanger1()).isFalse();
        assertTrue(extension.shouldEstimatePhaseTapChanger2());
        assertThat(extension.shouldEstimatePhaseTapChanger3()).isFalse();

        List<Series> series = createExtensionDataFrame(ThreeWindingsTransformerToBeEstimated.NAME, network);
        assertThat(series).extracting(Series::getName)
                .containsExactly("id",
                        "ratio_tap_changer1_status", "ratio_tap_changer2_status", "ratio_tap_changer3_status",
                        "phase_tap_changer1_status", "phase_tap_changer2_status", "phase_tap_changer3_status");
        assertThat(series.get(1).getBooleans()).containsExactly(true);
        assertThat(series.get(2).getBooleans()).containsExactly(false);
        assertThat(series.get(5).getBooleans()).containsExactly(true);

        DefaultUpdatingDataframe update = new DefaultUpdatingDataframe(1);
        update.addSeries("id", true, new TestStringSeries("3WT"));
        update.addSeries("ratio_tap_changer2_status", false, new TestIntSeries(1));
        updateExtension(ThreeWindingsTransformerToBeEstimated.NAME, network, update);
        ThreeWindingsTransformerToBeEstimated updated = network.getThreeWindingsTransformer("3WT")
                .getExtension(ThreeWindingsTransformerToBeEstimated.class);
        assertTrue(updated.shouldEstimateRatioTapChanger2());
        // the other legs are untouched
        assertTrue(updated.shouldEstimateRatioTapChanger1());
        assertTrue(updated.shouldEstimateRatioTapChanger3());

        NetworkExtensions.removeExtensions(network, ThreeWindingsTransformerToBeEstimated.NAME, List.of("3WT"));
        assertNull(network.getThreeWindingsTransformer("3WT")
                .getExtension(ThreeWindingsTransformerToBeEstimated.class));
    }

    private static List<Series> createExtensionDataFrame(String name, Network network) {
        List<Series> series = new ArrayList<>();
        NetworkDataframeMapper mapper = NetworkDataframes.getExtensionDataframeMapper(name, null);
        assertNotNull(mapper);
        mapper.createDataframe(network, new DefaultDataframeHandler(series::add), new DataframeFilter(),
                NetworkDataframeContext.DEFAULT);
        return series;
    }

    private static void updateExtension(String name, Network network, UpdatingDataframe updatingDataframe) {
        NetworkDataframeMapper mapper = NetworkDataframes.getExtensionDataframeMapper(name, null);
        assertNotNull(mapper);
        mapper.updateSeries(network, updatingDataframe, NetworkDataframeContext.DEFAULT);
    }
}
