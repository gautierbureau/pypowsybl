/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.python.dynamic.criteria;

import com.powsybl.commons.PowsyblException;
import com.powsybl.dataframe.SeriesMetadata;
import com.powsybl.dataframe.update.DoubleSeries;
import com.powsybl.dataframe.update.StringSeries;
import com.powsybl.dataframe.update.UpdatingDataframe;
import com.powsybl.dynawo.criteria.Criteria;
import com.powsybl.dynawo.criteria.CriteriaCollection;
import com.powsybl.dynawo.criteria.CriteriaParams;
import com.powsybl.dynawo.criteria.CriteriaParamsVoltageLevel;
import com.powsybl.dynawo.criteria.CriteriaScope;
import com.powsybl.dynawo.criteria.CriteriaType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.powsybl.dataframe.network.adders.SeriesUtils.getRequiredStrings;

/**
 * Builds a {@link CriteriaCollection} from the four dataframes that mirror the CRT model, keyed by criteria
 * id: the criteria themselves (kind, scope, type, active power bounds), the per-criteria voltage bands, the
 * components each watches, and the countries it filters on. It is the Java heart of the pypowsybl criteria
 * binding — the C entry point reads a dataframe array into it — so all the reading, grouping and enum
 * parsing lives here and is tested on its own, without the native image.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public final class CriteriaDataframeAdder {

    // the criteria table (primary), one row per criteria
    private static final String ID = "id";
    private static final String KIND = "kind";
    private static final String SCOPE = "scope";
    private static final String TYPE = "type";
    private static final String P_MIN = "p_min";
    private static final String P_MAX = "p_max";

    // the join key the three secondary tables carry
    private static final String CRITERIA_ID = "criteria_id";

    // the voltage_levels table, the voltage bands of a criteria
    private static final String U_MIN_PU = "u_min_pu";
    private static final String U_MAX_PU = "u_max_pu";
    private static final String U_NOM_MIN = "u_nom_min";
    private static final String U_NOM_MAX = "u_nom_max";

    // the components table, the equipments a criteria watches
    private static final String COMPONENT_ID = "id";
    private static final String VOLTAGE_LEVEL_ID = "voltage_level_id";

    // the countries table
    private static final String COUNTRY = "country";

    private static final List<SeriesMetadata> CRITERIA_METADATA = List.of(
            SeriesMetadata.stringIndex(ID),
            SeriesMetadata.strings(KIND),
            SeriesMetadata.strings(SCOPE),
            SeriesMetadata.strings(TYPE),
            SeriesMetadata.doubles(P_MIN),
            SeriesMetadata.doubles(P_MAX));

    private static final List<SeriesMetadata> VOLTAGE_LEVEL_METADATA = List.of(
            SeriesMetadata.stringIndex(CRITERIA_ID),
            SeriesMetadata.doubles(U_MIN_PU),
            SeriesMetadata.doubles(U_MAX_PU),
            SeriesMetadata.doubles(U_NOM_MIN),
            SeriesMetadata.doubles(U_NOM_MAX));

    private static final List<SeriesMetadata> COMPONENT_METADATA = List.of(
            SeriesMetadata.stringIndex(CRITERIA_ID),
            SeriesMetadata.strings(COMPONENT_ID),
            SeriesMetadata.strings(VOLTAGE_LEVEL_ID));

    private static final List<SeriesMetadata> COUNTRY_METADATA = List.of(
            SeriesMetadata.stringIndex(CRITERIA_ID),
            SeriesMetadata.strings(COUNTRY));

    private static final List<List<SeriesMetadata>> METADATA_LIST =
            List.of(CRITERIA_METADATA, VOLTAGE_LEVEL_METADATA, COMPONENT_METADATA, COUNTRY_METADATA);

    private static final int TABLE_COUNT = 4;

    private CriteriaDataframeAdder() {
    }

    /** The columns of the four tables, in the order the entry point ships their dataframes. */
    public static List<List<SeriesMetadata>> getMetadata() {
        return METADATA_LIST;
    }

    /**
     * Reads the four dataframes into the collection: the secondary tables are grouped by criteria id first,
     * then each row of the primary criteria table builds one {@link Criteria} under its kind, taking its
     * bands, components and countries from the groups.
     */
    public static void addElements(PythonCriteria criteria, List<UpdatingDataframe> dataframes) {
        if (dataframes.size() != TABLE_COUNT) {
            throw new PowsyblException("Expected " + TABLE_COUNT + " criteria dataframes (criteria, voltage "
                    + "levels, components, countries), got " + dataframes.size());
        }
        UpdatingDataframe criteriaDataframe = dataframes.get(0);
        Map<String, List<CriteriaParamsVoltageLevel>> voltageLevels = readVoltageLevels(dataframes.get(1));
        Map<String, List<Criteria.ComponentRef>> components = readComponents(dataframes.get(2));
        Map<String, List<String>> countries = readCountries(dataframes.get(3));

        CriteriaCollection collection = criteria.getCollection();
        if (criteriaDataframe == null || criteriaDataframe.getRowCount() == 0) {
            return;
        }
        StringSeries ids = getRequiredStrings(criteriaDataframe, ID);
        StringSeries kinds = getRequiredStrings(criteriaDataframe, KIND);
        StringSeries scopes = getRequiredStrings(criteriaDataframe, SCOPE);
        StringSeries types = getRequiredStrings(criteriaDataframe, TYPE);
        DoubleSeries pMin = criteriaDataframe.getDoubles(P_MIN);
        DoubleSeries pMax = criteriaDataframe.getDoubles(P_MAX);

        for (int row = 0; row < criteriaDataframe.getRowCount(); row++) {
            String id = ids.get(row);
            CriteriaParams.Builder params = CriteriaParams.builder()
                    .id(id)
                    .scope(parseEnum(CriteriaScope.class, scopes.get(row), SCOPE))
                    .type(parseEnum(CriteriaType.class, types.get(row), TYPE));
            if (hasValue(pMin, row)) {
                params.pMin(pMin.get(row));
            }
            if (hasValue(pMax, row)) {
                params.pMax(pMax.get(row));
            }
            voltageLevels.getOrDefault(id, List.of()).forEach(params::voltageLevel);

            Criteria.Builder builder = Criteria.builder().params(params.build());
            for (Criteria.ComponentRef component : components.getOrDefault(id, List.of())) {
                component.getVoltageLevelId().ifPresentOrElse(
                        voltageLevelId -> builder.component(component.id(), voltageLevelId),
                        () -> builder.component(component.id()));
            }
            countries.getOrDefault(id, List.of()).forEach(builder::country);

            collection.add(parseEnum(CriteriaCollection.Type.class, kinds.get(row), KIND), builder.build());
        }
    }

    private static Map<String, List<CriteriaParamsVoltageLevel>> readVoltageLevels(UpdatingDataframe dataframe) {
        Map<String, List<CriteriaParamsVoltageLevel>> byCriteria = new LinkedHashMap<>();
        if (dataframe == null || dataframe.getRowCount() == 0) {
            return byCriteria;
        }
        StringSeries criteriaIds = getRequiredStrings(dataframe, CRITERIA_ID);
        DoubleSeries uMinPu = dataframe.getDoubles(U_MIN_PU);
        DoubleSeries uMaxPu = dataframe.getDoubles(U_MAX_PU);
        DoubleSeries uNomMin = dataframe.getDoubles(U_NOM_MIN);
        DoubleSeries uNomMax = dataframe.getDoubles(U_NOM_MAX);
        for (int row = 0; row < dataframe.getRowCount(); row++) {
            CriteriaParamsVoltageLevel.Builder band = CriteriaParamsVoltageLevel.builder();
            if (hasValue(uMinPu, row)) {
                band.uMinPu(uMinPu.get(row));
            }
            if (hasValue(uMaxPu, row)) {
                band.uMaxPu(uMaxPu.get(row));
            }
            if (hasValue(uNomMin, row)) {
                band.uNomMin(uNomMin.get(row));
            }
            if (hasValue(uNomMax, row)) {
                band.uNomMax(uNomMax.get(row));
            }
            byCriteria.computeIfAbsent(criteriaIds.get(row), k -> new ArrayList<>()).add(band.build());
        }
        return byCriteria;
    }

    private static Map<String, List<Criteria.ComponentRef>> readComponents(UpdatingDataframe dataframe) {
        Map<String, List<Criteria.ComponentRef>> byCriteria = new LinkedHashMap<>();
        if (dataframe == null || dataframe.getRowCount() == 0) {
            return byCriteria;
        }
        StringSeries criteriaIds = getRequiredStrings(dataframe, CRITERIA_ID);
        StringSeries componentIds = getRequiredStrings(dataframe, COMPONENT_ID);
        StringSeries voltageLevelIds = dataframe.getStrings(VOLTAGE_LEVEL_ID);
        for (int row = 0; row < dataframe.getRowCount(); row++) {
            String voltageLevelId = voltageLevelIds == null ? null : emptyToNull(voltageLevelIds.get(row));
            byCriteria.computeIfAbsent(criteriaIds.get(row), k -> new ArrayList<>())
                    .add(new Criteria.ComponentRef(componentIds.get(row), voltageLevelId));
        }
        return byCriteria;
    }

    private static Map<String, List<String>> readCountries(UpdatingDataframe dataframe) {
        Map<String, List<String>> byCriteria = new LinkedHashMap<>();
        if (dataframe == null || dataframe.getRowCount() == 0) {
            return byCriteria;
        }
        StringSeries criteriaIds = getRequiredStrings(dataframe, CRITERIA_ID);
        StringSeries countries = getRequiredStrings(dataframe, COUNTRY);
        for (int row = 0; row < dataframe.getRowCount(); row++) {
            byCriteria.computeIfAbsent(criteriaIds.get(row), k -> new ArrayList<>()).add(countries.get(row));
        }
        return byCriteria;
    }

    /** A double cell is set when its column was supplied and the cell is not the {@code NaN} that marks unset. */
    private static boolean hasValue(DoubleSeries series, int row) {
        return series != null && !Double.isNaN(series.get(row));
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> enumClass, String value, String column) {
        try {
            return Enum.valueOf(enumClass, value);
        } catch (IllegalArgumentException e) {
            throw new PowsyblException("Unknown " + column + " '" + value + "', expected one of "
                    + List.of(enumClass.getEnumConstants()));
        }
    }
}
