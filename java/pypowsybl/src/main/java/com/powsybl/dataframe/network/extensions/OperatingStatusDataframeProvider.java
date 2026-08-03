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
import com.powsybl.dataframe.network.adders.NetworkUtils;
import com.powsybl.iidm.network.Identifiable;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.extensions.OperatingStatus;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * The set of elements that can carry the extension is not restated here: it is read from
 * {@link OperatingStatus#isAllowedIdentifiable(Identifiable)}, so that this dataframe cannot drift from what
 * powsybl-core accepts.
 *
 * @author Claude Code
 */
@AutoService(NetworkExtensionDataframeProvider.class)
public class OperatingStatusDataframeProvider extends AbstractSingleDataframeNetworkExtension {

    @Override
    public String getExtensionName() {
        return OperatingStatus.NAME;
    }

    @Override
    public ExtensionInformation getExtensionInformation() {
        return new ExtensionInformation(OperatingStatus.NAME,
                "Provides the operating status of a busbar section, a line, a transformer, a boundary line, an HVDC line or a tie line",
                "index : id (str), " +
                        "status (str)");
    }

    private Stream<OperatingStatus> itemsStream(Network network) {
        return network.getIdentifiables().stream()
                .filter(OperatingStatus::isAllowedIdentifiable)
                .map(i -> (OperatingStatus) i.getExtension(OperatingStatus.class))
                .filter(Objects::nonNull);
    }

    private OperatingStatus getOrThrow(Network network, String id) {
        Identifiable<?> identifiable = getAllowedIdentifiableOrThrow(network, id);
        OperatingStatus extension = identifiable.getExtension(OperatingStatus.class);
        if (extension == null) {
            throw new PowsyblException("Network element '" + id + "' has no OperatingStatus extension");
        }
        return extension;
    }

    static Identifiable<?> getAllowedIdentifiableOrThrow(Network network, String id) {
        Identifiable<?> identifiable = NetworkUtils.getIdentifiableOrThrow(network, id);
        if (!OperatingStatus.isAllowedIdentifiable(identifiable)) {
            throw new PowsyblException("Network element '" + id + "' of type " + identifiable.getType()
                    + " cannot have an OperatingStatus extension");
        }
        return identifiable;
    }

    @Override
    public NetworkDataframeMapper createMapper() {
        return NetworkDataframeMapperBuilder.ofStream(this::itemsStream, this::getOrThrow)
                .stringsIndex("id", ext -> ((Identifiable<?>) ext.getExtendable()).getId())
                .enums("status", OperatingStatus.Status.class,
                        OperatingStatus::getStatus,
                        (ext, value) -> ext.setStatus(value))
                .build();
    }

    @Override
    public void removeExtensions(Network network, List<String> ids) {
        ids.stream().filter(Objects::nonNull)
                .map(network::getIdentifiable)
                .filter(Objects::nonNull)
                .filter(OperatingStatus::isAllowedIdentifiable)
                .forEach(i -> i.removeExtension(OperatingStatus.class));
    }

    @Override
    public NetworkElementAdder createAdder() {
        return new OperatingStatusDataframeAdder();
    }

}
