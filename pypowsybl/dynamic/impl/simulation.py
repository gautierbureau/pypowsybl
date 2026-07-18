# Copyright (c) 2023, RTE (http://www.rte-france.com)
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
# SPDX-License-Identifier: MPL-2.0
#
from typing import List
from typing import Optional
from pandas import DataFrame
from pypowsybl.network import Network
from pypowsybl import _pypowsybl as _pp
from pypowsybl.utils import create_data_frame_from_series_array
from pypowsybl.report import ReportNode
from .event_mapping import EventMapping
from .model_config import _to_c_dataframe
from .model_mapping import ModelMapping
from .simulation_result import SimulationResult
from .output_variable_mapping import OutputVariableMapping
from .parameters import Parameters


class Simulation:  # pylint: disable=too-few-public-methods
    def __init__(self) -> None:
        self._handle = _pp.create_dynamic_simulation_context()

    def run(self,
            network: Network,
            model_mapping: ModelMapping,
            event_mapping: Optional[EventMapping] = None,
            timeseries_mapping: Optional[OutputVariableMapping] = None,
            parameters: Optional[Parameters] = None,
            report_node: Optional[ReportNode] = None
            ) -> SimulationResult:
        """Run the dynawo simulation"""
        # Register additional dynamic model definitions (if any) on the simulation context
        # before running; they are applied to the Dynawo parameters natively.
        if parameters is not None and parameters.additional_models:
            _pp.add_additional_models(self._handle, _to_c_dataframe(parameters.additional_models))
        return SimulationResult(
                _pp.run_dynamic_simulation(
                    self._handle,
                    network._handle, # pylint: disable=protected-access
                    model_mapping._handle, # pylint: disable=protected-access
                    None if event_mapping is None else event_mapping._handle, # pylint: disable=protected-access
                    None if timeseries_mapping is None else timeseries_mapping._handle, # pylint: disable=protected-access
                    parameters._to_c_parameters() if parameters is not None else _pp.DynamicSimulationParameters(), # pylint: disable=protected-access
                    None if report_node is None else report_node._report_node) # pylint: disable=protected-access
        )


    @staticmethod
    def get_provider_parameters_names() -> List[str]:
        """
        Get list of parameters for Dynawo provider.

        Returns:
            the list of Dynawo's parameters
        """
        return _pp.get_dynamic_simulation_provider_parameters_names()

    @staticmethod
    def get_provider_parameters() -> DataFrame:
        """
        Supported dynamic simulation specific parameters for a given provider.

        Returns:
            dynamic simulation parameters dataframe
        """
        series_array = _pp.create_dynamic_simulation_provider_parameters_series_array()
        return create_data_frame_from_series_array(series_array)
