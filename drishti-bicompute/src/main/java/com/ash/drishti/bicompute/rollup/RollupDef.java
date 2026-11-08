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
package com.ash.drishti.bicompute.rollup;

import com.ash.drishti.bicompute.core.Dim;
import com.ash.drishti.bicompute.core.PivotView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A rollup's definition (ARCHITECTURE.md section 26.6): a source, a grain (the dimensions its rows are grouped by, a date at day grain answers
 * week, month, quarter and year too) and the functions whose partial states it keeps (mergeable or sketch, never holistic).
 *
 * <pre>{@code
 * { "id": "trades-desk-month", "source": "trade", "grain": ["DESK", "CURRENCY", "TRADE_DATE:month"],
 *   "functions": ["sum(NOTIONAL_USD)", "count()", "distinct(COUNTERPARTY) ≈"], "dates": "latest" }
 * }</pre>
 *
 * @param id its id (a file name: letters, digits, dashes)
 * @param source the source
 * @param grain the dimension ids
 * @param functions the measures' texts, as a view writes them
 * @param dates {@code latest} (the source's latest date, rebuilt as it moves) or {@code all} (every date with a rollup kept)
 * @param title what Admin and the answer call it
 */
public record RollupDef(String id, String source, List<String> grain, List<String> functions, String dates, String title) {

    public RollupDef {
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) {
            throw PivotView.bad("a rollup's id is letters, digits, - and _ (at most 64): '" + id + "'");
        }
        if (source == null || source.isBlank()) {
            throw PivotView.bad("rollup " + id + " needs a source");
        }
        grain = List.copyOf(grain == null ? List.of() : grain.stream().map(g -> Dim.parse(g).id()).toList());
        functions = List.copyOf(functions == null ? List.of() : functions);
        if (functions.isEmpty()) {
            throw PivotView.bad("rollup " + id + " keeps no function: name at least one, \"functions\": [\"sum(NOTIONAL_USD)\"]");
        }
        dates = "all".equals(dates) ? "all" : "latest";
        title = title == null || title.isBlank() ? source + " by " + (grain.isEmpty() ? "nothing (the grand total)" : String.join(" × ", grain)) : title;
    }

    /** From its JSON or YAML map. */
    @SuppressWarnings("unchecked")
    public static RollupDef parse(Map<String, Object> m) {
        List<String> grain = new ArrayList<>();
        if (m.get("grain") instanceof List<?> l) {
            l.forEach(x -> grain.add(String.valueOf(x)));
        }
        List<String> fns = new ArrayList<>();
        if (m.get("functions") instanceof List<?> l) {
            l.forEach(x -> fns.add(x instanceof Map<?, ?> fm ? String.valueOf(((Map<String, Object>) fm).get("measure")) : String.valueOf(x)));
        }
        return new RollupDef(str(m.get("id")), str(m.get("source")), grain, fns, str(m.get("dates")), str(m.get("title")));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("source", source);
        m.put("grain", grain);
        m.put("functions", functions);
        m.put("dates", dates);
        m.put("title", title);
        return m;
    }
}
