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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An honest estimate (docs/architecture/BI_DESIGN.md › BI runs: every BI query as a bounded, visible job (built in 2.6.7), section 4.4): a range with its basis, never a single false-precise number. Unknown rows are
 * never zero: with {@code basis: unknown} the row and second figures are null.
 *
 * @param readRows     rows the engine reads (rollup rows for covered dates, raw rows for the rest); null when unknown
 * @param buildRows    rows to build from the source first (0 when every partition is built or a rollup covers it); null when unknown
 * @param bytes        bytes read; null when unknown
 * @param rowsOut      rows of the answer: min(limit, product of the grain's cardinalities, readRows); null when unknown
 * @param secondsLow   the range's low end; null when unknown
 * @param secondsHigh  the range's high end; null when unknown
 * @param basis        {@code layers | delta-stats | iceberg-manifests | store-stats | history | unknown}
 * @param dates        business dates asked
 * @param datesToBuild dates with at least one partition to build
 * @param covered      a rollup (or product) is valid for every date
 * @param coveredDates dates a rollup answers (all of them when covered; some when the plan mixes layers, section 5.2)
 * @param why          one sentence: what it reads and why it costs what it costs
 */
public record RunEstimate(Long readRows, Long buildRows, Long bytes, Long rowsOut, Double secondsLow, Double secondsHigh, Basis basis, int dates,
                          int datesToBuild, boolean covered, int coveredDates, String why) {

    /** Where the row counts came from, weakest last. */
    public enum Basis {
        LAYERS("layers"), DELTA_STATS("delta-stats"), ICEBERG_MANIFESTS("iceberg-manifests"), STORE_STATS("store-stats"), HISTORY("history"),
        UNKNOWN("unknown");

        private final String wire;

        Basis(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    public boolean known() {
        return basis != Basis.UNKNOWN;
    }

    /** The {@code estimate} JSON of the run object (14.3) and the run row. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("readRows", readRows);
        m.put("buildRows", buildRows);
        m.put("bytes", bytes);
        m.put("rowsOut", rowsOut);
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("low", secondsLow);
        s.put("high", secondsHigh);
        m.put("seconds", s);
        m.put("basis", basis.wire());
        m.put("dates", dates);
        m.put("datesToBuild", datesToBuild);
        m.put("covered", covered);
        m.put("why", why);
        return m;
    }
}
