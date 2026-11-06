#
# Copyright (c) 2026, RTE (http://www.rte-france.com)
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
# SPDX-License-Identifier: MPL-2.0
#
import pytest
import pypowsybl as pp
import pypowsybl.loadflow as lf

GPU_PROVIDER = 'OpenLoadFlowGpu'
NO_GPU_MESSAGE = 'native library is not available'


@pytest.fixture(autouse=True)
def no_config():
    pp.set_config_read(False)


def test_gpu_providers_are_listed():
    assert GPU_PROVIDER in pp.loadflow.get_provider_names()
    assert GPU_PROVIDER in pp.security.get_provider_names()


def test_gpu_load_flow_matches_cpu():
    n_cpu = pp.network.create_ieee14()
    n_gpu = pp.network.create_ieee14()
    cpu = lf.run_ac(n_cpu, provider='OpenLoadFlow')
    try:
        gpu = lf.run_ac(n_gpu, provider=GPU_PROVIDER)
    except pp.PyPowsyblError as e:
        if NO_GPU_MESSAGE in str(e):
            pytest.skip('no GPU native library (libolfgpu) or no GPU')
        raise
    assert gpu[0].status == lf.ComponentStatus.CONVERGED
    assert cpu[0].status == lf.ComponentStatus.CONVERGED
    v_cpu = n_cpu.get_buses()[['v_mag', 'v_angle']]
    v_gpu = n_gpu.get_buses()[['v_mag', 'v_angle']]
    assert ((v_cpu - v_gpu).abs() < 1e-3).all().all()
