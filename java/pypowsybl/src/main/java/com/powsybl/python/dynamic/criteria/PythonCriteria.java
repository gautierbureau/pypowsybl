/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.python.dynamic.criteria;

import com.powsybl.dynawo.criteria.CriteriaCollection;

/**
 * The handle target the criteria binding hands back to Python: a {@link CriteriaCollection} the
 * {@link CriteriaDataframeAdder} fills from dataframes and a run reads onto its Dynawo parameters. It is the
 * criteria counterpart of the dynamic-model supplier, a mutable holder kept behind an {@code ObjectHandle}.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public class PythonCriteria {

    private final CriteriaCollection collection = new CriteriaCollection();

    public CriteriaCollection getCollection() {
        return collection;
    }
}
