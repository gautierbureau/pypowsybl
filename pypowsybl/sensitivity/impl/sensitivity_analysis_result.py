# Copyright (c) 2023, RTE (http://www.rte-france.com)
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
# SPDX-License-Identifier: MPL-2.0
#
from typing import Dict, List, Optional, Any
import numpy as np
import pandas as pd
from pypowsybl import _pypowsybl
from pypowsybl.utils import DataframeBackendMixin

DEFAULT_REFERENCE_COLUMN_ID = 'reference_values'

DEFAULT_MATRIX_ID = 'default'
TO_REMOVE = 'TO_REMOVE'


class SensitivityAnalysisResult(DataframeBackendMixin):
    """
    Represents the result of a sensitivity analysis.

    The result contains computed values (so called "reference" values) and sensitivity values
    of requested factors, on the base case and on post contingency states.

    By default the matrix accessors return :class:`pandas.DataFrame`. Set
    :attr:`dataframe_backend` to ``'polars'`` to get :class:`polars.DataFrame` instead
    (the row labels, which pandas exposes as the index, become a leading ``id`` column).
    """

    def __init__(self,
                 result_context_ptr: _pypowsybl.JavaHandle,
                 functions_ids: Dict[str, List[str]],
                 function_data_frame_index: Dict[str, List[str]]):
        self._handle = result_context_ptr
        self.result_context_ptr = result_context_ptr
        self.functions_ids = functions_ids
        self.function_data_frame_index = function_data_frame_index

    @staticmethod
    def clean_contingency_id(contingency_id: Optional[str]) -> str:
        return '' if contingency_id is None else contingency_id

    def _matrix_to_backend(self, df: Optional[pd.DataFrame]) -> Any:
        """convert a matrix dataframe to the selected backend (row labels -> leading 'id' column)"""
        if df is None or self._dataframe_backend == 'pandas':
            return df
        return self._convert_frame(df.rename_axis('id'))

    def process_ptdf(self, df: pd.DataFrame, matrix_id: str) -> pd.DataFrame:
        # substract second power transfer zone to first one
        i = 0
        while i < len(self.function_data_frame_index[matrix_id]):
            if self.function_data_frame_index[matrix_id][i] == TO_REMOVE:
                df.iloc[i - 1] = df.iloc[i - 1] - df.iloc[i]
            i += 1
        # remove rows corresponding to power transfer second zone
        return df.drop([TO_REMOVE], errors='ignore')

    def get_sensitivity_matrix(self, matrix_id: str = DEFAULT_MATRIX_ID, contingency_id:  Optional[str] = None) -> Optional[
        pd.DataFrame]:
        """
        Get the matrix of sensitivity values on the base case or on post contingency state.

        If contingency_id is None, returns the base case matrix.

        Args:
            matrix_id:      ID of the matrix
            contingency_id: ID of the contingency
        Returns:
            the matrix of sensitivity values
        """
        matrix = _pypowsybl.get_sensitivity_matrix(self.result_context_ptr, matrix_id, self.clean_contingency_id(contingency_id))
        if matrix is None:
            return None

        data = np.array(matrix, copy=False)

        df = pd.DataFrame(data=data, columns=self.functions_ids[matrix_id],
                          index=self.function_data_frame_index[matrix_id])

        return self._matrix_to_backend(self.process_ptdf(df, matrix_id)) # process_ptdf only used for PTDF

    def get_reference_matrix(self, matrix_id: str = DEFAULT_MATRIX_ID, contingency_id:  Optional[str] = None, reference_column_id: str = DEFAULT_REFERENCE_COLUMN_ID) -> Optional[pd.DataFrame]:
        """
        The reference values on the base case or on post contingency state.

        Args:
            matrix_id:      ID of the matrix
            contingency_id: ID of the contingency
        Returns:
            the reference values
        """
        matrix = _pypowsybl.get_reference_matrix(self.result_context_ptr, matrix_id, self.clean_contingency_id(contingency_id))
        if matrix is None:
            return None

        data = np.array(matrix, copy=False)

        return self._matrix_to_backend(
            pd.DataFrame(data=data, columns=self.functions_ids[matrix_id], index=[reference_column_id]))
