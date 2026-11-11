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
package com.ash.drishti.server.bi.engine;

import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.Histories;
import java.util.Map;

/**
 * Carries a reader's masks over to a live table's history. The history holds the raw values the measures had, so it must not be a way round a mask: a
 * group column of the history is masked when the live table's column is, and a sampled measure's column is masked when the measure reads a column the reader
 * may not see (the same rule a query of the measure itself follows).
 */
final class HistoryMasks {

    private HistoryMasks() {}

    static SqlCompiler.Masks over(Dataset d, SqlCompiler.Masks base) {
        if (d.tables().values().stream().noneMatch(Dataset.Table::isHistory)) {
            return base;
        }
        SqlCompiler compiler = new SqlCompiler(d, Dialect.DUCKDB);
        return (table, column) -> {
            if (base.masked(table, column)) {
                return true;
            }
            Dataset.Table t = d.tables().get(table);
            if (t == null || !t.isHistory()) {
                return false;
            }
            Dataset.Table parent = d.tables().get(t.of());
            Dataset.History h = parent == null || parent.live() == null ? null : parent.live().history();
            if (h == null) {
                return false;
            }
            if (h.by().contains(column)) {
                return base.masked(parent.name(), column);
            }
            for (Map.Entry<String, String> e : Histories.measureColumns(h).entrySet()) {
                if (e.getValue().equals(column)) {
                    return compiler.measureMasked(e.getKey(), base);
                }
            }
            return false;
        };
    }
}
