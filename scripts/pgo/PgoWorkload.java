/**
 * Copyright (c) 2024, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
import com.powsybl.iidm.network.Network;
import com.powsybl.ieeecdf.converter.IeeeCdfNetworkFactory;
import com.powsybl.dataframe.DataframeElementType;
import com.powsybl.dataframe.DataframeFilter;
import com.powsybl.dataframe.DataframeHandler;
import com.powsybl.dataframe.network.NetworkDataframes;
import com.powsybl.dataframe.network.NetworkDataframeContext;
import com.powsybl.loadflow.LoadFlow;

import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Profile-Guided Optimization (PGO) workload harness.
 *
 * <p>This is NOT part of the shipped library. It is compiled and built into an instrumented
 * native <em>executable</em> only by {@code scripts/build-pgo.sh}, run once to collect a
 * {@code default.iprof} profile, which is then fed to the real {@code --shared} build via
 * {@code native-image --pgo=<profile>}. GraalVM only dumps a profile from an executable's exit
 * path (a {@code --shared} library never dumps one), so we drive the same Java hot paths that the
 * pypowsybl C entry points invoke - dataframe serialization for the main element tables plus an AC
 * load flow - from a {@code main}. PGO matches profiles by method, so those methods are optimized
 * in the shared build; only the thin C-entry-point wrappers stay unmatched.
 *
 * <p>By default it profiles a self-contained IEEE 300-bus network so no data file is needed. Pass a
 * network file path as the first argument to profile against a larger, more representative case
 * (e.g. a PEGASE MATPOWER file) for a production build.
 */
public final class PgoWorkload {

    private PgoWorkload() {
    }

    // Discards all series data; we only care about exercising the mapper/serialization code.
    static final class NoopHandler implements DataframeHandler {
        public void allocate(int seriesCount) { }
        public StringSeriesWriter newStringIndex(String name, int size) { return (i, v) -> { }; }
        public IntSeriesWriter newIntIndex(String name, int size) { return (i, v) -> { }; }
        public StringSeriesWriter newStringSeries(String name, int size) { return (i, v) -> { }; }
        public IntSeriesWriter newIntSeries(String name, int size) { return (i, v) -> { }; }
        public OptionalIntSeriesWriter newOptionalIntSeries(String name, int size) { return (i, v) -> { }; }
        public OptionalDoubleSeriesWriter newOptionalDoubleSeries(String name, int size) { return (i, v) -> { }; }
        public BooleanSeriesWriter newBooleanSeries(String name, int size) { return (i, v) -> { }; }
        public DoubleSeriesWriter newDoubleSeries(String name, int size) { return (i, v) -> { }; }
    }

    private static final DataframeElementType[] TABLES = {
        DataframeElementType.BUS,
        DataframeElementType.GENERATOR,
        DataframeElementType.LOAD,
        DataframeElementType.LINE,
        DataframeElementType.TWO_WINDINGS_TRANSFORMER,
        DataframeElementType.VOLTAGE_LEVEL,
        DataframeElementType.SUBSTATION,
        DataframeElementType.SHUNT_COMPENSATOR,
    };

    public static void main(String[] args) {
        Network net = args.length > 0 ? Network.read(args[0]) : IeeeCdfNetworkFactory.create300();

        // Serialize the big tables many times (matches get_buses / get_generators / get_lines ...).
        for (int r = 0; r < 200; r++) {
            for (DataframeElementType t : TABLES) {
                NetworkDataframes.getDataframeMapper(t)
                    .createDataframe(net, new NoopHandler(), new DataframeFilter(), NetworkDataframeContext.DEFAULT);
            }
        }

        // Numeric-heavy path: a few AC load flows.
        for (int r = 0; r < 5; r++) {
            LoadFlow.find("OpenLoadFlow").run(net);
        }

        System.out.println("PGO workload done");
    }
}
