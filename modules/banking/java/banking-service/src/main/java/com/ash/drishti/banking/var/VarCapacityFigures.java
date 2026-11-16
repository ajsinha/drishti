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
package com.ash.drishti.banking.var;

import com.ash.drishti.kernel.api.capacity.CapacityFigures;
import com.ash.drishti.kernel.api.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/** The VaR engine's live figures on Admin → Capacity (the dates open, their heap, the newest date's size: the planner's inputs). */
public final class VarCapacityFigures implements CapacityFigures {

    private static final long MB = 1_048_576L;

    private final VarController vc;

    public VarCapacityFigures(VarController vc) {
        this.vc = vc;
    }

    @Override
    public String key() {
        return "engine";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> figures(Principal p, Map<String, Object> inputs) {
        Map<String, Object> v = null;
        if (vc == null) {
            return null;                                  // no VaR controller here (a test that makes the figures itself): null, never guessed
        }
        try {
            Map<String, Object> st = vc.status(p);
            v = new LinkedHashMap<>();
            Map<String, Object> mem = st.get("memory") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            Map<String, Object> newest = null;
            int loaded = 0;
            for (Object o : st.get("loaded") instanceof List<?> l ? l : List.of()) {
                if (o instanceof Map<?, ?> d && d.get("positions") != null) {
                    loaded++;
                    if (newest == null || String.valueOf(d.get("businessDate")).compareTo(String.valueOf(newest.get("businessDate"))) > 0) {
                        newest = (Map<String, Object>) d;
                    }
                }
            }
            v.put("enabled", st.get("enabled"));
            v.put("datesLoaded", loaded);
            v.put("newestDate", newest == null ? null : newest.get("businessDate"));
            v.put("positions", newest == null ? null : newest.get("positions"));
            v.put("trades", newest == null ? null : newest.get("trades"));
            v.put("nodes", newest == null ? null : newest.get("nodes"));
            v.put("dateHeapMb", newest == null || !(newest.get("heapBytes") instanceof Number h) ? null : h.longValue() / MB);
            v.put("openDates", mem.get("openDates"));
            v.put("hotDates", st.get("hotDates"));
            v.put("protected", mem.get("protected"));
            v.put("openHeapMb", mb(mem.get("openHeapBytes")));
            v.put("openHeapLimitMb", mb(mem.get("openHeapLimitBytes")));
            v.put("openHeapSetting", mem.get("openHeapMb"));
            v.put("mappedMb", mb(mem.get("mappedBytes")));
            if (newest != null) {
                inputs.put("Positions", newest.get("positions"));
                inputs.put("Trades", newest.get("trades"));
                inputs.put("Nodes", newest.get("nodes"));
            }
            if (st.get("hotDates") instanceof Number h) {
                inputs.put("HotDates", h);
            }
            if (st.get("currencies") instanceof Map<?, ?> c && c.get("listed") instanceof List<?> listed) {
                inputs.put("CcysAhead", (int) listed.stream().filter(x -> !"USD".equals(String.valueOf(x))).count());
            }
        } catch (RuntimeException e) {
            v = Map.of("error", String.valueOf(e.getMessage()));
        }
        return v;
    }

    @Override
    public List<Object[]> rows(Map<String, Object> v) {
        if (v == null) {
            return List.of();
        }
        return List.of(new Object[] {"VaR dates open", v.get("openDates"), "dates"}, new Object[] {"Open dates' heap", v.get("openHeapMb"), "MB"},
                new Object[] {"Safety net (open-heap limit)", v.get("openHeapLimitMb"), "MB"}, new Object[] {"Mapped hot files", v.get("mappedMb"), "MB"});
    }

    private static Long mb(Object bytes) {
        return bytes instanceof Number n ? n.longValue() / MB : null;
    }
}
