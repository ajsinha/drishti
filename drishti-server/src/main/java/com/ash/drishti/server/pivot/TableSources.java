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
package com.ash.drishti.server.pivot;

import com.ash.drishti.kernel.api.pivot.PivotProvider;
import com.ash.drishti.kernel.api.pivot.Viewer;
import com.ash.drishti.bicompute.sql.DuckDbExecutor;
import com.ash.drishti.bicompute.sql.TabularSource;
import com.ash.drishti.engine.ViewerContext;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.tables.TablesService;
import com.ash.drishti.tables.def.ColumnDef;
import com.ash.drishti.tables.read.TableReads;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Virtual tables as pivot sources (docs/architecture/ARCHITECTURE.md section 10, V5; BI_GUIDE.md "Virtual tables"): the source
 * {@code table:<domain>.<name>} is the table read for the person on the view's business date ({@link TablesService#read}: the source as
 * they may see it, then the table's rules, masks and hidden columns) and staged in the pivot engine's own executor, in memory. A masked
 * column is left out (never a dimension, an input or a filter), as the Data view's tables are. Every table over one kind opened in one
 * view shares one read of the kind (V7).
 */
public final class TableSources implements TabularSource.Resolver {

    /** The prefix of a virtual table's source id. */
    public static final String PREFIX = "table:";

    private final TablesService tables;
    private final Entitlements entitlements;
    private final StagedRows staged;
    /** What an open found, by the read's version ({@link TablesService#version}): a view opened again on unchanged data reads nothing. */
    private final Map<String, Opened> opened;

    public TableSources(TablesService tables, Entitlements entitlements, int keep) {
        this.tables = tables;
        this.entitlements = entitlements;
        this.staged = new StagedRows(keep);
        long most = Math.max(16, keep * 4L);                     // about that many opened tables' descriptions, weighed in bytes
        this.opened = com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumWeight(most * AVERAGE_OPENED)
                .weigher((String k, Opened o) -> weight(o)).<String, Opened>build().asMap();
    }

    /** The bytes an opened table's description is counted at, on average (its fields, dates and problems; the rows are never kept). */
    private static final long AVERAGE_OPENED = 16L * 1024;

    /** An opened table's description in bytes, roughly: what it holds besides the rows. */
    private static int weight(Opened o) {
        long w = 1024L + 256L * o.fields().size() + 16L * o.cols().size() + 32L * o.dates().size()
                + o.problems().stream().mapToLong(p -> 64L + 2L * p.length()).sum();
        return (int) Math.min(Integer.MAX_VALUE, w);
    }

    @Override
    public Map<String, String> sources(Viewer v) {
        ViewerContext p = entitlements.viewer(KindSources.principal(v));
        Map<String, String> out = new LinkedHashMap<>();
        tables.visible(p).forEach((name, title) -> out.put(PREFIX + name, title));
        return out;
    }

    @Override
    public boolean serves(String source) {
        return source != null && source.startsWith(PREFIX);
    }

    /** How many opens were answered from the per-view cache without reading the source (the cache's measure). */
    private final java.util.concurrent.atomic.AtomicLong hits = new java.util.concurrent.atomic.AtomicLong();

    long hits() {
        return hits.get();
    }

    @Override
    public TabularSource open(Viewer v, String source, String date) {
        ViewerContext p = entitlements.viewer(KindSources.principal(v));
        String name = source.substring(PREFIX.length());
        String known = tables.version(p, name, date);
        Opened o = known == null ? null : opened.get(known);
        if (o != null) {
            hits.incrementAndGet();
            return new Table(o, () -> tables.read(tables.reads(p), p, name, date, null, null).rows());
        }
        TableReads reads = tables.reads(p);
        TableReads.Read r = tables.read(reads, p, name, date, null, null);
        List<PivotProvider.Field> fields = new ArrayList<>();
        List<Integer> cols = new ArrayList<>();
        for (int i = 0; i < r.columns().size(); i++) {
            String c = r.columns().get(i);
            if (r.masked().contains(c)) {
                continue;
            }
            ColumnDef def = r.table().columns().get(c);
            String about = def == null ? "built in" : def.description() != null ? def.description()
                    : def.path() != null ? "path " + def.path() : def.expr() != null ? "= " + def.expr() : "";
            fields.add(new PivotProvider.Field(c, type(r.types().get(i)), c.toLowerCase(Locale.ROOT).replace('_', ' '), about, unit(def)));
            cols.add(i);
        }
        String version = known != null ? known
                : r.date() + "|" + r.rows().size() + "|" + Integer.toHexString(r.rows().hashCode()) + "|" + String.join(",", r.masked());
        String table = "vt_" + Integer.toHexString((source + "|" + version).hashCode()).replace('-', 'n');
        List<String> dates = reads.dates(name).stream().map(Object::toString).toList();
        Object title = r.table().about().get("title");
        o = new Opened(source, title == null ? name : String.valueOf(title), List.copyOf(fields), List.copyOf(cols), r.table(), r.date().toString(), dates,
                r.masked().size(), r.rows().size(), r.problems().stream().map(com.ash.drishti.tables.Problem::line).toList(), version, table);
        if (known != null) {
            opened.put(known, o);
        }
        List<List<Object>> rows = r.rows();
        return new Table(o, () -> rows);
    }

    /** The pivot's type of a table's column type. */
    static String type(String t) {
        return switch (t == null ? "" : t) {
            case "integer", "decimal" -> "number";
            case "date", "timestamp" -> "date";
            default -> "string";
        };
    }

    private static String unit(ColumnDef c) {
        if (c == null || c.unit() == null) {
            return "";
        }
        String u = c.unit().toLowerCase(Locale.ROOT);
        return u.equals("pct") || u.equals("percent") ? "pct" : u.length() == 3 || u.equals("money") ? "money" : "";
    }

    /** What one open of a table found, kept by version: everything but the rows, which are staged. */
    private record Opened(String id, String title, List<PivotProvider.Field> fields, List<Integer> cols, com.ash.drishti.tables.def.TableDef def,
            String date, List<String> dates, int masked, long rows, List<String> problems, String version, String table) {}

    /** One virtual table opened for one person on one date; its rows read only when they must be staged. */
    private final class Table implements TabularSource {
        final Opened o;
        final java.util.function.Supplier<List<List<Object>>> rows;

        Table(Opened o, java.util.function.Supplier<List<List<Object>>> rows) {
            this.o = o;
            this.rows = rows;
        }

        @Override
        public String id() {
            return o.id();
        }

        @Override
        public String title() {
            return o.title();
        }

        @Override
        public List<PivotProvider.Field> fields() {
            return o.fields();
        }

        @Override
        public int masked() {
            return o.masked();
        }

        @Override
        public String date() {
            return o.date();
        }

        @Override
        public List<String> dates() {
            return o.dates();
        }

        @Override
        public String version() {
            return o.version();
        }

        @Override
        public long rows() {
            return o.rows();
        }

        @Override
        public long bytes() {
            return o.rows() * Math.max(1, o.fields().size()) * 12L;
        }

        @Override
        public Map<String, Object> notes() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("route", "the virtual table " + o.def().name() + " read from its source as you may see it, on " + o.date()
                    + ", staged in memory (kept while its source's data version is unchanged)");
            m.put("grain", o.def().grain() == null ? "one row per entity" : "one row per element of " + o.def().grain());
            m.put("masks", o.masked() == 0 ? "none" : o.masked() + " column(s) masked for you: not read, not usable");
            if (!o.problems().isEmpty()) {
                m.put("problems", o.problems());
            }
            return m;
        }

        @Override
        public Relation relation(DuckDbExecutor e) {
            return staged.relation(e, o.table(), o.fields(), o.cols(), rows);
        }
    }

    /** Drops every staged table (the engine switched off). */
    public void release(DuckDbExecutor e) {
        staged.release(e);
    }
}
