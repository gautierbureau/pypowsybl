#
# Copyright (c) 2020, RTE (http://www.rte-france.com)
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
# SPDX-License-Identifier: MPL-2.0
#
from os import PathLike
from typing import Union, Any, TYPE_CHECKING
import pandas as pd
import numpy as np
from pypowsybl import _pypowsybl

if TYPE_CHECKING:
    import polars as pl  # optional dependency, only imported for type checking

PathOrStr = Union[str, PathLike]


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


DataframeBackend = str  # 'pandas' | 'polars'


def create_frame_from_series_array(series_array: _pypowsybl.SeriesArray,
                                   backend: DataframeBackend = 'pandas') -> Any:
    """
    Builds a dataframe from a native series array, using the requested backend
    (``'pandas'`` -> :class:`pandas.DataFrame`, ``'polars'`` -> :class:`polars.DataFrame`).
    """
    if backend == 'pandas':
        return create_data_frame_from_series_array(series_array)
    if backend == 'polars':
        return create_polars_frame_from_series_array(series_array)
    raise ValueError(f"Unsupported dataframe backend '{backend}', expected 'pandas' or 'polars'")


class DataframeBackendMixin:
    """
    Mixin giving a result object a settable dataframe backend honored by all its
    dataframe accessors. Defaults to pandas, so existing behaviour is unchanged.
    """
    _dataframe_backend: DataframeBackend = 'pandas'

    @property
    def dataframe_backend(self) -> DataframeBackend:
        """The dataframe library used by this object's dataframe accessors ('pandas' or 'polars')."""
        return self._dataframe_backend

    @dataframe_backend.setter
    def dataframe_backend(self, backend: DataframeBackend) -> None:
        if backend not in ('pandas', 'polars'):
            raise ValueError(f"Unsupported dataframe backend '{backend}', expected 'pandas' or 'polars'")
        self._dataframe_backend = backend

    def _create_frame(self, series_array: _pypowsybl.SeriesArray) -> Any:
        return create_frame_from_series_array(series_array, self._dataframe_backend)

    def _convert_frame(self, df: Any) -> Any:
        """
        Convert an already-built pandas frame to the selected backend. For polars the
        pandas index (element ids / row labels) is moved into regular leading columns.

        Built column-by-column (no ``pl.from_pandas``) so that string columns do not
        pull in an optional pyarrow dependency.
        """
        if df is None or self._dataframe_backend == 'pandas':
            return df
        import polars as pl  # pylint: disable=import-outside-toplevel
        reset = df.reset_index()
        return pl.DataFrame({str(col): reset[col].to_list() for col in reset.columns})


def create_polars_frame_from_series_array(series_array: _pypowsybl.SeriesArray) -> 'pl.DataFrame':
    """
    Builds a polars DataFrame from a native series array.

    Unlike pandas, polars has no row index concept: the columns that pandas would
    turn into an :class:`~pandas.Index` (the element IDs, and possibly a composite
    key such as ``(id, position)``) are kept as regular columns, placed first in the
    frame so they stay easy to identify and to use in joins/filters. Optional (nullable)
    values are represented with polars native nulls instead of numpy masked arrays.
    """
    try:
        import polars as pl  # pylint: disable=import-outside-toplevel
    except ImportError as e:
        raise ImportError("The 'polars' backend requires the polars package. "
                          "Install it with `pip install pypowsybl[polars]`.") from e

    index_columns: list = []
    data_columns: list = []
    for series in series_array:
        column = pl.Series(series.name, series.data)
        if series.index:
            index_columns.append(column)
        else:
            mask = series.mask
            if mask.size and mask.any():
                # native polars nulls instead of a numpy masked array
                column = column.scatter(np.flatnonzero(mask).tolist(), None)
            data_columns.append(column)
    if not index_columns:
        raise ValueError('No index in returned dataframe')
    # index columns first, so element keys are the leading columns of the frame
    return pl.DataFrame(index_columns + data_columns)


def path_to_str(path: PathOrStr) -> str:
    if isinstance(path, str):
        return path
    return path.__fspath__()
