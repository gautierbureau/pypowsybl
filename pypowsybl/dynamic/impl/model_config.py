# Copyright (c) 2026, RTE (http://www.rte-france.com)
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
# SPDX-License-Identifier: MPL-2.0
#
"""
Python equivalent of the Dynawo additional dynamic model definitions.

Additional dynamic models can be described directly as :class:`ModelConfig` objects and passed to
:class:`pypowsybl.dynamic.Parameters`. They are marshalled to the native layer as a dataframe and
registered on the Dynawo simulation parameters (``DynawoSimulationParameters.setAdditionalModels``),
so no ``models.json`` file has to be authored by hand.

Each :class:`ModelConfig` mirrors one entry of the Dynawo ``models.json`` schema.
"""
from dataclasses import dataclass, field
from typing import List, Optional

import pandas as pd
from pypowsybl import _pypowsybl
from pypowsybl.utils import _create_c_dataframe  # pylint: disable=protected-access

# String series type expected by the native dataframe layer.
_STRING_SERIES_TYPE = 0

# Fixed schema of the additional-models dataframe (all string columns, ``category`` as index).
_ADDITIONAL_MODELS_METADATA = [
    _pypowsybl.SeriesMetadata('category', _STRING_SERIES_TYPE, True, False, False),
    _pypowsybl.SeriesMetadata('lib', _STRING_SERIES_TYPE, False, False, False),
    _pypowsybl.SeriesMetadata('doc', _STRING_SERIES_TYPE, False, False, False),
    _pypowsybl.SeriesMetadata('alias', _STRING_SERIES_TYPE, False, False, False),
    _pypowsybl.SeriesMetadata('internal_model_prefix', _STRING_SERIES_TYPE, False, False, False),
    _pypowsybl.SeriesMetadata('properties', _STRING_SERIES_TYPE, False, False, False),
    _pypowsybl.SeriesMetadata('min_version', _STRING_SERIES_TYPE, False, False, False),
    _pypowsybl.SeriesMetadata('max_version', _STRING_SERIES_TYPE, False, False, False),
    _pypowsybl.SeriesMetadata('end_cause', _STRING_SERIES_TYPE, False, False, False),
]

_ADDITIONAL_MODELS_COLUMNS = [s.name for s in _ADDITIONAL_MODELS_METADATA]


@dataclass
class ModelConfig:
    """
    Definition of an additional Dynawo dynamic model, i.e. one entry of the
    Dynawo ``models.json`` catalog.

    Registering a :class:`ModelConfig` makes its model name available exactly
    like a built-in one: it can be referenced through the ``model_name``
    argument of the :class:`~pypowsybl.dynamic.ModelMapping` ``add_*`` methods
    and shows up in ``ModelMapping.get_supported_models``.

    Dynawo constraints (enforced by the Dynawo layer):
        - ``category`` must be an **existing** category
          (see :meth:`~pypowsybl.dynamic.ModelMapping.get_categories_names`);
          models declared in an unknown category are ignored.
        - a model cannot **overwrite** a built-in model of the same name; a
          duplicate name is ignored (with a warning logged by Dynawo).

    Args:
        category: name of the (existing) category this model extends,
            e.g. ``'BASE_LOAD'``, ``'SYNCHRONOUS_GENERATOR'``.
        lib: the Dynawo model library identifier. Unless ``alias`` is set this
            is also the model name used in the ``add_*`` methods.
        doc: human readable description of the model.
        alias: optional alternative model name. When set, it is the name used
            to reference the model (instead of ``lib``).
        internal_model_prefix: optional Dynawo internal model prefix.
        properties: optional list of Dynawo property tags,
            e.g. ``['CONTROLLABLE']``, ``['SYNCHRONIZED']``.
        min_version: optional minimum compatible Dynawo version, e.g. ``'1.7.0'``.
        max_version: optional maximum compatible Dynawo version.
        end_cause: optional note describing why a model is deprecated.
    """
    category: str
    lib: str
    doc: str = ""
    alias: Optional[str] = None
    internal_model_prefix: Optional[str] = None
    properties: List[str] = field(default_factory=list)
    min_version: Optional[str] = None
    max_version: Optional[str] = None
    end_cause: Optional[str] = None

    @property
    def name(self) -> str:
        """Name used to reference this model (``alias`` if set, else ``lib``)."""
        return self.alias if self.alias else self.lib


def _to_c_dataframe(model_configs: List[ModelConfig]) -> _pypowsybl.Dataframe:
    """
    Build the native dataframe (one row per model) consumed by
    ``_pypowsybl.add_additional_models``. ``category`` is the index column; the
    ``properties`` list is encoded as a comma-separated string.
    """
    records = [{
        'category': model_config.category,
        'lib': model_config.lib,
        'doc': model_config.doc,
        'alias': model_config.alias,
        'internal_model_prefix': model_config.internal_model_prefix,
        'properties': ','.join(model_config.properties),
        'min_version': model_config.min_version,
        'max_version': model_config.max_version,
        'end_cause': model_config.end_cause,
    } for model_config in model_configs]
    dataframe = pd.DataFrame(records, columns=_ADDITIONAL_MODELS_COLUMNS).set_index('category')
    return _create_c_dataframe(dataframe.fillna(''), _ADDITIONAL_MODELS_METADATA)
