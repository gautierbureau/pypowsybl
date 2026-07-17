# Copyright (c) 2024, RTE (http://www.rte-france.com)
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
# SPDX-License-Identifier: MPL-2.0
#
import json
import os
import tempfile
from contextlib import contextmanager
from typing import Dict, Iterator, List, Optional
from pypowsybl import _pypowsybl
from .model_config import ModelConfig, ADDITIONAL_MODELS_FILE_KEY, serialize_model_configs


class Parameters:  # pylint: disable=too-few-public-methods
    """
    Parameters for a dynamic simulation execution.

    All parameters are first read from you configuration file, then overridden with
    the constructor arguments.

    .. currentmodule:: pypowsybl.dynamic

    Args:
        start_time: instant of time at which the dynamic simulation begins, in seconds
        stop_time: instant of time at which the dynamic simulation ends, in seconds
        provider_parameters: Define parameters linked to the dynamic simulation provider
            currently Dynawo is the only provider handled by pypowsybl
        additional_models: Additional dynamic model definitions to register at runtime,
            as a list of :class:`ModelConfig`. This is the Python equivalent of Dynawo's
            ``additionalModelsFile``: the models are serialized to a temporary
            ``models.json`` and wired to the ``additionalModelsFile`` provider parameter
            automatically, so no JSON file has to be authored by hand.
    """

    def __init__(self, start_time: Optional[float] = None,
                 stop_time: Optional[float] = None,
                 provider_parameters: Optional[Dict[str, str]] = None,
                 additional_models: Optional[List[ModelConfig]] = None):
        self._init_with_default_values()
        if start_time is not None:
            self.start_time = start_time
        if stop_time is not None:
            self.stop_time = stop_time
        if provider_parameters is not None:
            self.provider_parameters = provider_parameters
        self.additional_models: List[ModelConfig] = list(additional_models) if additional_models is not None else []

    def _init_with_default_values(self) -> None:
        default_parameters = _pypowsybl.DynamicSimulationParameters()
        self.start_time = default_parameters.start_time
        self.stop_time = default_parameters.stop_time
        self.provider_parameters = dict(
            zip(default_parameters.provider_parameters_keys, default_parameters.provider_parameters_values))

    def _to_c_parameters(self, additional_models_file: Optional[str] = None) -> _pypowsybl.DynamicSimulationParameters:
        c_parameters = _pypowsybl.DynamicSimulationParameters()
        c_parameters.start_time = self.start_time
        c_parameters.stop_time = self.stop_time
        provider_parameters = dict(self.provider_parameters)
        if additional_models_file is not None:
            provider_parameters[ADDITIONAL_MODELS_FILE_KEY] = additional_models_file
        c_parameters.provider_parameters_keys = list(provider_parameters.keys())
        c_parameters.provider_parameters_values = list(provider_parameters.values())
        return c_parameters

    @contextmanager
    def _additional_models_file(self) -> Iterator[Optional[str]]:
        """
        Serialize :attr:`additional_models` to a temporary Dynawo ``models.json``
        and yield its path (or ``None`` when there is no additional model). The
        file is removed when the context exits.
        """
        if not self.additional_models:
            yield None
            return
        catalog = serialize_model_configs(self.additional_models)
        fd, path = tempfile.mkstemp(prefix="pypowsybl_additional_models_", suffix=".json")
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as models_file:
                json.dump(catalog, models_file)
            yield path
        finally:
            if os.path.exists(path):
                os.remove(path)

    def __repr__(self) -> str:
        return f"{self.__class__.__name__}(" \
               f"start_time={self.start_time}" \
               f", stop_time={self.stop_time}" \
               f", provider_parameters={self.provider_parameters!r}" \
               f", additional_models={self.additional_models!r}" \
               f")"
