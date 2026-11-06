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
import com.ash.drishti.bicompute.sql.DuckDbExecutor;
import com.ash.drishti.bicompute.sql.TabularSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rows already read in memory (a panel's, a virtual table's) staged once in the pivot engine's executor under a name that carries their
 * version, the most recent {@code keep} kept: the one staging of {@link PanelSources} and {@link TableSources}.
 */
final class StagedRows {

    private final Map<String, Long> staged = java.util.Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true));
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final int keep;

    StagedRows(int keep) {
        this.keep = Math.max(4, keep);
    }

    /** The relation of {@code rows} (the columns at {@code cols}, as {@code fields}) staged as {@code table}, staged now if it is not. */
    TabularSource.Relation relation(DuckDbExecutor e, String table, List<PivotProvider.Field> fields, List<Integer> cols, List<List<Object>> rows) {
        return relation(e, table, fields, cols, () -> rows);
    }

    /** As above, the rows asked for only when the table must be staged (what was staged under this name is still there otherwise). */
    TabularSource.Relation relation(DuckDbExecutor e, String table, List<PivotProvider.Field> fields, List<Integer> cols,
            java.util.function.Supplier<List<List<Object>>> read) {
        Object lock = locks.computeIfAbsent(table, k -> new Object());
        synchronized (lock) {
            if (!staged.containsKey(table) || !e.exists(table)) {
                List<DuckDbExecutor.Column> dc = fields.stream().map(f -> new DuckDbExecutor.Column(f.name(), "number".equals(f.type()) ? "number"
                        : "text")).toList();
                List<List<Object>> rows = read.get();
                long n = e.stage(table, dc, sink -> {
                    for (List<Object> row : rows) {
                        Object[] vals = new Object[cols.size()];
                        for (int i = 0; i < vals.length; i++) {
                            Object o = row.get(cols.get(i));
                            vals[i] = o == null || o instanceof Number || o instanceof String ? o : String.valueOf(o);
                        }
                        sink.row(vals);
                    }
                    return rows.size();
                });
                staged.put(table, n);
                synchronized (staged) {
                    while (staged.size() > keep) {
                        String eldest = staged.keySet().iterator().next();
                        staged.remove(eldest);
                        try {
                            e.drop(eldest);
                        } catch (RuntimeException ex) {
                            // dropped later
                        }
                    }
                }
            }
        }
        return new TabularSource.Relation(DuckDbExecutor.quote(table), List.of(), null, List.of());
    }

    /** Drops every staged table (the engine switched off). */
    void release(DuckDbExecutor e) {
        Set<String> names;
        synchronized (staged) {
            names = Set.copyOf(staged.keySet());
            staged.clear();
        }
        for (String t : names) {
            try {
                e.drop(t);
            } catch (RuntimeException ex) {
                // already gone
            }
        }
    }
}
