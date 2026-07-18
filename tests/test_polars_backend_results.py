#
# Copyright (c) 2026, RTE (http://www.rte-france.com)
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
# SPDX-License-Identifier: MPL-2.0
#
"""
Tests for the optional polars dataframe backend on the result modules.

Result objects expose a settable ``dataframe_backend`` attribute (default 'pandas');
flowdecomposition's ``run`` takes a per-call ``backend`` argument. Skipped without polars.
"""
import math
import pathlib

import pytest

import pypowsybl as pp

pl = pytest.importorskip("polars")

DATA_DIR = pathlib.Path(__file__).parent.parent / 'data'


def _mod(x):
    return type(x).__module__.split('.')[0]


def _norm(v):
    if v is None:
        return None
    if isinstance(v, float) and math.isnan(v):
        return None
    if isinstance(v, str) and v == '':
        return None
    return v


def _assert_equivalent(pdf, pldf):
    reset = pdf.reset_index()
    assert pldf.height == len(pdf)
    assert set(reset.columns) == set(pldf.columns)
    for col in reset.columns:
        assert [_norm(x) for x in reset[col].tolist()] == [_norm(x) for x in pldf[col].to_list()], col


# --- shared mixin machinery -------------------------------------------------

def test_mixin_backend_switch_and_validation():
    from pypowsybl.utils import DataframeBackendMixin

    class R(DataframeBackendMixin):
        pass

    r = R()
    assert r.dataframe_backend == 'pandas'   # default
    r.dataframe_backend = 'polars'
    assert r.dataframe_backend == 'polars'
    with pytest.raises(ValueError, match="Unsupported dataframe backend"):
        r.dataframe_backend = 'numpy'


# --- security ---------------------------------------------------------------

def test_security_result_backend():
    n = pp.network.create_ieee14()
    sa = pp.security.create_analysis()
    sa.add_monitored_elements(voltage_level_ids=['VL1'])
    r = sa.run_ac(n)
    assert _mod(r.branch_results) == 'pandas'
    r.dataframe_backend = 'polars'
    for accessor in ('limit_violations', 'branch_results', 'bus_results', 'three_windings_transformer_results'):
        assert _mod(getattr(r, accessor)) == 'polars', accessor
    # equivalence on bus_results
    r.dataframe_backend = 'pandas'
    pdf = r.bus_results
    r.dataframe_backend = 'polars'
    _assert_equivalent(pdf, r.bus_results)


# --- sensitivity ------------------------------------------------------------

def test_sensitivity_matrix_backend():
    n = pp.network.create_ieee14()
    sa = pp.sensitivity.create_dc_analysis()
    sa.add_branch_flow_factor_matrix(branches_ids=['L1-2-1'], variables_ids=['B1-G'])
    r = sa.run(n)
    assert _mod(r.get_sensitivity_matrix()) == 'pandas'
    r.dataframe_backend = 'polars'
    m = r.get_sensitivity_matrix()
    assert _mod(m) == 'polars'
    # the pandas row label becomes a leading 'id' column
    assert m.columns[0] == 'id'
    ref = r.get_reference_matrix()
    assert _mod(ref) == 'polars' and ref.columns[0] == 'id'


# --- loadflow validation ----------------------------------------------------

def test_loadflow_validation_backend():
    n = pp.network.create_ieee14()
    pp.loadflow.run_ac(n)
    vr = pp.loadflow.run_validation(n)
    valid_before = vr.valid          # computed on pandas internally
    pdf = vr.buses
    assert _mod(pdf) == 'pandas'
    vr.dataframe_backend = 'polars'
    assert vr.valid == valid_before  # switching backend must not change validity
    assert _mod(vr.buses) == 'polars'
    _assert_equivalent(pdf, vr.buses)


# --- flowdecomposition ------------------------------------------------------

def test_flowdecomposition_backend():
    n = pp.network.create_eurostag_tutorial_example1_with_tie_lines_and_areas()
    dec = pp.flowdecomposition.create_decomposition() \
        .add_single_element_contingencies(['NHV1_NHV2_1']) \
        .add_monitored_elements(['NHV1_NHV2_1', 'NHV1_NHV2_2'])
    pdf = dec.run(n)
    assert _mod(pdf) == 'pandas'
    dec.dataframe_backend = 'polars'
    pldf = dec.run(n)
    assert _mod(pldf) == 'polars'
    _assert_equivalent(pdf, pldf)
    with pytest.raises(ValueError, match="Unsupported dataframe backend"):
        dec.dataframe_backend = 'numpy'


# --- rao crac ---------------------------------------------------------------

def test_rao_crac_backend():
    net = pp.network.load(str(DATA_DIR / 'rao' / 'network_crac.xiidm'))
    crac = pp.rao.Crac.from_file_source(net, str(DATA_DIR / 'rao' / 'crac-v2.8.json'))
    pdf = crac.get_contingencies()
    assert _mod(pdf) == 'pandas'
    crac.dataframe_backend = 'polars'
    pldf = crac.get_contingencies()
    assert _mod(pldf) == 'polars'
    _assert_equivalent(pdf, pldf)
    assert _mod(crac.get_flow_cnecs()) == 'polars'
