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

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One explanation, the same four parts for every number (SHARED_SCREENS.md section 3): the <b>formula</b> with its actual values, the
 * <b>inputs and lineage</b> (what it was computed from, down to the sources, tables and load dates), <b>why it moved</b> (since the
 * previous date, steps that add up, when the explainer can), the <b>workbook</b> (the live-formula Excel, where one exists), plus the
 * <b>checks</b> and the one-line <b>preview</b> the hover card shows. Built by an {@link Explainer}; the answer is {@link #toMap(ExplainRequest)}.
 *
 * <p>A part the explainer cannot give yet names its entry in the under-construction registry ({@code config/under-construction.yaml});
 * {@link NumberExplainService} adds the entry's words and release. A stored figure says it is stored ({@link #stored(String)}).
 */
public final class Explanation {

    private final Map<String, Object> head = new LinkedHashMap<>();
    private final List<String> facts = new ArrayList<>();
    private final List<String> formula = new ArrayList<>();
    private String rachana;
    private final List<Map<String, Object>> inputs = new ArrayList<>();
    private final List<Map<String, Object>> lineage = new ArrayList<>();
    private Map<String, Object> why;
    private final List<Map<String, Object>> workbook = new ArrayList<>();
    private final List<Map<String, Object>> checks = new ArrayList<>();
    private final List<Map<String, Object>> links = new ArrayList<>();
    private final Map<String, String> unbuilt = new LinkedHashMap<>();

    /**
     * @param kind    the explainer's kind
     * @param measure the measure explained
     * @param subject what the number is of ({@code DESK:RATES}, a cell's coordinates)
     * @param title   the number's name in words ({@code VaR 99%, 1 day})
     */
    public Explanation(String kind, String measure, String subject, String title) {
        head.put("kind", kind);
        head.put("measure", measure);
        head.put("subject", subject);
        head.put("title", title);
        head.put("stored", false);
    }

    /** The number itself, its unit and its business date. */
    public Explanation value(Double value, String unit, String asOf) {
        head.put("value", value);
        head.put("text", value == null ? "—" : number(value));
        head.put("unit", unit);
        head.put("asOf", asOf);
        return this;
    }

    /** Where the number comes from, in words ("computed by the VaR engine in the server"). */
    public Explanation origin(String words) {
        head.put("origin", words);
        return this;
    }

    /** A stored figure says it is stored (number honesty): read from {@code where}, not computed. */
    public Explanation stored(String where) {
        head.put("stored", true);
        head.put("origin", "stored: " + where + " (read as stored, not computed here)");
        return this;
    }

    /** The preview card's one line ({@code the 3rd worst of 250 scenario P&Ls}) and its short facts ({@code 412 positions}). */
    public Explanation preview(String line, String... facts) {
        head.put("preview", line);
        for (String f : facts) {
            if (f != null && !f.isBlank()) {
                this.facts.add(f);
            }
        }
        return this;
    }

    /** The formula's line, with its actual values ({@code k = ceil(250 × (1 − 0.99)) = 3}). */
    public Explanation formula(String line) {
        formula.add(line);
        return this;
    }

    /** The number as the Pivot, BI and Sutras name it ({@code var()}), when it has one. */
    public Explanation rachana(String expression) {
        this.rachana = expression;
        return this;
    }

    /** One input: a label and its value in words or figures. */
    public Explanation input(String label, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("value", value instanceof Number n ? number(n.doubleValue()) : value == null ? "" : String.valueOf(value));
        inputs.add(m);
        return this;
    }

    /** One line of lineage: a source (a table, a connector), what of it was read, and its version or load date. */
    public Explanation lineage(String source, String read, String version) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", source);
        m.put("read", read);
        m.put("version", version == null ? "" : version);
        lineage.add(m);
        return this;
    }

    /**
     * Why it moved: the two dates, the change, whether the steps add up, the views offered and the one drawn, and its panels (ViewModel
     * panels of the standard kinds, VIEWMODEL.md: metric, waterfall, table, hbar, markdown) painted by the one renderer.
     */
    public Explanation why(String from, String to, Double change, boolean addsUp, List<String> views, String view, List<Map<String, Object>> panels,
            Map<String, Object> answer) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("available", true);
        m.put("from", from);
        m.put("to", to);
        m.put("change", change);
        m.put("addsUp", addsUp);
        m.put("views", views);
        m.put("view", view);
        m.put("panels", panels);
        if (answer != null) {
            m.put("answer", answer);                                   // the engine's own answer, for the command line and the API
        }
        this.why = m;
        return this;
    }

    /** Why it moved cannot be told for this number, and why not (one date only, a stored figure): said in words. */
    public Explanation whyUnavailable(String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("available", false);
        m.put("note", note);
        this.why = m;
        return this;
    }

    /** A workbook: the live-formula Excel (or the playground that exports one), by its console address and its API path. */
    public Explanation workbook(String label, String href, String api, String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("href", href);
        if (api != null) {
            m.put("api", api);
        }
        m.put("note", note == null ? "" : note);
        workbook.add(m);
        return this;
    }

    /** A check: passed (true), failed (false) or not made (null), in words. */
    public Explanation check(Boolean ok, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", ok);
        m.put("text", text);
        checks.add(m);
        return this;
    }

    /** A link onward (the screen of an input, the guide). */
    public Explanation link(String label, String href) {
        links.add(Map.of("label", label, "href", href));
        return this;
    }

    /** A part not built yet ({@code why}, {@code workbook}, a check), by its under-construction registry id. */
    public Explanation unbuilt(String part, String registryId) {
        unbuilt.put(part, registryId);
        return this;
    }

    Map<String, String> unbuilt() {
        return unbuilt;
    }

    /** The answer: the head, then the four parts, the checks and the links; a part not asked for is left out. */
    public Map<String, Object> toMap(ExplainRequest r) {
        Map<String, Object> out = new LinkedHashMap<>(head);
        out.put("part", r.part());
        out.put("facts", facts);
        if (r.wants("formula")) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("lines", formula);
            f.put("rachana", rachana);
            out.put("formula", f);
        }
        if (r.wants("inputs")) {
            out.put("inputs", Map.of("rows", inputs, "lineage", lineage));
        }
        if (r.wants("why")) {
            out.put("why", why == null ? new LinkedHashMap<>(Map.of("available", false)) : why);
        }
        if (r.wants("workbook")) {
            out.put("workbook", Map.of("available", !workbook.isEmpty(), "links", workbook));
        }
        if (!r.part().equals("preview")) {
            out.put("checks", checks);
            out.put("links", links);
        }
        return out;
    }

    /** A figure as the screens show it: grouped, no decimals at or above 100, else up to four significant decimals. */
    public static String number(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return "—";
        }
        DecimalFormat f = new DecimalFormat(Math.abs(v) >= 100 ? "#,##0" : "#,##0.####", DecimalFormatSymbols.getInstance(Locale.US));
        return f.format(v);
    }

    /** A signed amount (+1,234 or −1,234). */
    public static String signed(double v) {
        return (v >= 0 ? "+" : "−") + number(Math.abs(v));
    }
}
