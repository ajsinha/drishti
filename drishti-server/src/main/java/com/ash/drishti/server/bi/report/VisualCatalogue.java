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
package com.ash.drishti.server.bi.report;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The visuals a report may hold and the wells each takes (RUPAKA.md section 8). A well holds fields (columns of the dataset to group by), measures
 * (the dataset's named calculations) or either; a type lists its wells, which are required and how many items each may hold. Types the grammar
 * reserves are listed in {@link #RESERVED} and refused as not built.
 */
public final class VisualCatalogue {

    /** What a well holds. */
    public enum Kind { FIELD, MEASURE, ANY }

    /** One well: its kind, whether it must be filled, and the most items it takes (0: any number). */
    public record Well(Kind kind, boolean required, int max) {}

    /** A visual type: the group it belongs to (chart, card, table, slicer, static) and its wells in order. */
    public record Type(String group, Map<String, Well> wells) {}

    /** Types the grammar reserves and this server does not build (RUPAKA.md sections 8 and 12). */
    public static final Set<String> RESERVED = Set.of("bubble", "funnel", "histogram", "box", "map", "python", "entity-view", "shape");
    /** Slicer modes built; {@code relative-date} is reserved. */
    public static final Set<String> SLICER_MODES = Set.of("list", "dropdown", "range");

    private static final Map<String, Type> TYPES = new LinkedHashMap<>();

    static {
        Well axis = new Well(Kind.FIELD, true, 1);
        Well legend = new Well(Kind.FIELD, false, 1);
        Well values = new Well(Kind.MEASURE, true, 0);
        for (String t : List.of("bar", "column", "line", "area")) {
            put(t, "chart", "axis", axis, "legend", legend, "values", values);
        }
        put("combo", "chart", "axis", axis, "values", values, "secondary", new Well(Kind.MEASURE, false, 0));
        put("scatter", "chart", "details", new Well(Kind.FIELD, true, 1), "x", new Well(Kind.MEASURE, true, 1), "y", new Well(Kind.MEASURE, true, 1), "size",
                new Well(Kind.MEASURE, false, 1));
        for (String t : List.of("pie", "donut")) {
            put(t, "chart", "axis", axis, "values", new Well(Kind.MEASURE, true, 1));
        }
        put("waterfall", "chart", "axis", axis, "values", new Well(Kind.MEASURE, true, 1));
        put("treemap", "chart", "axis", new Well(Kind.FIELD, true, 2), "values", new Well(Kind.MEASURE, true, 1));
        put("heatmap", "chart", "axis", axis, "legend", new Well(Kind.FIELD, true, 1), "values", new Well(Kind.MEASURE, true, 1));
        put("card", "card", "values", new Well(Kind.MEASURE, true, 1));
        put("multirow-card", "card", "axis", legend, "values", values);
        put("kpi", "card", "values", new Well(Kind.MEASURE, true, 1), "target", new Well(Kind.MEASURE, false, 1), "axis", legend);
        put("gauge", "card", "values", new Well(Kind.MEASURE, true, 1), "target", new Well(Kind.MEASURE, false, 1));
        put("table", "table", "columns", new Well(Kind.ANY, true, 0));
        put("matrix", "table", "rows", new Well(Kind.FIELD, true, 0), "columns", new Well(Kind.FIELD, false, 1), "values", values);
        put("slicer", "slicer", "field", new Well(Kind.FIELD, true, 1));
        // streaming BI (phase 5): Perspective grids and pivots over the rows of one table, and a rolling line of measures
        put("perspective-grid", "perspective", "columns", new Well(Kind.FIELD, true, 0));
        put("perspective-pivot", "perspective", "rows", new Well(Kind.FIELD, true, 0), "columns", new Well(Kind.FIELD, false, 0), "values", new Well(Kind.FIELD, true, 0));
        put("live-chart", "live", "values", new Well(Kind.MEASURE, true, 4));
        put("text", "static");
        put("image", "static");
        put("button", "static");
    }

    private VisualCatalogue() {}

    private static void put(String type, String group, Object... wells) {
        Map<String, Well> w = new LinkedHashMap<>();
        for (int i = 0; i < wells.length; i += 2) {
            w.put((String) wells[i], (Well) wells[i + 1]);
        }
        TYPES.put(type, new Type(group, w));
    }

    public static Type type(String name) {
        return TYPES.get(name);
    }

    public static Set<String> names() {
        return TYPES.keySet();
    }

    /** The catalogue as JSON for the designer: per type its group and wells (kind, required, max). */
    public static Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        TYPES.forEach((name, t) -> {
            Map<String, Object> wells = new LinkedHashMap<>();
            t.wells().forEach((w, spec) -> wells.put(w, Map.of("kind", spec.kind().name().toLowerCase(Locale.ROOT), "required", spec.required(), "max", spec.max())));
            out.put(name, Map.of("group", t.group(), "wells", wells));
        });
        return out;
    }
}
