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

import static com.ash.drishti.server.explain.ExplainPanels.change;
import static com.ash.drishti.server.explain.ExplainPanels.riseTone;
import static com.ash.drishti.server.explain.ExplainPanels.row;

import com.ash.drishti.server.explain.ExplainPanels;
import com.ash.drishti.server.explain.Explanation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * VaR's "Why it moved" as the Explain drawer's panels (VAR_GUIDE.md section 3.11): the explanation of {@link VarExplain} (the states, the
 * steps that add up, who drove each) in three views, Waterfall (the steps, every step's drivers, how it was worked out, the states and the
 * order-free view), Matrix (every child node, the steps across) and Sentences. Built on the server from the engine's own answer; the
 * browser paints them with the one renderer.
 */
final class VarWhyPanels {

    static final List<String> VIEWS = List.of("waterfall", "matrix", "sentences");

    private final Map<String, Object> a;

    VarWhyPanels(Map<String, Object> answer) {
        this.a = answer;
    }

    // ---- reading the answer -------------------------------------------------------------------------------------------------------

    static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : Double.NaN;
    }

    static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Object o) {
        return o instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    static String money(Object o) {
        double v = num(o);
        return Double.isNaN(v) ? "—" : Explanation.number(Math.round(v));
    }

    static String signed(Object o) {
        double v = num(o);
        return Double.isNaN(v) ? "—" : Explanation.signed(Math.round(v));
    }

    static String pct(Object o) {
        double v = num(o);
        return Double.isNaN(v) ? "—" : String.format(java.util.Locale.ROOT, "%.1f%%", v * 100);
    }

    private List<Map<String, Object>> lines() {
        return list(a.get("lines"));
    }

    /** The engine's steps that make up one line (a line is one step, or the window's two). */
    private List<Map<String, Object>> stepsOf(Map<String, Object> line) {
        List<String> keys = new ArrayList<>();
        Object ks = line.get("steps");
        if (ks instanceof List<?> l) {
            l.forEach(k -> keys.add(String.valueOf(k)));
        } else {
            keys.add(str(line.get("key")));
        }
        return list(a.get("steps")).stream().filter(s -> keys.contains(str(s.get("key")))).toList();
    }

    // ---- the views ----------------------------------------------------------------------------------------------------------------

    /** The panels of a view (waterfall, sentences; the matrix takes its own answer). */
    List<Map<String, Object>> view(String view, Map<String, Object> matrix) {
        return switch (view) {
            case "matrix" -> matrixView(matrix);
            case "sentences" -> sentencesView();
            default -> waterfallView();
        };
    }

    private List<Map<String, Object>> waterfallView() {
        List<Map<String, Object>> out = new ArrayList<>(tiles());
        out.add(waterfall());
        out.add(stepsTable(5));
        out.addAll(drivers(7));
        out.add(notes());
        out.add(states());
        out.add(orderFree());
        return out;
    }

    private List<Map<String, Object>> tiles() {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Map<String, Object>> st = list(a.get("states"));
        Map<String, Object> first = st.isEmpty() ? Map.of() : st.get(0);
        Map<String, Object> last = st.isEmpty() ? Map.of() : st.get(st.size() - 1);
        boolean captured = Boolean.TRUE.equals(map(a.get("official")).get("captured"));
        double ch = num(a.get("change"));
        out.add(ExplainPanels.metric("t-from", "VaR " + str(a.get("from")), Map.of("text", money(a.get("varFrom"))), null, "USD",
                captured ? "as officially captured" : "the earlier date", 3));
        out.add(ExplainPanels.metric("t-to", "VaR " + str(a.get("to")), Map.of("text", money(a.get("varTo")), "emphasis", true),
                Map.of("text", signed(ch), "tone", riseTone(ch)), "USD", "the later date", 3));
        out.add(ExplainPanels.metric("t-change", "Change", Map.of("text", signed(ch), "tone", riseTone(ch)), null, "USD",
                Boolean.TRUE.equals(a.get("addsUp")) ? "the steps add up exactly" : "the steps do NOT add up: see the warnings", 3));
        Map<String, Object> lim = map(a.get("limit"));
        if (!lim.isEmpty()) {
            double d = (num(lim.get("useTo")) - num(lim.get("useFrom"))) * 100;
            out.add(ExplainPanels.metric("t-limit", "Limit use", Map.of("text", pct(lim.get("useTo"))),
                    Map.of("text", (d >= 0 ? "+" : "−") + String.format(java.util.Locale.ROOT, "%.1f", Math.abs(d)) + " pts", "tone", riseTone(d)),
                    "USD", "was " + pct(lim.get("useFrom")), 3));
        } else {
            out.add(ExplainPanels.metric("t-scen", "VaR scenario", Map.of("text", last.get("varScenario") == null ? "—" : str(last.get("varScenario"))),
                    null, "USD", "was " + (first.get("varScenario") == null ? "—" : str(first.get("varScenario"))), 3));
        }
        return out;
    }

    private Map<String, Object> waterfall() {
        List<Map<String, Object>> steps = new ArrayList<>();
        double from = num(a.get("varFrom"));
        double run = from;
        steps.add(ExplainPanels.step("VaR " + str(a.get("from")).substring(5), from, 0, from, money(from), "muted", true));
        for (Map<String, Object> l : lines()) {
            double e = num(l.get("effect"));
            steps.add(ExplainPanels.step(str(l.get("label")), e, run, run + e, signed(e), riseTone(e), false));
            run += e;
        }
        double to = num(a.get("varTo"));
        steps.add(ExplainPanels.step("VaR " + str(a.get("to")).substring(5), to, 0, to, money(to), "muted", true));
        return ExplainPanels.waterfall("waterfall", "The steps from " + a.get("from") + " to " + a.get("to") + " (rises in VaR orange, falls blue)",
                steps, "USD", 7);
    }

    private Map<String, Object> stepsTable(int span) {
        List<String> cols = List.of("Step", "Effect", "VaR after", "VaR scenario after");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> l : lines()) {
            List<Map<String, Object>> st = stepsOf(l);
            Map<String, Object> lastSt = st.isEmpty() ? Map.of() : st.get(st.size() - 1);
            rows.add(row(cols, List.of(str(l.get("label")), change(num(l.get("effect")), false), money(lastSt.get("varAfter")),
                    (Boolean.TRUE.equals(lastSt.get("varScenarioChanged")) ? "→ " : "") + str(lastSt.get("varScenarioAfter"))), str(l.get("key")), false));
        }
        Map<String, Object> total = row(cols, List.of(Map.of("text", "Σ the change"), change(num(a.get("sumOfSteps")), true), money(a.get("varTo")),
                Map.of()), null, false);
        return ExplainPanels.table("steps", "The steps, each with who drove it below", cols, List.of(false, true, true, false), rows, total, span, null);
    }

    /** Who drove each step, the largest step first. */
    private List<Map<String, Object>> drivers(int span) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Map<String, Object>> ordered = new ArrayList<>(lines());
        ordered.sort(Comparator.comparingDouble((Map<String, Object> l) -> -Math.abs(num(l.get("effect")))));
        for (Map<String, Object> line : ordered) {
            for (Map<String, Object> s : stepsOf(line)) {
                out.addAll(driversOf(s, span));
            }
        }
        return out;
    }

    private List<Map<String, Object>> driversOf(Map<String, Object> s, int span) {
        Map<String, Object> d = map(s.get("drivers"));
        String key = str(s.get("key"));
        String title = s.get("label") + ": " + signed(s.get("effect")) + " — who drove it";
        String kind = str(d.get("kind"));
        List<String> cols;
        List<Boolean> numeric;
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> total = null;
        switch (kind) {
            case "children" -> {
                cols = List.of("node".equals(d.get("by")) ? "Child" : "Book", "Component before", "Component after", "Change", "Share");
                numeric = List.of(false, true, true, true, true);
                for (Map<String, Object> r : list(d.get("rows"))) {
                    rows.add(row(cols, List.of(str(r.get("label")), money(r.get("componentBefore")), money(r.get("componentAfter")),
                            change(num(r.get("change")), false), pct(r.get("share"))), null, false));
                }
                total = row(cols, List.of(Map.of("text", "Σ = the step"), Map.of(), Map.of(), change(num(d.get("sum")), true), Map.of()), null, false);
            }
            case "days" -> {
                cols = List.of("Scenario day", "P&L", "Rank", "In the tail", "The VaR scenario");
                numeric = List.of(false, true, true, false, false);
                for (Map<String, Object> r : list(d.get("rows"))) {
                    rows.add(row(cols, List.of(str(r.get("day")), money(r.get("pnl")), str(r.get("rank")),
                            Boolean.TRUE.equals(r.get("inTail")) ? "yes (rank ≤ " + d.get("k") + ")" : "no",
                            Boolean.TRUE.equals(r.get("varScenario")) ? "yes" : ""), null, false));
                }
            }
            case "trades", "books" -> {
                boolean books = kind.equals("books");
                cols = List.of(books ? "Book" : "Trade", books ? "How" : "Book", books ? "Trades" : "Why", "Incremental VaR");
                numeric = List.of(false, false, books, true);
                for (Map<String, Object> r : list(d.get("rows"))) {
                    rows.add(row(cols, books
                            ? List.of(str(r.get("id")), str(r.get("how")), str(r.get("trades")), change(num(r.get("incrementalVar")), false))
                            : List.of(str(r.get("id")), str(r.get("book")), r.get("reason") != null ? str(r.get("reason")) : str(r.get("product")),
                                    change(num(r.get("incrementalVar")), false)), null, false));
                }
                if (d.get("restLabel") != null) {
                    rows.add(row(cols, List.of(str(d.get("restLabel")), "", "", change(num(d.get("rest")), false)), null, false));
                }
                total = row(cols, List.of(Map.of("text", "Σ = the step"), Map.of(), Map.of(), change(num(s.get("effect")), true)), null, false);
            }
            default -> {
                return List.of(ExplainPanels.markdown("drivers-" + key, title, d.get("note") != null ? str(d.get("note")) : str(s.get("reads")), span));
            }
        }
        int count = (int) Math.max(0, num(d.get("count")) - (Double.isNaN(num(d.get("shown"))) ? 0 : num(d.get("shown"))));
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(ExplainPanels.table("drivers-" + key, title, cols, numeric, rows, total, span,
                d.get("count") != null && count > 0 ? count + " more in the last line" : null));
        if (d.get("note") != null) {
            out.add(ExplainPanels.markdown("note-" + key, "How to read it", str(d.get("note")), span));
        }
        return out;
    }

    private Map<String, Object> states() {
        List<String> cols = List.of("State", "After", "Trades", "Vectors of", "Days", "Scenarios", "VaR", "VaR scenario", "Step");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> s : list(a.get("states"))) {
            rows.add(row(cols, List.of(str(s.get("id")), str(s.get("what")), str(s.get("trades")), str(s.get("vectors")), str(s.get("days")),
                    str(s.get("scenarios")), money(s.get("var")), str(s.get("varScenario")),
                    s.get("effect") == null ? "" : change(num(s.get("effect")), false)), null, false));
        }
        List<Object> t = new ArrayList<>(List.of(Map.of("text", "Σ"), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of()));
        t.add(change(num(a.get("change")), true));
        return ExplainPanels.table("states", "The states: each a full VaR, each step the difference of two", cols,
                List.of(false, false, true, false, false, true, true, false, true), rows, row(cols, t, null, false), 12, null);
    }

    private Map<String, Object> orderFree() {
        Map<String, Object> o = map(a.get("orderFree"));
        List<String> cols = List.of("Part", str(a.get("from")), str(a.get("to")), "Change");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : list(o.get("rows"))) {
            rows.add(row(cols, List.of(r.get("label") + (r.get("note") != null ? " (" + r.get("note") + ")" : ""), money(r.get("from")),
                    money(r.get("to")), change(num(r.get("change")), false)), null, false));
        }
        Map<String, Object> total = row(cols, List.of(Map.of("text", "Σ = the change"), Map.of(), Map.of(), change(num(o.get("total")), true)), null, false);
        return ExplainPanels.table("orderfree", "Order-free: each " + ("node".equals(o.get("by")) ? "child's" : "book's")
                + " component VaR, later less earlier", cols, List.of(false, true, true, true), rows, total, 7, null);
    }

    private Map<String, Object> notes() {
        List<String> out = new ArrayList<>();
        List<String> order = new ArrayList<>();
        if (a.get("order") instanceof List<?> l) {
            l.forEach(x -> order.add(String.valueOf(x)));
        }
        out.add("Order: " + String.join(", ", order) + " (drishti.var.explain.order). Each step is the next state's VaR less this one's, so the steps "
                + "add up exactly; another order gives other step sizes with the same total. The Shapley variant, averaging every order, is planned.");
        Map<String, Object> off = map(a.get("official"));
        if (off.get("note") != null) {
            out.add("Start: " + off.get("note") + ".");
        }
        out.add("Risk factors: the market step by curve, issuer and currency needs position sensitivities (planned); the exact steps above do not.");
        if (a.get("warnings") instanceof List<?> w) {
            w.forEach(x -> out.add("Note: " + x + "."));
        }
        return ExplainPanels.markdown("notes", "How it was worked out", String.join("\n\n", out), 5);
    }

    private Map<String, Object> sentencesPanel() {
        List<String> s = new ArrayList<>();
        if (a.get("sentences") instanceof List<?> l) {
            l.forEach(x -> s.add(String.valueOf(x)));
        }
        return ExplainPanels.markdown("answer", "The answer", String.join(" ", s), 12);
    }

    private List<Map<String, Object>> sentencesView() {
        List<String> cols = List.of("Cause", "Sequence", "Shapley");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> l : lines()) {
            rows.add(row(cols, List.of(str(l.get("label")), change(num(l.get("effect")), false), "planned"), null, false));
        }
        Map<String, Object> total = row(cols, List.of(Map.of("text", "Σ = the change"), change(num(a.get("sumOfSteps")), true), Map.of()), null, false);
        return List.of(sentencesPanel(), ExplainPanels.table("seq", "Sequence and Shapley, by cause (Shapley: planned, not built)", cols,
                List.of(false, true, true), rows, total, 7, null), notes());
    }

    private List<Map<String, Object>> matrixView(Map<String, Object> m) {
        List<Map<String, Object>> steps = list(m.get("steps"));
        List<String> cols = new ArrayList<>(List.of("Node", "VaR " + a.get("from")));
        steps.forEach(s -> cols.add(str(s.get("label"))));
        cols.add("VaR " + a.get("to"));
        cols.add("Change");
        List<Boolean> numeric = new ArrayList<>();
        for (int i = 0; i < cols.size(); i++) {
            numeric.add(i > 0);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> sentences = new ArrayList<>();
        for (Map<String, Object> r : list(m.get("rows"))) {
            Map<String, Object> eff = map(r.get("effects"));
            List<Object> cells = new ArrayList<>();
            cells.add((num(r.get("depth")) > 0 ? "  " : "") + str(r.get("label")));
            cells.add(money(r.get("varFrom")));
            for (Map<String, Object> s : steps) {
                Object v = eff.get(str(s.get("key")));
                cells.add(v == null ? "—" : change(num(v), false));
            }
            cells.add(money(r.get("varTo")));
            cells.add(change(num(r.get("change")), true));
            rows.add(row(cols, cells, null, false));
            if (r.get("sentence") != null) {
                sentences.add("**" + r.get("label") + "**: " + r.get("sentence"));
            }
        }
        Object more = m.get("more");
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(ExplainPanels.table("matrix", "The bridge of every node: the steps across", cols, numeric, rows, null, 12,
                more instanceof Number n && n.intValue() > 0 ? n + " more children" : null));
        out.add(ExplainPanels.markdown("matrix-sentences", "Each node in words", String.join("\n\n", sentences), 12));
        return out;
    }

    /** The answer's one-line summary for the hover card and the sentences (the engine's first sentence). */
    String first() {
        if (a.get("sentences") instanceof List<?> l && !l.isEmpty()) {
            return String.valueOf(l.get(0));
        }
        return "";
    }

    /** The answer kept for the API and the command line (the panels are the screen's). */
    Map<String, Object> answer() {
        return new LinkedHashMap<>(a);
    }
}
