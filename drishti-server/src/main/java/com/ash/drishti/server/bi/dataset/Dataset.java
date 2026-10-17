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
        Compose compose, Derived derived, List<ExpectedValue> tests, Model model) {

    /**
     * What a data model adds for its expressions (wave B, docs/architecture/BI_DESIGN.md section 12.2 task 2): the business calendar its dates follow (null:
     * the pack's), the month its fiscal year starts, where its FX rates are, and the per-row currency column of each amount ({@code trades.notional} to
     * {@code trades.currency}, or an ISO code for a fixed currency).
     */
    public record Model(String holidays, int fiscalYearStart, Fx fx, Map<String, String> currencyOf) {
        public static final Model NONE = new Model(null, 1, null, Map.of());

        public Model {
            currencyOf = currencyOf == null ? Map.of() : Map.copyOf(currencyOf);
            fiscalYearStart = fiscalYearStart < 1 || fiscalYearStart > 12 ? 1 : fiscalYearStart;
        }
    }

    /** The FX rates of a model: the alias of its rates table, the columns of the currencies, the rate and its date, and the base currency of cross rates. */
    public record Fx(String rates, String from, String to, String rate, String date, String base) {}

    public Dataset {
        model = model == null ? Model.NONE : model;
    }

    /** A dataset with tests and no model information (a version-1 dataset, or a copy that keeps none). */
    public Dataset(String name, int version, String title, String description, boolean sample, String engine, Map<String, Table> tables, List<Relationship> relationships,
            Map<String, Measure> measures, Map<String, List<String>> hierarchies, Security security, List<Rollup> rollups, Refresh refresh, Compose compose, Derived derived,
            List<ExpectedValue> tests) {
        this(name, version, title, description, sample, engine, tables, relationships, measures, hierarchies, security, rollups, refresh, compose, derived, tests, Model.NONE);
    }

    /** The same dataset with other measures (local, guard, calculation-group and context measures), keeping everything else. */
    public Dataset withMeasures(Map<String, Measure> m) {
        return new Dataset(name, version, title, description, sample, engine, tables, relationships, m, hierarchies, security, rollups, refresh, compose, derived, tests, model);
    }

    /** The same dataset with other relationships, keeping everything else. */
    public Dataset withRelationships(List<Relationship> r) {
        return new Dataset(name, version, title, description, sample, engine, tables, r, measures, hierarchies, security, rollups, refresh, compose, derived, tests, model);
    }

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
     * @param shape for a dataset that de-duplicates or groups its rows (docs/architecture/BI_DESIGN.md section 3.1): how, and the columns read before; else null
     */
    public record Table(String name, String connector, String kind, String each, String mode, boolean onLoad, String schedule, String description, Map<String, Column> columns,
            Live live, String of, Shape shape) {

        public Table(String name, String connector, String kind, String each, String mode, boolean onLoad, String schedule, String description, Map<String, Column> columns,
                Live live, String of) {
            this(name, connector, kind, each, mode, onLoad, schedule, description, columns, live, of, null);
        }

        public Table(String name, String connector, String kind, String each, String mode, boolean onLoad, String schedule, String description, Map<String, Column> columns,
                Live live) {
            this(name, connector, kind, each, mode, onLoad, schedule, description, columns, live, null, null);
        }

        public Table(String name, String connector, String kind, String each, String mode, boolean onLoad, String schedule, String description, Map<String, Column> columns) {
            this(name, connector, kind, each, mode, onLoad, schedule, description, columns, null, null, null);
        }

        /** True for the table the engine makes from a live table's {@code history:}: samples of its measures over time (the live table it belongs to is {@code of}). */
        public boolean isHistory() {
            return HISTORY.equals(mode);
        }

        public boolean isLive() {
            return "live".equals(mode);
        }

        /** The columns read from the source (everything but calculated ones and the ones read from another table; for a shaped table, those before the shape). */
        public List<Column> physical() {
            Map<String, Column> from = shape != null && !shape.source().isEmpty() ? shape.source() : columns;
            return from.values().stream().filter(c -> c.expr() == null && c.ref() == null).toList();
        }

        /** The calculated columns, worked out on each row before the shape (a dataset's {@code derive}). */
        public List<Column> calculated() {
            Map<String, Column> from = shape != null && !shape.source().isEmpty() ? shape.source() : columns;
            return from.values().stream().filter(c -> c.expr() != null).toList();
        }
    }

    /**
     * How a dataset reshapes its rows, in order: one row per {@code dedupKeys} within a business date, keeping the latest (or earliest) by {@code dedupBy};
     * then the calculated columns; then, when {@code groupBy} is set, one row per group with {@code groupColumns} (name to {@code sum}, {@code avg},
     * {@code min}, {@code max}, {@code count}, {@code distinct} of the same-named column; {@code count} of no column counts rows). {@code source} holds the
     * columns before grouping (what is read and derived); the table's own columns are those after.
     */
    public record Shape(List<String> dedupKeys, String dedupBy, boolean latest, List<String> groupBy, Map<String, String> groupColumns, Map<String, Column> source,
            List<String> filters) {

        /** Without row filters before the grouping. */
        public Shape(List<String> dedupKeys, String dedupBy, boolean latest, List<String> groupBy, Map<String, String> groupColumns, Map<String, Column> source) {
            this(dedupKeys, dedupBy, latest, groupBy, groupColumns, source, List.of());
        }

        public Shape {
            filters = filters == null ? List.of() : List.copyOf(filters);
            dedupKeys = dedupKeys == null ? List.of() : List.copyOf(dedupKeys);
            groupBy = groupBy == null ? List.of() : List.copyOf(groupBy);
            groupColumns = groupColumns == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(groupColumns));
            source = source == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(source));
        }

        public boolean dedup() {
            return !dedupKeys.isEmpty();
        }

        public boolean grouped() {
            return !groupBy.isEmpty();
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
     * A live table's history (docs/guides/DATASETS.md, section 7.1 "History of a live table"): every {@code every} the listed measures are worked out over the live table, grouped by
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
     * @param ref for a column read from another table along a many-to-one reference ({@code related(desk.name)}): that table's {@code table.column}; else null
     */
    public record Column(String name, String path, String type, String format, boolean key, String expr, String description, String ref) {

        public Column(String name, String path, String type, String format, boolean key, String expr, String description) {
            this(name, path, type, format, key, expr, description, null);
        }
    }

    /**
     * A relationship, {@code from} the many side, {@code to} the one side for {@code many-to-one}.
     *
     * @param direction {@code single} (filters flow from the one side to the many side) or {@code both}; stored, queries use the join as written
     * @param active an inactive relationship is not joined by a query
     */
    public record Relationship(String fromTable, String fromColumn, String toTable, String toColumn, String cardinality, String direction, boolean active, String id) {

        /** A relationship without a stable id (a version-1 dataset's). */
        public Relationship(String fromTable, String fromColumn, String toTable, String toColumn, String cardinality, String direction, boolean active) {
            this(fromTable, fromColumn, toTable, toColumn, cardinality, direction, active, null);
        }

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

    /**
     * Row rules and masked columns. {@code rows} are the rules of a version-1 dataset: they name any table and are joined into every query the reader runs
     * (fail-closed). {@code tables} is the security of a data model (docs/architecture/BI_MODEL_SPEC.md section 2.6): each entry is bound to its own table,
     * applied only where that table is read, and a dimension's rules reach the facts that reference it many-to-one. A dataset has one form or the other.
     */
    public record Security(List<RowRule> rows, List<String> masked, Map<String, TableSecurity> tables) {

        public static final Security NONE = new Security(List.of(), List.of());

        /** The security of a version-1 dataset: rules joined into every query. */
        public Security(List<RowRule> rows, List<String> masked) {
            this(rows, masked, Map.of());
        }

        public Security {
            tables = tables == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(tables));
        }

        /** True for a data model's security, bound per table. */
        public boolean perTable() {
            return !tables.isEmpty();
        }
    }

    /**
     * The security bound to one table of a data model. {@code filters} always apply (the {@code where:} of a dataset that {@code extends} another); each layer of
     * {@code layers} is the OR of the reader's matching rules (a layer with rules and none for the reader gives no rows), and the layers are ANDed: the dataset's
     * own rules, then the model's, so a model can only remove rows. {@code dimension} lets the rules reach the facts that reference this table many-to-one.
     */
    public record TableSecurity(List<String> filters, List<List<RowRule>> layers, boolean dimension) {

        public TableSecurity {
            filters = filters == null ? List.of() : List.copyOf(filters);
            layers = layers == null ? List.of() : layers.stream().map(List::copyOf).toList();
        }

        /** True when nothing restricts the table. */
        public boolean open() {
            return filters.isEmpty() && layers.stream().allMatch(List::isEmpty);
        }
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
    public record Join(String left, String right, List<String> on, String type, boolean allowRepeat) {

        public Join(String left, String right, List<String> on, String type) {
            this(left, right, on, type, false);
        }

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
