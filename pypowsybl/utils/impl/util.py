#
# Copyright (c) 2020, RTE (http://www.rte-france.com)
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
# SPDX-License-Identifier: MPL-2.0
#
from functools import lru_cache
from os import PathLike
from typing import List, Union, Any
import pandas as pd
import numpy as np
from pypowsybl import _pypowsybl

PathOrStr = Union[str, PathLike]


# Series metadata is static for the lifetime of the process (it only depends on the element/extension/
# modification type, not on any network instance). Fetching it crosses the native boundary and rebuilds
# a list of Python objects on every call, so we memoize it. These are hot: every get/update/create call
# on network elements fetches metadata first.

@lru_cache(maxsize=None)
def get_network_elements_dataframe_metadata(element_type: _pypowsybl.ElementType) -> List[_pypowsybl.SeriesMetadata]:
    return _pypowsybl.get_network_elements_dataframe_metadata(element_type)


@lru_cache(maxsize=None)
def get_network_elements_creation_dataframes_metadata(
        element_type: _pypowsybl.ElementType) -> List[List[_pypowsybl.SeriesMetadata]]:
    return _pypowsybl.get_network_elements_creation_dataframes_metadata(element_type)


@lru_cache(maxsize=None)
def get_network_extensions_dataframe_metadata(
        extension_name: str, table_name: str = "") -> List[_pypowsybl.SeriesMetadata]:
    return _pypowsybl.get_network_extensions_dataframe_metadata(extension_name, table_name)


@lru_cache(maxsize=None)
def get_network_extensions_creation_dataframes_metadata(
        extension_name: str) -> List[List[_pypowsybl.SeriesMetadata]]:
    return _pypowsybl.get_network_extensions_creation_dataframes_metadata(extension_name)


@lru_cache(maxsize=None)
def get_network_modification_metadata(
        modification_type: _pypowsybl.NetworkModificationType) -> List[_pypowsybl.SeriesMetadata]:
    return _pypowsybl.get_network_modification_metadata(modification_type)


@lru_cache(maxsize=None)
def get_network_modification_metadata_with_element_type(
        modification_type: _pypowsybl.NetworkModificationType,
        element_type: _pypowsybl.ElementType) -> List[List[_pypowsybl.SeriesMetadata]]:
    return _pypowsybl.get_network_modification_metadata_with_element_type(modification_type, element_type)


def create_data_frame_from_series_array(series_array: _pypowsybl.SeriesArray) -> pd.DataFrame:
    series_dict: dict[str, Any] = {}
    index_data = []
    index_names = []
    for series in series_array:
        if series.index:
            index_data.append(series.data)
            index_names.append(series.name)
        else:
            if series.mask.any():
                series_dict[series.name] = np.ma.masked_array(series.data, series.mask)
            else:
                series_dict[series.name] = series.data
    if not index_names:
        raise ValueError('No index in returned dataframe')
    if len(index_names) == 1:
        index = pd.Index(index_data[0], name=index_names[0])
    else:
        index = pd.MultiIndex.from_arrays(index_data, names=index_names)
    return pd.DataFrame(series_dict, index=index)


def path_to_str(path: PathOrStr) -> str:
    if isinstance(path, str):
        return path
    return path.__fspath__()
