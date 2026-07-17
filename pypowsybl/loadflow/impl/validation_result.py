# Copyright (c) 2023, RTE (http://www.rte-france.com)
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
# SPDX-License-Identifier: MPL-2.0
#
from typing import Optional, Any
import pandas as pd
from pypowsybl.utils import DataframeBackendMixin

OptionalDf = Optional[pd.DataFrame]


class ValidationResult(DataframeBackendMixin):
    """
    The result of a loadflow validation.

    By default the dataframe properties return :class:`pandas.DataFrame`. Set
    :attr:`dataframe_backend` to ``'polars'`` to get :class:`polars.DataFrame` instead.
    """

    def __init__(self, branch_flows: OptionalDf, buses: OptionalDf, generators: OptionalDf, svcs: OptionalDf,
                 shunts: OptionalDf, twts: OptionalDf, t3wts: OptionalDf):
        self._branch_flows = branch_flows
        self._buses = buses
        self._generators = generators
        self._svcs = svcs
        self._shunts = shunts
        self._twts = twts
        self._t3wts = t3wts
        self._valid = self._is_valid_or_unchecked(self._branch_flows) and self._is_valid_or_unchecked(self._buses) \
                      and self._is_valid_or_unchecked(self._generators) and self._is_valid_or_unchecked(self._svcs) \
                      and self._is_valid_or_unchecked(self._shunts) and self._is_valid_or_unchecked(self._twts) \
                      and self._is_valid_or_unchecked(self._t3wts)

    @staticmethod
    def _is_valid_or_unchecked(df: OptionalDf) -> bool:
        return df is None or df['validated'].all()

    @property
    def branch_flows(self) -> Any:
        """
        Validation results for branch flows.
        """
        return self._convert_frame(self._branch_flows)

    @property
    def buses(self) -> Any:
        """
        Validation results for buses.
        """
        return self._convert_frame(self._buses)

    @property
    def generators(self) -> Any:
        """
        Validation results for generators.
        """
        return self._convert_frame(self._generators)

    @property
    def svcs(self) -> Any:
        """
        Validation results for SVCs.
        """
        return self._convert_frame(self._svcs)

    @property
    def shunts(self) -> Any:
        """
        Validation results for shunts.
        """
        return self._convert_frame(self._shunts)

    @property
    def twts(self) -> Any:
        """
        Validation results for two winding transformers.
        """
        return self._convert_frame(self._twts)

    @property
    def t3wts(self) -> Any:
        """
        Validation results for three winding transformers.
        """
        return self._convert_frame(self._t3wts)

    @property
    def valid(self) -> bool:
        """
        True if all checked data is valid.
        """
        return self._valid
