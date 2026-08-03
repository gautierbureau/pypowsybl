/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.dataframe.network.extensions;

import com.powsybl.commons.extensions.ExtensionProviders;
import com.powsybl.commons.extensions.ExtensionSerDe;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tracks which network extensions powsybl-core can read from a network file but pypowsybl does not expose.
 *
 * <p>An extension registered in core but not wired through here is invisible from python: a network imported
 * with it carries the extension in the IIDM model, yet {@code get_extensions} cannot return it and there is no
 * way to create or remove it. Nothing fails - the data is simply unreachable.
 *
 * <p>Not every core extension is meant to be exposed, so this test does not require full coverage. It pins the
 * current gap in {@link #NOT_EXPOSED} and fails when that gap changes, which makes wiring an extension - or
 * consciously declining to - a deliberate decision rather than an oversight. When a new extension shows up in
 * core, add a dataframe provider for it or add its name below.
 *
 * @author Claude Code
 */
class CoreExtensionsExposureTest {

    /**
     * Network extensions known to powsybl-core that pypowsybl deliberately does not expose yet.
     * Remove a name from this set as soon as a dataframe provider is added for it.
     */
    private static final Set<String> NOT_EXPOSED = Set.of(
            // CGMES / CIM specific
            "baseVoltageMapping",
            "cgmesBoundaryLineBoundaryNode",
            "cgmesControlAreas",
            "cgmesLineBoundaryNode",
            "cgmesTapChangers",
            "cimCharacteristics",
            // unbalanced (Fortescue) modelling
            "generatorFortescue",
            "lineFortescue",
            "loadAsymmetrical",
            "threeWindingsTransformerFortescue",
            "twoWindingsTransformerFortescue",
            // state estimation
            "observabilityArea",
            "threeWindingsTransformerToBeEstimated",
            "twoWindingsTransformerToBeEstimated",
            // others
            "batteryShortCircuit",
            "branchStatus",
            "generatorRemoteReactivePowerControl",
            "lineCouplings",
            "manualFrequencyRestorationReserve",
            "operatingStatus",
            "referenceTerminals",
            "startup",
            "voltageLevelLoadCharacteristics");

    private static Set<String> coreNetworkExtensionNames() {
        Collection<ExtensionSerDe> serDes = ExtensionProviders.createProvider(ExtensionSerDe.class, "network")
                .getProviders();
        return serDes.stream()
                .map(ExtensionSerDe::getExtensionName)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void everyCoreExtensionIsEitherExposedOrKnownToBeMissing() {
        Set<String> core = coreNetworkExtensionNames();
        Set<String> exposed = new TreeSet<>(NetworkExtensions.getExtensionsNames());

        Set<String> unexpectedlyMissing = new TreeSet<>(core);
        unexpectedlyMissing.removeAll(exposed);
        unexpectedlyMissing.removeAll(NOT_EXPOSED);

        assertThat(unexpectedlyMissing)
                .withFailMessage("""
                        %s can be read from a network by powsybl-core but is not exposed by pypowsybl, \
                        so it is unreachable from python. Add a NetworkExtensionDataframeProvider for it, \
                        or add its name to NOT_EXPOSED if it is deliberately left out.""",
                        unexpectedlyMissing)
                .isEmpty();
    }

    @Test
    void notExposedListDoesNotContainExposedExtensions() {
        Set<String> stale = new TreeSet<>(NOT_EXPOSED);
        stale.retainAll(NetworkExtensions.getExtensionsNames());

        assertThat(stale)
                .withFailMessage("%s is now exposed by pypowsybl, remove it from NOT_EXPOSED", stale)
                .isEmpty();
    }

    @Test
    void notExposedListOnlyContainsExtensionsKnownToCore() {
        Set<String> unknown = new TreeSet<>(NOT_EXPOSED);
        unknown.removeAll(coreNetworkExtensionNames());

        assertThat(unknown)
                .withFailMessage("""
                        %s is no longer registered by powsybl-core - it was renamed or its module left the \
                        classpath. Remove it from NOT_EXPOSED.""", unknown)
                .isEmpty();
    }
}
