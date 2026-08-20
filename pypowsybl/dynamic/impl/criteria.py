# Copyright (c) 2026, RTE (http://www.rte-france.com)
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
# SPDX-License-Identifier: MPL-2.0
#
from typing import List, Optional
from pandas import DataFrame
from pypowsybl import _pypowsybl as _pp
from pypowsybl.utils import _get_c_dataframes  # pylint: disable=protected-access


class Criteria:
    """
    A set of typed Dynawo criteria a dynamic simulation checks on its buses, loads and generators, built
    from four dataframes keyed by criteria id and handed to :meth:`Simulation.run`. It is the typed
    alternative to pointing at a ``.crt`` file, and takes precedence over one when both are set.
    """

    def __init__(self) -> None:
        self._handle = _pp.create_criteria()

    def add(self,
            criteria: DataFrame,
            voltage_levels: Optional[DataFrame] = None,
            components: Optional[DataFrame] = None,
            countries: Optional[DataFrame] = None) -> None:
        """
        Add criteria from the four tables that mirror the Dynawo CRT model, each keyed by criteria id. Only
        the criteria table is required; a table left out is taken as empty.

        Args:
            criteria: one row per criteria, index ``id``; columns ``kind`` (BUS, LOAD or GENERATOR),
                ``scope`` (FINAL or DYNAMIC), ``type`` (LOCAL_VALUE or SUM), and the optional active power
                bounds ``p_min`` / ``p_max``.
            voltage_levels: the voltage bands of a criteria, index ``criteria_id``; the optional bounds
                ``u_min_pu``, ``u_max_pu``, ``u_nom_min``, ``u_nom_max`` (several rows give several bands).
            components: the equipments a criteria watches, index ``criteria_id``; column ``id`` and the
                optional ``voltage_level_id``.
            countries: the countries a criteria filters on, index ``criteria_id``; column ``country``.
        """
        metadata = _pp.get_criteria_meta_data()
        tables = [criteria, voltage_levels, components, countries]
        # an omitted table ships as an empty dataframe, never None: the native side reads the four tables by
        # position and dereferences each, so a missing one must still be a valid zero-row dataframe
        tables = [table if table is not None else _empty_table(metadata[i])
                  for i, table in enumerate(tables)]
        c_dfs = _get_c_dataframes(tables, metadata)
        _pp.add_criteria(self._handle, c_dfs)


def _empty_table(table_metadata: List[_pp.SeriesMetadata]) -> DataFrame:
    """An empty dataframe carrying a table's index and columns, so an omitted table is still valid."""
    index = [column.name for column in table_metadata if column.is_index]
    columns = [column.name for column in table_metadata]
    return DataFrame(columns=columns).set_index(index)
