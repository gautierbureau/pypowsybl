/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.python.dynamic;

import com.powsybl.commons.report.ReportNode;
import com.powsybl.dynawo.parameters.Parameter;
import com.powsybl.dynawo.parameters.ParameterType;
import com.powsybl.dynawo.parameters.ParametersSet;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks that the sets a mapping derived for models given after their parameters were written reach
 * the report node, each with the parameters it added listed beneath it.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class ParameterCompletionReportsTest {

    private static ReportNode root() {
        // an existing key for the root, so strict mode is left on and the templates under test have
        // to resolve out of the bundle for the assertions to run at all
        return ReportNode.newRootReportNode()
                .withAllResourceBundlesFromClasspath()
                .withMessageTemplate("pypowsybl.dynasim.pypowsyblDynamicModels")
                .build();
    }

    @Test
    void shouldReportEachCompletionWithItsAddedParametersBeneathIt() {
        ParameterCompletion completion = new ParameterCompletion("B1-G", "GeneratorSynchronousFourWindingsTfoAux",
                new ParametersSet("DynaWaltz_B1-G"),
                new ParametersSet("DynaWaltz_B1-G_GeneratorSynchronousFourWindingsTfoAux"),
                List.of(new Parameter("transformer_XPu", ParameterType.DOUBLE, "0.1"),
                        new Parameter("transformer_RPu", ParameterType.DOUBLE, "0.0")));

        ReportNode root = root();
        ParameterCompletionReports.report(root, List.of(completion));

        assertThat(root.getChildren()).singleElement().satisfies(completions -> {
            assertThat(completions.getMessageKey()).isEqualTo("pypowsybl.dynasim.parameterCompletions");
            assertThat(completions.getChildren()).singleElement().satisfies(reported -> {
                assertThat(reported.getMessageKey()).isEqualTo("pypowsybl.dynasim.parameterCompletion");
                assertThat(reported.getValue("equipment")).get().extracting(v -> v.getValue()).isEqualTo("B1-G");
                assertThat(reported.getValue("model")).get().extracting(v -> v.getValue())
                        .isEqualTo("GeneratorSynchronousFourWindingsTfoAux");
                assertThat(reported.getValue("source")).get().extracting(v -> v.getValue()).isEqualTo("DynaWaltz_B1-G");
                assertThat(reported.getValue("completed")).get().extracting(v -> v.getValue())
                        .isEqualTo("DynaWaltz_B1-G_GeneratorSynchronousFourWindingsTfoAux");
                assertThat(reported.getValue("count")).get().extracting(v -> v.getValue()).isEqualTo(2);
                // each added parameter is a child, named with its value
                assertThat(reported.getChildren()).satisfiesExactly(
                        first -> {
                            assertThat(first.getMessageKey()).isEqualTo("pypowsybl.dynasim.parameterAdded");
                            assertThat(first.getValue("parameter")).get().extracting(v -> v.getValue()).isEqualTo("transformer_XPu");
                            assertThat(first.getValue("value")).get().extracting(v -> v.getValue()).isEqualTo("0.1");
                        },
                        second -> {
                            assertThat(second.getValue("parameter")).get().extracting(v -> v.getValue()).isEqualTo("transformer_RPu");
                            assertThat(second.getValue("value")).get().extracting(v -> v.getValue()).isEqualTo("0.0");
                        });
            });
        });
    }

    @Test
    void shouldReportNothingWhereNothingWasCompleted() {
        ReportNode root = root();
        ParameterCompletionReports.report(root, List.of());
        assertThat(root.getChildren()).isEmpty();
    }
}
