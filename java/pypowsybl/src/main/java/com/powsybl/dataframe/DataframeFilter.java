/**
 * Copyright (c) 2021-2022, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.dataframe;

import com.powsybl.dataframe.update.UpdatingDataframe;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Define filters to apply to a dataframe.
 *
 * @author Massimo Ferraro {@literal <massimo.ferraro@soft.it>}
 * @author Christian Biasuzzi {@literal <christian.biasuzzi@soft.it>}
 */
public class DataframeFilter {

    private final AttributeFilterType attributeFilterType;
    private final List<String> inputAttributes;
    private final Set<String> inputAttributesSet;
    private final UpdatingDataframe selectingDataframe;

    public enum AttributeFilterType {
        DEFAULT_ATTRIBUTES,
        INPUT_ATTRIBUTES,
        ALL_ATTRIBUTES
    }

    public DataframeFilter(AttributeFilterType attributeFilterType, List<String> inputAttributes, UpdatingDataframe selectingDataframe) {
        this.attributeFilterType = Objects.requireNonNull(attributeFilterType);
        this.inputAttributes = Objects.requireNonNull(inputAttributes);
        this.inputAttributesSet = new HashSet<>(inputAttributes);
        this.selectingDataframe = selectingDataframe;
    }

    public DataframeFilter(AttributeFilterType attributeFilterType, List<String> inputAttributes) {
        this(attributeFilterType, inputAttributes, null);
    }

    public DataframeFilter() {
        this(AttributeFilterType.DEFAULT_ATTRIBUTES, Collections.emptyList(), null);
    }

    public AttributeFilterType getAttributeFilterType() {
        return attributeFilterType;
    }

    public List<String> getInputAttributes() {
        return inputAttributes;
    }

    /**
     * Whether the given attribute name is part of the input attributes. O(1) lookup, unlike
     * {@code getInputAttributes().contains(name)} which is linear and called once per column.
     */
    public boolean isInputAttribute(String name) {
        return inputAttributesSet.contains(name);
    }

    public Optional<UpdatingDataframe> getSelectingDataframe() {
        return Optional.ofNullable(selectingDataframe);
    }
}
