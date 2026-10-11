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
 */
public record Dataset(String name, int version, String title, String description, boolean sample, String engine, Map<String, Table> tables,
        List<Relationship> relationships, Map<String, Measure> measures, Map<String, List<String>> hierarchies, Security security, List<Rollup> rollups) {

    public static final Set<String> TYPES = Set.of("string", "integer", "decimal", "boolean", "date", "timestamp");
    public static final Set<String> MODES = Set.of("live", "cached", "direct");
    public static final Set<String> CARDINALITIES = Set.of("many-to-one", "one-to-many", "one-to-one", "many-to-many");
    /** The column every table has: the entity's id. */
    public static final String ID = "id";
    /** The column every table has: the business date the row is for. */
    public static final String DATE = "businessDate";
    /** The column of a table over an array ({@code each}): the element's position in the array, from 0. */
    public static final String INDEX = "index";

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
     */
    public record Table(String name, String connector, String kind, String each, String mode, boolean onLoad, String schedule, String description, Map<String, Column> columns) {

        /** The columns read from the source (everything but calculated ones). */
        public List<Column> physical() {
            return columns.values().stream().filter(c -> c.expr() == null).toList();
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

    /** A measure: an aggregate expression, or a Python measure (stored, not run). */
    public record Measure(String name, String expr, String format, String description, String python) {}

    /** Row rules and masked columns. */
    public record Security(List<RowRule> rows, List<String> masked) {

        public static final Security NONE = new Security(List.of(), List.of());
    }

    /** A row rule: readers holding {@code role} see the rows where {@code where} is true. */
    public record RowRule(String role, String where) {}

    /**
     * A rollup: the aggregates of {@code measures} grouped by {@code by} (columns of {@code table}) per business date.
     */
    public record Rollup(String name, String table, List<String> by, List<String> measures) {}

    /** The tables served by this connector or kind, for invalidation on a data-load signal. */
    public List<Table> tablesOfKind(String kind) {
        return tables.values().stream().filter(t -> t.kind().equals(kind)).toList();
    }
}
