/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.dataframe.dynamic.extensions;

import com.powsybl.commons.PowsyblException;
import com.powsybl.dataframe.DataframeFilter;
import com.powsybl.dataframe.impl.DefaultDataframeHandler;
import com.powsybl.dataframe.impl.Series;
import com.powsybl.dataframe.network.NetworkDataframeContext;
import com.powsybl.dataframe.network.NetworkDataframeMapper;
import com.powsybl.dataframe.network.NetworkDataframes;
import com.powsybl.dataframe.network.adders.NetworkElementAdders;
import com.powsybl.dataframe.network.extensions.NetworkExtensions;
import com.powsybl.dataframe.update.DefaultUpdatingDataframe;
import com.powsybl.dataframe.update.TestStringSeries;
import com.powsybl.dataframe.update.UpdatingDataframe;
import com.powsybl.dynawo.extensions.api.svarc.StaticVarCompensatorProperties;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.test.SvcTestCaseFactory;
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
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class StaticVarCompensatorPropertiesDataframeTest {

    private static final String EXTENSION_NAME = "staticVarCompensatorProperties";

    @Test
    void createReadUpdateAndRemove() {
        Network network = SvcTestCaseFactory.create();
        assertNull(network.getStaticVarCompensator("SVC2").getExtension(StaticVarCompensatorProperties.class));

        DefaultUpdatingDataframe dataframe = new DefaultUpdatingDataframe(1);
        dataframe.addSeries("id", true, new TestStringSeries("SVC2"));
        dataframe.addSeries("constructor", false, new TestStringSeries("Alstom"));
        NetworkElementAdders.addExtensions(EXTENSION_NAME, network, singletonList(dataframe));

        StaticVarCompensatorProperties extension =
                network.getStaticVarCompensator("SVC2").getExtension(StaticVarCompensatorProperties.class);
        assertNotNull(extension);
        assertEquals("Alstom", extension.getConstructor());

        List<Series> series = createExtensionDataFrame(network);
        assertThat(series)
                .extracting(Series::getName)
                .containsExactly("id", "constructor");
        assertThat(series.get(0).getStrings()).containsExactly("SVC2");
        assertThat(series.get(1).getStrings()).containsExactly("Alstom");

        DefaultUpdatingDataframe update = new DefaultUpdatingDataframe(1);
        update.addSeries("id", true, new TestStringSeries("SVC2"));
        update.addSeries("constructor", false, new TestStringSeries("Areva"));
        updateExtension(network, update);
        assertEquals("Areva",
                network.getStaticVarCompensator("SVC2").getExtension(StaticVarCompensatorProperties.class).getConstructor());
        assertThat(createExtensionDataFrame(network).get(1).getStrings()).containsExactly("Areva");

        NetworkExtensions.removeExtensions(network, EXTENSION_NAME, List.of("SVC2"));
        assertNull(network.getStaticVarCompensator("SVC2").getExtension(StaticVarCompensatorProperties.class));
        assertEquals(0, createExtensionDataFrame(network).get(0).getStrings().length);
    }

    @Test
    void createOnAnUnknownStaticVarCompensatorThrows() {
        Network network = SvcTestCaseFactory.create();
        DefaultUpdatingDataframe dataframe = new DefaultUpdatingDataframe(1);
        dataframe.addSeries("id", true, new TestStringSeries("UNKNOWN"));
        dataframe.addSeries("constructor", false, new TestStringSeries("Alstom"));
        List<UpdatingDataframe> dataframes = singletonList(dataframe);

        PowsyblException e = assertThrows(PowsyblException.class,
                () -> NetworkElementAdders.addExtensions(EXTENSION_NAME, network, dataframes));
        assertEquals("Static var compensator 'UNKNOWN' does not exist.", e.getMessage());
    }

    @Test
    void createWithoutConstructorThrows() {
        Network network = SvcTestCaseFactory.create();
        DefaultUpdatingDataframe dataframe = new DefaultUpdatingDataframe(1);
        dataframe.addSeries("id", true, new TestStringSeries("SVC2"));
        List<UpdatingDataframe> dataframes = singletonList(dataframe);

        // the constructor has no default value, the extension cannot be created without it
        PowsyblException e = assertThrows(PowsyblException.class,
                () -> NetworkElementAdders.addExtensions(EXTENSION_NAME, network, dataframes));
        assertEquals("Required column constructor is missing.", e.getMessage());
    }

    private static List<Series> createExtensionDataFrame(Network network) {
        List<Series> series = new ArrayList<>();
        NetworkDataframeMapper mapper = NetworkDataframes.getExtensionDataframeMapper(EXTENSION_NAME, null);
        assertNotNull(mapper);
        mapper.createDataframe(network, new DefaultDataframeHandler(series::add), new DataframeFilter(),
                NetworkDataframeContext.DEFAULT);
        return series;
    }

    private static void updateExtension(Network network, UpdatingDataframe updatingDataframe) {
        NetworkDataframeMapper mapper = NetworkDataframes.getExtensionDataframeMapper(EXTENSION_NAME, null);
        assertNotNull(mapper);
        mapper.updateSeries(network, updatingDataframe, NetworkDataframeContext.DEFAULT);
    }
}
