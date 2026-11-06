/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.python.gpu;

import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.LoadFlowProvider;
import com.powsybl.loadflow.LoadFlowResult;
import com.powsybl.loadflow.LoadFlowRunParameters;
import com.powsybl.openloadflow.ac.equations.vector.gpu.GpuAcNewtonSolver;
import com.powsybl.python.loadflow.LoadFlowCUtils;
import com.powsybl.python.security.SecurityAnalysisCUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The GPU providers of powsybl-open-loadflow-gpu are found the way pypowsybl looks providers up.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class GpuProvidersTest {

    private static final String GPU_PROVIDER = "OpenLoadFlowGpu";

    @Test
    void providersAreFound() {
        assertEquals(GPU_PROVIDER, LoadFlowCUtils.getLoadFlowProvider(GPU_PROVIDER).getName());
        assertEquals(GPU_PROVIDER, SecurityAnalysisCUtils.getSecurityAnalysisProvider(GPU_PROVIDER).getName());
    }

    @Test
    void loadFlowRunsOnDevice() {
        assumeTrue(GpuAcNewtonSolver.isAvailable(), "no GPU native library (libolfgpu) or no GPU");
        Network network = EurostagTutorialExample1Factory.create();
        LoadFlowProvider provider = LoadFlowCUtils.getLoadFlowProvider(GPU_PROVIDER);
        LoadFlowResult result = provider.run(network, network.getVariantManager().getWorkingVariantId(),
                new LoadFlowRunParameters().setParameters(new LoadFlowParameters())).join();
        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getComponentResults().get(0).getStatus());
    }
}
