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

import com.ash.drishti.compute.var.VarMath;
import com.ash.drishti.server.dist.XlsxWriter;
import com.ash.drishti.server.playground.PlaygroundTemplate;
import com.ash.drishti.server.playground.SheetStream;
import com.ash.drishti.server.playground.WorkbookStream;
import com.ash.drishti.server.var.VarExplainMath.Op;
import com.ash.drishti.server.var.VarExplainMath.State;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "Why it moved" as a workbook of formulas (VAR_GUIDE.md section 3.11), on the VaR Playground's frame (its template, styles and checks,
 * docs/guides/VAR_GUIDE.md section 3.10): Main (the inputs, each state's VaR worked from its column beside the engine's with a check, each
 * step as a difference of two cells, the add-up check), Data (values only: the two dates' scope P&amp;L and the churn's sums by scenario
 * day), States (each state's P&amp;L column, a formula over the one before and Data, and its ranks), Drivers (the engine's contributors),
 * Explain and Provenance. Every formula is checked against the common function set ({@link com.ash.drishti.server.dist.XlsxFormulas}).
 */
final class VarExplainBook {

    static final String PASS = VarPlaygroundBook.PASS;
    static final String FAIL = VarPlaygroundBook.FAIL;
    static final String ALL_PASS = VarPlaygroundBook.ALL_PASS;
    /** Data's columns, from B (A is the day). */
    static final List<String> DATA = List.of("In earlier set", "In later set", "Captured", "Earlier now", "Moved in (earlier)", "Moved out (earlier)",
            "Trades out", "Amended (earlier)", "Later", "Trades in", "Amended (later)", "Moved in (later)", "Moved out (later)");
    private static final int STATE_FIRST = 12;

    private final VarExplain.Result r;
    private final PlaygroundTemplate t;
    private final boolean cached;
    private final String user;
    private final String now;
    private final VarExplainMath.Result math;
    private final int n;
    private final List<State> states;
    private final Map<String, String> names = new LinkedHashMap<>();
    private final Map<String, Object> values = new LinkedHashMap<>();

    VarExplainBook(VarExplain.Result r, PlaygroundTemplate t, boolean cached, String user, String now) {
        this.r = r;
        this.t = t;
        this.cached = cached;
        this.user = user == null ? "" : user;
        this.now = now == null ? "" : now;
        this.math = r.math();
        this.n = math.days().size();
        this.states = math.states();
    }

    /** The built-in template: the Playground's look, this workbook's own Explain words. */
    static PlaygroundTemplate builtIn() {
        return PlaygroundTemplate.builtIn("var-explain", VarPlaygroundBook.STYLES);
    }

    void write(OutputStream out) {
        Map<String, String> v = new LinkedHashMap<>(t.brandingValues(com.ash.drishti.common.Branding.current()));
        v.put("TITLE", "Why VaR moved · " + r.label() + " · " + r.from() + " to " + r.to());
        v.put("SUBJECT", "The VaR change split into causes that add up exactly, worked in formulas");
        v.put("USER", user);
        v.put("NOW", now);
        try (WorkbookStream w = new WorkbookStream(out, t, v)) {
            main(w.sheet("Main", "1F3A5F"));
            data(w.sheet("Data", "B08A2E"));
            states(w.sheet("States", "2E5A88"));
            drivers(w.sheet("Drivers", "2E5A88"));
            explain(w.sheet("Explain", "6B7686"));
            provenance(w.sheet("Provenance", "6B7686"));
            names.forEach((name, ref) -> w.name(name, "Main", ref));
        }
    }

    private Double c(double x) {
        return cached ? x : null;
    }

    private String ct(String s) {
        return cached ? s : null;
    }

    // ---- references -----------------------------------------------------------------------------------------------------------------

    private int dataRow(int u) {
        return 2 + u;
    }

    private String dataCol(String name) {
        return XlsxWriter.col(1 + DATA.indexOf(name));
    }

    private String data(String name, int u) {
        return "Data!" + dataCol(name) + dataRow(u);
    }

    private String pnlCol(int s) {
        return XlsxWriter.col(1 + 2 * s);
    }

    private String rankCol(int s) {
        return XlsxWriter.col(2 + 2 * s);
    }

    /** A state's P&amp;L column on States, absolute. */
    private String range(int s) {
        String col = pnlCol(s);
        return "States!$" + col + "$2:$" + col + "$" + (n + 1);
    }

    private String dates() {
        return "States!$A$2:$A$" + (n + 1);
    }

    private int stateRow(int s) {
        return STATE_FIRST + s;
    }

    // ---- Main ------------------------------------------------------------------------------------------------------------------------

    private void main(SheetStream s) {
        int last = states.size() - 1;
        int stepsHead = stateRow(last) + 2;
        int stepFirst = stepsHead + 1;
        int stepLast = stepFirst + math.steps().size() - 1;
        int sumRow = stepLast + 1;
        int changeRow = sumRow + 1;
        int addRow = changeRow + 1;
        int laterRow = addRow + 1;
        int orderRow = laterRow + 2;
        s.freeze(3, 0).width(0, 12).width(1, 40).widths(2, 6, 9).width(7, 18).width(8, 14).width(9, 18).width(10, 8);
        s.row(1);
        s.text(0, com.ash.drishti.common.Branding.current().productOr("") + " · Why VaR moved · " + r.label() + " · " + r.from() + " to " + r.to(),
                "title");
        for (int col = 1; col <= 10; col++) {
            s.blank(col, "title");
        }
        s.row(2);
        s.text(0, r.canonical() + " · " + states.size() + " states, " + math.steps().size() + " steps · USD, 1-day · order: " + String.join(", ",
                order()), "band");
        for (int col = 1; col <= 10; col++) {
            s.blank(col, "band");
        }
        boolean ok = allOk();
        String status = "IF(AND(SUMPRODUCT((K" + STATE_FIRST + ":K" + stateRow(last) + "=\"" + PASS + "\")*1)=" + states.size() + ",SUMPRODUCT((G"
                + stepFirst + ":G" + stepLast + "=\"" + PASS + "\")*1)=" + math.steps().size() + ",B" + addRow + "=\"" + PASS + "\",B" + laterRow + "=\""
                + PASS + "\"),\"" + ALL_PASS + "\",\"CHECK THE RED CELLS\")";
        s.row(3);
        s.text(0, "Status", "label");
        s.formulaText(1, status, ct(ok ? ALL_PASS : "CHECK THE RED CELLS"), "band.status");
        s.row(5);
        s.text(0, "Inputs", "head");
        s.text(1, "Value", "head");
        s.text(2, "Meaning", "head");
        named(s, 6, "Confidence", "VaR confidence: the same k rule for every state", x -> x.number(1, r.calc().confidence(), "input.dec"), r.calc().confidence());
        String rule = r.calc().rule() == VarMath.RankRule.INTERPOLATED ? "interpolated" : "kth-worst";
        named(s, 7, "RankRule", "kth-worst or interpolated", x -> x.text(1, rule, "input.text"), rule);
        named(s, 8, "FromDate", "the earlier business date", x -> x.text(1, r.from().toString(), "text"), r.from().toString());
        named(s, 9, "ToDate", "the later business date", x -> x.text(1, r.to().toString(), "text"), r.to().toString());
        s.row(11);
        String[] head = {"State", "After the step", "N", "k", "ILo", "IHi", "IW", "VaR", "VaR scenario", "Engine", "Check"};
        for (int i = 0; i < head.length; i++) {
            s.text(i, head[i], "head");
        }
        for (int i = 0; i <= last; i++) {
            State st = states.get(i);
            int row = stateRow(i);
            String rng = range(i);
            int nn = st.n();
            double pos = nn * (1 - r.calc().confidence());
            int ilo = Math.max(1, (int) Math.floor(pos));
            s.row(row);
            s.text(0, st.id(), "label");
            s.text(1, st.op() == null ? "Start: " + (r.inputs().captured() == null ? "the earlier date now" : "the earlier date as captured")
                    : st.op().label, "text");
            s.formula(2, "COUNT(" + rng + ")", c(nn), "int");
            s.formula(3, "MAX(1,MIN(C" + row + ",ROUNDUP(C" + row + "*(1-Confidence)-1E-9,0)))", c(st.k()), "int");
            s.formula(4, "MAX(1,ROUNDDOWN(C" + row + "*(1-Confidence),0))", c(ilo), "int");
            s.formula(5, "MIN(C" + row + ",E" + row + "+1)", c(Math.min(nn, ilo + 1)), "int");
            s.formula(6, "MIN(1,MAX(0,C" + row + "*(1-Confidence)-E" + row + "))", c(Math.min(1, Math.max(0, pos - ilo))), "rate");
            s.formula(7, "IF(RankRule=\"interpolated\",-((1-G" + row + ")*SMALL(" + rng + ",E" + row + ")+G" + row + "*SMALL(" + rng + ",F" + row
                    + ")),-SMALL(" + rng + ",D" + row + "))", c(st.var()), "money.bold");
            String day = st.varDay() < 0 ? "" : math.days().union().get(st.varDay()).toString();
            s.formulaText(8, "INDEX(" + dates() + ",MATCH(SMALL(" + rng + ",D" + row + ")," + rng + ",0))", ct(day), "text");
            s.number(9, st.var(), "money");
            s.formulaText(10, "IF(ABS(H" + row + "-J" + row + ")<0.005,\"" + PASS + "\",\"" + FAIL + "\")", ct(PASS), "check");
        }
        names.put("VaRFrom", "$H$" + STATE_FIRST);
        values.put("VaRFrom", states.get(0).var());
        names.put("VaRTo", "$H$" + stateRow(last));
        values.put("VaRTo", states.get(last).var());
        s.row(stepsHead);
        String[] sh = {"Step", "Cause", "From", "To", "Effect", "Engine", "Check"};
        for (int i = 0; i < sh.length; i++) {
            s.text(i, sh[i], "head");
        }
        for (int i = 0; i < math.steps().size(); i++) {
            VarExplainMath.Step st = math.steps().get(i);
            int row = stepFirst + i;
            s.row(row);
            s.text(0, String.valueOf(st.index()), "label");
            s.text(1, st.op().label, "text");
            s.text(2, st.before().id(), "text");
            s.text(3, st.after().id(), "text");
            s.formula(4, "H" + stateRow(st.after().index()) + "-H" + stateRow(st.before().index()), c(st.effect()), "money.bold");
            s.number(5, st.effect(), "money");
            s.formulaText(6, "IF(ABS(E" + row + "-F" + row + ")<0.005,\"" + PASS + "\",\"" + FAIL + "\")", ct(PASS), "check");
        }
        s.row(sumRow);
        s.text(0, "StepsSum", "label");
        s.text(1, "the sum of the steps", "note");
        s.formula(4, "SUM(E" + stepFirst + ":E" + stepLast + ")", c(math.sumOfSteps()), "total");
        names.put("StepsSum", "$E$" + sumRow);
        values.put("StepsSum", math.sumOfSteps());
        s.row(changeRow);
        s.text(0, "Change", "label");
        s.text(1, "the last state's VaR less the first's", "note");
        s.formula(4, "VaRTo-VaRFrom", c(math.change()), "total");
        names.put("Change", "$E$" + changeRow);
        values.put("Change", math.change());
        s.row(addRow);
        s.text(0, "Adds up", "label");
        s.formulaText(1, "IF(ABS(StepsSum-Change)<0.005,\"" + PASS + "\",\"" + FAIL + "\")", ct(PASS), "check");
        s.text(2, "the steps add up to the change (a telescoping sum)", "note");
        State lastSt = states.get(last);
        String later = "Data!$" + dataCol("Later") + "$2:$" + dataCol("Later") + "$" + (n + 1);
        s.row(laterRow);
        s.text(0, "Is the later", "label");
        s.formulaText(1, "IF(ABS(VaRTo-(IF(RankRule=\"interpolated\",-((1-G" + stateRow(last) + ")*SMALL(" + later + ",E" + stateRow(last) + ")+G"
                + stateRow(last) + "*SMALL(" + later + ",F" + stateRow(last) + ")),-SMALL(" + later + ",D" + stateRow(last) + "))))<0.005,\"" + PASS
                + "\",\"" + FAIL + "\")", ct(PASS), "check");
        s.text(2, "the last state is the later date's scope (" + lastSt.id() + " against Data, Later)", "note");
        s.row(orderRow);
        s.text(0, "Order", "label");
        s.text(1, String.join(", ", order()) + " (drishti.var.explain.order): another order gives other step sizes with the same total", "wrap");
        s.merge("A1:K1");
        s.merge("A2:K2");
        s.checks("K" + STATE_FIRST + ":K" + stateRow(last), PASS);
        s.checks("G" + stepFirst + ":G" + stepLast, PASS);
        s.checks("B" + addRow + ":B" + laterRow, PASS);
        s.checks("B3", ALL_PASS);
    }

    private List<String> order() {
        @SuppressWarnings("unchecked")
        List<String> o = (List<String>) r.answer().get("order");
        return o == null ? List.of() : o;
    }

    private boolean allOk() {
        return Math.abs(math.sumOfSteps() - math.change()) < 0.005;
    }

    private void named(SheetStream s, int row, String name, String meaning, java.util.function.Consumer<SheetStream> value, Object v) {
        names.put(name, "$B$" + row);
        values.put(name, v);
        s.row(row);
        s.text(0, name, "label");
        value.accept(s);
        s.text(2, meaning, "note");
    }

    // ---- Data ------------------------------------------------------------------------------------------------------------------------

    private void data(SheetStream s) {
        VarExplainMath.Days d = math.days();
        VarExplainMath.Inputs in = r.inputs();
        List<double[]> cols = new ArrayList<>();
        cols.add(null);
        cols.add(null);
        cols.add(in.captured() == null ? null : d.liftFrom(in.captured()));
        cols.add(d.liftFrom(in.fromTotal()));
        cols.add(d.liftFrom(in.movedInFrom()));
        cols.add(d.liftFrom(in.movedOutFrom()));
        cols.add(d.liftFrom(in.outFrom()));
        cols.add(d.liftFrom(in.amendedFrom()));
        cols.add(d.liftTo(in.toTotal()));
        cols.add(d.liftTo(in.inTo()));
        cols.add(d.liftTo(in.amendedTo()));
        cols.add(d.liftTo(in.movedInTo()));
        cols.add(d.liftTo(in.movedOutTo()));
        s.freeze(1, 1).width(0, 12).widths(1, 2, 9).widths(3, DATA.size(), 16);
        s.row(1);
        s.text(0, "Scenario day", "head");
        for (int i = 0; i < DATA.size(); i++) {
            s.text(1 + i, DATA.get(i), "head");
        }
        for (int u = 0; u < n; u++) {
            s.row(dataRow(u));
            s.text(0, d.union().get(u).toString(), "text");
            s.number(1, d.inFrom()[u] ? 1 : 0, "int");
            s.number(2, d.inTo()[u] ? 1 : 0, "int");
            for (int i = 2; i < cols.size(); i++) {
                double[] v = cols.get(i);
                if (v != null && !Double.isNaN(v[u])) {
                    s.number(1 + i, v[u], "input.money");
                }
            }
        }
    }

    // ---- States ----------------------------------------------------------------------------------------------------------------------

    private void states(SheetStream s) {
        s.freeze(1, 1).width(0, 12).widths(1, 2 * states.size(), 15);
        s.row(1);
        s.text(0, "Scenario day", "head");
        for (State st : states) {
            s.text(1 + 2 * st.index(), st.id() + " P&L", "head");
            s.text(2 + 2 * st.index(), st.id() + " rank", "head");
        }
        List<int[]> ranks = new ArrayList<>();
        for (State st : states) {
            int[] rk = new int[n];
            int[] order = st.measures().order();
            for (int i = 0; i < order.length; i++) {
                rk[st.at()[order[i]]] = i + 1;
            }
            ranks.add(rk);
        }
        boolean captured = r.inputs().captured() != null;
        for (int u = 0; u < n; u++) {
            int row = dataRow(u);
            s.row(row);
            s.text(0, math.days().union().get(u).toString(), "text");
            for (State st : states) {
                int col = 1 + 2 * st.index();
                String prev = st.index() == 0 ? null : pnlCol(st.index() - 1) + row;
                String f = formula(st, prev, u, captured);
                double v = st.v()[u];
                if (Double.isNaN(v)) {
                    s.formulaText(col, f, ct(""), "money");
                    s.formulaText(col + 1, rankFormula(st.index(), row), ct(""), "int");
                } else {
                    s.formula(col, f, c(v), "money");
                    s.formula(col + 1, rankFormula(st.index(), row), c(ranks.get(st.index())[u]), "int");
                }
            }
        }
    }

    /** A state's P&amp;L cell: the state before it changed by one step (its op), over Data. */
    private String formula(State st, String prev, int u, boolean captured) {
        String inF = "Data!$B" + dataRow(u) + "=1";
        String inT = "Data!$C" + dataRow(u) + "=1";
        if (st.op() == null) {
            return "IF(" + inF + "," + data(captured ? "Captured" : "Earlier now", u) + ",\"\")";
        }
        String num = "ISNUMBER(" + prev + ")";
        Op op = st.op();
        return switch (op) {
            case RESTATE -> "IF(" + num + "," + prev + "-(" + data(captured ? "Captured" : "Earlier now", u) + "-" + data("Earlier now", u) + "),\"\")";
            case HIER -> st.today() ? "IF(" + num + "," + prev + "+" + data("Moved in (later)", u) + "-" + data("Moved out (later)", u) + ",\"\")"
                    : "IF(" + num + "," + prev + "+" + data("Moved in (earlier)", u) + "-" + data("Moved out (earlier)", u) + ",\"\")";
            case OUT -> "IF(" + num + "," + prev + "-" + data("Trades out", u) + ",\"\")";
            case AMEND_OUT -> "IF(" + num + "," + prev + "-" + data("Amended (earlier)", u) + ",\"\")";
            case DROP -> "IF(AND(" + num + "," + inT + ")," + prev + ",\"\")";
            case MARKET -> "IF(AND(" + inF + "," + inT + ")," + laterLess(st, u) + ",\"\")";
            case ADD, SET_CHANGE -> "IF(" + inT + "," + laterLess(st, u) + ",\"\")";
            case IN -> "IF(" + num + "," + prev + "+" + data("Trades in", u) + ",\"\")";
            case AMEND_IN -> "IF(" + num + "," + prev + "+" + data("Amended (later)", u) + ",\"\")";
        };
    }

    /** The later date's scope less what the steps after the market put in. */
    private String laterLess(State st, int u) {
        StringBuilder b = new StringBuilder(data("Later", u));
        List<Op> later = math.ops().subList(st.index(), math.ops().size());
        for (Op o : later) {
            switch (o) {
                case IN -> b.append('-').append(data("Trades in", u));
                case AMEND_IN -> b.append('-').append(data("Amended (later)", u));
                case HIER -> b.append("-(").append(data("Moved in (later)", u)).append('-').append(data("Moved out (later)", u)).append(')');
                default -> { }
            }
        }
        return b.toString();
    }

    /** A day's rank in a state: the days with a lower P&amp;L, plus the earlier days with the same, plus one; empty outside the state. */
    private String rankFormula(int s, int row) {
        String col = pnlCol(s);
        String x = col + row;
        String same = row == 2 ? "" : "+SUMPRODUCT(($" + col + "$2:" + col + (row - 1) + "=" + x + ")*1)";
        return "IF(ISNUMBER(" + x + "),SUMPRODUCT((" + range(s).replace("States!", "") + "<" + x + ")*1)" + same + "+1,\"\")";
    }

    // ---- Drivers, Explain, Provenance -----------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void drivers(SheetStream s) {
        s.width(0, 34).widths(1, 6, 18);
        int row = 1;
        s.row(row++);
        s.text(0, "Who drove each step (the engine's figures)", "title");
        for (int col = 1; col <= 6; col++) {
            s.blank(col, "title");
        }
        for (Map<String, Object> st : (List<Map<String, Object>>) r.answer().get("steps")) {
            row++;
            s.row(row++);
            s.text(0, st.get("index") + ". " + st.get("label"), "head");
            s.text(1, "Effect", "head");
            s.number(2, num(st.get("effect")), "money.bold");
            Map<String, Object> d = (Map<String, Object>) st.get("drivers");
            if (d == null) {
                continue;
            }
            if (d.get("note") != null) {
                s.row(row++);
                s.text(0, String.valueOf(d.get("note")), "note");
            }
            for (Map<String, Object> x : (List<Map<String, Object>>) d.getOrDefault("rows", List.of())) {
                s.row(row++);
                int col = 0;
                for (Map.Entry<String, Object> e : x.entrySet()) {
                    if (col > 6) {
                        break;
                    }
                    if (e.getValue() instanceof Number nb && !(e.getValue() instanceof Integer)) {
                        s.number(col++, nb.doubleValue(), "money");
                    } else {
                        s.text(col++, e.getKey() + ": " + e.getValue(), "text");
                    }
                }
            }
            if (d.get("restLabel") != null) {
                s.row(row++);
                s.text(0, String.valueOf(d.get("restLabel")), "label");
                s.number(1, num(d.get("rest")), "money");
            }
        }
        Map<String, Object> free = (Map<String, Object>) r.answer().get("orderFree");
        if (free != null) {
            row++;
            s.row(row++);
            s.text(0, "Order-free: each part's component VaR, later less earlier", "head");
            s.text(1, "Earlier", "head");
            s.text(2, "Later", "head");
            s.text(3, "Change", "head");
            for (Map<String, Object> x : (List<Map<String, Object>>) free.get("rows")) {
                s.row(row++);
                s.text(0, String.valueOf(x.get("label")), "text");
                s.number(1, num(x.get("from")), "money");
                s.number(2, num(x.get("to")), "money");
                s.number(3, num(x.get("change")), "money");
            }
            s.row(row);
            s.text(0, "Total (the change)", "label");
            s.number(3, num(free.get("total")), "total");
        }
    }

    private static double num(Object o) {
        return o instanceof Number x ? x.doubleValue() : Double.NaN;
    }

    private void explain(SheetStream s) {
        s.width(0, 44).width(1, 150);
        s.row(1);
        s.text(0, com.ash.drishti.common.Branding.current().productOr("") + " · Why VaR moved · how each figure is worked out", "title");
        s.blank(1, "title");
        Map<String, String> refs = new LinkedHashMap<>(names);
        refs.replaceAll((k, v) -> "Main!" + v.replace("$", ""));
        int row = 3;
        for (String[] step : t.explain()) {
            s.row(row++);
            s.text(0, fill(step[0], refs), "label.wrap");
            s.text(1, fill(step[1], refs), "wrap");
        }
    }

    private String fill(String text, Map<String, String> refs) {
        for (Map.Entry<String, Object> v : values.entrySet()) {
            text = text.replace("{" + v.getKey() + ":v}", shown(v.getValue()));
        }
        for (Map.Entry<String, String> ref : refs.entrySet()) {
            text = text.replace("{" + ref.getKey() + "}", ref.getValue());
        }
        return text;
    }

    private static String shown(Object v) {
        if (v instanceof Double d) {
            return String.format(Locale.ROOT, "%,.2f", d);
        }
        return String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private void provenance(SheetStream s) {
        s.width(0, 34).width(1, 120);
        s.row(1);
        s.text(0, "Provenance", "title");
        s.blank(1, "title");
        int row = 3;
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("scope", r.canonical());
        p.put("from", r.from().toString());
        p.put("to", r.to().toString());
        p.put("made by", user);
        p.put("made at", now);
        Object off = r.answer().get("official");
        if (off instanceof Map<?, ?> o) {
            p.put("official", o.get("note"));
        }
        if (r.answer().get("provenance") instanceof Map<?, ?> pr) {
            pr.forEach((k, v) -> p.put(String.valueOf(k), v));
        }
        p.put("warnings", r.answer().get("warnings"));
        for (Map.Entry<String, Object> e : p.entrySet()) {
            s.row(row++);
            s.text(0, e.getKey(), "label");
            s.text(1, e.getValue() == null ? "" : String.valueOf(e.getValue()), "wrap");
        }
    }
}
