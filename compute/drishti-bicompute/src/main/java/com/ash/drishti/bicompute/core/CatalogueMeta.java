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

import com.ash.drishti.kernel.api.pivot.Kind;
import com.ash.drishti.kernel.api.pivot.PivotProvider;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The field list's metadata of a source's catalogue (PIVOT_GUIDE.md section 2, the field pickers and panes): for each field its
 * <b>group</b> ({@code hierarchy}, {@code field} or {@code measure}), its <b>display folder</b>, whether it is <b>hidden</b> (under "More
 * fields"), the hierarchy it is a level of, and the <b>functions valid for it</b>; the source's <b>hierarchies</b> as one item each with
 * their levels; and the <b>show-as</b> forms the engine computes (ShowAs). A provider sets folders, hidden fields and hierarchies through
 * its catalogue's notes ({@code folders}: field or measure name to folder; {@code hidden}: field names; {@code hierarchies}: a list of
 * {@code {id, title, levels: [{field, label}], columns}}); a hierarchy's level fields and the columns its levels are built from are
 * hidden automatically. Nothing here computes a figure: the screens read it to offer only what the engine answers.
 */
final class CatalogueMeta {

    /** The built-in functions a value of a field may take, in the pickers' order (the rest stay writable in a view). */
    static final List<String> NUMBER = List.of("sum", "count", "count_distinct", "avg", "wavg", "min", "max", "median", "percentile",
            "var_samp", "stddev_samp");
    /** A rate or a percentage is never summed (ARCHITECTURE.md section 26.3). */
    static final List<String> RATE = List.of("avg", "wavg", "min", "max", "median", "percentile", "var_samp", "stddev_samp", "count",
            "count_distinct");
    /** A text, a date or a hierarchy level: its rows or its distinct values. */
    static final List<String> TEXT = List.of("count", "count_distinct");

    /** The show-as forms ShowAs computes, in the pickers' order; {@code needs} a dimension on the rows or the columns (diff, pct-change). */
    static final List<Map<String, Object>> SHOW_AS = List.of(
            showAs("pct-grand", "% of grand total", null),
            showAs("pct-row", "% of row total", null),
            showAs("pct-column", "% of column total", null),
            showAs("pct-parent-row", "% of parent row", null),
            showAs("rank", "Rank among siblings", null),
            showAs("ntile:4", "Quartile among siblings", null),
            showAs("diff", "Difference from previous", "dimension"),
            showAs("pct-change", "% change vs previous", "dimension"));

    private CatalogueMeta() {}

    private static Map<String, Object> showAs(String id, String label, String needs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("label", label);
        m.put("needs", needs);
        return m;
    }

    /** The functions valid for a field of the source (none when the provider offers no built-ins: its measures are its own). */
    static List<String> functions(PivotProvider.Field f, boolean builtIns) {
        if (!builtIns) {
            return List.of();
        }
        if ("number".equals(f.type())) {
            String u = f.unit() == null ? "" : f.unit().toLowerCase(Locale.ROOT);
            return "rate".equals(u) || "pct".equals(u) ? RATE : NUMBER;
        }
        return TEXT;
    }

    /** The catalogue's fields, measures, hierarchies and show-as forms with their metadata, put on the catalogue's map. */
    @SuppressWarnings("unchecked")
    static void put(Map<String, Object> m, PivotProvider.Catalogue c, boolean builtIns) {
        Map<String, Object> notes = c.notes() == null ? Map.of() : c.notes();
        Map<String, Object> folders = notes.get("folders") instanceof Map<?, ?> f ? (Map<String, Object>) f : Map.of();
        Set<String> hidden = new LinkedHashSet<>();
        if (notes.get("hidden") instanceof List<?> l) {
            l.forEach(x -> hidden.add(String.valueOf(x)));
        }
        List<Map<String, Object>> hierarchies = new ArrayList<>();
        Map<String, String> levelOf = new LinkedHashMap<>();
        if (notes.get("hierarchies") instanceof List<?> l) {
            for (Object o : l) {
                if (!(o instanceof Map<?, ?> h)) {
                    continue;
                }
                Map<String, Object> out = new LinkedHashMap<>();
                String id = String.valueOf(h.get("id"));
                out.put("id", id);
                out.put("title", h.get("title") == null ? id : String.valueOf(h.get("title")));
                List<Map<String, Object>> levels = new ArrayList<>();
                if (h.get("levels") instanceof List<?> ls) {
                    for (Object x : ls) {
                        if (x instanceof Map<?, ?> lv && lv.get("field") != null) {
                            String field = String.valueOf(lv.get("field"));
                            levels.add(Map.of("field", field, "label", lv.get("label") == null ? field : String.valueOf(lv.get("label"))));
                            levelOf.put(field, id);
                        }
                    }
                }
                out.put("levels", levels);
                List<String> columns = new ArrayList<>();
                if (h.get("columns") instanceof List<?> cs) {
                    cs.forEach(x -> columns.add(String.valueOf(x)));
                }
                out.put("columns", columns);
                hidden.addAll(columns);                                   // the columns its levels are built from: the levels say it once
                hierarchies.add(out);
            }
        }
        List<Map<String, Object>> fields = new ArrayList<>();
        for (PivotProvider.Field f : c.fields()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("name", f.name());
            x.put("type", f.type());
            x.put("label", f.label());
            x.put("note", f.note());
            x.put("unit", f.unit());
            String h = levelOf.get(f.name());
            x.put("group", h != null || "hierarchy".equals(f.type()) ? "hierarchy" : "field");
            x.put("hierarchy", h);
            x.put("folder", folders.get(f.name()) == null ? null : String.valueOf(folders.get(f.name())));
            // a hierarchy's level is offered through its hierarchy, so it is hidden from the plain list
            x.put("hidden", hidden.contains(f.name()) || h != null);
            x.put("functions", functions(f, builtIns));
            fields.add(x);
        }
        List<Map<String, Object>> measures = new ArrayList<>();
        for (PivotProvider.Native n : c.measures()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("name", n.name());
            x.put("label", n.label());
            x.put("kind", n.kind());
            x.put("unit", n.unit());
            x.put("description", n.description());
            x.put("group", "measure");
            x.put("folder", folders.get(n.name()) == null ? null : String.valueOf(folders.get(n.name())));
            x.put("hidden", hidden.contains(n.name()));
            // the provider's own figure: computed per cell from the cell's members, never re-aggregated, so no function is offered
            x.put("functions", List.of());
            x.put("perCell", n.kind() != Kind.MERGEABLE);
            measures.add(x);
        }
        Map<String, Object> rest = new LinkedHashMap<>(notes);
        rest.remove("folders");
        rest.remove("hidden");
        rest.remove("hierarchies");
        m.put("fields", fields);
        m.put("measures", measures);
        m.put("hierarchies", hierarchies);
        m.put("showAs", SHOW_AS);
        m.put("notes", rest);
    }
}
