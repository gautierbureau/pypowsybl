# Copyright (c) 2026, RTE (http://www.rte-france.com)
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
# SPDX-License-Identifier: MPL-2.0
#
from typing import Dict, List
import numpy as np
import pandas as pd
from pypowsybl import _pypowsybl
from .sensitivity_analysis_result import DEFAULT_MATRIX_ID, TO_REMOVE


class AcSensitivityAnalysisAdjointResult:
    """
    Result of a reverse-mode (adjoint / VJP) AC sensitivity analysis: the gradient ``dL/dvariable`` of a
    scalar loss ``L`` w.r.t. every declared variable, obtained by contracting the sensitivity matrix ``S``
    with the output cotangents ``dL/dfunction`` — ``theta_bar = S^T . y_bar`` — without materialising ``S``.
    """

    def __init__(self, result_context_ptr: _pypowsybl.JavaHandle,
                 function_data_frame_index: Dict[str, List[str]]):
        self._handle = result_context_ptr
        self.result_context_ptr = result_context_ptr
        self.function_data_frame_index = function_data_frame_index

    def get_gradient(self, vector_id: str = DEFAULT_MATRIX_ID) -> pd.Series:
        """
        The adjoint gradient ``dL/dvariable`` for a given factor matrix, as a vector over its variables.

        Args:
            vector_id: ID of the factor matrix whose variables' gradient to read (the ``matrix_id`` used
                       when the factors were declared)
        Returns:
            a Series of ``dL/dvariable`` indexed by the variable ids
        """
        matrix = _pypowsybl.get_gradient(self.result_context_ptr, vector_id)
        data = np.array(matrix, copy=False)  # shape [rowCount, 1]
        df = pd.DataFrame(data=data, columns=['value'], index=self.function_data_frame_index[vector_id])
        # same power-transfer row processing as the forward result (subtract the second transfer zone)
        i = 0
        while i < len(self.function_data_frame_index[vector_id]):
            if self.function_data_frame_index[vector_id][i] == TO_REMOVE:
                df.iloc[i - 1] = df.iloc[i - 1] - df.iloc[i]
            i += 1
        df = df.drop([TO_REMOVE], errors='ignore')
        return df['value'].rename('dL/dvariable')
