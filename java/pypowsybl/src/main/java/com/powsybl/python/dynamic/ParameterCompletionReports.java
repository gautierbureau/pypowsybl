/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.python.dynamic;

import com.powsybl.commons.report.ReportNode;
import com.powsybl.commons.report.TypedValue;
import com.powsybl.dynawo.parameters.Parameter;

import java.util.List;

/**
 * Says on the report node a run keeps what a mapping had to add to value a model given to an
 * equipment after its parameters were written.
 * <p>
 * The set the study holds is left as it is and a second one derived from it; that used to reach a
 * logger and the completions dataframe only, so a run stood on values chosen for it with nothing
 * said where a study is read from afterwards. These say it there too, which is what surfaces in
 * gridsuite and in the report a study keeps, the same way the resolver's own choices are.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public final class ParameterCompletionReports {

    private static final String EQUIPMENT = "equipment";
    private static final String MODEL = "model";
    private static final String SOURCE = "source";
    private static final String COMPLETED = "completed";
    private static final String COUNT = "count";
    private static final String PARAMETER = "parameter";
    private static final String VALUE = "value";

    private ParameterCompletionReports() {
    }

    /**
     * Reports every completion under a node of their own, each with the parameters it added listed
     * beneath it. Nothing is reported where nothing was completed, so a run whose sets valued their
     * models outright says nothing here.
     */
    public static void report(ReportNode reportNode, List<ParameterCompletion> completions) {
        if (completions.isEmpty()) {
            return;
        }
        ReportNode completionsNode = reportNode.newReportNode()
                .withMessageTemplate("pypowsybl.dynasim.parameterCompletions")
                .add();
        for (ParameterCompletion completion : completions) {
            ReportNode completionNode = completionsNode.newReportNode()
                    .withMessageTemplate("pypowsybl.dynasim.parameterCompletion")
                    .withTypedValue(EQUIPMENT, completion.equipment(), TypedValue.ID)
                    .withUntypedValue(MODEL, completion.model())
                    .withUntypedValue(SOURCE, completion.sourceId())
                    .withUntypedValue(COMPLETED, completion.completedId())
                    .withUntypedValue(COUNT, completion.added().size())
                    .add();
            for (Parameter parameter : completion.added()) {
                completionNode.newReportNode()
                        .withMessageTemplate("pypowsybl.dynasim.parameterAdded")
                        .withUntypedValue(PARAMETER, parameter.name())
                        .withUntypedValue(VALUE, parameter.value())
                        .add();
            }
        }
    }
}
