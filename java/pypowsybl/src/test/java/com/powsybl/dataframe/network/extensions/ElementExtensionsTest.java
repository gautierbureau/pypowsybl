/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.dataframe.network.extensions;

import com.powsybl.commons.PowsyblException;
import com.powsybl.dataframe.DataframeFilter;
import com.powsybl.dataframe.impl.DefaultDataframeHandler;
import com.powsybl.dataframe.impl.Series;
import com.powsybl.dataframe.network.NetworkDataframeContext;
import com.powsybl.dataframe.network.NetworkDataframeMapper;
import com.powsybl.dataframe.network.NetworkDataframes;
import com.powsybl.dataframe.network.adders.NetworkElementAdders;
import com.powsybl.dataframe.update.DefaultUpdatingDataframe;
import com.powsybl.dataframe.update.TestDoubleSeries;
import com.powsybl.dataframe.update.TestStringSeries;
import com.powsybl.dataframe.update.UpdatingDataframe;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.extensions.BatteryShortCircuit;
import com.powsybl.iidm.network.extensions.GeneratorStartup;
import com.powsybl.iidm.network.extensions.OperatingStatus;
import com.powsybl.iidm.network.test.BatteryNetworkFactory;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * @author Claude Code
 */
class ElementExtensionsTest {

    @Test
    void batteryShortCircuit() {
        Network network = BatteryNetworkFactory.create();
        assertNull(network.getBattery("BAT").getExtension(BatteryShortCircuit.class));

        DefaultUpdatingDataframe dataframe = new DefaultUpdatingDataframe(1);
        dataframe.addSeries("id", true, new TestStringSeries("BAT"));
        dataframe.addSeries("direct_sub_trans_x", false, new TestDoubleSeries(1.0));
        dataframe.addSeries("direct_trans_x", false, new TestDoubleSeries(2.0));
        dataframe.addSeries("step_up_transformer_x", false, new TestDoubleSeries(3.0));
        NetworkElementAdders.addExtensions(BatteryShortCircuit.NAME, network, singletonList(dataframe));

        BatteryShortCircuit extension = network.getBattery("BAT").getExtension(BatteryShortCircuit.class);
        assertNotNull(extension);
        assertEquals(2.0, extension.getDirectTransX());

        List<Series> series = createExtensionDataFrame(BatteryShortCircuit.NAME, network);
        assertThat(series).extracting(Series::getName)
                .containsExactly("id", "direct_sub_trans_x", "direct_trans_x", "step_up_transformer_x");
        assertThat(series.get(0).getStrings()).containsExactly("BAT");
        assertThat(series.get(2).getDoubles()).containsExactly(2.0);

        DefaultUpdatingDataframe update = new DefaultUpdatingDataframe(1);
        update.addSeries("id", true, new TestStringSeries("BAT"));
        update.addSeries("direct_trans_x", false, new TestDoubleSeries(9.0));
        updateExtension(BatteryShortCircuit.NAME, network, update);
        assertEquals(9.0, network.getBattery("BAT").getExtension(BatteryShortCircuit.class).getDirectTransX());

        NetworkExtensions.removeExtensions(network, BatteryShortCircuit.NAME, List.of("BAT"));
        assertNull(network.getBattery("BAT").getExtension(BatteryShortCircuit.class));
    }

    @Test
    void generatorStartup() {
        Network network = EurostagTutorialExample1Factory.create();
        assertNull(network.getGenerator("GEN").getExtension(GeneratorStartup.class));

        DefaultUpdatingDataframe dataframe = new DefaultUpdatingDataframe(1);
        dataframe.addSeries("id", true, new TestStringSeries("GEN"));
        dataframe.addSeries("planned_active_power_setpoint", false, new TestDoubleSeries(90.0));
        dataframe.addSeries("startup_cost", false, new TestDoubleSeries(5.0));
        dataframe.addSeries("marginal_cost", false, new TestDoubleSeries(10.0));
        dataframe.addSeries("planned_outage_rate", false, new TestDoubleSeries(0.8));
        dataframe.addSeries("forced_outage_rate", false, new TestDoubleSeries(0.7));
        NetworkElementAdders.addExtensions(GeneratorStartup.NAME, network, singletonList(dataframe));

        GeneratorStartup extension = network.getGenerator("GEN").getExtension(GeneratorStartup.class);
        assertNotNull(extension);
        assertEquals(90.0, extension.getPlannedActivePowerSetpoint());
        assertEquals(10.0, extension.getMarginalCost());

        List<Series> series = createExtensionDataFrame(GeneratorStartup.NAME, network);
        assertThat(series).extracting(Series::getName)
                .containsExactly("id", "planned_active_power_setpoint", "startup_cost", "marginal_cost",
                        "planned_outage_rate", "forced_outage_rate");
        assertThat(series.get(1).getDoubles()).containsExactly(90.0);

        DefaultUpdatingDataframe update = new DefaultUpdatingDataframe(1);
        update.addSeries("id", true, new TestStringSeries("GEN"));
        update.addSeries("marginal_cost", false, new TestDoubleSeries(12.0));
        updateExtension(GeneratorStartup.NAME, network, update);
        assertEquals(12.0, network.getGenerator("GEN").getExtension(GeneratorStartup.class).getMarginalCost());

        NetworkExtensions.removeExtensions(network, GeneratorStartup.NAME, List.of("GEN"));
        assertNull(network.getGenerator("GEN").getExtension(GeneratorStartup.class));
    }

    @Test
    void operatingStatusOnSeveralElementTypes() {
        Network network = EurostagTutorialExample1Factory.create();

        DefaultUpdatingDataframe dataframe = new DefaultUpdatingDataframe(2);
        dataframe.addSeries("id", true, new TestStringSeries("NHV1_NHV2_1", "NGEN_NHV1"));
        dataframe.addSeries("status", false, new TestStringSeries("PLANNED_OUTAGE", "FORCED_OUTAGE"));
        NetworkElementAdders.addExtensions(OperatingStatus.NAME, network, singletonList(dataframe));

        assertEquals(OperatingStatus.Status.PLANNED_OUTAGE,
                ((OperatingStatus<?>) network.getLine("NHV1_NHV2_1").getExtension(OperatingStatus.class)).getStatus());
        assertEquals(OperatingStatus.Status.FORCED_OUTAGE,
                ((OperatingStatus<?>) network.getTwoWindingsTransformer("NGEN_NHV1")
                        .getExtension(OperatingStatus.class)).getStatus());

        List<Series> series = createExtensionDataFrame(OperatingStatus.NAME, network);
        assertThat(series).extracting(Series::getName).containsExactly("id", "status");
        assertThat(series.get(0).getStrings()).containsExactlyInAnyOrder("NHV1_NHV2_1", "NGEN_NHV1");
        assertThat(series.get(1).getStrings()).containsExactlyInAnyOrder("PLANNED_OUTAGE", "FORCED_OUTAGE");

        DefaultUpdatingDataframe update = new DefaultUpdatingDataframe(1);
        update.addSeries("id", true, new TestStringSeries("NHV1_NHV2_1"));
        update.addSeries("status", false, new TestStringSeries("IN_OPERATION"));
        updateExtension(OperatingStatus.NAME, network, update);
        assertEquals(OperatingStatus.Status.IN_OPERATION,
                ((OperatingStatus<?>) network.getLine("NHV1_NHV2_1").getExtension(OperatingStatus.class)).getStatus());

        NetworkExtensions.removeExtensions(network, OperatingStatus.NAME, List.of("NHV1_NHV2_1", "NGEN_NHV1"));
        assertThat(createExtensionDataFrame(OperatingStatus.NAME, network).get(0).getStrings()).isEmpty();
    }

    @Test
    void operatingStatusOnAnUnsupportedElementThrows() {
        Network network = EurostagTutorialExample1Factory.create();
        DefaultUpdatingDataframe dataframe = new DefaultUpdatingDataframe(1);
        dataframe.addSeries("id", true, new TestStringSeries("GEN"));
        dataframe.addSeries("status", false, new TestStringSeries("IN_OPERATION"));
        List<UpdatingDataframe> dataframes = singletonList(dataframe);

        // a generator is not one of the types accepted by OperatingStatus.isAllowedIdentifiable
        PowsyblException e = assertThrows(PowsyblException.class,
                () -> NetworkElementAdders.addExtensions(OperatingStatus.NAME, network, dataframes));
        assertEquals("Network element 'GEN' of type GENERATOR cannot have an OperatingStatus extension",
                e.getMessage());
    }

    @Test
    void operatingStatusWithoutStatusThrows() {
        Network network = EurostagTutorialExample1Factory.create();
        DefaultUpdatingDataframe dataframe = new DefaultUpdatingDataframe(1);
        dataframe.addSeries("id", true, new TestStringSeries("NHV1_NHV2_1"));
        List<UpdatingDataframe> dataframes = singletonList(dataframe);

        PowsyblException e = assertThrows(PowsyblException.class,
                () -> NetworkElementAdders.addExtensions(OperatingStatus.NAME, network, dataframes));
        assertEquals("Required column status is missing.", e.getMessage());
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
