/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.dataframe.network.extensions;

import com.powsybl.cgmes.extensions.CgmesMetadataModels;
import com.powsybl.commons.extensions.Extension;
import com.powsybl.dataframe.DataframeFilter;
import com.powsybl.dataframe.impl.DefaultDataframeHandler;
import com.powsybl.dataframe.impl.Series;
import com.powsybl.dataframe.network.NetworkDataframeContext;
import com.powsybl.dataframe.network.NetworkDataframeMapper;
import com.powsybl.dataframe.network.NetworkDataframes;
import com.powsybl.iidm.network.Identifiable;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.extensions.ActivePowerControlAdder;
import com.powsybl.iidm.network.extensions.SecondaryVoltageControl;
import com.powsybl.iidm.network.test.BatteryNetworkFactory;
import com.powsybl.python.network.Networks;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks that the extension dataframes expose every element the IIDM model actually carries the extension on.
 *
 * <p>Each extension provider re-declares, in its {@code itemsStream}, which element types can carry the extension.
 * That declaration is written by hand and can be narrower than what powsybl-core supports, in which case
 * {@code get_extensions} silently returns fewer rows than expected - no error, it just looks like the extension
 * was never set. This test does not encode which types are legal: it compares the extension dataframe against
 * the IIDM model itself, so it covers every registered extension at once.
 *
 * <p>Adding a network to {@link #fixtures()} that carries an extension on a new element type is enough to get
 * that combination checked.
 *
 * @author Claude Code
 */
class ExtensionsCoverageTest {

    /**
     * Extensions attached to the network rather than to one of its identifiables, and whose dataframe is
     * therefore not indexed by the id of the element carrying the extension.
     */
    private static final Set<String> NOT_INDEXED_BY_ELEMENT_ID = Set.of(
            SecondaryVoltageControl.NAME,
            CgmesMetadataModels.NAME);

    private record Fixture(String description, Network network) {
        @Override
        public String toString() {
            return description;
        }
    }

    static Stream<Fixture> fixtures() {
        return Stream.of(
                new Fixture("activePowerControl on a generator",
                        Networks.createEurostagTutorialExample1WithApcExtension()),
                new Fixture("activePowerControl on a generator and on a battery",
                        activePowerControlOnGeneratorAndBattery()));
    }

    private static Network activePowerControlOnGeneratorAndBattery() {
        Network network = BatteryNetworkFactory.create();
        network.getGenerator("GEN")
                .newExtension(ActivePowerControlAdder.class)
                .withParticipate(true)
                .withDroop(1.1)
                .add();
        network.getBattery("BAT2")
                .newExtension(ActivePowerControlAdder.class)
                .withParticipate(true)
                .withDroop(2.2)
                .add();
        return network;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void everyExtensionAttachedInTheModelIsListedInItsDataframe(Fixture fixture) {
        Network network = fixture.network();

        Map<String, Set<String>> attachedInModel = new TreeMap<>();
        for (Identifiable<?> identifiable : network.getIdentifiables()) {
            for (Extension<?> extension : identifiable.getExtensions()) {
                attachedInModel.computeIfAbsent(extension.getName(), name -> new TreeSet<>())
                        .add(identifiable.getId());
            }
        }

        List<String> mismatches = new ArrayList<>();
        for (String extensionName : NetworkExtensions.getExtensionsNames()) {
            if (NOT_INDEXED_BY_ELEMENT_ID.contains(extensionName)) {
                continue;
            }
            NetworkDataframeMapper mapper = NetworkDataframes.getExtensionDataframeMapper(extensionName, null);
            if (mapper == null) {
                // extension exposed through several named dataframes, not keyed by a single element id
                continue;
            }
            Set<String> expected = attachedInModel.getOrDefault(extensionName, Set.of());
            Set<String> listed = listedElementIds(mapper, network);
            if (!expected.equals(listed)) {
                Set<String> missing = new TreeSet<>(expected);
                missing.removeAll(listed);
                Set<String> unexpected = new TreeSet<>(listed);
                unexpected.removeAll(expected);
                mismatches.add(String.format(
                        "'%s' is attached to %s in the model but its dataframe lists %s (missing: %s, unexpected: %s)",
                        extensionName, expected, listed, missing, unexpected));
            }
        }

        assertThat(mismatches).isEmpty();
    }

    /**
     * Ids of the elements the extension dataframe actually reports, read from its first index column.
     */
    private static Set<String> listedElementIds(NetworkDataframeMapper mapper, Network network) {
        List<Series> series = new ArrayList<>();
        mapper.createDataframe(network, new DefaultDataframeHandler(series::add), new DataframeFilter(),
                NetworkDataframeContext.DEFAULT);
        if (series.isEmpty()) {
            return Set.of();
        }
        Series index = series.get(0);
        assertThat(index.isIndex())
                .withFailMessage("First series of an extension dataframe is expected to be an index, got '%s'",
                        index.getName())
                .isTrue();
        return new TreeSet<>(Arrays.asList(index.getStrings()));
    }
}
