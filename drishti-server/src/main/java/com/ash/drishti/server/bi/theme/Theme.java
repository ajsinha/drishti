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
package com.ash.drishti.server.bi.theme;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A theme item (report schema v2, docs/archive/BI_PARITY_PLAN.md R6; docs/guides/REPORTS.md section 5.2): the colours, type and visual defaults a report
 * takes by naming it ({@code theme: corporate}). Versioned, reviewed and published like any other BI item; produced by {@link ThemeParser}.
 *
 * @param palette the series colours in order ({@code #rrggbb}, 3 to 12)
 * @param background the page and visual background, or null (the console's)
 * @param foreground the text colour, or null (the console's)
 * @param font {@code family} (one of {@link ThemeParser#FONTS}: nothing is fetched) and {@code size} (10 to 18 px); empty for the console's
 * @param good the colour of a good figure (a KPI above target), or null
 * @param bad the colour of a bad figure, or null
 * @param visuals format defaults by visual family ({@code chart}, {@code table} ...) or type ({@code matrix}); a visual's own format wins
 */
public record Theme(String name, int version, String title, String description, boolean sample, List<String> palette, String background, String foreground,
        Map<String, Object> font, String good, String bad, Map<String, Map<String, Object>> visuals) {

    /** What the viewer and the designer read: the theme as written, defaults left out. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rachana", 1);
        m.put("theme", name);
        m.put("version", version);
        m.put("title", title);
        if (!description.isBlank()) {
            m.put("description", description);
        }
        if (sample) {
            m.put("sample", true);
        }
        m.put("palette", palette);
        if (background != null) {
            m.put("background", background);
        }
        if (foreground != null) {
            m.put("foreground", foreground);
        }
        if (!font.isEmpty()) {
            m.put("font", font);
        }
        if (good != null) {
            m.put("good", good);
        }
        if (bad != null) {
            m.put("bad", bad);
        }
        if (!visuals.isEmpty()) {
            m.put("visuals", visuals);
        }
        return m;
    }
}
