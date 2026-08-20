/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.python.dynamic.criteria;

import com.powsybl.dataframe.update.DefaultUpdatingDataframe;
import com.powsybl.dataframe.update.DoubleSeries;
import com.powsybl.dataframe.update.StringSeries;
import com.powsybl.dataframe.update.UpdatingDataframe;
import com.powsybl.dynawo.criteria.Criteria;
import com.powsybl.dynawo.criteria.CriteriaCollection;
import com.powsybl.dynawo.criteria.CriteriaParams;
import com.powsybl.dynawo.criteria.CriteriaParamsVoltageLevel;
import com.powsybl.dynawo.criteria.CriteriaScope;
import com.powsybl.dynawo.criteria.CriteriaType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The criteria adder over the four dataframes on its own, without the native image: a generator criteria
 * (summed active power bounds, a full voltage band, two components one of which names its voltage level, two
 * countries) and a bus criteria (a single lower voltage bound, one component, no country) built into a
 * {@link CriteriaCollection}, each under its kind.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class CriteriaDataframeAdderTest {

    @Test
    void itBuildsTheCollectionFromTheFourDataframes() {
        PythonCriteria criteria = new PythonCriteria();
        CriteriaDataframeAdder.addElements(criteria, List.of(
                criteriaTable(), voltageLevelTable(), componentTable(), countryTable()));

        CriteriaCollection collection = criteria.getCollection();
        assertTrue(collection.getCriteria(CriteriaCollection.Type.LOAD).isEmpty(), "no load criteria was described");

        // the generator criteria: summed active power between 100 and 500, a full voltage band, its two
        // machines (the second without a voltage level), and the two countries it filters on
        List<Criteria> generators = collection.getCriteria(CriteriaCollection.Type.GENERATOR);
        assertEquals(1, generators.size());
        Criteria generator = generators.get(0);
        CriteriaParams generatorParams = generator.getParams();
        assertEquals("GEN_CRIT", generatorParams.getId());
        assertEquals(CriteriaScope.FINAL, generatorParams.getScope());
        assertEquals(CriteriaType.SUM, generatorParams.getType());
        assertEquals(OptionalDouble.of(100.0), generatorParams.getPMin());
        assertEquals(OptionalDouble.of(500.0), generatorParams.getPMax());
        assertEquals(1, generatorParams.getVoltageLevels().size());
        CriteriaParamsVoltageLevel band = generatorParams.getVoltageLevels().get(0);
        assertEquals(OptionalDouble.of(0.8), band.getUMinPu());
        assertEquals(OptionalDouble.of(1.2), band.getUMaxPu());
        assertEquals(OptionalDouble.of(90.0), band.getUNomMin());
        assertEquals(OptionalDouble.of(110.0), band.getUNomMax());
        assertEquals(List.of("G1", "G2"), generator.getComponents().stream().map(Criteria.ComponentRef::id).toList());
        assertEquals("VL1", generator.getComponents().get(0).getVoltageLevelId().orElseThrow());
        assertTrue(generator.getComponents().get(1).getVoltageLevelId().isEmpty(), "G2 names no voltage level");
        assertEquals(List.of("FR", "BE"), generator.getCountries());

        // the bus criteria: a single lower voltage bound, its one bus, and no country filter
        List<Criteria> buses = collection.getCriteria(CriteriaCollection.Type.BUS);
        assertEquals(1, buses.size());
        Criteria bus = buses.get(0);
        assertEquals(CriteriaScope.DYNAMIC, bus.getParams().getScope());
        assertEquals(CriteriaType.LOCAL_VALUE, bus.getParams().getType());
        assertEquals(OptionalDouble.empty(), bus.getParams().getPMin());
        assertEquals(OptionalDouble.empty(), bus.getParams().getPMax());
        CriteriaParamsVoltageLevel busBand = bus.getParams().getVoltageLevels().get(0);
        assertEquals(OptionalDouble.of(0.9), busBand.getUMinPu());
        assertEquals(OptionalDouble.empty(), busBand.getUMaxPu());
        assertEquals(List.of("B1"), bus.getComponents().stream().map(Criteria.ComponentRef::id).toList());
        assertEquals("VL2", bus.getComponents().get(0).getVoltageLevelId().orElseThrow());
        assertTrue(bus.getCountries().isEmpty(), "the bus criteria filters on no country");
    }

    private static UpdatingDataframe criteriaTable() {
        DefaultUpdatingDataframe dataframe = new DefaultUpdatingDataframe(2);
        dataframe.addSeries("id", true, strings("GEN_CRIT", "BUS_CRIT"));
        dataframe.addSeries("kind", false, strings("GENERATOR", "BUS"));
        dataframe.addSeries("scope", false, strings("FINAL", "DYNAMIC"));
        dataframe.addSeries("type", false, strings("SUM", "LOCAL_VALUE"));
        dataframe.addSeries("p_min", false, doubles(100.0, Double.NaN));
        dataframe.addSeries("p_max", false, doubles(500.0, Double.NaN));
        return dataframe;
    }

    private static UpdatingDataframe voltageLevelTable() {
        DefaultUpdatingDataframe dataframe = new DefaultUpdatingDataframe(2);
        dataframe.addSeries("criteria_id", true, strings("GEN_CRIT", "BUS_CRIT"));
        dataframe.addSeries("u_min_pu", false, doubles(0.8, 0.9));
        dataframe.addSeries("u_max_pu", false, doubles(1.2, Double.NaN));
        dataframe.addSeries("u_nom_min", false, doubles(90.0, Double.NaN));
        dataframe.addSeries("u_nom_max", false, doubles(110.0, Double.NaN));
        return dataframe;
    }

    private static UpdatingDataframe componentTable() {
        DefaultUpdatingDataframe dataframe = new DefaultUpdatingDataframe(3);
        dataframe.addSeries("criteria_id", true, strings("GEN_CRIT", "GEN_CRIT", "BUS_CRIT"));
        dataframe.addSeries("id", false, strings("G1", "G2", "B1"));
        // G2 leaves its voltage level unset (empty cell), G1 and B1 name theirs
        dataframe.addSeries("voltage_level_id", false, strings("VL1", "", "VL2"));
        return dataframe;
    }

    private static UpdatingDataframe countryTable() {
        DefaultUpdatingDataframe dataframe = new DefaultUpdatingDataframe(2);
        dataframe.addSeries("criteria_id", true, strings("GEN_CRIT", "GEN_CRIT"));
        dataframe.addSeries("country", false, strings("FR", "BE"));
        return dataframe;
    }

    private static StringSeries strings(String... values) {
        return index -> values[index];
    }

    private static DoubleSeries doubles(double... values) {
        return index -> values[index];
    }
}
