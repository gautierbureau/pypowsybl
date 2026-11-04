/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.dataframe.dynamic.adders;

import com.powsybl.dataframe.SeriesMetadata;
import com.powsybl.dataframe.dynamic.CategoryInformation;
import com.powsybl.dataframe.dynamic.PersistentStringSeries;
import com.powsybl.dataframe.update.StringSeries;
import com.powsybl.dataframe.update.UpdatingDataframe;
import com.powsybl.dynawo.models.hvdc.AbstractHvdcBuilder;
import com.powsybl.iidm.network.HvdcLine;
import com.powsybl.iidm.network.TwoSides;

import java.util.Collections;
import java.util.List;

import static com.powsybl.dataframe.dynamic.adders.DynamicModelDataframeConstants.*;
import static com.powsybl.dataframe.network.adders.SeriesUtils.applyIfPresent;

/**
 * HVDC adders accept, on top of the equipment columns, an optional {@code dangling_side} column ({@code ONE} or
 * {@code TWO}) giving the side connected to a switched-off bus for dangling models.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
abstract class AbstractHvdcAdder extends AbstractSimpleDynamicModelAdder {

    protected static final List<SeriesMetadata> HVDC_METADATA = List.of(
            SeriesMetadata.stringIndex(STATIC_ID),
            SeriesMetadata.strings(PARAMETER_SET_ID),
            SeriesMetadata.strings(MODEL_NAME),
            SeriesMetadata.strings(DANGLING_SIDE));

    protected AbstractHvdcAdder(CategoryInformation categoryInformation) {
        super(Collections.singletonList(HVDC_METADATA), categoryInformation);
    }

    abstract static class AbstractHvdcSeries<B extends AbstractHvdcBuilder<B>> extends AbstractEquipmentSeries<HvdcLine, B> {

        private final StringSeries danglingSides;

        AbstractHvdcSeries(UpdatingDataframe dataframe) {
            super(dataframe);
            this.danglingSides = PersistentStringSeries.copyOf(dataframe, DANGLING_SIDE);
        }

        @Override
        protected void applyOnBuilder(int row, B builder) {
            super.applyOnBuilder(row, builder);
            applyIfPresent(danglingSides, row, TwoSides.class, builder::dangling);
        }
    }
}
