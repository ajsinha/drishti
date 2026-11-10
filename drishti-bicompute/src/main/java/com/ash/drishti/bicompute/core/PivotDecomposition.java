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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.kernel.api.pivot.PivotProvider;
import com.ash.drishti.kernel.api.pivot.PivotView;
import com.ash.drishti.kernel.api.pivot.Viewer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One split of a decomposition tree: one node (a path of dimension members), the dimension it is split by, and every figure from the pivot
 * engine for that node's own slice. Holistic measures (VaR, a median) are recomputed per member, never added: the answer gives the engine's
 * Σ children − node as {@code diversification}, and the provider's component measure beside the standalone figure when the source has one
 * ({@code comp_<measure>}; Euler parts of the node, which add up to it because the node's slice is the view). The answer is the pivot's own
 * answer read into the tree's shape; the browser only draws it.
 */
public final class PivotDecomposition {

    /** The most members a split shows (the rest in one Others member, its figure the engine's); a request may ask for fewer. */
    public static final int MAX_MEMBERS = 50;

    private PivotDecomposition() {}

    /**
     * @param body {@code source} (required), {@code measure} (required, a function's text: {@code var()}), {@code split} (a dimension, or absent
     *     for the root alone), {@code path} ({@code [{field, value}]} from the root), {@code date}, {@code filters} ({@code {field: [values]}}),
     *     {@code options} (the provider's), {@code top} (default and most {@value #MAX_MEMBERS})
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> answer(PivotCore core, Viewer v, Map<String, Object> body) {
        String source = text(body, "source");
        String measure = text(body, "measure");
        String split = body.get("split") instanceof String s && !s.isBlank() ? s : null;
        String date = body.get("date") instanceof String d && !d.isBlank() ? d : null;
        int top = Math.max(1, Math.min(MAX_MEMBERS, body.get("top") instanceof Number n ? n.intValue() : MAX_MEMBERS));
        Map<String, Object> filters = new LinkedHashMap<>(body.get("filters") instanceof Map<?, ?> f ? (Map<String, Object>) f : Map.of());
        List<Map<String, Object>> path = new ArrayList<>();
        if (body.get("path") instanceof List<?> l) {
            for (Object o : l) {
                if (!(o instanceof Map<?, ?> m) || m.get("field") == null || m.get("value") == null) {
                    throw bad("a path step is { field, value }: " + o);
                }
                filters.put(String.valueOf(m.get("field")), List.of(String.valueOf(m.get("value"))));
                path.add((Map<String, Object>) m);
            }
        }
        if (split != null && filters.containsKey(split)) {
            throw bad("the path already fixes " + split + ": split by another dimension");
        }
        Component component = componentOf((List<?>) core.catalogue(v, source, date).get("measures"), measure);
        List<Map<String, Object>> values = new ArrayList<>();
        values.add(Map.of("measure", measure, "as", "value"));
        if (component != null) {
            values.add(Map.of("measure", component.name() + "()", "as", "component"));
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("source", source);
        if (date != null) {
            view.put("date", date);
        }
        view.put("rows", split == null ? List.of() : List.of(split));
        view.put("values", values);
        view.put("filters", filters);
        if (body.get("options") instanceof Map<?, ?> o) {
            view.put("options", o);
        }
        if (split != null) {
            view.put("sort", Map.of(split, Map.of("by", "value", "desc", true)));
            view.put("top", Map.of(split, Map.of("n", top, "by", "value")));
        }
        return shape(core.query(v, PivotView.parse(view)), source, measure, split, path, component);
    }

    /** The source's component of a measure: {@code comp_<name>} among its native measures, or null. */
    private static Component componentOf(List<?> natives, String measure) {
        int paren = measure.indexOf('(');
        String name = "comp_" + (paren < 0 ? measure : measure.substring(0, paren)).strip();
        if (natives == null) {
            return null;
        }
        for (Object n : natives) {
            if (n instanceof PivotProvider.Native x && x.name().equals(name)) {
                return new Component(x.name(), x.label());
            }
            if (n instanceof Map<?, ?> m && name.equals(m.get("name"))) {
                return new Component(name, String.valueOf(m.get("label")));
            }
        }
        return null;
    }

    private record Component(String name, String label) {}

    @SuppressWarnings("unchecked")
    private static Map<String, Object> shape(Map<String, Object> a, String source, String measure, String split, List<Map<String, Object>> path,
            Component component) {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) a.get("rows");
        Map<String, Map<String, Object>> cells = (Map<String, Map<String, Object>>) a.get("cells");
        String col = String.valueOf(((List<Map<String, Object>>) a.get("columns")).get(0).get("key"));
        Map<String, Object> m0 = ((List<Map<String, Object>>) a.get("measures")).get(0);
        boolean additive = Boolean.TRUE.equals(m0.get("plainSum"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", source);
        out.put("date", a.get("date"));
        out.put("measure", measure);
        out.put("unit", m0.get("unit"));
        out.put("additive", additive);
        out.put("split", split);
        out.put("path", path);
        out.put("componentLabel", component == null ? null : component.label());
        List<Map<String, Object>> children = new ArrayList<>();
        Map<String, Object> node = null;
        Object gap = null;
        for (Map<String, Object> r : rows) {
            String kind = String.valueOf(r.get("kind"));
            Map<String, Object> cell = cells.get(r.get("key") + "|" + col);
            if ("grand".equals(kind)) {
                node = figures(cell);
                if (r.get("diversification") instanceof Map<?, ?> d) {
                    gap = d.get("value");
                }
            } else if ("node".equals(kind) || "others".equals(kind)) {
                Map<String, Object> ch = figures(cell);
                ch.put("member", r.get("value"));
                ch.put("label", String.valueOf(r.get("label")));
                ch.put("others", "others".equals(kind));
                children.add(ch);
            }
        }
        out.put("node", node);
        out.put("children", children);
        if (!children.isEmpty() && !additive && gap instanceof Number g && node != null && node.get("value") instanceof Number n) {
            out.put("sumStandalone", n.doubleValue() + g.doubleValue());
            out.put("diversification", g.doubleValue());
        }
        out.put("exactness", a.get("exactness"));
        out.put("computedAs", a.get("computedAs"));
        out.put("notes", a.get("notes"));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> figures(Map<String, Object> cell) {
        Map<String, Object> f = new LinkedHashMap<>();
        Map<String, Object> vals = cell == null ? Map.of() : (Map<String, Object>) cell.get("values");
        f.put("value", vals.get("value"));
        f.put("component", vals.get("component"));
        if (cell != null && cell.get("notes") instanceof Map<?, ?> n && n.get("value") != null) {
            f.put("note", n.get("value"));
        }
        return f;
    }

    private static DrishtiException bad(String why) {
        return PivotView.bad(why);
    }

    private static String text(Map<String, Object> body, String key) {
        if (body != null && body.get(key) instanceof String s && !s.isBlank()) {
            return s;
        }
        throw bad("a decomposition names its " + key);
    }
}
