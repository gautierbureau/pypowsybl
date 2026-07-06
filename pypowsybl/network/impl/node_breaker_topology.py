# Copyright (c) 2023, RTE (http://www.rte-france.com)
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
# SPDX-License-Identifier: MPL-2.0
#
from pandas import DataFrame
import networkx as _nx
import pypowsybl._pypowsybl as _pp
from pypowsybl.utils import create_data_frame_from_series_array


class NodeBreakerTopology:
    """
    Node-breaker representation of the topology of a voltage level.

    The topology is actually represented as a graph, where
    vertices are called "nodes" and are identified by a unique number in the voltage level,
    while edges are switches (breakers and disconnectors), or internal connections (plain "wires").
    """

    def __init__(self, network_handle: _pp.JavaHandle, voltage_level_id: str):
        self._internal_connections = create_data_frame_from_series_array(
            _pp.get_node_breaker_view_internal_connections(network_handle, voltage_level_id))
        self._switchs = create_data_frame_from_series_array(
            _pp.get_node_breaker_view_switches(network_handle, voltage_level_id))
        self._nodes = create_data_frame_from_series_array(
            _pp.get_node_breaker_view_nodes(network_handle, voltage_level_id))

    @property
    def switches(self) -> DataFrame:
        """
        The list of switches of the voltage level, together with their connection status, as a dataframe.

        The dataframe includes the following columns:

        - **name**: Switch name
        - **kind**: Switch kind (BREAKER, DISCONNECTOR, ...)
        - **open**: True if the switch is opened
        - **retained**: True if the switch is to be retained in the Bus/Breaker view
        - **node1**: node where the switch is connected at side 1
        - **node2**: node where the switch is connected at side 2

        This dataframe is indexed by the id of the switches.
        """
        return self._switchs

    @property
    def nodes(self) -> DataFrame:
        """
        The list of nodes of the voltage level, together with their corresponding network element (if any),
        as a dataframe.

        The dataframe includes the following columns:

        - **connectable_id**: Connected element ID, if any.
        - **connectable_type**: Connected element type, if any.

        This dataframe is indexed by the id of the nodes.
        """
        return self._nodes

    @property
    def internal_connections(self) -> DataFrame:
        """
        The list of internal connection of the voltage level, together with the nodes they connect.

        The dataframe includes the following columns:

        - **node1**: node where the internal connection is connected at side 1
        - **node2**: node where the internal connection is connected at side 2
        """
        return self._internal_connections

    def create_graph(self) -> _nx.Graph:
        """
        Representation of the topology as a networkx graph.
        """
        graph = _nx.Graph()
        # build node/edge lists from the column arrays instead of iterrows (which materializes a
        # pandas Series per row)
        nodes = self.nodes
        graph.add_nodes_from(
            (index, {'connectable_id': connectable_id, 'connectable_type': connectable_type})
            for index, connectable_id, connectable_type
            in zip(nodes.index, nodes['connectable_id'], nodes['connectable_type']))
        switches = self._switchs
        graph.add_edges_from(
            (node1, node2, {'id': index, 'name': name, 'kind': kind, 'open': is_open, 'retained': retained})
            for index, node1, node2, name, kind, is_open, retained
            in zip(switches.index, switches['node1'], switches['node2'], switches['name'],
                   switches['kind'], switches['open'], switches['retained']))
        graph.add_edges_from(self._internal_connections[['node1', 'node2']].values.tolist())
        return graph
