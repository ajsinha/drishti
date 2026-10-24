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
package com.ash.drishti.engine.view;

import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.rachana.model.ScreenLayout;
import com.ash.drishti.rachana.model.Sutra;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A Sutra's {@code layout:} as the view model carries it (SUTRA_NEXT.md 13): the layout's own keys and each top-level panel's place
 * (its area, main and right mapped onto the picture; its order, section and whether it folds). Pure: the console draws it.
 */
public final class ScreenGrid {

    private ScreenGrid() {}

    /** The view's layout, or null when the Sutra has none (its panels flow, as before). */
    public static Map<String, Object> of(Sutra s) {
        if (s == null || s.layout() == null) {
            return null;
        }
        ScreenLayout layout = s.layout();
        Map<String, Object> out = layout.toView();
        Map<String, Object> place = new LinkedHashMap<>();
        for (Panel p : s.panels()) {
            Map<String, Object> at = new LinkedHashMap<>();
            if (layout.hasAreas()) {
                at.put("area", areaOf(layout, p));
            }
            p.option(Panel.ORDER).ifPresent(o -> at.put("order", Integer.parseInt(o)));
            p.option(Panel.SECTION).ifPresent(x -> at.put("section", x));
            if (Boolean.TRUE.equals(p.options().get(Panel.COLLAPSIBLE))) {
                at.put("collapsible", true);
            }
            place.put(p.id(), at);
        }
        out.put("place", place);
        return out;
    }

    /** The area a panel is drawn in: its named area, else main or right mapped onto the picture (an area so named, else the first or last). */
    static String areaOf(ScreenLayout layout, Panel p) {
        List<String> names = layout.areaNames();
        String named = p.option(Panel.GRID_AREA).orElse(null);
        if (named != null && names.contains(named)) {
            return named;
        }
        String flow = p.area() == null ? "main" : p.area().name().toLowerCase(Locale.ROOT);
        if (names.contains(flow)) {
            return flow;
        }
        return "right".equals(flow) ? names.get(names.size() - 1) : names.get(0);
    }
}
