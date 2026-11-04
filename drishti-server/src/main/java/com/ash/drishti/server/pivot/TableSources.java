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

import com.ash.drishti.bicompute.spi.PivotProvider;
import com.ash.drishti.bicompute.spi.Viewer;
import com.ash.drishti.bicompute.sql.DuckDbExecutor;
import com.ash.drishti.bicompute.sql.TabularSource;
import com.ash.drishti.server.security.Principal;
import com.ash.drishti.server.tables.TablesService;
import com.ash.drishti.tables.def.ColumnDef;
import com.ash.drishti.tables.read.TableReads;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Virtual tables as pivot sources (VIRTUAL_TABLES.md section 10, V5; PIVOT_GUIDE.md "Virtual tables"): the source
 * {@code table:<domain>.<name>} is the table read for the person on the view's business date ({@link TablesService#read}: the source as
 * they may see it, then the table's rules, masks and hidden columns) and staged in the pivot engine's own executor, in memory. A masked
 * column is left out (never a dimension, an input or a filter), as the Data view's tables are. Every table over one kind opened in one
 * view shares one read of the kind (V7).
 */
public final class TableSources implements TabularSource.Resolver {

    /** The prefix of a virtual table's source id. */
    public static final String PREFIX = "table:";

    private final TablesService tables;
    private final StagedRows staged;

    public TableSources(TablesService tables, int keep) {
        this.tables = tables;
        this.staged = new StagedRows(keep);
    }

    @Override
    public Map<String, String> sources(Viewer v) {
        Principal p = KindSources.principal(v);
        Map<String, String> out = new LinkedHashMap<>();
        tables.visible(p).forEach((name, title) -> out.put(PREFIX + name, title));
        return out;
    }

    @Override
    public boolean serves(String source) {
        return source != null && source.startsWith(PREFIX);
    }

    @Override
    public TabularSource open(Viewer v, String source, String date) {
        Principal p = KindSources.principal(v);
        String name = source.substring(PREFIX.length());
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
        String version = r.date() + "|" + r.rows().size() + "|" + Integer.toHexString(r.rows().hashCode()) + "|" + String.join(",", r.masked());
        String table = "vt_" + Integer.toHexString((source + "|" + version).hashCode()).replace('-', 'n');
        List<String> dates = reads.dates(name).stream().map(Object::toString).toList();
        Object title = r.table().about().get("title");
        return new Table(source, title == null ? name : String.valueOf(title), fields, cols, r, version, table, dates);
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

    /** One virtual table opened for one person on one date. */
    private final class Table implements TabularSource {
        final String id;
        final String title;
        final List<PivotProvider.Field> fields;
        final List<Integer> cols;
        final TableReads.Read read;
        final String version;
        final String table;
        final List<String> dates;

        Table(String id, String title, List<PivotProvider.Field> fields, List<Integer> cols, TableReads.Read read, String version, String table,
                List<String> dates) {
            this.id = id;
            this.title = title;
            this.fields = fields;
            this.cols = cols;
            this.read = read;
            this.version = version;
            this.table = table;
            this.dates = dates;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String title() {
            return title;
        }

        @Override
        public List<PivotProvider.Field> fields() {
            return fields;
        }

        @Override
        public int masked() {
            return read.masked().size();
        }

        @Override
        public String date() {
            return read.date().toString();
        }

        @Override
        public List<String> dates() {
            return dates;
        }

        @Override
        public String version() {
            return version;
        }

        @Override
        public long rows() {
            return read.rows().size();
        }

        @Override
        public long bytes() {
            return (long) read.rows().size() * Math.max(1, fields.size()) * 12L;
        }

        @Override
        public Map<String, Object> notes() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("route", "the virtual table " + read.table().name() + " read from its source as you may see it, on " + read.date()
                    + ", staged in memory");
            m.put("grain", read.table().grain() == null ? "one row per entity" : "one row per element of " + read.table().grain());
            m.put("masks", read.masked().isEmpty() ? "none" : read.masked().size() + " column(s) masked for you: not read, not usable");
            if (!read.problems().isEmpty()) {
                m.put("problems", read.problems().stream().map(com.ash.drishti.tables.Problem::line).toList());
            }
            return m;
        }

        @Override
        public Relation relation(DuckDbExecutor e) {
            return staged.relation(e, table, fields, cols, read.rows());
        }
    }

    /** Drops every staged table (the engine switched off). */
    public void release(DuckDbExecutor e) {
        staged.release(e);
    }
}
