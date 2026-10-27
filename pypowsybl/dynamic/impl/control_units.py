# Copyright (c) 2026, RTE (http://www.rte-france.com)
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
# SPDX-License-Identifier: MPL-2.0
#
"""
Machine controls described in Python: governors, voltage regulators, the exciters they drive, and
the controls acting through a regulator (stabilisers, limiters, reactive power control loops).

A control registered with :func:`pypowsybl.dynamic.ModelMapping.add_control_units` is put in the
catalog the mappings design their models from. A machine whose ``synchronousGeneratorProperties``
extension names it is then given a model built out of it, compiled by Dynawo when the mapping
resolves, the same way a combination of shipped controls nobody assembled before is.

A control says how it connects in the machine's terms: each of its own variables is wired to a
:class:`MachineQuantity` (the speed, the mechanical power...) rather than to a variable name, so
the machine models naming them differently are taken care of by the catalog.
"""
from enum import Enum
from typing import List, Optional, Tuple, Union

import pandas as pd
from pypowsybl import _pypowsybl
from pypowsybl.utils import _create_c_dataframe, create_data_frame_from_series_array  # pylint: disable=protected-access

# String series type expected by the native dataframe layer.
_STRING_SERIES_TYPE = 0

# One row per connection a control makes, its definition repeated on each, ``name`` as index.
_CONTROL_UNITS_METADATA = [
    _pypowsybl.SeriesMetadata('name', _STRING_SERIES_TYPE, True, False, False),
    _pypowsybl.SeriesMetadata('kind', _STRING_SERIES_TYPE, False, False, False),
    _pypowsybl.SeriesMetadata('model', _STRING_SERIES_TYPE, False, False, False),
    _pypowsybl.SeriesMetadata('init_model', _STRING_SERIES_TYPE, False, False, False),
    _pypowsybl.SeriesMetadata('instance_id', _STRING_SERIES_TYPE, False, False, False),
    _pypowsybl.SeriesMetadata('own_var', _STRING_SERIES_TYPE, False, False, False),
    _pypowsybl.SeriesMetadata('target_kind', _STRING_SERIES_TYPE, False, False, False),
    _pypowsybl.SeriesMetadata('target', _STRING_SERIES_TYPE, False, False, False),
    _pypowsybl.SeriesMetadata('init', _STRING_SERIES_TYPE, False, False, False),
]

_CONTROL_UNITS_COLUMNS = [s.name for s in _CONTROL_UNITS_METADATA]


class MachineQuantity(Enum):
    """
    A quantity of the machine a control reads or drives. The ``INIT_`` ones are the initial values
    a control's initialisation model starts from.
    """
    STATOR_VOLTAGE = 'STATOR_VOLTAGE'
    FIELD_VOLTAGE = 'FIELD_VOLTAGE'
    ROTOR_CURRENT = 'ROTOR_CURRENT'
    TERMINAL_VOLTAGE = 'TERMINAL_VOLTAGE'
    STATOR_CURRENT = 'STATOR_CURRENT'
    STATOR_CURRENT_MAGNITUDE = 'STATOR_CURRENT_MAGNITUDE'
    SPEED = 'SPEED'
    MECHANICAL_POWER = 'MECHANICAL_POWER'
    ACTIVE_POWER = 'ACTIVE_POWER'
    REACTIVE_POWER = 'REACTIVE_POWER'
    STATOR_REACTIVE_POWER = 'STATOR_REACTIVE_POWER'
    NOMINAL_STATOR_REACTIVE_POWER = 'NOMINAL_STATOR_REACTIVE_POWER'
    ROTOR_ANGLE = 'ROTOR_ANGLE'
    DIRECT_AXIS_CURRENT = 'DIRECT_AXIS_CURRENT'
    RUNNING = 'RUNNING'
    VOLTAGE_MAGNITUDE = 'VOLTAGE_MAGNITUDE'
    INIT_STATOR_VOLTAGE = 'INIT_STATOR_VOLTAGE'
    INIT_FIELD_VOLTAGE = 'INIT_FIELD_VOLTAGE'
    INIT_ROTOR_CURRENT = 'INIT_ROTOR_CURRENT'
    INIT_TERMINAL_VOLTAGE = 'INIT_TERMINAL_VOLTAGE'
    INIT_CURRENT = 'INIT_CURRENT'
    INIT_STATOR_CURRENT_MAGNITUDE = 'INIT_STATOR_CURRENT_MAGNITUDE'
    INIT_MECHANICAL_POWER = 'INIT_MECHANICAL_POWER'
    INIT_ACTIVE_POWER = 'INIT_ACTIVE_POWER'
    INIT_REACTIVE_POWER = 'INIT_REACTIVE_POWER'
    INIT_STATOR_REACTIVE_POWER = 'INIT_STATOR_REACTIVE_POWER'
    INIT_NOMINAL_STATOR_REACTIVE_POWER = 'INIT_NOMINAL_STATOR_REACTIVE_POWER'
    INIT_ROTOR_ANGLE = 'INIT_ROTOR_ANGLE'
    INIT_DIRECT_AXIS_CURRENT = 'INIT_DIRECT_AXIS_CURRENT'
    INIT_VOLTAGE_MAGNITUDE = 'INIT_VOLTAGE_MAGNITUDE'


class RegulatorInput(Enum):
    """
    An input of a voltage regulator another control drives. The regulator names the variable
    (:meth:`VoltageRegulator.accepting`), the control driving it only says which input it drives
    (:meth:`RegulatorControl.driving`), so the same stabiliser meets ``UPssPu`` on one regulator and
    ``UpssPu`` on another.
    """
    STABILISER = 'STABILISER'
    OVER_EXCITATION = 'OVER_EXCITATION'
    UNDER_EXCITATION = 'UNDER_EXCITATION'
    STATOR_CURRENT_OVER_EXCITATION = 'STATOR_CURRENT_OVER_EXCITATION'
    STATOR_CURRENT_UNDER_EXCITATION = 'STATOR_CURRENT_UNDER_EXCITATION'
    FEEDBACK = 'FEEDBACK'
    VOLTAGE_REFERENCE = 'VOLTAGE_REFERENCE'
    LIMITATION_UP = 'LIMITATION_UP'
    LIMITATION_DOWN = 'LIMITATION_DOWN'
    INIT_VOLTAGE_REFERENCE = 'INIT_VOLTAGE_REFERENCE'


# (own_var, target_kind, target, init)
_Connection = Tuple[str, str, str, bool]


class _ControlUnit:
    """
    What every control has: the name machines and models know it by, the Modelica model it stands
    for, the one computing its initial state, and the id it is given inside a built model, which
    prefixes its parameters.
    """
    _KIND = ''
    _DEFAULT_INSTANCE_ID: Optional[str] = None

    def __init__(self, name: str, model: str, init_model: Optional[str] = None,
                 instance_id: Optional[str] = None):
        if instance_id is None:
            instance_id = self._DEFAULT_INSTANCE_ID
        if instance_id is None:
            raise ValueError(f'{type(self).__name__} {name} needs an instance_id')
        self.name = name
        self.model = model
        self.init_model = init_model
        self.instance_id = instance_id
        self._connections: List[_Connection] = []

    def reading(self, own_var: str, quantity: MachineQuantity) -> '_ControlUnit':
        """Wires one of its variables to a quantity of the machine while the simulation runs."""
        self._connections.append((own_var, 'MACHINE', MachineQuantity(quantity).value, False))
        return self

    def starting_from(self, own_var: str, quantity: MachineQuantity) -> '_ControlUnit':
        """Wires one of its variables to a quantity of the machine while the initial state is computed."""
        self._connections.append((own_var, 'MACHINE', MachineQuantity(quantity).value, True))
        return self

    def _rows(self) -> List[dict]:
        definition = {'name': self.name, 'kind': self._KIND, 'model': self.model,
                      'init_model': self.init_model or '', 'instance_id': self.instance_id}
        if not self._connections:
            return [{**definition, 'own_var': '', 'target_kind': '', 'target': '', 'init': 'false'}]
        return [{**definition, 'own_var': own_var, 'target_kind': target_kind, 'target': target,
                 'init': 'true' if init else 'false'}
                for own_var, target_kind, target, init in self._connections]

    def __repr__(self) -> str:
        return f'{type(self).__name__}(name={self.name!r}, model={self.model!r}, init_model={self.init_model!r}, ' \
               f'instance_id={self.instance_id!r}, connections={self._connections!r})'


class Governor(_ControlUnit):
    """
    A turbine governor, named in a ``synchronousGeneratorProperties`` extension by its ``name``.

    Examples:
        .. code-block:: python

            gov = (dyn.Governor('MyGov',
                                model='Dynawo.Electrical.Controls.Machines.Governors.Standard.Generic.GovCt2',
                                init_model='Dynawo.Electrical.Controls.Machines.Governors.GovernorPmPGen_INIT')
                   .reading('PGenPu', dyn.MachineQuantity.ACTIVE_POWER)
                   .reading('PmPu', dyn.MachineQuantity.MECHANICAL_POWER)
                   .reading('omegaPu', dyn.MachineQuantity.SPEED)
                   .starting_from('PGen0Pu', dyn.MachineQuantity.INIT_ACTIVE_POWER)
                   .starting_from('Pm0Pu', dyn.MachineQuantity.INIT_MECHANICAL_POWER))
    """
    _KIND = 'GOVERNOR'
    _DEFAULT_INSTANCE_ID = 'governor'


class Exciter(_ControlUnit):
    """
    An exciter driven by a voltage regulator (see :meth:`VoltageRegulator.driving`). It is not named
    by an extension: it comes with the regulator driving it.
    """
    _KIND = 'EXCITER'
    _DEFAULT_INSTANCE_ID = 'exciter'

    def linked_to_regulator(self, var: str) -> 'Exciter':
        """A variable it shares with the regulator driving it while the simulation runs, the same name on both sides."""
        self._connections.append((var, 'REGULATOR_LINK', '', False))
        return self

    def linked_to_regulator_at_init(self, var: str) -> 'Exciter':
        """A variable it shares with the regulator driving it while the initial state is computed."""
        self._connections.append((var, 'REGULATOR_LINK', '', True))
        return self


class VoltageRegulator(_ControlUnit):
    """
    A voltage regulator, named in a ``synchronousGeneratorProperties`` extension by its ``name``.

    Examples:
        .. code-block:: python

            vr = (dyn.VoltageRegulator('MyVr',
                                       model='Dynawo.Electrical.Controls.Machines.VoltageRegulators.Standard.St5c',
                                       init_model='Dynawo.Electrical.Controls.Machines.VoltageRegulators.Standard.St15c_INIT')
                  .reading('EfdPu', dyn.MachineQuantity.FIELD_VOLTAGE)
                  .reading('IrPu', dyn.MachineQuantity.ROTOR_CURRENT)
                  .reading('UsPu', dyn.MachineQuantity.STATOR_VOLTAGE)
                  .starting_from('Efd0Pu', dyn.MachineQuantity.INIT_FIELD_VOLTAGE)
                  .starting_from('Ir0Pu', dyn.MachineQuantity.INIT_ROTOR_CURRENT)
                  .starting_from('Us0Pu', dyn.MachineQuantity.INIT_STATOR_VOLTAGE)
                  .accepting(dyn.RegulatorInput.STABILISER, 'UPssPu'))
    """
    _KIND = 'VOLTAGE_REGULATOR'
    _DEFAULT_INSTANCE_ID = 'voltageRegulator'

    def __init__(self, name: str, model: str, init_model: Optional[str] = None,
                 instance_id: Optional[str] = None):
        super().__init__(name, model, init_model, instance_id)
        self.exciter: Optional[Exciter] = None

    def accepting(self, regulator_input: RegulatorInput, own_var: str) -> 'VoltageRegulator':
        """Names the variable of this regulator another control drives as that input."""
        self._connections.append((own_var, 'REGULATOR_INPUT', RegulatorInput(regulator_input).value, False))
        return self

    def linked_to_governor(self, var: str) -> 'VoltageRegulator':
        """A variable it shares with the governor beside it, the same name on both sides."""
        self._connections.append((var, 'GOVERNOR_LINK', '', False))
        return self

    def driving(self, exciter: Exciter) -> 'VoltageRegulator':
        """Gives it the exciter it drives, which comes with it into every model built with it."""
        if self.exciter is not None:
            raise ValueError(f'VoltageRegulator {self.name} already drives {self.exciter.name}')
        self.exciter = exciter
        self._connections.append(('', 'EXCITER', exciter.name, False))
        return self


class RegulatorControl(_ControlUnit):
    """
    What the controls acting on the machine through its voltage regulator have in common: each
    reads the machine and drives an input of the regulator, which names it. Use the class of the
    part it plays: :class:`Pss`, :class:`OverExcitationLimiter`, :class:`UnderExcitationLimiter`,
    :class:`StatorCurrentLimiter` or :class:`ReactivePowerControlLoop`.
    """

    def __init__(self, name: str, model: str, init_model: Optional[str] = None,
                 instance_id: Optional[str] = None):
        if type(self) is RegulatorControl:  # pylint: disable=unidiomatic-typecheck
            raise TypeError('RegulatorControl says nothing of the part a control plays: use Pss, '
                            'OverExcitationLimiter, UnderExcitationLimiter, StatorCurrentLimiter or ReactivePowerControlLoop')
        super().__init__(name, model, init_model, instance_id)

    def driving(self, own_var: str, regulator_input: RegulatorInput) -> 'RegulatorControl':
        """Wires one of its variables to an input of the regulator while the simulation runs."""
        self._connections.append((own_var, 'REGULATOR_INPUT', RegulatorInput(regulator_input).value, False))
        return self

    def starting_from(self, own_var: str,  # type: ignore[override]
                      quantity: Union[MachineQuantity, RegulatorInput]) -> 'RegulatorControl':
        """
        Wires one of its variables, while the initial state is computed, to a quantity of the machine
        or to an input of the regulator.
        """
        if isinstance(quantity, RegulatorInput):
            self._connections.append((own_var, 'REGULATOR_INPUT', quantity.value, True))
        else:
            super().starting_from(own_var, quantity)
        return self


class Pss(RegulatorControl):
    """
    A power system stabiliser, named in a ``synchronousGeneratorProperties`` extension as its ``pss``.

    Dynawo's own assemblies call it ``powerSystemStabilizer`` inside a model, some older ones
    ``pss``: give ``instance_id='pss'`` to build one the older way.

    Examples:
        .. code-block:: python

            pss = (dyn.Pss('MyPss',
                           model='Dynawo.Electrical.Controls.Machines.PowerSystemStabilizers.Standard.Pss2a',
                           init_model='Dynawo.Electrical.Controls.Machines.PowerSystemStabilizers.Pss_INIT')
                   .reading('PGenPu', dyn.MachineQuantity.ACTIVE_POWER)
                   .reading('omegaPu', dyn.MachineQuantity.SPEED)
                   .starting_from('PGen0Pu', dyn.MachineQuantity.INIT_ACTIVE_POWER)
                   .driving('VPssPu', dyn.RegulatorInput.STABILISER))
    """
    _KIND = 'PSS'
    _DEFAULT_INSTANCE_ID = 'powerSystemStabilizer'


class OverExcitationLimiter(RegulatorControl):
    """An over excitation limiter, holding the field current of the machine within what it can stand."""
    _KIND = 'OVER_EXCITATION_LIMITER'
    _DEFAULT_INSTANCE_ID = 'overExcitationLimiter'


class UnderExcitationLimiter(RegulatorControl):
    """An under excitation limiter, keeping the machine from absorbing more reactive power than it can stand."""
    _KIND = 'UNDER_EXCITATION_LIMITER'
    _DEFAULT_INSTANCE_ID = 'underExcitationLimiter'


class StatorCurrentLimiter(RegulatorControl):
    """A stator current limiter, holding the stator current of the machine within what it can stand."""
    _KIND = 'STATOR_CURRENT_LIMITER'
    _DEFAULT_INSTANCE_ID = 'statorCurrentLimiter'


class ReactivePowerControlLoop(RegulatorControl):
    """A reactive power control loop, adjusting the regulator's voltage reference to a reactive power set point."""
    _KIND = 'REACTIVE_POWER_CONTROL_LOOP'
    _DEFAULT_INSTANCE_ID = 'reactivePowerControlLoop'


ControlUnit = Union[Governor, VoltageRegulator, Exciter, RegulatorControl]


def _with_exciters(units: List[ControlUnit]) -> List[ControlUnit]:
    """The units, with the exciters their regulators drive where they were not given themselves."""
    names = {unit.name for unit in units}
    exciters = [unit.exciter for unit in units
                if isinstance(unit, VoltageRegulator) and unit.exciter is not None and unit.exciter.name not in names]
    unique: List[ControlUnit] = list({exciter.name: exciter for exciter in exciters}.values())
    return list(units) + unique


def _to_dataframe(units: Union[List[ControlUnit], pd.DataFrame]) -> pd.DataFrame:
    """
    One row per connection, a control's definition repeated on each, ``name`` as index: what
    ``_pypowsybl.add_mapping_control_units`` reads. A dataframe in those columns, as
    :func:`get_control_units` returns, is taken as it is.
    """
    if isinstance(units, pd.DataFrame):
        return _from_dataframe(units)
    seen: dict = {}
    for unit in _with_exciters(units):
        if unit.name in seen and seen[unit.name] is not unit:
            raise ValueError(f'Two controls are named {unit.name}')
        seen[unit.name] = unit
    records = [row for unit in seen.values() for row in unit._rows()]  # pylint: disable=protected-access
    return pd.DataFrame(records, columns=_CONTROL_UNITS_COLUMNS).set_index('name')


def _to_c_dataframe(units: Union[List[ControlUnit], pd.DataFrame]) -> _pypowsybl.Dataframe:
    return _create_c_dataframe(_to_dataframe(units), _CONTROL_UNITS_METADATA)


def _from_dataframe(df: pd.DataFrame) -> pd.DataFrame:
    """The dataframe in the columns the native layer reads, every value a string."""
    df = df.reset_index() if 'name' not in df.columns else df
    missing = [c for c in _CONTROL_UNITS_COLUMNS if c not in df.columns]
    if missing:
        raise ValueError(f'The control units dataframe is missing the columns {missing}')
    df = df[_CONTROL_UNITS_COLUMNS].copy()
    df['init'] = df['init'].map(lambda v: 'true' if str(v).lower() == 'true' else 'false')
    return df.fillna('').astype(str).set_index('name')


def get_control_units(names: Optional[List[str]] = None) -> pd.DataFrame:
    """
    The machine controls that ship, Dynawo's own and those a deployment's providers bring: what
    each reads off the machine and drives on its regulator, while running and at initialisation.

    One row per connection, in the columns :func:`ModelMapping.add_control_units` takes, so a control
    that ships can be copied, changed, renamed and registered as one of one's own.

    Columns:
        - **name** (index): the name a ``synchronousGeneratorProperties`` extension and a model give it
        - **kind**: ``GOVERNOR``, ``VOLTAGE_REGULATOR``, ``EXCITER``, ``PSS``, ``OVER_EXCITATION_LIMITER``,
          ``UNDER_EXCITATION_LIMITER``, ``STATOR_CURRENT_LIMITER`` or ``REACTIVE_POWER_CONTROL_LOOP``
        - **model**, **init_model**: the Modelica models it stands for and computes its initial state with
        - **instance_id**: its id inside a model, which prefixes its parameters
        - **own_var**: its variable the connection wires
        - **target_kind**: ``MACHINE`` (a :class:`MachineQuantity`), ``REGULATOR_INPUT`` (a :class:`RegulatorInput`,
          the one a regulator names or the one a control acting through it drives), ``GOVERNOR_LINK``,
          ``REGULATOR_LINK``, or ``EXCITER`` (the exciter a regulator drives)
        - **target**: what it is wired to
        - **init**: whether the connection is made while the initial state is computed

    Args:
        names: the controls to keep, all of them if not given

    Examples:
        .. code-block:: python

            dyn.get_control_units(['GovCt2', 'St4b', 'Pss2a'])
    """
    df = create_data_frame_from_series_array(_pypowsybl.get_control_units())
    if names is not None:
        df = df[df.index.isin(names)]
    return df


def get_machine_quantities(windings: str = 'FOUR_WINDINGS', transformer: bool = False) -> pd.DataFrame:
    """
    What a synchronous machine offers the controls put on it, as the mappings build their models:
    the quantities a control reads or drives while running, and those it starts from at
    initialisation, each with the variable of the machine holding it. A quantity the machine has
    nothing to say about is left out.

    Columns:
        - **quantity** (index): a :class:`MachineQuantity`
        - **init**: whether it is a value the machine starts from, read at initialisation
        - **variable**: the machine's variable holding it

    Args:
        windings: ``FOUR_WINDINGS`` or ``THREE_WINDINGS``
        transformer: whether the machine stands behind a transformer of its own

    Examples:
        .. code-block:: python

            quantities = dyn.get_machine_quantities('FOUR_WINDINGS')
            at_init = quantities[quantities.init]
    """
    return create_data_frame_from_series_array(_pypowsybl.get_machine_quantities(windings, transformer))
