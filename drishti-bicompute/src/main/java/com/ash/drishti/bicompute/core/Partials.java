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
package com.ash.drishti.bicompute.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * A provider's answer to a plan (ARCHITECTURE.md section 23.6.1): per cell set, per cell key, the members counted and one partial state per
 * function of the plan (in the plan's function order). The core merges partials of several shards in a fixed order
 * ({@link com.ash.drishti.bicompute.cluster.OrderedMerge}) and finishes them.
 *
 * @param sets the sets' results, in the plan's set order
 * @param stats what the evaluation did (queries, statements, milliseconds), for the provenance
 */
public record Partials(List<SetResult> sets, Stats stats) {

    /**
     * One cell set's cells.
     *
     * @param set the set
     * @param cells key to its entry, in key order
     */
    public record SetResult(CellSet set, SortedMap<CellKey, Entry> cells) {
        public SetResult {
            cells = new TreeMap<>(cells);
        }
    }

    /**
     * One cell's members and states.
     *
     * @param members the rows (or members) counted in the cell
     * @param states one partial state per function, in the plan's order
     * @param scope the cell's scope in the provider's own grammar (null when it has none)
     * @param notes function index to why its state is missing (refused, masked)
     */
    public record Entry(long members, Object[] states, String scope, Map<Integer, String> notes) {
        public Entry(long members, Object[] states) {
            this(members, states, null, Map.of());
        }
    }

    /**
     * @param queries the statements or batches run
     * @param statements the SQL (or batch descriptions) run, for the provenance (parameters bound, never spliced)
     * @param millis the time taken
     * @param rowsRead rows read by the provider (-1: unknown)
     */
    public record Stats(int queries, List<String> statements, long millis, long rowsRead) {
        public static Stats none() {
            return new Stats(0, List.of(), 0, -1);
        }

        public Stats plus(Stats o) {
            List<String> s = new ArrayList<>(statements);
            s.addAll(o.statements);
            return new Stats(queries + o.queries, s, millis + o.millis, rowsRead < 0 || o.rowsRead < 0 ? Math.max(rowsRead, o.rowsRead)
                    : rowsRead + o.rowsRead);
        }
    }
}
