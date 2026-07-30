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


def _settings_map(settings: dict) -> dict:
    return {name: _to_setting_value(value) for name, value in settings.items()}


def _providers_frame(lines: list) -> DataFrame:
    rows = [line.split('\t', 1) for line in lines]
    return DataFrame.from_records(
        index='name',
        columns=['name', 'description'],
        data=[(name, description) for name, description in rows])


def add_synchronous_generator_properties(network: Network, provider_name: str, **settings: Any) -> None:
    """
    Describe the dynamic controls of a network's synchronous generators, with a named provider.

    This is a step before a mapping, and separate from it: it writes the ``synchronousGeneratorProperties``
    extension on each machine, from which a mapping later chooses a model. A machine already carrying
    the extension is left as it is, so a study may describe part of a fleet this way and let the
    mapping's own rule describe the rest. Reading or changing a control afterwards is done on the
    extension itself, with the network's ``update_extensions('synchronousGeneratorProperties', ...)``.

    The providers, and what each describes, are given by
    :func:`get_synchronous_generator_properties_providers`.

    Args:
        network: the network whose generators are described
        provider_name: name of a registered provider, for instance ``EnergySource`` to deduce the
            controls from each machine's energy source, or ``Nordic32`` for the controls of that
            test system machine by machine
        settings: the settings the provider takes, for instance ``tso_voltage_min=63`` for the
            energy source provider, the voltage below which a machine carries no auxiliaries

    Examples:
        .. code-block:: python

            dyn.add_synchronous_generator_properties(network, 'EnergySource', tso_voltage_min=63)
            model_mapping = dyn.ModelMapping()
            model_mapping.create_mapping('UniversalDynaWaltz')
    """
    _pp.add_synchronous_generator_properties(network._handle, provider_name,  # pylint: disable=protected-access
                                             _settings_map(settings))


def get_synchronous_generator_properties_providers() -> DataFrame:
    """
    The providers that can be given to :func:`add_synchronous_generator_properties`, each with the
    one line it describes.

    Returns:
        a dataframe indexed by provider name, holding its description
    """
    return _providers_frame(_pp.get_synchronous_generator_properties_providers())


def add_tap_changer_blockings(network: Network, provider_name: str, **settings: Any) -> None:
    """
    Add the tap changer blockings of a named system to a network, with a named provider.

    This is a step before a mapping, and separate from it: it writes the ``tapChangerBlockings``
    extension on the network, naming the points each blocking watches and the levels whose
    transformers it blocks, from which a mapping later builds a blocking automaton. A network already
    carrying the extension is left as it is.

    The providers, and what each describes, are given by :func:`get_tap_changer_blockings_providers`.

    Args:
        network: the network the blockings are added to
        provider_name: name of a registered provider, for instance ``Nordic32`` for the blockings of
            that test system
        settings: the settings the provider takes

    Examples:
        .. code-block:: python

            dyn.add_tap_changer_blockings(network, 'Nordic32')
    """
    _pp.add_tap_changer_blockings(network._handle, provider_name,  # pylint: disable=protected-access
                                  _settings_map(settings))


def get_tap_changer_blockings_providers() -> DataFrame:
    """
    The providers that can be given to :func:`add_tap_changer_blockings`, each with the one line it
    describes.

    Returns:
        a dataframe indexed by provider name, holding its description
    """
    return _providers_frame(_pp.get_tap_changer_blockings_providers())


def add_dynamic_simulation_extensions(network: Network, system_name: str, **settings: Any) -> None:
    """
    Add every dynamic simulation extension a named system needs, in one call.

    This composes the finer providers: for ``Nordic32`` it adds the synchronous generator properties
    and the tap changer blockings that system's machines and transformers need, so a study can set a
    network up for a mapping in a single step instead of calling each provider in turn. Where finer
    control is wanted, :func:`add_synchronous_generator_properties` and
    :func:`add_tap_changer_blockings` add one kind of extension at a time.

    The systems, and what each adds, are given by :func:`get_dynamic_simulation_systems`.

    Args:
        network: the network the extensions are added to
        system_name: name of a registered system, for instance ``Nordic32`` or ``IEEE``
        settings: the settings the system's providers take

    Examples:
        .. code-block:: python

            dyn.add_dynamic_simulation_extensions(network, 'Nordic32')
            model_mapping = dyn.ModelMapping()
            model_mapping.create_mapping('UniversalDynaWaltz')
    """
    _pp.add_dynamic_simulation_extensions(network._handle, system_name,  # pylint: disable=protected-access
                                          _settings_map(settings))


def get_dynamic_simulation_systems() -> DataFrame:
    """
    The systems that can be given to :func:`add_dynamic_simulation_extensions`, each with the one
    line it describes.

    Returns:
        a dataframe indexed by system name, holding its description
    """
    return _providers_frame(_pp.get_dynamic_simulation_systems())
