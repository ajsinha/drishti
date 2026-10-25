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
package com.ash.drishti.server.var;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.compute.var.VarBook;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One loaded business date: the engine's {@link VarBook}, the stored portfolios' definitions, and what the date was read from.
 *
 * @param version the vector table's data version (the pin a result is stamped with)
 * @param loadMs the whole load, {@code vectorMs} the vectors' part
 */
record VarDate(LocalDate date, VarBook book, Map<String, Portfolio> portfolios, String version, Instant loadedAt, long loadMs, long vectorMs) {

    /**
     * A stored portfolio (the {@code portfolio} kind): its definition as scope text.
     *
     * @param type HIERARCHY, RULE or BASKET
     * @param scope its definition in the scope grammar: {@code NODE:x}, {@code WHERE:rule}, {@code POS:... + TRD:...}
     */
    record Portfolio(String id, String name, String type, String owner, String scope, double limit) {

        static Portfolio of(String id, DataNode d) {
            String type = VarLoader.text(d, "TYPE");
            DataNode def = d.get("DEFINITION");
            String scope;
            if ("HIERARCHY".equals(type)) {
                scope = "NODE:" + VarLoader.text(def, "NODE");
            } else if ("RULE".equals(type)) {
                scope = "WHERE:" + VarLoader.text(def, "RULE");
            } else {
                List<String> pos = new ArrayList<>();
                List<String> trd = new ArrayList<>();
                DataNode m = def.get("MEMBERS");
                for (int i = 0; i < m.size(); i++) {
                    String p = VarLoader.text(m.get(i), "POSITION_ID");
                    String t = VarLoader.text(m.get(i), "TRADE_ID");
                    if (p != null) {
                        pos.add(p);
                    } else if (t != null) {
                        trd.add(t);
                    }
                }
                List<String> parts = new ArrayList<>();
                if (!pos.isEmpty()) {
                    parts.add("POS:" + String.join(",", pos));
                }
                if (!trd.isEmpty()) {
                    parts.add("TRD:" + String.join(",", trd));
                }
                scope = parts.isEmpty() ? "TRD:NONE" : String.join(" + ", parts);
            }
            return new Portfolio(id, VarLoader.text(d, "NAME"), type, VarLoader.text(d, "OWNER"), scope, VarLoader.number(VarLoader.text(d, "LIMIT_VAR")));
        }
    }
}
