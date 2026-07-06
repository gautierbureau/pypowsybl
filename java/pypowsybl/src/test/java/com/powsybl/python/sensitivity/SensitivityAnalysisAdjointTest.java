/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.python.sensitivity;

import com.powsybl.commons.report.ReportNode;
import com.powsybl.contingency.ContingencyContextType;
import com.powsybl.ieeecdf.converter.IeeeCdfNetworkFactory;
import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.sensitivity.SensitivityAnalysisParameters;
import com.powsybl.sensitivity.SensitivityFunctionType;
import com.powsybl.sensitivity.SensitivityVariableType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JVM-level validation of the pypowsybl adjoint plumbing ({@link SensitivityAnalysisContext#runAdjoint}):
 * that it builds the factors + cotangent map from its factor matrices, dispatches to the OpenLoadFlow
 * {@code runAdjoint}, and maps the result back correctly. Self-consistent check: with a unit cotangent on a
 * single monitored function, {@code θ̄ = Sᵀ·e = S}, so the adjoint gradient must equal the (pypowsybl)
 * forward sensitivity computed from the same declared factor matrix. Runs entirely in the JVM — it does not
 * touch the native (createGradient/UnmanagedMemory) path.
 */
class SensitivityAnalysisAdjointTest {

    @Test
    void runAdjointReproducesTheForwardSensitivityOnIeee14() {
        Network network = IeeeCdfNetworkFactory.create14();
        LoadFlowParameters lfParams = new LoadFlowParameters().setDc(false).setDistributedSlack(false);
        OpenLoadFlowParameters.create(lfParams).setNetworkCacheEnabled(true);
        SensitivityAnalysisParameters parameters = new SensitivityAnalysisParameters();
        parameters.setLoadFlowParameters(lfParams);

        // scaling-free dP_branch1/dP_injection factors (function base == variable base) on IEEE-14
        String branch = "L1-2-1";
        List<String> gens = List.of("B2-G", "B3-G", "B6-G");
        SensitivityAnalysisContext ctx = new SensitivityAnalysisContext();
        ctx.addFactorMatrix("m", List.of(branch), gens, List.of(), ContingencyContextType.NONE,
                SensitivityFunctionType.BRANCH_ACTIVE_POWER_1, SensitivityVariableType.INJECTION_ACTIVE_POWER);

        // warm the OpenLoadFlow network cache that runAdjoint reuses
        assertTrue(LoadFlow.find("OpenLoadFlow").run(network, lfParams).isFullyConverged());

        // reverse mode: unit cotangent on the single monitored branch flow ⇒ θ̄ = dP_branch1/dP_gen
        Map<String, Double> thetaBar = ctx.runAdjoint(network, new double[] {1.0}, parameters, "OpenLoadFlow")
                .getGradientByVariableId();

        // forward mode through the same context: S row is baseCaseValues (rows = gens, single column)
        double[] forwardS = ctx.run(network, parameters, "OpenLoadFlow", ReportNode.NO_OP).getBaseCaseValues();

        double maxAbs = 0;
        for (int i = 0; i < gens.size(); i++) {
            double s = forwardS[i];
            double theta = thetaBar.get(gens.get(i));
            assertEquals(s, theta, 1e-4 * (Math.abs(s) + 1e-3), "runAdjoint vs forward S for " + gens.get(i));
            maxAbs = Math.max(maxAbs, Math.abs(theta));
        }
        assertTrue(maxAbs > 0.1, "gradient must be non-trivial, got max |θ̄| = " + maxAbs);
    }
}
