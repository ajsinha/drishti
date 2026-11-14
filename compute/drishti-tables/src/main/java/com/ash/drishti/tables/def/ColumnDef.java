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
 * One column of a virtual table (docs/architecture/ARCHITECTURE.md §28 sections 3.1 and 3.2): a Rachana path ({@code $.MTM}, {@code @.INDEX.NAME},
 * {@code ^.LEG_ID}), a row-scope expression over the source (never over another column), or a Python column. Exactly one of the three.
 *
 * @param name the column's name
 * @param path the path, or null
 * @param expr the row-scope expression, or null
 * @param python the Python column as written, or null
 * @param type {@code string}, {@code integer}, {@code decimal}, {@code boolean}, {@code date}, {@code timestamp}, or null (read as given)
 * @param role {@code key}, {@code dimension}, {@code measure}, {@code attribute}, or null
 * @param meaning what the value means (a glossary term), or null
 * @param references the table and column a value points at ({@code banking.trading_risk_desks.id}), or null
 * @param unit the unit or currency, or null
 * @param additive whether sums of it mean something (null: decided by its type)
 * @param aggregate the aggregate a consumer uses by default ({@code sum}), or null
 * @param format the display format ({@code amount0}), or null
 * @param key true when the column is a key (it is then in the table's {@code keys} too)
 * @param description the words, or null
 * @param masked true when readers without the raw power see {@code •••} (Q9; a column built from a masked path is masked too)
 * @param hiddenFor roles that never see the column, never null
 * @param extras the other descriptive keys as written ({@code title}, {@code id}, {@code term}, {@code means}, {@code sign}, {@code of})
 */
public record ColumnDef(String name, String path, String expr, Object python, String type, String role, String meaning, String references,
        String unit, Boolean additive, String aggregate, String format, boolean key, String description, boolean masked, List<String> hiddenFor,
        Map<String, Object> extras) {

    /** The types a column may have (DEL's, kept by checked mode). */
    public static final Set<String> TYPES = Set.of("string", "integer", "decimal", "boolean", "date", "timestamp", "list");

    /** The roles a column may have. */
    public static final Set<String> ROLES = Set.of("key", "dimension", "measure", "attribute", "time");

    /** The descriptive keys kept as written in {@link #extras}. */
    public static final Set<String> EXTRA_KEYS = Set.of("title", "id", "term", "means", "sign", "of");

    public ColumnDef {
        hiddenFor = hiddenFor == null ? List.of() : List.copyOf(hiddenFor);
        extras = extras == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(extras));
    }

    /** A column that reads a path. */
    public static ColumnDef ofPath(String name, String path, String type) {
        return new ColumnDef(name, path, null, null, type, null, null, null, null, null, null, null, false, null, false, null, null);
    }

    /** A calculated column. */
    public static ColumnDef ofExpr(String name, String expr, String type) {
        return new ColumnDef(name, null, expr, null, type, null, null, null, null, null, null, null, false, null, false, null, null);
    }

    /** The same column with {@code masked} set. */
    public ColumnDef withMasked(boolean m) {
        return new ColumnDef(name, path, expr, python, type, role, meaning, references, unit, additive, aggregate, format, key, description, m, hiddenFor, extras);
    }

    /** The same column reading another path. */
    public ColumnDef withPath(String p) {
        return new ColumnDef(name, p, expr, python, type, role, meaning, references, unit, additive, aggregate, format, key, description, masked, hiddenFor, extras);
    }

    /** The same column with another expression. */
    public ColumnDef withExpr(String e) {
        return new ColumnDef(name, path, e, python, type, role, meaning, references, unit, additive, aggregate, format, key, description, masked, hiddenFor, extras);
    }

    /** How many of path, expr and python are set (exactly one is right). */
    public int forms() {
        return (path == null ? 0 : 1) + (expr == null ? 0 : 1) + (python == null ? 0 : 1);
    }

    /** The descriptive keys that are set, in the order the YAML writes them (for the writer). */
    public Map<String, Object> describing() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        put(m, "role", role);
        put(m, "meaning", meaning);
        put(m, "references", references);
        put(m, "unit", unit);
        put(m, "additive", additive);
        put(m, "aggregate", aggregate);
        put(m, "format", format);
        if (key) {
            m.put("key", true);
        }
        if (masked) {
            m.put("masked", true);
        }
        if (!hiddenFor.isEmpty()) {
            m.put("hidden-for", hiddenFor);
        }
        put(m, "description", description);
        m.putAll(extras);
        return m;
    }

    private static void put(Map<String, Object> m, String k, Object v) {
        if (v != null) {
            m.put(k, v);
        }
    }
}
