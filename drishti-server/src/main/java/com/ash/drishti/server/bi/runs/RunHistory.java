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
package com.ash.drishti.server.bi.runs;

import com.ash.drishti.server.bi.engine.Layers;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The learned rows per business day of each (model, table) (docs/architecture/BI_RUNS.md section 4.1): an exponentially weighted mean of the rows of
 * the built partitions the planner has seen ({@code meta.json}). The third source of a date's rows, after a valid partition and the source's own
 * metadata; it lets a connector that cannot count (a document store) be sized once any of its dates is built. In memory: it learns again after a
 * start from the partitions the BI store already holds, the first time the planner peeks at them.
 */
public final class RunHistory implements Layers.RowHistory {

    /** The weight of a new observation. */
    static final double ALPHA = 0.2;

    private final Map<String, double[]> means = new ConcurrentHashMap<>();

    @Override
    public Long rowsPerDay(String key, String table) {
        double[] m = means.get(key + "|" + table);
        return m == null ? null : Math.round(m[0]);
    }

    @Override
    public void saw(String key, String table, long rows) {
        if (rows < 0) {
            return;
        }
        means.compute(key + "|" + table, (k, m) -> {
            if (m == null) {
                return new double[] {rows};
            }
            m[0] = m[0] + ALPHA * (rows - m[0]);
            return m;
        });
    }

    /** How many tables have a learned size (Admin). */
    public int size() {
        return means.size();
    }
}
