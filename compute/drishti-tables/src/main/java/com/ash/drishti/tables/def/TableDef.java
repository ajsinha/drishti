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
package com.ash.drishti.tables.def;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A virtual table (HOW_IT_FITS.md section A4; HOW_IT_FITS.md §A28 section 3.1): a named, typed table over any kind any connector serves,
 * a lake table, a SQL query, a Python transform, a model's answer (a summary table, Q13) or another virtual table. Its columns are scalar
 * Rachana paths or row-scope expressions over the source ({@code $} the entity, {@code @} the grain's element, {@code ^} the element's
 * parent); its rules and masks are on the table and apply to every reader (Q9).
 *
 * @param name {@code <domain>.<name>} (Q11)
 * @param was the name it had before, resolved with a warning for one release (Q18), or null
 * @param version semver, {@code 1.0.0} when not given
 * @param about the descriptive keys kept as written: {@code id}, {@code title}, {@code description}, {@code owner}, {@code sample},
 *     {@code endorsement}, {@code tags}, {@code ai}, {@code layout}
 * @param from where the rows come from
 * @param grain the path whose elements are the rows ({@code $.LEGS[*]}), or null for one row per entity
 * @param filter a row-scope condition over the source, or null
 * @param columns the columns in the order written
 * @param keys the columns that identify a row (with {@code business_date})
 * @param keepLatest the column whose greatest value wins among rows with the same keys ({@code keep: { latest: V }}), or null
 * @param says what one row is, in words ({@code says:}), or null
 * @param materialize how the rows are kept (none, on-load, live)
 * @param asOf the as-of rule ({@code as-of:}), or null
 * @param retention how many business dates are kept ({@code keep:} as a number or a period), or null
 * @param refresh the refresh keys as written ({@code on-load}, {@code schedule}), never null
 * @param rules the row rules, applied to every reader of the table
 * @param hiddenFor roles that see none of the table's rows, never null
 * @param tests the data tests as written ({@code unique}, {@code not-null}, {@code references-exist}, {@code agg})
 */
public record TableDef(String name, String was, String version, Map<String, Object> about, Source from, String grain, String filter,
        Map<String, ColumnDef> columns, List<String> keys, String keepLatest, String says, Materialize materialize, String asOf, String retention,
        Map<String, Object> refresh, List<Rule> rules, List<String> hiddenFor, List<Map<String, Object>> tests) {

    /** The columns every table has without declaring them (Q12): the lake's names. */
    public static final Set<String> BUILT_INS = Set.of("id", "business_date", "index", "update_seq", "updated_at");

    /** The built-ins a live table adds. */
    public static final Set<String> LIVE_BUILT_INS = Set.of("update_seq", "updated_at");

    public TableDef {
        about = about == null ? Map.of() : Map.copyOf(about);
        columns = columns == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(columns));
        keys = keys == null ? List.of() : List.copyOf(keys);
        refresh = refresh == null ? Map.of() : Map.copyOf(refresh);
        rules = rules == null ? List.of() : List.copyOf(rules);
        hiddenFor = hiddenFor == null ? List.of() : List.copyOf(hiddenFor);
        tests = tests == null ? List.of() : List.copyOf(tests);
        version = version == null ? "1.0.0" : version;
        materialize = materialize == null ? Materialize.NONE : materialize;
    }

    /** The domain ({@code trading} of {@code trading.trade_legs}). */
    public String domain() {
        return TableName.domain(name);
    }

    /** The major version ({@code 1} of {@code 1.2.0}); 1 when it cannot be read. */
    public int major() {
        try {
            return Integer.parseInt(version.split("\\.")[0]);
        } catch (RuntimeException e) {
            return 1;
        }
    }

    /** How many lists deep the grain goes: 0 (one row per entity), 1 ({@code $.LEGS[*]}), 2 ({@code $.LEGS[*].CASH_FLOWS[*]}). */
    public int depth() {
        return grain == null ? 0 : (grain.length() - grain.replace("[*]", "").length()) / 3;
    }

    /** True when a column of this name is masked for readers without the raw power. */
    public boolean masked(String column) {
        ColumnDef c = columns.get(column);
        return c != null && c.masked();
    }

    /**
     * Where the rows come from (HOW_IT_FITS.md §A28 section 3.1, {@code from:}). Exactly one form is set.
     *
     * @param kind an entity kind any connector serves, or null
     * @param pack the pack that defines the kind, or null
     * @param connector the connector to read it through, or null for the kind's own
     * @param lake a lake's name ({@code { lake: demo, table: trading.trade_endur }}), or null
     * @param table a virtual table read as the source (a table over a table), or the lake's table when {@code lake} is set, or null
     * @param query SQL for a SQL-capable connector, or null
     * @param params the query's or Python's parameters as written
     * @param python a Python transform's item, or null
     * @param inputs the Python transform's inputs (model queries asked as the reader) as written
     * @param model a model whose answer is the rows (a summary table, Q13), or null
     * @param modelQuery the model query as written
     * @param run a promoted run's snapshot whose rows the table is ({@code from: { run: <name>, params: { version, token } }}), or null
     */
    public record Source(String kind, String pack, String connector, String lake, String table, String query, Map<String, Object> params,
            String python, Map<String, Object> inputs, String model, Map<String, Object> modelQuery, String run) {

        /** The source's form, by the keys set. */
        public enum Form { KIND, LAKE, TABLE, SQL, PYTHON, MODEL, RUN, NONE }

        public Source {
            params = params == null ? Map.of() : params;
            inputs = inputs == null ? Map.of() : inputs;
            modelQuery = modelQuery == null ? Map.of() : modelQuery;
        }

        /** Every form but a run's snapshot. */
        public Source(String kind, String pack, String connector, String lake, String table, String query, Map<String, Object> params,
                String python, Map<String, Object> inputs, String model, Map<String, Object> modelQuery) {
            this(kind, pack, connector, lake, table, query, params, python, inputs, model, modelQuery, null);
        }

        /** A promoted run's snapshot: its name, the version promoted and the token that binds it to this table. */
        public static Source ofRun(String name, Object version, String token) {
            Map<String, Object> p = new java.util.LinkedHashMap<>();
            p.put("version", version);
            p.put("token", token);
            return new Source(null, null, null, null, null, null, p, null, null, null, null, name);
        }

        public static Source ofKind(String kind) {
            return new Source(kind, null, null, null, null, null, null, null, null, null, null);
        }

        public Form form() {
            if (run != null) {
                return Form.RUN;
            }
            if (model != null) {
                return Form.MODEL;
            }
            if (python != null) {
                return Form.PYTHON;
            }
            if (query != null) {
                return Form.SQL;
            }
            if (lake != null) {
                return Form.LAKE;
            }
            if (table != null) {
                return Form.TABLE;
            }
            return kind != null ? Form.KIND : Form.NONE;
        }
    }

    /**
     * How a table's rows are kept (HOW_IT_FITS.md section A6 as amended by Q14).
     *
     * @param mode {@code none} (read on demand), {@code on-load} (Parquet per business date, rebuilt on the data-load signal), {@code live}
     *     (in memory, maintained by key from what the source pushes)
     * @param rows a live table's most rows, or null
     * @param age a live table's oldest row ({@code 1h}, {@code 2d}), or null
     */
    public record Materialize(String mode, Integer rows, String age) {

        public static final String MODE_NONE = "none";
        public static final String MODE_ON_LOAD = "on-load";
        public static final String MODE_LIVE = "live";
        public static final Set<String> MODES = Set.of(MODE_NONE, MODE_ON_LOAD, MODE_LIVE);

        public static final Materialize NONE = new Materialize(MODE_NONE, null, null);
        public static final Materialize ON_LOAD = new Materialize(MODE_ON_LOAD, null, null);

        public boolean live() {
            return MODE_LIVE.equals(mode);
        }
    }

    /**
     * A row rule (Q9): the roles it is for and the rule-scope condition a row must meet ({@code $.DESK in user.desks}).
     *
     * @param roles the roles; a reader with several roles sees the rows any of their rules allows
     * @param where the condition ({@code true} for every row)
     */
    public record Rule(List<String> roles, String where) {
        public Rule {
            roles = roles == null ? List.of() : List.copyOf(roles);
        }
    }
}
