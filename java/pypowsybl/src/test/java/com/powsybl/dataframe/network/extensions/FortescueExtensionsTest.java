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
import com.powsybl.dataframe.update.TestDoubleSeries;
import com.powsybl.dataframe.update.TestIntSeries;
import com.powsybl.dataframe.update.TestStringSeries;
import com.powsybl.dataframe.update.UpdatingDataframe;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.extensions.GeneratorFortescue;
import com.powsybl.iidm.network.extensions.LineFortescue;
import com.powsybl.iidm.network.extensions.LoadAsymmetrical;
import com.powsybl.iidm.network.extensions.LoadConnectionType;
import com.powsybl.iidm.network.extensions.ThreeWindingsTransformerFortescue;
import com.powsybl.iidm.network.extensions.TwoWindingsTransformerFortescue;
import com.powsybl.iidm.network.extensions.WindingConnectionType;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.iidm.network.test.ThreeWindingsTransformerNetworkFactory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Claude Code
 */
class FortescueExtensionsTest {

    @Test
    void generatorFortescue() {
        Network network = EurostagTutorialExample1Factory.create();
        assertNull(network.getGenerator("GEN").getExtension(GeneratorFortescue.class));

        DefaultUpdatingDataframe dataframe = new DefaultUpdatingDataframe(1);
        dataframe.addSeries("id", true, new TestStringSeries("GEN"));
        dataframe.addSeries("grounded", false, new TestIntSeries(1));
        dataframe.addSeries("grounding_r", false, new TestDoubleSeries(0.1));
        dataframe.addSeries("grounding_x", false, new TestDoubleSeries(0.2));
        dataframe.addSeries("rz", false, new TestDoubleSeries(1.0));
        dataframe.addSeries("xz", false, new TestDoubleSeries(2.0));
        dataframe.addSeries("rn", false, new TestDoubleSeries(3.0));
        dataframe.addSeries("xn", false, new TestDoubleSeries(4.0));
        NetworkElementAdders.addExtensions(GeneratorFortescue.NAME, network, singletonList(dataframe));

        GeneratorFortescue extension = network.getGenerator("GEN").getExtension(GeneratorFortescue.class);
        assertNotNull(extension);
        assertTrue(extension.isGrounded());
        assertEquals(3.0, extension.getRn());

        List<Series> series = createExtensionDataFrame(GeneratorFortescue.NAME, network);
        assertThat(series).extracting(Series::getName)
                .containsExactly("id", "grounded", "grounding_r", "grounding_x", "rz", "xz", "rn", "xn");
        assertThat(series.get(0).getStrings()).containsExactly("GEN");
        assertThat(series.get(1).getBooleans()).containsExactly(true);
        assertThat(series.get(6).getDoubles()).containsExactly(3.0);

        DefaultUpdatingDataframe update = new DefaultUpdatingDataframe(1);
        update.addSeries("id", true, new TestStringSeries("GEN"));
        update.addSeries("rn", false, new TestDoubleSeries(5.0));
        updateExtension(GeneratorFortescue.NAME, network, update);
        assertEquals(5.0, network.getGenerator("GEN").getExtension(GeneratorFortescue.class).getRn());

        NetworkExtensions.removeExtensions(network, GeneratorFortescue.NAME, List.of("GEN"));
        assertNull(network.getGenerator("GEN").getExtension(GeneratorFortescue.class));
    }

    @Test
    void lineFortescue() {
        Network network = EurostagTutorialExample1Factory.create();
        assertNull(network.getLine("NHV1_NHV2_1").getExtension(LineFortescue.class));

        DefaultUpdatingDataframe dataframe = new DefaultUpdatingDataframe(1);
        dataframe.addSeries("id", true, new TestStringSeries("NHV1_NHV2_1"));
        dataframe.addSeries("rz", false, new TestDoubleSeries(1.0));
        dataframe.addSeries("xz", false, new TestDoubleSeries(2.0));
        dataframe.addSeries("g1z", false, new TestDoubleSeries(3.0));
        dataframe.addSeries("b1z", false, new TestDoubleSeries(4.0));
        dataframe.addSeries("g2z", false, new TestDoubleSeries(5.0));
        dataframe.addSeries("b2z", false, new TestDoubleSeries(6.0));
        dataframe.addSeries("open_phase_a", false, new TestIntSeries(1));
        dataframe.addSeries("open_phase_b", false, new TestIntSeries(0));
        dataframe.addSeries("open_phase_c", false, new TestIntSeries(0));
        NetworkElementAdders.addExtensions(LineFortescue.NAME, network, singletonList(dataframe));

        LineFortescue extension = network.getLine("NHV1_NHV2_1").getExtension(LineFortescue.class);
        assertNotNull(extension);
        assertEquals(1.0, extension.getRz());
        assertTrue(extension.isOpenPhaseA());

        List<Series> series = createExtensionDataFrame(LineFortescue.NAME, network);
        assertThat(series).extracting(Series::getName)
                .containsExactly("id", "rz", "xz", "g1z", "b1z", "g2z", "b2z",
                        "open_phase_a", "open_phase_b", "open_phase_c");
        assertThat(series.get(0).getStrings()).containsExactly("NHV1_NHV2_1");
        assertThat(series.get(7).getBooleans()).containsExactly(true);

        DefaultUpdatingDataframe update = new DefaultUpdatingDataframe(1);
        update.addSeries("id", true, new TestStringSeries("NHV1_NHV2_1"));
        update.addSeries("open_phase_a", false, new TestIntSeries(0));
        updateExtension(LineFortescue.NAME, network, update);
        assertThat(network.getLine("NHV1_NHV2_1").getExtension(LineFortescue.class).isOpenPhaseA()).isFalse();

        NetworkExtensions.removeExtensions(network, LineFortescue.NAME, List.of("NHV1_NHV2_1"));
        assertNull(network.getLine("NHV1_NHV2_1").getExtension(LineFortescue.class));
    }

    @Test
    void loadAsymmetrical() {
        Network network = EurostagTutorialExample1Factory.create();
        assertNull(network.getLoad("LOAD").getExtension(LoadAsymmetrical.class));

        DefaultUpdatingDataframe dataframe = new DefaultUpdatingDataframe(1);
        dataframe.addSeries("id", true, new TestStringSeries("LOAD"));
        dataframe.addSeries("connection_type", false, new TestStringSeries("DELTA"));
        dataframe.addSeries("delta_pa", false, new TestDoubleSeries(1.0));
        dataframe.addSeries("delta_pb", false, new TestDoubleSeries(2.0));
        dataframe.addSeries("delta_pc", false, new TestDoubleSeries(3.0));
        dataframe.addSeries("delta_qa", false, new TestDoubleSeries(4.0));
        dataframe.addSeries("delta_qb", false, new TestDoubleSeries(5.0));
        dataframe.addSeries("delta_qc", false, new TestDoubleSeries(6.0));
        NetworkElementAdders.addExtensions(LoadAsymmetrical.NAME, network, singletonList(dataframe));

        LoadAsymmetrical extension = network.getLoad("LOAD").getExtension(LoadAsymmetrical.class);
        assertNotNull(extension);
        assertEquals(LoadConnectionType.DELTA, extension.getConnectionType());
        assertEquals(1.0, extension.getDeltaPa());

        List<Series> series = createExtensionDataFrame(LoadAsymmetrical.NAME, network);
        assertThat(series).extracting(Series::getName)
                .containsExactly("id", "connection_type", "delta_pa", "delta_pb", "delta_pc",
                        "delta_qa", "delta_qb", "delta_qc");
        assertThat(series.get(1).getStrings()).containsExactly("DELTA");

        DefaultUpdatingDataframe update = new DefaultUpdatingDataframe(1);
        update.addSeries("id", true, new TestStringSeries("LOAD"));
        update.addSeries("connection_type", false, new TestStringSeries("Y"));
        updateExtension(LoadAsymmetrical.NAME, network, update);
        assertEquals(LoadConnectionType.Y,
                network.getLoad("LOAD").getExtension(LoadAsymmetrical.class).getConnectionType());

        NetworkExtensions.removeExtensions(network, LoadAsymmetrical.NAME, List.of("LOAD"));
        assertNull(network.getLoad("LOAD").getExtension(LoadAsymmetrical.class));
    }

    @Test
    void twoWindingsTransformerFortescue() {
        Network network = EurostagTutorialExample1Factory.create();
        assertNull(network.getTwoWindingsTransformer("NGEN_NHV1").getExtension(TwoWindingsTransformerFortescue.class));

        DefaultUpdatingDataframe dataframe = new DefaultUpdatingDataframe(1);
        dataframe.addSeries("id", true, new TestStringSeries("NGEN_NHV1"));
        dataframe.addSeries("rz", false, new TestDoubleSeries(1.0));
        dataframe.addSeries("xz", false, new TestDoubleSeries(2.0));
        dataframe.addSeries("free_fluxes", false, new TestIntSeries(1));
        dataframe.addSeries("xm", false, new TestDoubleSeries(3.0));
        dataframe.addSeries("connection_type1", false, new TestStringSeries("Y_GROUNDED"));
        dataframe.addSeries("connection_type2", false, new TestStringSeries("DELTA"));
        dataframe.addSeries("grounding_r1", false, new TestDoubleSeries(4.0));
        dataframe.addSeries("grounding_x1", false, new TestDoubleSeries(5.0));
        dataframe.addSeries("grounding_r2", false, new TestDoubleSeries(6.0));
        dataframe.addSeries("grounding_x2", false, new TestDoubleSeries(7.0));
        NetworkElementAdders.addExtensions(TwoWindingsTransformerFortescue.NAME, network, singletonList(dataframe));

        TwoWindingsTransformerFortescue extension =
                network.getTwoWindingsTransformer("NGEN_NHV1").getExtension(TwoWindingsTransformerFortescue.class);
        assertNotNull(extension);
        assertTrue(extension.isFreeFluxes());
        assertEquals(WindingConnectionType.Y_GROUNDED, extension.getConnectionType1());
        assertEquals(WindingConnectionType.DELTA, extension.getConnectionType2());

        List<Series> series = createExtensionDataFrame(TwoWindingsTransformerFortescue.NAME, network);
        assertThat(series).extracting(Series::getName)
                .containsExactly("id", "rz", "xz", "free_fluxes", "xm", "connection_type1", "connection_type2",
                        "grounding_r1", "grounding_x1", "grounding_r2", "grounding_x2");
        assertThat(series.get(5).getStrings()).containsExactly("Y_GROUNDED");

        DefaultUpdatingDataframe update = new DefaultUpdatingDataframe(1);
        update.addSeries("id", true, new TestStringSeries("NGEN_NHV1"));
        update.addSeries("connection_type2", false, new TestStringSeries("Y"));
        updateExtension(TwoWindingsTransformerFortescue.NAME, network, update);
        assertEquals(WindingConnectionType.Y, network.getTwoWindingsTransformer("NGEN_NHV1")
                .getExtension(TwoWindingsTransformerFortescue.class).getConnectionType2());

        NetworkExtensions.removeExtensions(network, TwoWindingsTransformerFortescue.NAME, List.of("NGEN_NHV1"));
        assertNull(network.getTwoWindingsTransformer("NGEN_NHV1").getExtension(TwoWindingsTransformerFortescue.class));
    }

    @Test
    void threeWindingsTransformerFortescue() {
        Network network = ThreeWindingsTransformerNetworkFactory.create();
        assertNull(network.getThreeWindingsTransformer("3WT").getExtension(ThreeWindingsTransformerFortescue.class));

        DefaultUpdatingDataframe dataframe = new DefaultUpdatingDataframe(1);
        dataframe.addSeries("id", true, new TestStringSeries("3WT"));
        for (int leg = 1; leg <= 3; leg++) {
            dataframe.addSeries("rz" + leg, false, new TestDoubleSeries(leg * 10.0));
            dataframe.addSeries("xz" + leg, false, new TestDoubleSeries(leg * 20.0));
            dataframe.addSeries("free_fluxes" + leg, false, new TestIntSeries(leg == 2 ? 1 : 0));
            dataframe.addSeries("grounding_r" + leg, false, new TestDoubleSeries(leg * 30.0));
            dataframe.addSeries("grounding_x" + leg, false, new TestDoubleSeries(leg * 40.0));
        }
        dataframe.addSeries("connection_type1", false, new TestStringSeries("Y"));
        dataframe.addSeries("connection_type2", false, new TestStringSeries("Y_GROUNDED"));
        dataframe.addSeries("connection_type3", false, new TestStringSeries("DELTA"));
        NetworkElementAdders.addExtensions(ThreeWindingsTransformerFortescue.NAME, network, singletonList(dataframe));

        ThreeWindingsTransformerFortescue extension =
                network.getThreeWindingsTransformer("3WT").getExtension(ThreeWindingsTransformerFortescue.class);
        assertNotNull(extension);
        assertEquals(10.0, extension.getLeg1().getRz());
        assertEquals(40.0, extension.getLeg2().getXz());
        assertEquals(90.0, extension.getLeg3().getGroundingR());
        assertTrue(extension.getLeg2().isFreeFluxes());
        assertThat(extension.getLeg1().isFreeFluxes()).isFalse();
        assertEquals(WindingConnectionType.Y, extension.getLeg1().getConnectionType());
        assertEquals(WindingConnectionType.Y_GROUNDED, extension.getLeg2().getConnectionType());
        assertEquals(WindingConnectionType.DELTA, extension.getLeg3().getConnectionType());

        List<Series> series = createExtensionDataFrame(ThreeWindingsTransformerFortescue.NAME, network);
        assertThat(series).extracting(Series::getName)
                .containsExactly("id",
                        "rz1", "xz1", "free_fluxes1", "connection_type1", "grounding_r1", "grounding_x1",
                        "rz2", "xz2", "free_fluxes2", "connection_type2", "grounding_r2", "grounding_x2",
                        "rz3", "xz3", "free_fluxes3", "connection_type3", "grounding_r3", "grounding_x3");
        assertThat(series.get(0).getStrings()).containsExactly("3WT");
        assertThat(series.get(1).getDoubles()).containsExactly(10.0);
        assertThat(series.get(13).getDoubles()).containsExactly(30.0);

        DefaultUpdatingDataframe update = new DefaultUpdatingDataframe(1);
        update.addSeries("id", true, new TestStringSeries("3WT"));
        update.addSeries("rz3", false, new TestDoubleSeries(99.0));
        update.addSeries("connection_type3", false, new TestStringSeries("Y"));
        updateExtension(ThreeWindingsTransformerFortescue.NAME, network, update);
        ThreeWindingsTransformerFortescue updated =
                network.getThreeWindingsTransformer("3WT").getExtension(ThreeWindingsTransformerFortescue.class);
        assertEquals(99.0, updated.getLeg3().getRz());
        assertEquals(WindingConnectionType.Y, updated.getLeg3().getConnectionType());
        // the other legs are untouched
        assertEquals(10.0, updated.getLeg1().getRz());
        assertEquals(20.0, updated.getLeg2().getRz());

        NetworkExtensions.removeExtensions(network, ThreeWindingsTransformerFortescue.NAME, List.of("3WT"));
        assertNull(network.getThreeWindingsTransformer("3WT").getExtension(ThreeWindingsTransformerFortescue.class));
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
