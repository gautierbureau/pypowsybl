/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.python.sensitivity;

import com.powsybl.commons.PowsyblException;
import com.powsybl.python.commons.PyPowsyblApiHeader;
import org.graalvm.nativeimage.UnmanagedMemory;
import org.graalvm.nativeimage.c.struct.SizeOf;
import org.graalvm.nativeimage.c.type.CDoublePointer;

import java.util.List;
import java.util.Map;

/**
 * Result of a reverse-mode (adjoint / VJP) sensitivity run: {@code θ̄ = dL/dvariable} keyed by variable id,
 * exposed per declared factor matrix as a {@code rowCount × 1} column indexed by that matrix's variable
 * (row) ids — the reverse-mode dual of {@link SensitivityAnalysisResultContext#createSensitivityMatrix}.
 */
public class SensitivityAnalysisAdjointResultContext {

    private final Map<String, SensitivityAnalysisContext.MatrixInfo> factorsMatrix;

    private final Map<String, Double> gradientByVariableId;

    public SensitivityAnalysisAdjointResultContext(Map<String, SensitivityAnalysisContext.MatrixInfo> factorsMatrix,
                                                   Map<String, Double> gradientByVariableId) {
        this.factorsMatrix = factorsMatrix;
        this.gradientByVariableId = gradientByVariableId;
    }

    /** dL/dvariable keyed by variable id (used by tests; the C entry point returns it as {@link #createGradient}). */
    Map<String, Double> getGradientByVariableId() {
        return gradientByVariableId;
    }

    public PyPowsyblApiHeader.MatrixPointer createGradient(String vectorId) {
        SensitivityAnalysisContext.MatrixInfo m = factorsMatrix.get(vectorId);
        if (m == null) {
            throw new PowsyblException("Factor matrix '" + vectorId + "' not found");
        }
        List<String> rows = m.getRowIds();
        double[] values = new double[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            // NaN, never 0.0, for a variable ABSENT from θ̄. Absent and zero are different answers, and
            // only one of them is an answer: OpenLoadFlow keys θ̄ off the factor groups, so a variable
            // whose element did not resolve in the solved network (a disconnected shunt, a line open at
            // either end, a pilot bus outside the solved component) has NO entry, whereas 0.0 is the
            // honest "this lever cannot move the monitored functions". Substituting 0.0 here made the
            // two indistinguishable, so a caller that trusts the value silently stops using those levers
            // and nothing downstream can tell why. NaN propagates and reads as NA in pandas, leaving the
            // caller to fill, warn or fail knowingly.
            values[i] = gradientByVariableId.getOrDefault(rows.get(i), Double.NaN);
        }
        return doubleArrToMatrix(values, rows.size(), 1);
    }

    private static PyPowsyblApiHeader.MatrixPointer doubleArrToMatrix(double[] values, int rowCount, int colCount) {
        CDoublePointer valuePtr = UnmanagedMemory.calloc(rowCount * colCount * SizeOf.get(CDoublePointer.class));
        for (int i = 0; i < colCount * rowCount; i++) {
            valuePtr.addressOf(i).write(values[i]);
        }
        PyPowsyblApiHeader.MatrixPointer matrixPtr = UnmanagedMemory.calloc(SizeOf.get(PyPowsyblApiHeader.MatrixPointer.class));
        matrixPtr.setRowCount(rowCount);
        matrixPtr.setColumnCount(colCount);
        matrixPtr.setValues(valuePtr);
        return matrixPtr;
    }
}
