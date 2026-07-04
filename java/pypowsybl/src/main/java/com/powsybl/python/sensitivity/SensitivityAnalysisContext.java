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

    private final Map<String, MatrixInfo> factorsMatrix = new HashMap<>();

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

    /**
     * Streams factors out of the matrix descriptions ({@link MatrixInfo}) without materializing a
     * {@code List<SensitivityFactor>}: for each matrix, resolves the variable type per row (once),
     * then emits one factor per (column, contingency context) pair. Kept as its own class so the
     * factor-generation logic is readable in isolation and so the per-row resolution is not lost
     * inside a lambda body.
     */
    static final class MatrixFactorReader implements SensitivityFactorReader {

        private final Network network;

        private final List<MatrixInfo> matrices;

        private final Map<String, SensitivityVariableSet> variableSetsById;

        MatrixFactorReader(Network network, List<MatrixInfo> matrices, Map<String, SensitivityVariableSet> variableSetsById) {
            this.network = network;
            this.matrices = matrices;
            this.variableSetsById = variableSetsById;
        }

        @Override
        public void read(Handler handler) {
            for (MatrixInfo matrix : matrices) {
                readMatrix(matrix, handler);
            }
        }

        private void readMatrix(MatrixInfo matrix, Handler handler) {
            List<String> columns = matrix.getColumnIds();
            List<String> rows = matrix.getRowIds();
            List<ContingencyContext> contingencyContexts = buildContingencyContexts(matrix);
            SensitivityFunctionType functionType = matrix.getFunctionType();
            SensitivityVariableType matrixVariableType = matrix.getVariableType();

            // Resolve variableType (and variableSet flag) per row, not per (row, column, context).
            // network.getIdentifiable / variableSetsById lookups depend only on variableId.
            for (String variableId : rows) {
                SensitivityVariableType variableType = matrixVariableType;
                boolean variableSet = false;
                if (variableType == null) {
                    variableType = resolveVariableTypeFromNetwork(network, variableId);
                    if (variableType == null) {
                        if (variableSetsById.containsKey(variableId)) {
                            variableSet = true;
                            variableType = SensitivityVariableType.INJECTION_ACTIVE_POWER;
                        } else {
                            throw new PowsyblException("Variable '" + variableId + "' not found");
                        }
                    }
                }
                for (String functionId : columns) {
                    for (ContingencyContext cCtx : contingencyContexts) {
                        handler.onFactor(functionType, functionId, variableType, variableId, variableSet, cCtx);
                    }
                }
            }
        }

        private static List<ContingencyContext> buildContingencyContexts(MatrixInfo matrix) {
            ContingencyContextType type = matrix.getContingencyContextType();
            if (type == ContingencyContextType.ALL) {
                return List.of(ContingencyContext.all());
            }
            if (type == ContingencyContextType.NONE) {
                return List.of(ContingencyContext.none());
            }
            List<String> contingencyIds = matrix.getContingencyIds();
            List<ContingencyContext> contexts = new ArrayList<>(contingencyIds.size());
            for (String c : contingencyIds) {
                contexts.add(ContingencyContext.specificContingency(c));
            }
            return contexts;
        }

        private static SensitivityVariableType resolveVariableTypeFromNetwork(Network network, String variableId) {
            Identifiable<?> identifiable = network.getIdentifiable(variableId);
            if (identifiable instanceof Injection<?>) {
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
    }

    SensitivityAnalysisResultContext run(Network network, SensitivityAnalysisParameters sensitivityAnalysisParameters, String provider, ReportNode reportNode) {
        List<Contingency> contingencies = createContingencies(network);

        List<MatrixInfo> matrices = prepareMatrices();

        Map<String, SensitivityVariableSet> variableSetsById = variableSets.stream().collect(Collectors.toMap(SensitivityVariableSet::getId, e -> e));

        SensitivityFactorReader factorReader = new MatrixFactorReader(network, matrices, variableSetsById);

        // Single pass over the matrices: capture total factor / column counts (for allocating the
        // result arrays), plus a sorted (offset, MatrixInfo) table indexed by factorContext for
        // the result writer's hot-path lookup. prepareMatrices() produces matrices in strictly
        // increasing offset order, so we can rely on natural ordering here.
        final int matrixCount = matrices.size();
        final MatrixInfo[] matricesArr = new MatrixInfo[matrixCount];
        final int[] offsetTable = new int[matrixCount];
        int baseCaseValueSize = 0;
        int totalColumnsCount = 0;
        for (int i = 0; i < matrixCount; i++) {
            MatrixInfo m = matrices.get(i);
            matricesArr[i] = m;
            offsetTable[i] = m.getOffsetData();
            baseCaseValueSize += m.getRowCount() * m.getColumnCount();
            totalColumnsCount += m.getColumnCount();
        }

        double[] baseCaseValues = new double[baseCaseValueSize];
        double[][] valuesByContingencyIndex = new double[contingencies.size()][baseCaseValueSize];
        double[] baseCaseReferences = new double[totalColumnsCount];
        double[][] referencesByContingencyIndex = new double[contingencies.size()][totalColumnsCount];

        SensitivityResultWriter valueWriter = new SensitivityResultWriter() {
            @Override
            public void writeSensitivityValue(int factorContext, int contingencyIndex, int strategyIndex,
                                              double value, double functionReference) {
                // Locate the owning matrix without boxing factorContext to Integer or allocating a
                // Map.Entry: the single-matrix case (common) is a direct read; otherwise binarySearch
                // on the sorted offset table. This method is called F * (K + 1) times, so avoiding
                // per-call allocations matters on large workloads.
                MatrixInfo m;
                if (matrixCount == 1) {
                    m = matricesArr[0];
                } else {
                    int idx = Arrays.binarySearch(offsetTable, factorContext);
                    if (idx < 0) {
                        // binarySearch returns -(insertion point) - 1; floor position is that minus one.
                        idx = -idx - 2;
                    }
                    m = matricesArr[idx];
                }

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

}
