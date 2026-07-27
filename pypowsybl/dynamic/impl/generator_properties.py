# Copyright (c) 2026, RTE (http://www.rte-france.com)
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
# SPDX-License-Identifier: MPL-2.0
#
from typing import Any

from pandas import DataFrame

from pypowsybl import _pypowsybl as _pp
from pypowsybl.network import Network


def _to_setting_value(value: Any) -> str:
    """Every setting crosses as text, booleans in lower case as Dynawo writes them."""
    return str(value).lower() if isinstance(value, bool) else str(value)


def set_generator_properties(network: Network, provider_name: str, **settings: Any) -> None:
    """
    Describe the dynamic controls of a network's synchronous generators, with a named provider.

    This is the step before a mapping, and separate from it: it writes the ``synchronousGeneratorProperties``
    extension each machine, from which a mapping later chooses a model. A machine already carrying
    the extension is left as it is, so a study may describe part of a fleet this way and let the
    mapping's own rule describe the rest. Reading or changing a control afterwards is done on the
    extension itself, with the network's ``update_extensions('synchronousGeneratorProperties', ...)``.

    The providers, and what each describes, are given by :func:`get_generator_properties_providers`.

    Args:
        network: the network whose generators are described
        provider_name: name of a registered provider, for instance ``EnergySource`` to deduce the
            controls from each machine's energy source, or ``Nordic32`` for the controls of that
            test system machine by machine
        settings: the settings the provider takes, for instance ``tso_voltage_min=63`` for the
            energy source provider, the voltage below which a machine carries no auxiliaries

    Examples:
        .. code-block:: python

            dyn.set_generator_properties(network, 'EnergySource', tso_voltage_min=63)
            model_mapping = dyn.ModelMapping()
            model_mapping.create_mapping('UniversalDynaWaltz')
    """
    _pp.set_generator_properties(network._handle, provider_name,  # pylint: disable=protected-access
                                 {name: _to_setting_value(value) for name, value in settings.items()})


def get_generator_properties_providers() -> DataFrame:
    """
    The providers that can be given to :func:`set_generator_properties`, each with the one line it
    describes.

    Returns:
        a dataframe indexed by provider name, holding its description
    """
    rows = [line.split('\t', 1) for line in _pp.get_generator_properties_providers()]
    return DataFrame.from_records(
        index='name',
        columns=['name', 'description'],
        data=[(name, description) for name, description in rows])
