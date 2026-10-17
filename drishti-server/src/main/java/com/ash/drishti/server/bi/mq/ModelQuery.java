/*
 * Project Drishti · Any data. Any domain. One grammar.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL.
 *
 * This file is the confidential and proprietary property of Ashutosh Sinha.
 * Unauthorised copying, use, modification, distribution or disclosure of this
 * file, via any medium, is strictly prohibited except with the express prior
 * written permission of the copyright holder.
 *
 * See the LICENSE file in the root of this repository for the full terms.
 */
package com.ash.drishti.server.bi.mq;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A model query, {@code mq: 1} (docs/architecture/BI_MODEL_SPEC.md section 5.1): the one request for data that every consumer sends. YAML or JSON with the
 * same keys; {@link MqParser} reads it, {@link MqValidator} checks it against the model, {@link MqCompiler} turns it into the engine's requests. Immutable.
 *
 * @param model the model reference: a name or catalogue id, {@code @major} or {@code @exact}; {@code dataset:<name>} for an implicit model
 * @param define local measures: name to expression (a name may not shadow a model measure)
 * @param fields what to group by: {@code alias.column[.part]} or a stable id
 * @param measures model or {@code define} measures, by name or id
 * @param where row filters, ANDed
 * @param having filters on measures, after aggregation
 * @param shape {@code grouped} (the default) or {@code rows} (an extract: no measures)
 * @param params what-if and field parameter values ({@code mq: 2}, wave B): parameter name to value
 * @param ext the other keys of {@code mq: 2}: calculation groups applied, the perspective, the budget
 */
public record ModelQuery(int mq, String model, Map<String, String> define, List<String> fields, List<String> measures, List<Where> where, List<Having> having,
        Time time, List<Sort> sort, Integer limit, Totals totals, Drill drill, String shape, Map<String, Object> params, Options options, Ext ext) {

    public static final String GROUPED = "grouped";
    public static final String ROWS = "rows";

    /**
     * The keys {@code mq: 2} adds (docs/architecture/BI_DESIGN.md section 3.6).
     *
     * @param calc calculation group to the item applied to the selected measures ({@code { Time: YTD }})
     * @param perspective the perspective whose fields and measures the query may name (names outside it are {@code DRS-9153}), or null
     * @param budget the most time and rows the query may take ({@code options.budget}); null values: the work class's
     */
    public record Ext(Map<String, String> calc, String perspective, Budget budget) {
        public static final Ext NONE = new Ext(Map.of(), null, Budget.NONE);

        public Ext {
            calc = Collections.unmodifiableMap(new LinkedHashMap<>(calc == null ? Map.of() : calc));
            budget = budget == null ? Budget.NONE : budget;
        }

        public boolean empty() {
            return calc.isEmpty() && perspective == null && budget.equals(Budget.NONE);
        }
    }

    /** {@code options.budget}: milliseconds and estimated rows read; null for the work class's default. */
    public record Budget(Long millis, Long rows) {
        public static final Budget NONE = new Budget(null, null);
    }

    public ModelQuery {
        define = Collections.unmodifiableMap(new LinkedHashMap<>(define));
        fields = List.copyOf(fields);
        measures = List.copyOf(measures);
        where = List.copyOf(where);
        having = List.copyOf(having);
        sort = List.copyOf(sort);
        params = Collections.unmodifiableMap(new LinkedHashMap<>(params));
        ext = ext == null ? Ext.NONE : ext;
    }

    /** A query of {@code mq: 1}'s keys (no calculation groups, perspective or budget). */
    public ModelQuery(int mq, String model, Map<String, String> define, List<String> fields, List<String> measures, List<Where> where, List<Having> having, Time time,
            List<Sort> sort, Integer limit, Totals totals, Drill drill, String shape, Map<String, Object> params, Options options) {
        this(mq, model, define, fields, measures, where, having, time, sort, limit, totals, drill, shape, params, options, Ext.NONE);
    }

    /** The same query against another model reference (the model of {@code POST /models/{id}/query}). */
    public ModelQuery withModel(String ref) {
        return new ModelQuery(mq, ref, define, fields, measures, where, having, time, sort, limit, totals, drill, shape, params, options, ext);
    }

    /** The same query with other fields, filters and limit (what step 4 and step 5 of the compiler produce). */
    public ModelQuery with(List<String> fields, List<Where> where, Integer limit) {
        return new ModelQuery(mq, model, define, fields, measures, where, having, time, sort, limit, totals, null, shape, params, options, ext);
    }

    /** The same query with other fields and measures (field parameters resolved, calculation groups bound). */
    public ModelQuery withSelect(List<String> fields, List<String> measures) {
        return new ModelQuery(mq, model, define, fields, measures, where, having, time, sort, limit, totals, drill, shape, params, options, ext);
    }

    public boolean rows() {
        return ROWS.equals(shape);
    }

    /**
     * A row filter. {@code op} is one of the engine's comparisons, or {@code relative} ({@code window} a relative-date name) or {@code top} ({@code n} values with
     * the highest ({@code dir: desc}) or lowest {@code by} measure).
     */
    public record Where(String field, String op, List<Object> values, String window, Integer n, String by, String dir) {

        public Where {
            values = values == null ? List.of() : Collections.unmodifiableList(new java.util.ArrayList<>(values));
        }

        public static Where of(String field, String op, List<Object> values) {
            return new Where(field, op, values, null, null, null, null);
        }
    }

    /** A filter on a measure. */
    public record Having(String measure, String op, List<Object> values) {

        public Having {
            values = List.copyOf(values);
        }
    }

    /** Exactly one of: an as-of business date, a date window ({@code from}..{@code to}), a sliding window ({@code last}), or the live state. All null: the default. */
    public record Time(LocalDate asOf, LocalDate from, LocalDate to, Duration last, boolean live) {

        public static final Time DEFAULT = new Time(null, null, null, null, false);
    }

    public record Sort(String by, boolean desc) {}

    /** {@code grand}, {@code subtotals} or explicit {@code levels} (each a subset of the fields). */
    public record Totals(String mode, List<List<String>> levels) {

        public Totals {
            levels = levels == null ? List.of() : levels.stream().map(List::copyOf).toList();
        }
    }

    /** A drill-down position: a hierarchy of the model and the values clicked so far. */
    public record Drill(String hierarchy, List<String> path) {

        public Drill {
            path = List.copyOf(path);
        }
    }

    /** {@code layer} (auto, columns, rollup, direct), {@code explain} (the SQL, for authors), {@code format} (json, arrow). */
    public record Options(String layer, boolean explain, String format) {

        public static final Options DEFAULT = new Options("auto", false, "json");
    }
}
