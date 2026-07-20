/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.python.dynamic;

import com.powsybl.commons.report.ReportNode;
import com.powsybl.dynamicsimulation.DynamicModel;
import com.powsybl.dynawo.DynawoSimulationParameters;
import com.powsybl.dynawo.models.generators.SynchronousGeneratorBuilder;
import com.powsybl.dynawo.parameters.ParametersSet;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks what becomes of the parameters of an equipment whose model is replaced.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class PythonDynamicModelsSupplierTest {

    private static final String EQUIPMENT = "GEN";
    private static final String MAPPED_SET = "DynaWaltz_GEN";
    private static final String LIB = "GeneratorSynchronousFourWindingsProportionalRegulations";

    @Test
    void shouldKeepTheParametersOfTheDescriptionItReplaces() {
        Network network = EurostagTutorialExample1Factory.create();
        PythonDynamicModelsSupplier supplier = supplierWith(network);

        // replacing the description without saying anything about parameters
        supplier.setDefaultMode(PythonDynamicModelsSupplier.Mode.KEEP_LAST);
        supplier.addModel((n, r) -> model(n, EQUIPMENT));

        assertThat(parameterSetIdOf(supplier.get(network, ReportNode.NO_OP)))
                .as("a set the mapping wrote is kept rather than left behind")
                .isEqualTo(MAPPED_SET);
    }

    @Test
    void shouldUseASetNamedOutrightEvenWhenTheStudyDoesNotHoldIt() {
        Network network = EurostagTutorialExample1Factory.create();
        PythonDynamicModelsSupplier supplier = supplierWith(network);

        supplier.setDefaultMode(PythonDynamicModelsSupplier.Mode.KEEP_LAST);
        supplier.addModel((n, r) -> modelNaming(n, EQUIPMENT, "a_set_of_its_own"));

        assertThat(parameterSetIdOf(supplier.get(network, ReportNode.NO_OP)))
                .as("naming a set is something to be told about, not quietly overruled")
                .isEqualTo("a_set_of_its_own");
    }

    /**
     * A supplier holding a description of the equipment valued by a set of the mapping's own,
     * which is what applying a mapping leaves behind.
     */
    private static PythonDynamicModelsSupplier supplierWith(Network network) {
        PythonDynamicModelsSupplier supplier = new PythonDynamicModelsSupplier();
        DynawoSimulationParameters parameters = new DynawoSimulationParameters();
        parameters.addModelParameters(new ParametersSet(MAPPED_SET));
        supplier.setMappingParameters(parameters);
        supplier.setDefaultMode(PythonDynamicModelsSupplier.Mode.KEEP_FIRST);
        supplier.addModel((n, r) -> modelNaming(n, EQUIPMENT, MAPPED_SET));
        return supplier;
    }

    private static DynamicModel model(Network network, String staticId) {
        // what a description naming no set is given, which is the equipment's own id
        return modelNaming(network, staticId, staticId);
    }

    private static DynamicModel modelNaming(Network network, String staticId, String parameterSetId) {
        return SynchronousGeneratorBuilder.of(network, LIB)
                .staticId(staticId)
                .parameterSetId(parameterSetId)
                .build();
    }

    private static String parameterSetIdOf(List<DynamicModel> models) {
        assertThat(models).hasSize(1);
        return ((com.powsybl.dynawo.models.AbstractBlackBoxModel) models.get(0)).getParameterSetId();
    }
}
