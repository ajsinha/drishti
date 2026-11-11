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
package com.ash.drishti.server.explain;

import com.ash.drishti.kernel.api.explain.Explanation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The ViewModel panels an explanation's "Why it moved" is drawn with (docs/architecture/VIEWMODEL.md, the standard kinds metric, waterfall,
 * table, hbar and markdown): built here, on the server, and painted in the browser by the one renderer (Drishti Render), so the console
 * computes nothing and every screen's Explain looks the same. Amounts are formatted here; tones follow risk (a rise is {@code neg}).
 */
public final class ExplainPanels {

    private ExplainPanels() {}

    /** The tone of a change in a risk figure: a rise is more risk ({@code neg}), a fall less ({@code pos}). */
    public static String riseTone(double v) {
        return v > 0 ? "neg" : v < 0 ? "pos" : "muted";
    }

    /** A cell with a tone and emphasis. */
    public static Map<String, Object> cell(String text, String tone, boolean emphasis) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("text", text);
        if (tone != null) {
            c.put("tone", tone);
        }
        if (emphasis) {
            c.put("emphasis", true);
        }
        return c;
    }

    /** A signed amount, toned as a change in risk. */
    public static Map<String, Object> change(double v, boolean emphasis) {
        return cell(Explanation.signed(v), riseTone(v), emphasis);
    }

    /** A table row: the cells (text, or a cell map) labelled by their columns; {@code ev} the event a click sends (null: none). */
    public static Map<String, Object> row(List<String> cols, List<Object> cells, String ev, boolean highlight) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < cells.size(); i++) {
            Object t = cells.get(i);
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("label", cols.get(i));
            if (t instanceof Map<?, ?> m) {
                m.forEach((k, v) -> c.put(String.valueOf(k), v));
            } else {
                c.put("text", t == null ? "" : String.valueOf(t));
            }
            out.add(c);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("cells", out);
        r.put("highlight", highlight);
        r.put("path", null);
        if (ev != null) {
            r.put("ev", ev);
        }
        return r;
    }

    /** A table panel. */
    public static Map<String, Object> table(String id, String title, List<String> cols, List<Boolean> numeric, List<Map<String, Object>> rows,
            Map<String, Object> total, int span, String more) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("columns", cols);
        d.put("numeric", numeric);
        d.put("rows", rows);
        d.put("search", false);
        if (total != null) {
            d.put("total", total);
        }
        if (more != null) {
            d.put("more", more);
        }
        return panel(id, "table", title, span, rows.isEmpty(), d);
    }

    /** A markdown panel (words). */
    public static Map<String, Object> markdown(String id, String title, String text, int span) {
        return panel(id, "markdown", title, span, text == null || text.isBlank(), Map.of("text", text == null ? "" : text));
    }

    /** A metric tile: its value, an optional delta and a caption. */
    public static Map<String, Object> metric(String id, String label, Map<String, Object> value, Map<String, Object> delta, String unit,
            String caption, int span) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("label", label);
        v.putAll(value);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("value", v);
        d.put("delta", delta);
        d.put("unit", unit);
        d.put("caption", caption == null ? "" : caption);
        return panel(id, "metric", label, span, false, d);
    }

    /** A waterfall panel: the steps from a start total to an end total. */
    public static Map<String, Object> waterfall(String id, String title, List<Map<String, Object>> steps, String unit, int span) {
        return panel(id, "waterfall", title, span, steps.isEmpty(), Map.of("steps", steps, "unit", unit == null ? "" : unit));
    }

    /** One waterfall step. */
    public static Map<String, Object> step(String label, double value, double from, double to, String text, String tone, boolean total) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("label", label);
        s.put("value", value);
        s.put("from", from);
        s.put("to", to);
        s.put("text", text);
        s.put("tone", tone);
        s.put("total", total);
        return s;
    }

    /** A horizontal bar panel: one bar a labelled amount. */
    public static Map<String, Object> hbar(String id, String title, List<Map<String, Object>> bars, int span) {
        return panel(id, "hbar", title, span, bars.isEmpty(), Map.of("bars", bars));
    }

    /** One bar. */
    public static Map<String, Object> bar(String label, double value, String ev) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("label", label);
        b.put("value", value);
        b.put("text", Explanation.signed(value));
        b.put("tone", riseTone(value));
        if (ev != null) {
            b.put("ev", ev);
        }
        return b;
    }

    private static Map<String, Object> panel(String id, String kind, String title, int span, boolean empty, Map<String, Object> data) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", id);
        p.put("kind", kind);
        p.put("title", title);
        p.put("area", "main");
        p.put("span", span);
        p.put("inferred", false);
        p.put("empty", empty);
        p.put("data", data);
        return p;
    }
}
