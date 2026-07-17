# Copyright (c) 2026, RTE (http://www.rte-france.com)
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
# SPDX-License-Identifier: MPL-2.0
#
"""
Python equivalent of the Dynawo ``additionalModelsFile`` (``models.json``).

Instead of authoring a JSON file by hand and pointing the ``additionalModelsFile``
provider parameter at it, additional dynamic model definitions can be described
directly as :class:`ModelConfig` objects and passed to
:class:`pypowsybl.dynamic.Parameters`. pypowsybl serializes them to a temporary
``models.json`` and wires the ``additionalModelsFile`` parameter automatically.

Each :class:`ModelConfig` mirrors one entry of the Dynawo ``models.json`` schema.
"""
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

# Provider parameter key understood by ``DynawoSimulationParameters`` to locate
# the additional models JSON file.
ADDITIONAL_MODELS_FILE_KEY = "additionalModelsFile"


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

    def _to_json_entry(self) -> Dict[str, Any]:
        """Serialize this model to one ``libs`` entry of the models.json schema."""
        entry: Dict[str, Any] = {"lib": self.lib}
        if self.alias:
            entry["alias"] = self.alias
        if self.internal_model_prefix:
            entry["internalModelPrefix"] = self.internal_model_prefix
        if self.doc:
            entry["doc"] = self.doc
        if self.properties:
            entry["properties"] = list(self.properties)
        if self.min_version:
            entry["minVersion"] = self.min_version
        if self.max_version:
            entry["maxVersion"] = self.max_version
        if self.end_cause:
            entry["endCause"] = self.end_cause
        return entry


def serialize_model_configs(model_configs: List[ModelConfig]) -> Dict[str, Any]:
    """
    Serialize a list of :class:`ModelConfig` to the Dynawo ``models.json``
    structure: a mapping of category name to ``{"libs": [entry, ...]}``.

    Args:
        model_configs: the additional models to serialize.

    Returns:
        a JSON-serializable dictionary following the Dynawo models.json schema.
    """
    catalog: Dict[str, Any] = {}
    for model_config in model_configs:
        category = catalog.setdefault(model_config.category, {"libs": []})
        category["libs"].append(model_config._to_json_entry())  # pylint: disable=protected-access
    return catalog
