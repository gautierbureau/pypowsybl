#
# Copyright (c) 2026, RTE (http://www.rte-france.com)
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
# SPDX-License-Identifier: MPL-2.0
#
"""
Prototype tests for the optional polars dataframe backend.

The polars backend is opt-in: reading with ``backend='polars'`` returns a polars
DataFrame (element IDs as leading columns, since polars has no row index), and
update methods accept a polars DataFrame. These tests are skipped when polars is
not installed.
"""
import math

import pytest

import pypowsybl as pp

pl = pytest.importorskip("polars")


def test_get_generators_polars_read():
    n = pp.network.create_eurostag_tutorial_example1_network()
    df = n.get_generators(backend='polars')
    assert isinstance(df, pl.DataFrame)
    # polars has no index: the element id is a regular, leading column
    assert df.columns[0] == 'id'
    assert set(['GEN', 'GEN2']).issubset(set(df['id'].to_list()))

    # cross-check values against the pandas backend
    pdf = n.get_generators()
    gen_pl = df.filter(pl.col('id') == 'GEN').to_dicts()[0]
    assert gen_pl['target_p'] == pytest.approx(pdf.loc['GEN', 'target_p'])
    assert gen_pl['voltage_regulator_on'] == bool(pdf.loc['GEN', 'voltage_regulator_on'])


def test_get_generators_polars_attributes_keeps_id():
    n = pp.network.create_eurostag_tutorial_example1_network()
    df = n.get_generators(attributes=['target_p'], backend='polars')
    assert isinstance(df, pl.DataFrame)
    # id (the key column) is always kept, plus the requested attribute
    assert set(df.columns) == {'id', 'target_p'}


def test_update_generators_from_polars():
    n = pp.network.create_eurostag_tutorial_example1_network()
    update = pl.DataFrame({'id': ['GEN'], 'target_p': [123.0], 'voltage_regulator_on': [False]})
    n.update_generators(update)
    pdf = n.get_generators()
    assert pdf.loc['GEN', 'target_p'] == pytest.approx(123.0)
    assert not pdf.loc['GEN', 'voltage_regulator_on']


def test_polars_round_trip():
    n = pp.network.create_eurostag_tutorial_example1_network()
    df = n.get_generators(backend='polars')
    # bump every generator's target_p by 10 MW using polars, then write it back
    updated = df.select(['id', pl.col('target_p') + 10.0])
    n.update_generators(updated)
    check = n.get_generators(backend='polars')
    for row_before, row_after in zip(df.sort('id').iter_rows(named=True),
                                     check.sort('id').iter_rows(named=True)):
        assert row_after['target_p'] == pytest.approx(row_before['target_p'] + 10.0)


@pytest.mark.parametrize('getter', ['get_loads', 'get_lines', 'get_buses',
                                    'get_voltage_levels', 'get_substations', 'get_2_windings_transformers'])
def test_getters_support_polars_backend(getter):
    n = pp.network.create_eurostag_tutorial_example1_network()
    df = getattr(n, getter)(backend='polars')
    assert isinstance(df, pl.DataFrame)
    # element id(s) exposed as leading column(s), not an index
    assert 'id' in df.columns


def test_multi_index_table_polars():
    n = pp.network.create_eurostag_tutorial_example1_network()
    steps = n.get_ratio_tap_changer_steps(backend='polars')
    assert isinstance(steps, pl.DataFrame)
    # the (id, position) composite key becomes the two leading columns
    assert steps.columns[:2] == ['id', 'position']


def test_create_from_polars():
    n = pp.network.create_eurostag_tutorial_example1_network()
    n.create_loads(pl.DataFrame({
        'id': ['NEW_LOAD'],
        'voltage_level_id': ['VLLOAD'],
        'bus_id': ['NLOAD'],
        'p0': [10.0],
        'q0': [5.0],
    }))
    loads = n.get_loads()
    assert 'NEW_LOAD' in loads.index
    assert loads.loc['NEW_LOAD', 'p0'] == pytest.approx(10.0)


def test_invalid_backend_raises():
    n = pp.network.create_eurostag_tutorial_example1_network()
    with pytest.raises(ValueError, match="Unsupported dataframe backend"):
        n.get_generators(backend='numpy')
