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

import com.ash.drishti.server.bi.engine.Footprints;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * The run object's {@code data} in words (BI_RUNS.md 14.3; BIR-09): from a planned question's footprint, one entry per table read
 * ({@code {table, connector, dates, rows, versions}}) and one line for them all ({@code trades 6,000 rows · v412 (1 date)}). Names, counts and source
 * versions only, never a data value. Kept in the run row's estimate (under {@code data}), so it survives a restart without a new column.
 */
public final class RunDataWords {

    /** The key in the estimate JSON the tables are kept under (taken out of {@code estimate} when the run object is written). */
    static final String KEY = "data";

    private RunDataWords() {}

    /** One entry per table the footprint reads. */
    public static List<Map<String, Object>> tables(Footprints.Footprint f) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (f == null || f.plan() == null) {
            return out;
        }
        for (RunEstimator.Table t : f.plan().tables()) {
            long rows = 0;
            boolean known = true;
            int dates = 0;
            for (RunEstimator.DateRows d : t.dates()) {
                if (!LocalDate.MIN.equals(d.date())) {
                    dates++;
                }
                if (d.rows() == null) {
                    known = false;
                } else {
                    rows += d.rows();
                }
            }
            TreeSet<String> versions = new TreeSet<>();
            if (f.data() != null) {
                f.data().parts().forEach((part, v) -> {
                    if (part.connector().equals(t.connector())) {
                        v.ifPresent(versions::add);
                    }
                });
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("table", t.name());
            m.put("connector", t.connector());
            m.put("dates", dates);
            m.put("rows", known ? rows : null);
            m.put("versions", versions(versions, dates));
            out.add(m);
        }
        return out;
    }

    /** {@code v412 (182 dates)}, {@code v410, v412 (2 dates)}, {@code 14 versions (182 dates)}, or {@code no version (3 dates)}. */
    static String versions(TreeSet<String> versions, int dates) {
        String v = versions.isEmpty() ? "no version" : versions.size() <= 3 ? String.join(", ", versions.stream().map(RunDataWords::v).toList())
                : versions.size() + " versions";
        return dates == 0 ? v : v + " (" + dates + (dates == 1 ? " date)" : " dates)");
    }

    private static String v(String version) {
        return version.chars().allMatch(Character::isDigit) ? "v" + version : version;
    }

    /** The tables in one line: {@code trades 6,000 rows · v412 (1 date) · desks · no version (1 date)}; null when none. */
    public static String words(List<Map<String, Object>> tables) {
        if (tables == null || tables.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (Map<String, Object> t : tables) {
            Object rows = t.get("rows");
            parts.add(t.get("table") + (rows instanceof Number n ? " " + RunEstimator.words(n.longValue()) + " rows" : "") + ", " + t.get("versions"));
        }
        return String.join(" · ", parts);
    }

    /** The tables kept in a run row's estimate JSON, if any. */
    @SuppressWarnings("unchecked")
    static Optional<List<Map<String, Object>>> of(Object estimate) {
        if (estimate instanceof Map<?, ?> m && m.get(KEY) instanceof List<?> l) {
            return Optional.of((List<Map<String, Object>>) l);
        }
        return Optional.empty();
    }
}
