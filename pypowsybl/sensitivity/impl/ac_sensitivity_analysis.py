# Copyright (c) 2023, RTE (http://www.rte-france.com)
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
# SPDX-License-Identifier: MPL-2.0
#
import warnings
from typing import Any, Dict, List, Union, Optional
from pypowsybl import _pypowsybl
from pypowsybl.network import Network
from pypowsybl.report import ReportNode
from pypowsybl.loadflow import Parameters as LfParameters
from pypowsybl._pypowsybl import ContingencyContextType, SensitivityFunctionType, SensitivityVariableType
from .ac_sensitivity_analysis_result import AcSensitivityAnalysisResult
from .ac_sensitivity_analysis_adjoint_result import AcSensitivityAnalysisAdjointResult
from .sensitivity_analysis_result import DEFAULT_MATRIX_ID
from .sensitivity import SensitivityAnalysis
from .parameters import Parameters


class AcSensitivityAnalysis(SensitivityAnalysis):
    """ Represents an AC sensitivity analysis."""

    def __init__(self, handle: _pypowsybl.JavaHandle):
        SensitivityAnalysis.__init__(self, handle)
        self.bus_voltage_ids: List[str] = []
        self.target_voltage_ids: List[str] = []

    def set_bus_voltage_factor_matrix(self, bus_ids: List[str], target_voltage_ids: List[str]) -> None:
        """
        .. deprecated:: 1.1.0
          Use :meth:`add_bus_voltage_factor_matrix` instead.

        Defines buses voltage sensitivities to be computed.

        Args:
            bus_ids:            IDs of buses for which voltage sensitivities should be computed
            target_voltage_ids: IDs of regulating equipments to which we should compute sensitivities
       """
        warnings.warn("set_bus_voltage_factor_matrix is deprecated, use add_bus_voltage_factor_matrix instead",
                      DeprecationWarning)
        self.add_bus_voltage_factor_matrix(bus_ids, target_voltage_ids)

    def add_bus_voltage_factor_matrix(self, bus_ids: List[str], target_voltage_ids: List[str], matrix_id: str = DEFAULT_MATRIX_ID) -> None:
        """
        Defines buses voltage sensitivities to be computed.

        Args:
            bus_ids:            IDs of buses for which voltage sensitivities should be computed
            target_voltage_ids: IDs of regulating equipments to which we should compute sensitivities
            matrix_id:          The matrix unique identifier, to be used to retrieve the sensibility value
        """
        self.add_factor_matrix(bus_ids, target_voltage_ids, [], ContingencyContextType.ALL,
                               SensitivityFunctionType.BUS_VOLTAGE, SensitivityVariableType.BUS_TARGET_VOLTAGE, matrix_id)
        self.bus_voltage_ids = bus_ids
        self.target_voltage_ids = target_voltage_ids

    def add_shunt_susceptance_factor_matrix(self, functions_ids: List[str], shunt_ids: List[str],
                                            sensitivity_function_type: SensitivityFunctionType,
                                            matrix_id: str = DEFAULT_MATRIX_ID) -> None:
        """
        Declare sensitivities of monitored functions to shunt susceptances — the TVC shunt lever.

        Args:
            functions_ids:             ids of the monitored elements (buses for BUS_VOLTAGE, branches for
                                       BRANCH_CURRENT / BRANCH_ACTIVE_POWER, ...)
            shunt_ids:                 ids of the controllable shunt compensators (the levers)
            sensitivity_function_type: type of the monitored function (e.g. BUS_VOLTAGE, BRANCH_CURRENT_1)
            matrix_id:                 the factor matrix unique identifier
        """
        self.add_factor_matrix(functions_ids, shunt_ids, [], ContingencyContextType.ALL,
                               sensitivity_function_type, SensitivityVariableType.SHUNT_COMPENSATOR_SUSCEPTANCE, matrix_id)

    def add_branch_admittance_factor_matrix(self, functions_ids: List[str], branch_ids: List[str],
                                            sensitivity_function_type: SensitivityFunctionType,
                                            matrix_id: str = DEFAULT_MATRIX_ID) -> None:
        """
        Declare sensitivities of monitored functions to branch admittances — the TVC line lever.

        Args:
            functions_ids:             ids of the monitored elements
            branch_ids:                ids of the controllable branches/lines (the levers)
            sensitivity_function_type: type of the monitored function (e.g. BUS_VOLTAGE, BRANCH_CURRENT_1)
            matrix_id:                 the factor matrix unique identifier
        """
        self.add_factor_matrix(functions_ids, branch_ids, [], ContingencyContextType.ALL,
                               sensitivity_function_type, SensitivityVariableType.BRANCH_ADMITTANCE, matrix_id)

    def add_svc_pilot_factor_matrix(self, functions_ids: List[str], zone_ids: List[str],
                                    sensitivity_function_type: SensitivityFunctionType,
                                    matrix_id: str = DEFAULT_MATRIX_ID) -> None:
        """
        Declare sensitivities of monitored functions to secondary-voltage-control pilot-point target
        voltages — the TVC RST lever. The variable ids are the SVC zone names (the network must carry the
        SecondaryVoltageControl extension and the load flow must run with secondaryVoltageControl on).

        Args:
            functions_ids:             ids of the monitored elements
            zone_ids:                  names of the SVC zones (the levers)
            sensitivity_function_type: type of the monitored function (e.g. BUS_VOLTAGE, BRANCH_CURRENT_1)
            matrix_id:                 the factor matrix unique identifier
        """
        self.add_factor_matrix(functions_ids, zone_ids, [], ContingencyContextType.ALL,
                               sensitivity_function_type, SensitivityVariableType.SVC_PILOT_POINT_TARGET_VOLTAGE, matrix_id)

    def run(self, network: Network, parameters: Optional[Union[Parameters, LfParameters]] = None,
            provider: str = '', reporter: Optional[ReportNode] = None, report_node: Optional[ReportNode] = None) -> AcSensitivityAnalysisResult:
        """
        Runs the sensitivity analysis.

        Args:
            network:     The network
            parameters:  The sensitivity parameters
            provider:    Name of the sensitivity analysis provider
            reporter:    deprecated, use report_node instead
            report_node: The reporter to be used to create an execution report, default is None (no report)

        Returns:
            a sensitivity analysis result
        """
        if reporter is not None:
            warnings.warn("Use of deprecated attribute reporter. Use report_node instead.", DeprecationWarning)
            report_node = reporter
        sensitivity_parameters = Parameters(load_flow_parameters=parameters) if isinstance(parameters,
                                                                                           LfParameters) else parameters
        p: _pypowsybl.SensitivityAnalysisParameters = sensitivity_parameters._to_c_parameters() if sensitivity_parameters is not None else Parameters()._to_c_parameters()  # pylint: disable=W0212
        p.loadflow_parameters.dc = False
        return AcSensitivityAnalysisResult(
            _pypowsybl.run_sensitivity_analysis(self._handle, network._handle, p, provider, None if report_node is None else report_node._report_node), # pylint: disable=protected-access
            functions_ids=self.functions_ids, function_data_frame_index=self.function_data_frame_index)

    def run_adjoint(self, network: Network, cotangents: Any,
                    parameters: Optional[Union[Parameters, LfParameters]] = None,
                    provider: str = '') -> AcSensitivityAnalysisAdjointResult:
        """
        Runs a reverse-mode (adjoint / VJP) sensitivity analysis.

        Given output cotangents ``dL/dfunction`` over the declared functions, returns ``dL/dvariable`` for
        every declared variable — ``theta_bar = S^T . y_bar`` — in a single transpose solve, without
        materialising the sensitivity matrix. Reuses the OpenLoadFlow load flow retained in the network
        cache, so a load flow with ``network_cache_enabled=True`` must have run on ``network`` first.

        Args:
            network:    The network (with a warm networkCacheEnabled AC load flow)
            cotangents: ``dL/dfunction``; either a flat sequence aligned with all declared functions in
                        declaration order, or a dict ``{vector_id: sequence over that factor matrix's functions}``
                        (``vector_id`` being the ``matrix_id`` used when the factors were declared)
            parameters: The sensitivity parameters
            provider:   Name of the sensitivity analysis provider

        Returns:
            an adjoint sensitivity analysis result (``dL/dvariable`` per factor matrix)
        """
        sensitivity_parameters = Parameters(load_flow_parameters=parameters) if isinstance(parameters,
                                                                                           LfParameters) else parameters
        p: _pypowsybl.SensitivityAnalysisParameters = sensitivity_parameters._to_c_parameters() if sensitivity_parameters is not None else Parameters()._to_c_parameters()  # pylint: disable=W0212
        p.loadflow_parameters.dc = False
        flat_cotangents = self._flatten_cotangents(cotangents)
        return AcSensitivityAnalysisAdjointResult(
            _pypowsybl.run_sensitivity_analysis_adjoint(self._handle, network._handle, flat_cotangents, p, provider),
            function_data_frame_index=self.function_data_frame_index)

    def _flatten_cotangents(self, cotangents: Any) -> List[float]:
        # flatten to a vector aligned with the declared functions across all matrices, in declaration order
        if isinstance(cotangents, dict):
            flat: List[float] = []
            for vector_id, function_ids in self.functions_ids.items():
                values = cotangents.get(vector_id)
                if values is None:
                    flat.extend([0.0] * len(function_ids))
                    continue
                values = list(values)
                if len(values) != len(function_ids):
                    raise ValueError(
                        f"cotangents for factor matrix '{vector_id}' must have length {len(function_ids)}, got {len(values)}")
                flat.extend(float(v) for v in values)
            return flat
        flat = [float(v) for v in cotangents]
        expected = sum(len(f) for f in self.functions_ids.values())
        if len(flat) != expected:
            raise ValueError(f"cotangents must have length {expected} (total declared functions), got {len(flat)}")
        return flat
