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
package com.ash.drishti.bicompute.whatif;

import com.ash.drishti.bicompute.core.Dim;
import com.ash.drishti.bicompute.spi.PivotProvider;
import com.ash.drishti.bicompute.spi.Viewer;
import com.ash.drishti.bicompute.sql.DuckDbExecutor;
import com.ash.drishti.bicompute.sql.GroupingSetsCompiler;
import com.ash.drishti.bicompute.sql.TabularSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A source with a what-if overlay joined in (ARCHITECTURE.md section 26.7, item 3): the same fields, the same row rules (applied to the
 * source's rows first), then the overlay: members left out, fields scaled or set where a condition holds, hypothetical rows added. Values the
 * person typed are bound parameters; field names come from the source's catalogue only. The version carries the overlay's digest, so the
 * what-if's states are cached apart from the data as it is.
 */
public final class WhatIfResolver implements TabularSource.Resolver {

    private final TabularSource.Resolver inner;
    private final Overlay overlay;

    public WhatIfResolver(TabularSource.Resolver inner, Overlay overlay) {
        this.inner = inner;
        this.overlay = overlay;
    }

    @Override
    public Map<String, String> sources(Viewer v) {
        return inner.sources(v);
    }

    @Override
    public boolean serves(String source) {
        return inner.serves(source);
    }

    @Override
    public TabularSource open(Viewer v, String source, String date) {
        TabularSource s = inner.open(v, source, date);
        Map<String, String> types = new LinkedHashMap<>();
        s.fields().forEach(f -> types.put(f.name(), f.type()));
        check(types);
        return new TabularSource() {
            @Override
            public String id() {
                return s.id();
            }

            @Override
            public String title() {
                return s.title() + " (what-if)";
            }

            @Override
            public List<PivotProvider.Field> fields() {
                return s.fields();
            }

            @Override
            public int masked() {
                return s.masked();
            }

            @Override
            public String date() {
                return s.date();
            }

            @Override
            public List<String> dates() {
                return s.dates();
            }

            @Override
            public String version() {
                return s.version() + "|what-if:" + overlay.signature();
            }

            @Override
            public long rows() {
                return s.rows() < 0 ? -1 : s.rows() + overlay.add().size();
            }

            @Override
            public long bytes() {
                return s.bytes();
            }

            @Override
            public Map<String, Object> notes() {
                Map<String, Object> n = new LinkedHashMap<>(s.notes());
                n.put("whatIf", overlay.toMap());
                return n;
            }

            @Override
            public Relation relation(DuckDbExecutor exec) {
                return wrap(s.relation(exec), types);
            }
        };
    }

    private void check(Map<String, String> types) {
        Set<String> named = new LinkedHashSet<>(overlay.exclude().keySet());
        for (Overlay.Change c : overlay.changes()) {
            named.addAll(c.where().keySet());
            if (!"number".equals(types.get(c.field()))) {
                throw Overlay.bad(c.field() + (types.containsKey(c.field()) ? " is not a number: only numbers are scaled or set"
                        : " is not a field you may use"));
            }
        }
        overlay.add().forEach(r -> named.addAll(r.keySet()));
        for (String f : named) {
            if (!types.containsKey(Dim.parse(f).field())) {
                throw Overlay.bad(f + " is not a field you may use (masked fields are never named)");
            }
        }
    }

    /** A piece of SQL and its parameters, in text order. */
    private record Sql(String text, List<Object> params) {
        Sql then(String more, List<Object> p) {
            List<Object> all = new ArrayList<>(params);
            all.addAll(p);
            return new Sql(text + more, all);
        }
    }

    private static Sql in(Map<String, List<String>> where, Map<String, String> types, boolean negate) {
        List<String> parts = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        where.forEach((f, vals) -> {
            Dim d = Dim.parse(f);
            String e = GroupingSetsCompiler.dimExpr(d, types.getOrDefault(d.field(), "string"));
            parts.add(e + " IN (" + String.join(", ", java.util.Collections.nCopies(vals.size(), "?")) + ")");
            params.addAll(vals);
        });
        if (parts.isEmpty()) {
            return new Sql("TRUE", List.of());           // no condition: every row, whether kept or left out by negation
        }
        String all = String.join(" AND ", parts);
        return new Sql(negate ? "NOT (" + all + ")" : "(" + all + ")", params);
    }

    TabularSource.Relation wrap(TabularSource.Relation rel, Map<String, String> types) {
        // the changed fields: a CASE per change, in order, over the field as a number
        Map<String, Sql> replaced = new LinkedHashMap<>();
        for (Overlay.Change c : overlay.changes()) {
            Sql cur = replaced.getOrDefault(c.field(), new Sql("TRY_CAST(" + DuckDbExecutor.quote(c.field()) + " AS DOUBLE)", List.of()));
            Sql cond = in(c.where(), types, false);
            List<Object> p = new ArrayList<>(cond.params());
            String then;
            if (c.scale() != null) {
                p.addAll(cur.params());
                p.add(c.scale());
                then = "(" + cur.text() + ") * ?";
            } else {
                p.add(c.set());
                then = "?";
            }
            p.addAll(cur.params());
            replaced.put(c.field(), new Sql("CASE WHEN " + cond.text() + " THEN " + then + " ELSE " + cur.text() + " END", p));
        }
        List<Object> params = new ArrayList<>();
        StringBuilder b = new StringBuilder("(SELECT *");
        if (!replaced.isEmpty()) {
            List<String> r = new ArrayList<>();
            replaced.forEach((f, s) -> {
                r.add(s.text() + " AS " + DuckDbExecutor.quote(f));
                params.addAll(s.params());
            });
            b.append(" REPLACE (").append(String.join(", ", r)).append(")");
        }
        b.append(" FROM ").append(rel.from()).append(" AS \"t\"");
        params.addAll(rel.params());
        List<String> where = new ArrayList<>();
        if (rel.rowRules() != null && !rel.rowRules().isBlank()) {
            where.add("(" + rel.rowRules() + ")");
            params.addAll(rel.ruleParams());
        }
        if (!overlay.exclude().isEmpty()) {
            Sql ex = in(overlay.exclude(), types, true);
            where.add(ex.text());
            params.addAll(ex.params());
        }
        if (!where.isEmpty()) {
            b.append(" WHERE ").append(String.join(" AND ", where));
        }
        if (!overlay.add().isEmpty()) {
            List<String> cols = new ArrayList<>();
            overlay.add().forEach(row -> row.keySet().forEach(k -> {
                if (!cols.contains(k)) {
                    cols.add(k);
                }
            }));
            List<String> rows = new ArrayList<>();
            for (Map<String, Object> row : overlay.add()) {
                List<String> cells = new ArrayList<>();
                for (String c : cols) {
                    Object v = row.get(c);
                    String t = switch (types.getOrDefault(c, "string")) {
                        case "number" -> "DOUBLE";
                        case "date" -> "DATE";
                        default -> "VARCHAR";
                    };
                    cells.add("CAST(? AS " + t + ")");
                    params.add(v == null ? null : v instanceof Number n ? n.doubleValue() : String.valueOf(v));
                }
                rows.add("(" + String.join(", ", cells) + ")");
            }
            b.append(" UNION ALL BY NAME SELECT * FROM (VALUES ").append(String.join(", ", rows)).append(") AS \"h\"(")
                    .append(String.join(", ", cols.stream().map(DuckDbExecutor::quote).toList())).append(")");
        }
        b.append(")");
        return new TabularSource.Relation(b.toString(), params, null, List.of());
    }
}
