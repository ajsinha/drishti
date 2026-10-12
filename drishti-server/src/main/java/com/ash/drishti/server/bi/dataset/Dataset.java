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
package com.ash.drishti.server.bi.dataset;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A parsed dataset, the semantic model of RUPAKA.md section 6 (immutable). Tables come from pack kinds by connector logical name, columns carry
 * a document path and a type, relationships join tables, measures and calculated columns are Rachana-EL extended with aggregation, security
 * holds row rules per role and masked columns. Produced by {@link DatasetParser}; the YAML reference is docs/guides/DATASETS.md.
 *
 * @param name the {@code dataset:} name (the item's id is its catalogue id)
 * @param version the author's version number
 * @param title title for the catalogue
 * @param description what the model is for
 * @param sample true for sample content (hidden from business users when sample packs are)
 * @param engine the analytic engine, {@code duckdb} by default
 * @param tables by table name, in file order
 * @param relationships the joins
 * @param measures by measure name, in file order
 * @param hierarchies by hierarchy name: its levels, each {@code table.column} or {@code table.column.part}
 * @param security row rules and masked columns
 * @param rollups the pre-aggregations the author asks for
 * @param refresh when the dataset's layers are rebuilt without a person asking (blank schedule: only by a data-load signal or on demand)
 * @param compose for a composed dataset: the sources it reads as the reader and how they are joined (its {@code tables} then hold the one joined frame); else null
 * @param derived for a derived dataset: the Python Transform that makes its table and the inputs it is given; else null
 * @param tests expected values Check and publishing run ({@link ExpectedValue}); empty when the dataset declares none
 */
public record Dataset(String name, int version, String title, String description, boolean sample, String engine, Map<String, Table> tables,
        List<Relationship> relationships, Map<String, Measure> measures, Map<String, List<String>> hierarchies, Security security, List<Rollup> rollups, Refresh refresh,
        Compose compose, Derived derived, List<ExpectedValue> tests) {

    /** A dataset without tests. */
    public Dataset(String name, int version, String title, String description, boolean sample, String engine, Map<String, Table> tables, List<Relationship> relationships,
            Map<String, Measure> measures, Map<String, List<String>> hierarchies, Security security, List<Rollup> rollups, Refresh refresh, Compose compose, Derived derived) {
        this(name, version, title, description, sample, engine, tables, relationships, measures, hierarchies, security, rollups, refresh, compose, derived, List.of());
    }

    /** An ordinary dataset: tables from pack kinds, neither composed nor derived. */
    public Dataset(String name, int version, String title, String description, boolean sample, String engine, Map<String, Table> tables, List<Relationship> relationships,
            Map<String, Measure> measures, Map<String, List<String>> hierarchies, Security security, List<Rollup> rollups, Refresh refresh) {
        this(name, version, title, description, sample, engine, tables, relationships, measures, hierarchies, security, rollups, refresh, null, null);
    }

    /** The mode and the source kind of the one table of a composed or derived dataset: its rows are a frame the engine builds, not a connector's. */
    public static final String FRAME = "frame";
    /** The name of the one table a composed dataset has. */
    public static final String JOINED = "joined";
    /** The name of the one table a derived dataset has. */
    public static final String RESULT = "result";

    public boolean composed() {
        return compose != null;
    }

    public boolean derivedFrame() {
        return derived != null;
    }

    /** True when the dataset's table is a frame (composed or derived), not rows read from a connector. */
    public boolean framed() {
        return compose != null || derived != null;
    }

    public static final Set<String> TYPES = Set.of("string", "integer", "decimal", "boolean", "date", "timestamp");
    public static final Set<String> MODES = Set.of("live", "cached", "direct");
    /** The mode of the table the engine makes from a live table's history. */
    public static final String HISTORY = "history";
    /** The column of a history table: when the sample was taken. */
    public static final String TS = "ts";
    public static final Set<String> CARDINALITIES = Set.of("many-to-one", "one-to-many", "one-to-one", "many-to-many");
    /** The column every table has: the entity's id. */
    public static final String ID = "id";
    /** The column every table has: the business date the row is for. */
    public static final String DATE = "businessDate";
    /** The column of a table over an array ({@code each}): the element's position in the array, from 0. */
    public static final String INDEX = "index";
    /** The column every live table has: the order in which rows last changed (a counter that only grows), for "what changed since". */
    public static final String SEQ = "updateSeq";
    /** The column every live table has: when the row last changed. */
    public static final String UPDATED = "updatedAt";

    /**
     * A table.
     *
     * @param connector the connector's logical name ({@code config/connectors/<name>.yaml}); empty means whichever connector serves the kind
     * @param kind the pack kind
     * @param each a path to an array inside each document ({@code legs}, {@code schedule}): the table then has one row per element, with {@code id} the
     *     parent entity's id and {@code index} the element's position; null for one row per entity
     * @param mode {@code live}, {@code cached} or {@code direct}
     * @param onLoad rebuild the affected business date when a data-load signal for the kind arrives
     * @param schedule cron text, stored (not scheduled: see RUPAKA.md section 20.2)
     * @param columns by column name, in file order
     * @param live for {@code mode: live}: how many rows to keep and for how long (null: the server's defaults)
     * @param of for a history table ({@code mode: history}): the live table whose measures it samples; else null
     */
    public record Table(String name, String connector, String kind, String each, String mode, boolean onLoad, String schedule, String description, Map<String, Column> columns,
            Live live, String of) {

        public Table(String name, String connector, String kind, String each, String mode, boolean onLoad, String schedule, String description, Map<String, Column> columns,
                Live live) {
            this(name, connector, kind, each, mode, onLoad, schedule, description, columns, live, null);
        }

        public Table(String name, String connector, String kind, String each, String mode, boolean onLoad, String schedule, String description, Map<String, Column> columns) {
            this(name, connector, kind, each, mode, onLoad, schedule, description, columns, null, null);
        }

        /** True for the table the engine makes from a live table's {@code history:}: samples of its measures over time (the live table it belongs to is {@code of}). */
        public boolean isHistory() {
            return HISTORY.equals(mode);
        }

        public boolean isLive() {
            return "live".equals(mode);
        }

        /** The columns read from the source (everything but calculated ones). */
        public List<Column> physical() {
            return columns.values().stream().filter(c -> c.expr() == null).toList();
        }
    }

    /**
     * What a live table keeps (RUPAKA.md section 12): at most {@code rows} rows (the oldest updated go first) and none that has not changed for {@code age}.
     * Either may be null: the server's {@code drishti.bi.live.max-rows} and no age limit.
     */
    public record Live(Integer rows, java.time.Duration age, History history) {

        public Live(Integer rows, java.time.Duration age) {
            this(rows, age, null);
        }
    }

    /**
     * A live table's history (docs/guides/LIVE_BI.md, "History"): every {@code every} the listed measures are worked out over the live table, grouped by
     * {@code by}, and appended to a bounded in-memory table queryable as the dataset table {@code table}; the newest {@code keep} samples are kept.
     *
     * @param table the name of the history table in the dataset ({@code history} when not given)
     * @param every the time between samples
     * @param keep samples kept (the oldest go first)
     * @param by columns of the live table the samples are grouped by (none: one row per sample)
     * @param measures the measures of the dataset that are sampled (they aggregate the live table only; no window or time-intelligence measure)
     */
    public record History(String table, java.time.Duration every, int keep, List<String> by, List<String> measures) {

        public History {
            by = by == null ? List.of() : List.copyOf(by);
            measures = measures == null ? List.of() : List.copyOf(measures);
        }
    }

    /**
     * A column.
     *
     * @param path the document path without {@code $.}; the name when omitted
     * @param type one of {@link #TYPES}
     * @param format a format name of the pack (display only)
     * @param key true for the column relationships join on
     * @param expr a row-level expression for a calculated column, else null
     */
    public record Column(String name, String path, String type, String format, boolean key, String expr, String description) {}

    /**
     * A relationship, {@code from} the many side, {@code to} the one side for {@code many-to-one}.
     *
     * @param direction {@code single} (filters flow from the one side to the many side) or {@code both}; stored, queries use the join as written
     * @param active an inactive relationship is not joined by a query
     */
    public record Relationship(String fromTable, String fromColumn, String toTable, String toColumn, String cardinality, String direction, boolean active) {

        public String label() {
            return fromTable + "." + fromColumn + " -> " + toTable + "." + toColumn;
        }
    }

    /**
     * A measure: an aggregate expression, or a Python measure (RUPAKA.md section 20.8): {@code python} names a Python item, {@code inputs} the columns its
     * function reads and {@code by} the columns it is grouped by (all {@code table.column}). The query engine never runs one: the console's runner does.
     */
    public record Measure(String name, String expr, String format, String description, String python, List<String> inputs, List<String> by) {

        public Measure {
            inputs = inputs == null ? List.of() : List.copyOf(inputs);
            by = by == null ? List.of() : List.copyOf(by);
        }

        public Measure(String name, String expr, String format, String description, String python) {
            this(name, expr, format, description, python, List.of(), List.of());
        }
    }

    /** Row rules and masked columns. */
    public record Security(List<RowRule> rows, List<String> masked) {

        public static final Security NONE = new Security(List.of(), List.of());
    }

    /** A row rule: readers holding {@code role} see the rows where {@code where} is true. */
    public record RowRule(String role, String where) {}

    /**
     * Scheduled refresh (RUPAKA.md section 7): the text is a schedule as the distribution subscriptions say it ({@code daily 06:00}, {@code business-days 07:30},
     * {@code weekly mon 07:00}, {@code monthly last 18:00 business}, {@code hourly}, {@code cron <six fields>} or a five-field cron), or {@code after-load [kind]}:
     * not a time but the data load that finished ready.
     */
    public record Refresh(String schedule) {

        public static final Refresh NONE = new Refresh("");

        public boolean scheduled() {
            return schedule != null && !schedule.isBlank();
        }
    }

    /**
     * A rollup: the aggregates of {@code measures} grouped by {@code by} (columns of {@code table}) per business date.
     */
    public record Rollup(String name, String table, List<String> by, List<String> measures) {}

    /** The tables served by this connector or kind, for invalidation on a data-load signal. */
    public List<Table> tablesOfKind(String kind) {
        return tables.values().stream().filter(t -> t.kind().equals(kind)).toList();
    }

    /**
     * One input of a composed dataset or a Python Transform: another dataset asked a question as the reader. {@code by} are the columns the answer is grouped by
     * (each {@code table.column}, optionally {@code table.column as name}), {@code measures} the measures computed ({@code Name} or {@code Name as other}),
     * {@code fields} the columns of a row-level extract instead (no measures), {@code filters} extra conditions of the same form a report's filter has, and
     * {@code key} (optional) the frame names of the columns that identify one row, when the others are attributes of them (a counterparty's name, say): the
     * fan-out check then takes the answer to have one row per key, not per combination of every grouping column.
     *
     * @param alias the name the join and the frame use
     * @param dataset the dataset: a catalogue id, a file name or the dataset's own name
     */
    public record Input(String alias, String dataset, List<Named> by, List<Named> measures, List<Named> fields, List<InputFilter> filters, List<String> key) {

        /** An input with no declared key: its grouping columns together identify a row. */
        public Input(String alias, String dataset, List<Named> by, List<Named> measures, List<Named> fields, List<InputFilter> filters) {
            this(alias, dataset, by, measures, fields, filters, List.of());
        }

        public Input {
            key = List.copyOf(key);
            by = List.copyOf(by);
            measures = List.copyOf(measures);
            fields = List.copyOf(fields);
            filters = List.copyOf(filters);
        }

        /** True for a row-level extract ({@code fields}), false for a grouped answer. */
        public boolean rows() {
            return !fields.isEmpty();
        }
    }

    /** A column or measure of a source and the name it has in the frame. */
    public record Named(String source, String as) {}

    /** A filter on a source, of the dataset query's form: {@code field}, {@code op} and {@code values}. */
    public record InputFilter(String field, String op, List<Object> values) {}

    /** A join of two sources on columns of their frames; {@code on} entries are {@code name} (same on both sides) or {@code left = right}. */
    public record Join(String left, String right, List<String> on, String type) {

        public static final Set<String> TYPES = Set.of("inner", "left", "full");
    }

    /**
     * One column of the joined frame of a composed dataset: {@code name} in the frame, the {@code alias} of the input it comes from, {@code source} the column of
     * that input's answer ({@code trades.desk} as the query named it, or the measure's name), its {@code type}, whether it is a {@code measure}, and
     * {@code merged} the alias of another input whose same-named join key is folded into this column (null otherwise).
     */
    public record FrameColumn(String name, String alias, String source, String type, boolean measure, String merged) {}

    /** What makes a composed dataset: the inputs, the joins between them and (after checking) the columns of the joined frame. */
    public record Compose(Map<String, Input> from, List<Join> joins, List<FrameColumn> frame) {

        public Compose {
            from = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(from));
            joins = List.copyOf(joins);
            frame = List.copyOf(frame);
        }
    }

    /** What makes a derived dataset: the Python item (Transform interface) and the inputs it receives, in order, as masked pandas frames. */
    public record Derived(String transform, Map<String, Input> inputs, Map<String, Object> params) {}
}
