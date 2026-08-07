/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.python.dynamic;

import com.powsybl.dynawo.parameters.Parameter;
import com.powsybl.dynawo.parameters.ParametersSet;

import java.util.List;

/**
 * What had to be added for a model given to an equipment after its parameters were written.
 * <p>
 * A completion is kept rather than applied: the set the study holds is left as it is and a second
 * one is derived from it, so that asking what a mapping made of a network changes nothing, and so
 * that what was added can be read before a simulation is run.
 *
 * @param equipment  the equipment given another model
 * @param model      the model it was given
 * @param source     the set its parameters were written in, left untouched
 * @param completed  the set derived from it, valuing the model
 * @param added      the parameters the source did not hold, with the values chosen for them
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public record ParameterCompletion(String equipment, String model, ParametersSet source,
                                  ParametersSet completed, List<Parameter> added) {

    public String sourceId() {
        return source.getId();
    }

    public String completedId() {
        return completed.getId();
    }
}
