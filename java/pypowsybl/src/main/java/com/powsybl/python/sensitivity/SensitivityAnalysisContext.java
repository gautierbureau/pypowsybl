/**
 * Copyright (c) 2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.python.sensitivity;

import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.contingency.Contingency;
import com.powsybl.contingency.ContingencyContext;
import com.powsybl.contingency.ContingencyContextType;
import com.powsybl.iidm.network.*;
import com.powsybl.openloadflow.sensi.AcSensitivityAnalysis;
import com.powsybl.openloadflow.sensi.OpenSensitivityAnalysisProvider;
import com.powsybl.python.commons.CommonObjects;
import com.powsybl.python.contingency.ContingencyContainerImpl;
import com.powsybl.sensitivity.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
class SensitivityAnalysisContext extends ContingencyContainerImpl {

    private List<SensitivityVariableSet> variableSets = Collections.emptyList();

    public static class MatrixInfo {
        private final ContingencyContextType contingencyContextType;

        private final SensitivityFunctionType functionType;

        private final SensitivityVariableType variableType;

        private final List<String> columnIds;

        private final List<String> rowIds;

        private final List<String> contingencyIds;

        private int offsetData;

        private int offsetColumn;

        MatrixInfo(ContingencyContextType context, SensitivityFunctionType functionType, SensitivityVariableType variableType,
                   List<String> columnIds, List<String> rowIds, List<String> contingencyIds) {
            this.contingencyContextType = context;
            this.functionType = functionType;
            this.variableType = variableType;
            this.columnIds = columnIds;
            this.rowIds = rowIds;
            this.contingencyIds = contingencyIds;
        }

        ContingencyContextType getContingencyContextType() {
            return contingencyContextType;
        }

        SensitivityFunctionType getFunctionType() {
            return functionType;
        }

        public SensitivityVariableType getVariableType() {
            return variableType;
        }

        void setOffsetData(int offset) {
            this.offsetData = offset;
        }

        void setOffsetColumn(int offset) {
            this.offsetColumn = offset;
        }

        int getOffsetData() {
            return offsetData;
        }

        int getOffsetColumn() {
            return offsetColumn;
        }

        List<String> getRowIds() {
            return rowIds;
        }

        List<String> getColumnIds() {
            return columnIds;
        }

        List<String> getContingencyIds() {
            return contingencyIds;
        }

        int getRowCount() {
            return rowIds.size();
        }

        int getColumnCount() {
            return columnIds.size();
        }
    }

    // LinkedHashMap: preserve matrix declaration order so the flat column layout (offsetColumn) — hence the
    // adjoint cotangent vector alignment — is deterministic and matches the Python-side declaration order.
    private final Map<String, MatrixInfo> factorsMatrix = new LinkedHashMap<>();

    void addFactorMatrix(String matrixId, List<String> branchesIds, List<String> variablesIds,
                         List<String> contingencies, ContingencyContextType contingencyContextType,
                         SensitivityFunctionType sensitivityFunctionType, SensitivityVariableType sensitivityVariableType) {
        if (factorsMatrix.containsKey(matrixId)) {
            throw new PowsyblException("Matrix '" + matrixId + "' already exists.");
        }
        MatrixInfo info = new MatrixInfo(contingencyContextType, sensitivityFunctionType, sensitivityVariableType, branchesIds, variablesIds, contingencies);
        factorsMatrix.put(matrixId, info);
    }

    public void setVariableSets(List<SensitivityVariableSet> variableSets) {
        this.variableSets = Objects.requireNonNull(variableSets);
    }

    List<MatrixInfo> prepareMatrices() {
        List<MatrixInfo> matrices = new ArrayList<>();
        int offsetData = 0;
        int offsetColumns = 0;

        for (MatrixInfo matrix : factorsMatrix.values()) {
            matrix.setOffsetData(offsetData);
            matrix.setOffsetColumn(offsetColumns);
            matrices.add(matrix);
            offsetData += matrix.getColumnCount() * matrix.getRowCount();
            offsetColumns += matrix.getColumnCount();
        }

        return matrices;
    }

    int getTotalNumberOfMatrixFactors(List<MatrixInfo> matrices) {
        int count = 0;
        for (MatrixInfo matrix : matrices) {
            count += matrix.getColumnCount() * matrix.getRowCount();
        }
        return count;
    }

    int getTotalNumberOfMatrixFactorsColumns(List<MatrixInfo> matrices) {
        int count = 0;
        for (MatrixInfo matrix : matrices) {
            count += matrix.getColumnCount();
        }
        return count;
    }

    // Resolve a variable's SensitivityVariableType for the adjoint factor build, mirroring the per-variable
    // logic the old cross-product loop used inline: the matrix's declared type, else inferred from the
    // network, else a variable set (INJECTION_ACTIVE_POWER). isVariableSetOut[0] receives the variable-set flag.
    private SensitivityVariableType resolveAdjointVariableType(Network network, String variableId, MatrixInfo matrix,
                                                              Map<String, SensitivityVariableSet> variableSetsById,
                                                              boolean[] isVariableSetOut) {
        SensitivityVariableType variableType = matrix.getVariableType();
        isVariableSetOut[0] = false;
        if (variableType == null) {
            variableType = getVariableType(network, variableId);
            if (variableType == null) {
                if (variableSetsById.containsKey(variableId)) {
                    isVariableSetOut[0] = true;
                    variableType = SensitivityVariableType.INJECTION_ACTIVE_POWER;
                } else {
                    throw new PowsyblException("Variable '" + variableId + "' not found");
                }
            }
        }
        return variableType;
    }

    private SensitivityVariableType getVariableType(Network network, String variableId) {
        Identifiable<?> identifiable = network.getIdentifiable(variableId);
        if (identifiable instanceof Injection<?>) {
            if (identifiable instanceof ShuntCompensator) {
                return SensitivityVariableType.SHUNT_COMPENSATOR_SUSCEPTANCE;
            }
            return SensitivityVariableType.INJECTION_ACTIVE_POWER;
        } else if (identifiable instanceof TwoWindingsTransformer) {
            return SensitivityVariableType.TRANSFORMER_PHASE;
        } else if (identifiable instanceof ThreeWindingsTransformer twt) {
            ThreeWindingsTransformer.Leg phaseTapChangerLeg = twt.getLegStream()
                    .filter(PhaseTapChangerHolder::hasPhaseTapChanger)
                    .findFirst()
                    .orElse(null);
            if (phaseTapChangerLeg != null) {
                return switch (phaseTapChangerLeg.getSide()) {
                    case ONE -> SensitivityVariableType.TRANSFORMER_PHASE_1;
                    case TWO -> SensitivityVariableType.TRANSFORMER_PHASE_2;
                    case THREE -> SensitivityVariableType.TRANSFORMER_PHASE_3;
                };
            }
            return null;
        } else if (identifiable instanceof HvdcLine) {
            return SensitivityVariableType.HVDC_LINE_ACTIVE_POWER;
        } else {
            return null;
        }
    }

    SensitivityAnalysisResultContext run(Network network, SensitivityAnalysisParameters sensitivityAnalysisParameters, String provider, ReportNode reportNode) {
        List<Contingency> contingencies = createContingencies(network);

        List<MatrixInfo> matrices = prepareMatrices();

        Map<String, SensitivityVariableSet> variableSetsById = variableSets.stream().collect(Collectors.toMap(SensitivityVariableSet::getId, e -> e));

        SensitivityFactorReader factorReader = handler -> {

            for (MatrixInfo matrix : matrices) {
                List<String> columns = matrix.getColumnIds();
                List<String> rows = matrix.getRowIds();
                List<ContingencyContext> contingencyContexts = new ArrayList<>();
                if (matrix.getContingencyContextType() == ContingencyContextType.ALL) {
                    contingencyContexts.add(ContingencyContext.all());
                } else if (matrix.getContingencyContextType() == ContingencyContextType.NONE) {
                    contingencyContexts.add(ContingencyContext.none());
                } else {
                    for (String c : matrix.getContingencyIds()) {
                        contingencyContexts.add(ContingencyContext.specificContingency(c));
                    }
                }

                for (String variableId : rows) {
                    for (String functionId : columns) {
                        SensitivityVariableType variableType = matrix.getVariableType();
                        boolean variableSet = false;
                        if (variableType == null) {
                            variableType = getVariableType(network, variableId);
                            if (variableType == null) {
                                if (variableSetsById.containsKey(variableId)) {
                                    variableSet = true;
                                    variableType = SensitivityVariableType.INJECTION_ACTIVE_POWER;
                                } else {
                                    throw new PowsyblException("Variable '" + variableId + "' not found");
                                }
                            }
                        }
                        for (ContingencyContext cCtx : contingencyContexts) {
                            SensitivityFunctionType functionType = matrix.getFunctionType();
                            String finalFunctionId = SensitivityFactor.resolveBusId(functionId, matrix.getFunctionType(), network);
                            handler.onFactor(functionType, finalFunctionId, variableType, variableId, variableSet, cCtx);
                        }
                    }
                }
            }
        };

        int baseCaseValueSize = getTotalNumberOfMatrixFactors(matrices);
        double[] baseCaseValues = new double[baseCaseValueSize];
        double[][] valuesByContingencyIndex = new double[contingencies.size()][baseCaseValueSize];

        int totalColumnsCount = getTotalNumberOfMatrixFactorsColumns(matrices);
        double[] baseCaseReferences = new double[totalColumnsCount];
        double[][] referencesByContingencyIndex = new double[contingencies.size()][totalColumnsCount];

        NavigableMap<Integer, MatrixInfo> factorIndexMatrixMap = new TreeMap<>();
        for (MatrixInfo m : matrices) {
            factorIndexMatrixMap.put(m.getOffsetData(), m);
        }

        SensitivityResultWriter valueWriter = new SensitivityResultWriter() {
            @Override
            public void writeSensitivityValue(int factorContext, int contingencyIndex, int strategyIndex,
                                              double value, double functionReference) {
                MatrixInfo m = factorIndexMatrixMap.floorEntry(factorContext).getValue();

                int columnIdx = m.getOffsetColumn() + (factorContext - m.getOffsetData()) % m.getColumnCount();
                if (contingencyIndex != -1) {
                    valuesByContingencyIndex[contingencyIndex][factorContext] = value;
                    referencesByContingencyIndex[contingencyIndex][columnIdx] = functionReference;
                } else {
                    baseCaseValues[factorContext] = value;
                    baseCaseReferences[columnIdx] = functionReference;
                }
            }

            @Override
            public void writeStateStatus(int i, int strategy, SensitivityAnalysisResult.Status status) {
                // nothing to do
            }
        };

        SensitivityAnalysis.find(provider)
                .run(network,
                        network.getVariantManager().getWorkingVariantId(),
                        factorReader,
                        valueWriter,
                        contingencies,
                        variableSets,
                        sensitivityAnalysisParameters,
                        CommonObjects.getComputationManager(),
                        (reportNode == null) ? ReportNode.NO_OP : reportNode);

        Map<String, double[]> valuesByContingencyId = new HashMap<>(contingencies.size());
        Map<String, double[]> referencesByContingencyId = new HashMap<>(contingencies.size());
        for (int contingencyIndex = 0; contingencyIndex < contingencies.size(); contingencyIndex++) {
            Contingency contingency = contingencies.get(contingencyIndex);
            valuesByContingencyId.put(contingency.getId(), valuesByContingencyIndex[contingencyIndex]);
            referencesByContingencyId.put(contingency.getId(), referencesByContingencyIndex[contingencyIndex]);
        }

        return new SensitivityAnalysisResultContext(factorsMatrix,
                                                    baseCaseValues,
                                                    valuesByContingencyId,
                                                    baseCaseReferences,
                                                    referencesByContingencyId);
    }

    /**
     * Reverse-mode (adjoint / VJP) run: given output cotangents {@code ȳ} over the declared functions
     * (the columns of the factor matrices, flat in offsetColumn order), returns {@code θ̄ = Sᵀ·ȳ} keyed by
     * variable id — the reverse-mode dual of {@link #run}. Reuses the OpenLoadFlow-retained
     * {@code networkCacheEnabled} context, so a cached AC load flow must have run on the network first.
     *
     * @param functionCotangents dL/dfunction, one entry per declared function column (offsetColumn layout).
     */
    SensitivityAnalysisAdjointResultContext runAdjoint(Network network, double[] functionCotangents,
                                                       SensitivityAnalysisParameters sensitivityAnalysisParameters,
                                                       String provider) {
        List<MatrixInfo> matrices = prepareMatrices();
        Map<String, SensitivityVariableSet> variableSetsById = variableSets.stream()
                .collect(Collectors.toMap(SensitivityVariableSet::getId, e -> e));

        // Reverse mode needs only the monitored functions + the variables, NOT the functions×variables cross
        // product. Pass them as per-function-type blocks; OpenLoadFlow builds the O(F+V) adjoint factor set
        // from them (AcSensitivityAnalysis.buildAdjointFactors, gated in AcSensitivityAnalysisAdjointTest),
        // turning a ~F·V allocation (millions of factors on a real case) into ~F+V.
        List<AcSensitivityAnalysis.AdjointBlock> blocks = new ArrayList<>();
        Map<String, Double> cotangentByFunctionId = new HashMap<>();

        for (MatrixInfo matrix : matrices) {
            List<String> columns = matrix.getColumnIds();
            List<String> rows = matrix.getRowIds();
            SensitivityFunctionType functionType = matrix.getFunctionType();

            // dL/dfunction per declared function (column), read from the flat column-aligned cotangent vector.
            // Key by (functionType, resolvedFunctionId): a branch monitored by several function types (current
            // and active power) shares one id, so keying by id alone would merge their cotangents. This build
            // stays caller-side — it depends on the matrix column layout (offsetColumn), which OLF ignores.
            for (int j = 0; j < columns.size(); j++) {
                String functionId = SensitivityFactor.resolveBusId(columns.get(j), functionType, network);
                cotangentByFunctionId.merge(AcSensitivityAnalysis.functionCotangentKey(functionType, functionId),
                        functionCotangents[matrix.getOffsetColumn() + j], Double::sum);
            }

            // variables (rows) of this block, each with its resolved type + set-ness
            List<AcSensitivityAnalysis.AdjointVariable> variables = new ArrayList<>();
            for (String variableId : rows) {
                boolean[] vSet = new boolean[1];
                SensitivityVariableType vType = resolveAdjointVariableType(network, variableId, matrix, variableSetsById, vSet);
                variables.add(new AcSensitivityAnalysis.AdjointVariable(variableId, vType, vSet[0]));
            }
            blocks.add(new AcSensitivityAnalysis.AdjointBlock(functionType, columns, variables));
        }

        SensitivityAnalysisProvider p = SensitivityAnalysisCUtils.getSensitivityAnalysisProvider(provider);
        if (!(p instanceof OpenSensitivityAnalysisProvider olfProvider)) {
            throw new PowsyblException("Adjoint (VJP) sensitivity requires the OpenLoadFlow provider, got '" + p.getName() + "'");
        }
        Map<String, Double> gradientByVariableId = olfProvider.runAdjoint(network,
                network.getVariantManager().getWorkingVariantId(), blocks, cotangentByFunctionId, variableSets,
                sensitivityAnalysisParameters);

        return new SensitivityAnalysisAdjointResultContext(factorsMatrix, gradientByVariableId);
    }

}
