/**
 * Copyright (c) 2022, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.python.commons;

import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.config.PlatformConfig;
import com.powsybl.computation.ComputationManager;
import com.powsybl.computation.local.LocalComputationConfig;
import com.powsybl.computation.local.LocalComputationManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.ForkJoinPool;

/**
 * Manages common runtime objects, typically library-wide singletons.
 *
 * @author Sylvain Leclerc {@literal <sylvain.leclerc@rte-france.com>}
 */
public final class CommonObjects {

    private static final String LOCAL_COMPUTATION_MANAGER_MODULE = "local-computation-manager";

    private static ComputationManager computationManager;

    private static ForkJoinPool computationPool;

    private CommonObjects() {
    }

    public static synchronized ComputationManager getComputationManager() {
        if (computationManager == null) {
            computationPool = new ForkJoinPool(getComputationThreadCount());
            try {
                // a dedicated pool instead of the ForkJoinPool.commonPool() that
                // LocalComputationManager uses by default: computations are then neither limited by the
                // common pool parallelism (availableProcessors - 1) nor competing with the parallel
                // streams of the calling application, which share that common pool
                computationManager = new LocalComputationManager(LocalComputationConfig.load(), computationPool);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return computationManager;
    }

    /**
     * Number of threads running computations, from the 'available-core' property of the
     * 'local-computation-manager' module, and all the available cores when it is not configured.
     */
    private static int getComputationThreadCount() {
        int threadCount = PyPowsyblConfiguration.isReadConfig()
                ? PlatformConfig.defaultConfig().getOptionalModuleConfig(LOCAL_COMPUTATION_MANAGER_MODULE)
                    .map(moduleConfig -> moduleConfig.getOptionalIntProperty("available-core").orElse(0))
                    .orElse(0)
                : 0;
        if (threadCount < 0) {
            throw new PowsyblException("Invalid available-core value: " + threadCount);
        }
        return threadCount == 0 ? Runtime.getRuntime().availableProcessors() : threadCount;
    }

    public static synchronized void close() {
        if (computationManager != null) {
            computationManager.close();
            computationManager = null;
        }
        if (computationPool != null) {
            computationPool.shutdown();
            computationPool = null;
        }
    }
}
