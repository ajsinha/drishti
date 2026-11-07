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

import static com.ash.drishti.server.var.VarWhyPanels.map;
import static com.ash.drishti.server.var.VarWhyPanels.num;
import static com.ash.drishti.server.var.VarWhyPanels.str;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.compute.var.VarLens;
import com.ash.drishti.server.explain.ExplainRequest;
import com.ash.drishti.server.explain.Explainer;
import com.ash.drishti.server.explain.Explanation;
import com.ash.drishti.server.security.Principal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Explain a VaR figure (kind {@code var}; VAR_GUIDE.md section 3.13): VaR, ES, parametric VaR and ES, and the fat-tail gap of a scope, as
 * the VaR page shows them. One explanation folds what were three panels: the formula with its values and the parametric lines (the
 * <b>?</b> pop-ups), the inputs and lineage (How calculated), and why it moved (the Why it moved tab: {@link VarExplain}'s exact steps in
 * three views). Every figure is the engine's own answer for the person ({@link VarViews#headline}), so the numbers are the screen's.
 *
 * <p>Arguments: {@code scope} (required), {@code date} (the business date; none: the newest), {@code ccy}, the lens ({@code horizon},
 * {@code method}, {@code confidence}, {@code esConfidence}, {@code set}), and for why it moved {@code from}, {@code to} and {@code view}
 * (waterfall, matrix, sentences) and {@code attribution} (sequence; shapley is planned and refused in words).
 */
final class VarNumberExplainer implements Explainer {

    static final List<String> MEASURES = List.of("var", "es", "pvar", "pes", "pgap");

    private final VarService svc;
    private final VarViews views;
    private final VarExplain why;

    VarNumberExplainer(VarService svc, VarViews views, VarExplain why) {
        this.svc = svc;
        this.views = views;
        this.why = why;
    }

    @Override
    public String kind() {
        return "var";
    }

    @Override
    public String title() {
        return "VaR and ES of a scope: historical, parametric and the fat-tail gap";
    }

    @Override
    public List<String> measures() {
        return MEASURES;
    }

    @Override
    public String engine() {
        return "var";
    }

    private VarLens lens(ExplainRequest r) {
        String h = r.text("horizon");
        String m = r.text("method");
        Double c = r.number("confidence");
        Double ec = r.number("esConfidence");
        String set = r.text("set");
        if (h == null && m == null && c == null && ec == null && set == null) {
            return null;
        }
        try {
            return svc.props().lens(h, m, c, ec, set);
        } catch (IllegalArgumentException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, e.getMessage());
        }
    }

    @Override
    public Explanation explain(Principal p, ExplainRequest r) {
        String scope = r.required("scope", "scope=BANK, DESK:RATES, NODE:<id>, PORT:<id> ...");
        String measure = r.measureOr("var");
        LocalDate date = r.date("date");
        String ccy = r.text("ccy");
        VarLens lens = lens(r);
        Map<String, Object> h = lens == null ? views.headline(p, scope, date, ccy) : views.headline(p, scope, date, ccy, lens);
        Map<String, Object> prov = map(h.get("provenance"));
        Map<String, Object> pm = map(h.get("parametric"));
        boolean parametric = measure.startsWith("p");
        if (parametric && pm.isEmpty()) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, Boolean.TRUE.equals(h.get("empty")) ? "no trades: nothing to fit"
                    : "parametric VaR is switched off on this server (drishti.var.parametric.enabled)");
        }
        String day = str(h.get("businessDate"));
        String cur = h.get("currency") == null ? "USD" : str(h.get("currency"));
        String days = "10D".equals(h.get("horizon")) ? "10 days" : "1 day";
        Explanation x = new Explanation("var", measure, str(h.get("canonical")), title(measure, h, pm, days) + ", " + h.get("label"));
        x.value(value(measure, h, pm), cur, day);
        x.origin("computed by the VaR engine in the server for you (" + prov.get("engine") + ")");
        if (parametric) {
            List<String> lines = lines(measure, pm);
            x.preview(lines.isEmpty() ? "" : lines.get(0), "cross-check, not official", int0(prov.get("positions")) + " positions");
            lines.forEach(x::formula);
            x.rachana("pvar".equals(measure) ? "pvar()" : null);
        } else {
            formula(x, measure, h, prov, p, scope, date, ccy, lens);
        }
        if (r.wants("inputs")) {
            inputs(x, h, prov);
        }
        checks(x, h, prov);
        if (r.wants("why")) {
            if ("var".equals(measure)) {
                why(x, p, scope, r, lens);
            } else {
                x.unbuilt("why", "explain-why-es-parametric");
            }
        }
        workbook(x, measure, scope, r);
        x.link("The VaR page of this scope", "/var?scope=" + enc(scope));
        x.link("Guide: VaR", parametric ? "/help/var-parametric" : "/help/context/var");
        return x;
    }

    private static String title(String measure, Map<String, Object> h, Map<String, Object> pm, String days) {
        String conf = VarLens.pct(num(h.get("confidence")));
        String esc = VarLens.pct(num(h.get("esConfidence")));
        boolean stressed = "STRESSED".equals(str(h.get("set")));
        return switch (measure) {
            case "es" -> (stressed ? "Stressed ES " : "ES ") + esc + ", " + days;
            case "pvar" -> "Parametric VaR " + VarLens.pct(num(pm.get("confidence"))) + ", " + days;
            case "pes" -> "Parametric ES " + VarLens.pct(num(pm.get("esConfidence"))) + ", " + days;
            case "pgap" -> "Fat-tail gap, " + days;
            default -> (stressed ? "Stressed VaR " : "VaR ") + conf + ", " + days;
        };
    }

    private static Double value(String measure, Map<String, Object> h, Map<String, Object> pm) {
        Object v = switch (measure) {
            case "es" -> h.get("es");
            case "pvar" -> pm.get("var");
            case "pes" -> pm.get("es");
            case "pgap" -> pm.get("gap");
            default -> h.get("var");
        };
        return v instanceof Number n ? n.doubleValue() : null;
    }

    /** The parametric figure's lines: the server's own describe lines, as the ? showed them (the gap's: its first line renamed, then its own). */
    static List<String> lines(String measure, Map<String, Object> pm) {
        List<String> describe = new ArrayList<>();
        if (pm.get("describe") instanceof List<?> l) {
            l.forEach(o -> describe.add(String.valueOf(o)));
        }
        if (!"pgap".equals(measure) || describe.isEmpty()) {
            return describe;
        }
        List<String> out = new ArrayList<>();
        out.add(describe.get(0).replace("Parametric VaR", "Fat-tail gap"));
        describe.stream().filter(s -> s.startsWith("Fat-tail gap")).forEach(out::add);
        if (pm.get("notes") instanceof List<?> n) {
            n.forEach(o -> out.add(String.valueOf(o)));
        }
        return out;
    }

    /** VaR and ES: the rank rule with its values, what was summed, and the VaR scenario (1 day). */
    private void formula(Explanation x, String measure, Map<String, Object> h, Map<String, Object> prov, Principal p, String scope, LocalDate date,
            String ccy, VarLens lens) {
        int k = int0(h.get("k"));
        int n = int0(h.get("scenarios"));
        int esCount = int0(h.get("esCount"));
        boolean sum = lens != null && lens.method() == VarLens.Method.SUM;
        boolean sqrt = lens != null && lens.method() == VarLens.Method.SQRT;
        String unit = sum ? " overlapping 10-day windows" : " scenario P&Ls";
        if (Boolean.TRUE.equals(h.get("empty"))) {
            x.preview("no trades: nothing to compute");
            x.formula("no trades in this scope on " + h.get("businessDate") + ": nothing to compute");
            return;
        }
        String scen = null;
        double scenPnl = Double.NaN;
        if (lens == null || lens.method() == VarLens.Method.NONE) {
            VarService.Scoped s = lens == null ? svc.scoped(p, scope, date, ccy) : svc.scoped(p, scope, date, ccy, lens);
            List<LocalDate> dates = s.date().book().scenarioDates();
            int at = s.measures().varScenario();
            if (at >= 0 && at < s.vector().length && s.vector().length == dates.size()) {
                scen = dates.get(at).toString();
                scenPnl = s.vector()[at];
            }
        }
        if ("es".equals(measure)) {
            x.preview("the mean of the worst " + esCount + " of " + n + unit + (sqrt ? ", × √10" : ""), int0(prov.get("positions")) + " positions");
            x.rachana("es()");
            x.formula("ES = −mean(the worst " + esCount + " of " + n + unit + ")" + (sqrt ? " × √10" : ""));
            x.formula("ES " + prov.get("es"));
            x.formula("P&L(s) = Σ positions' P&L(s)   (" + Explanation.number(int0(prov.get("positions"))) + " positions, "
                    + Explanation.number(int0(prov.get("trades"))) + " trades)");
            x.formula("ES = " + Explanation.number(num(h.get("es"))) + " " + h.get("currency"));
            return;
        }
        x.preview("the " + ordinal(k) + " worst of " + n + unit + (scen == null ? "" : " (" + scen + ")") + (sqrt ? ", × √10" : ""),
                int0(prov.get("positions")) + " positions");
        x.rachana("var()");
        x.formula("VaR = −(k-th worst of " + n + unit + ")" + (sqrt ? " × √10" : ""));
        x.formula("k = " + k + "   (" + prov.get("method") + ")");
        x.formula("P&L(s) = Σ positions' P&L(s)   (" + Explanation.number(int0(prov.get("positions"))) + " positions, "
                + Explanation.number(int0(prov.get("trades"))) + " trades)");
        if (scen != null) {
            x.formula(ordinal(k) + " worst: " + scen + "   " + Explanation.signed(Math.round(scenPnl)));
        }
        x.formula("VaR = " + Explanation.number(num(h.get("var"))) + " " + h.get("currency"));
    }

    /** The provenance, row for row as the VaR page's How calculated box showed it. */
    private static void inputs(Explanation x, Map<String, Object> h, Map<String, Object> p) {
        Map<String, Object> fx = map(p.get("fx"));
        Map<String, Object> intra = map(p.get("intraday"));
        x.input("Scope", h.get("kind") + " " + h.get("label"));
        x.input("Reporting currency", (p.get("currency") == null ? "USD" : p.get("currency")) + ", chosen in the header");
        x.input("Input", p.get("input") == null ? "P&L vectors (USD)" : p.get("input"));
        x.input("By source system", bySystem(map(p.get("inputsBySystem"))));
        x.input("Intraday", intra.isEmpty() ? "none: the morning run" : int0(intra.get("updates")) + " updates of " + int0(intra.get("trades")) + " trades in "
                + int0(intra.get("batchCount")) + " batches, latest " + intra.get("lastAt") + "; the official records are the morning run's");
        x.input("Scenario FX", fx.get("scenarioFx") == null ? "none needed" : fx.get("scenarioFx"));
        x.input("Cross rates", "via USD");
        List<String> resolved = strings(p.get("resolved"));
        x.input("Resolved", resolved.isEmpty() ? str(p.get("scope")) : String.join("; ", resolved));
        x.input("Positions", Explanation.number(int0(p.get("positions"))));
        x.input("Trades", Explanation.number(int0(p.get("trades"))) + (int0(p.get("failed")) > 0 ? " · " + p.get("failed") + " without a vector" : ""));
        x.input("Outside your view", int0(p.get("hidden")) > 0 ? Explanation.number(int0(p.get("hidden"))) + " trades not included" : "none");
        List<String> unknown = strings(p.get("unknown"));
        x.input("Unknown ids", unknown.isEmpty() ? "none" : String.join(", ", unknown));
        List<String> warnings = strings(p.get("warnings"));
        x.input("Warnings", warnings.isEmpty() ? "none" : String.join("; ", warnings));
        List<String> rej = strings(p.get("rejections"));
        x.input("Rows left out", Explanation.number(int0(p.get("rejected"))) + " rejected" + (rej.isEmpty() ? "" : " (" + String.join("; ",
                rej.subList(0, Math.min(3, rej.size()))) + ")") + " · " + Explanation.number(int0(p.get("excluded"))) + " not live");
        x.input("Vectors", str(p.get("vectors")) + (p.get("tier") != null ? " · " + p.get("tier") : ""));
        x.input("Scenario set", p.get("scenarioSet"));
        x.input("Method", p.get("method"));
        x.input("ES", p.get("es"));
        x.input("Components", p.get("components"));
        x.input("Positions by", p.get("positionRules") == null ? "" : p.get("positionRules"));
        x.input("Engine", p.get("engine"));
        x.input("View", p.get("view"));
        x.input("Members", p.get("membershipHash"));
        String at = str(p.get("computedAt")).replace("T", " ");
        x.input("Computed", at.substring(0, Math.min(19, at.length())) + " · " + p.get("computeMs") + " ms · cache " + p.get("cache"));
        // the lineage: every input table's data version on the date, the batches and the scenario set
        Map<String, Object> versions = map(p.get("inputs"));
        for (Map.Entry<String, Object> e : versions.entrySet()) {
            if (e.getKey().equals("batchIds") || e.getKey().equals("positionRules")) {
                continue;
            }
            x.lineage(e.getKey(), "business date " + h.get("businessDate"), str(e.getValue()));
        }
        List<String> batches = strings(versions.get("batchIds"));
        if (!batches.isEmpty()) {
            x.lineage("batches", batches.size() + " batch" + (batches.size() == 1 ? "" : "es") + " loaded", String.join(", ", batches));
        }
        x.lineage("scenario set", str(p.get("scenarioSet")), "");
    }

    /** Per source system on the date: its form and grain, its records (VAR_GUIDE.md section 15.13). */
    private static String bySystem(Map<String, Object> m) {
        if (m.isEmpty()) {
            return "one form a trade";
        }
        List<String> out = new ArrayList<>();
        for (String k : new TreeSet<>(m.keySet())) {
            Map<String, Object> x = map(m.get(k));
            out.add(k + ": " + ("pv".equals(x.get("form")) ? "scenario PVs" : "P&L vectors") + " per " + x.get("grain") + " ("
                    + Explanation.number(int0(x.get("rows"))) + ")");
        }
        return String.join("; ", out);
    }

    private static void checks(Explanation x, Map<String, Object> h, Map<String, Object> p) {
        int trades = int0(p.get("trades"));
        int failed = int0(p.get("failed"));
        x.check(failed == 0, failed == 0 ? "inputs complete (" + Explanation.number(trades) + " of " + Explanation.number(trades) + " trades with a vector)"
                : failed + " of " + Explanation.number(trades) + " trades without a vector: left out of the figure");
        List<String> unknown = strings(p.get("unknown"));
        if (!unknown.isEmpty()) {
            x.check(false, unknown.size() + " ids in the scope match nothing");
        }
        if (h.get("override") != null) {
            x.check(null, "recomputed for this answer: never official");
        }
        x.unbuilt("official-check", "explain-official-check");
    }

    private void why(Explanation x, Principal p, String scope, ExplainRequest r, VarLens lens) {
        String view = r.text("view") == null ? "waterfall" : r.text("view").toLowerCase(Locale.ROOT);
        if (!VarWhyPanels.VIEWS.contains(view)) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "view is one of " + String.join(", ", VarWhyPanels.VIEWS) + " (not " + view + ")");
        }
        String attribution = r.text("attribution") == null ? "sequence" : r.text("attribution").toLowerCase(Locale.ROOT);
        if (!attribution.equals("sequence")) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "attribution=" + attribution + " is not built (shapley is planned), not built: the explanation is the configured sequence "
                    + "(drishti.var.explain.order), whose steps add up exactly");
        }
        LocalDate to = r.date("to") != null ? r.date("to") : r.date("date");
        Double conf = lens == null ? r.number("confidence") : Double.valueOf(lens.confidence());
        VarExplain.Result res = why.explain(p, scope, r.date("from"), to, conf);
        Map<String, Object> a = res.answer();
        Map<String, Object> matrix = view.equals("matrix") ? why.matrix(p, scope, res.from(), res.to(), conf) : null;
        VarWhyPanels panels = new VarWhyPanels(a);
        x.why(str(a.get("from")), str(a.get("to")), num(a.get("change")), Boolean.TRUE.equals(a.get("addsUp")), VarWhyPanels.VIEWS, view,
                panels.view(view, matrix), matrix == null ? a : Map.of("explain", a, "matrix", matrix));
    }

    private static void workbook(Explanation x, String measure, String scope, ExplainRequest r) {
        StringBuilder q = new StringBuilder("scope=" + enc(scope));
        for (String k : List.of("from", "to")) {
            if (r.text(k) != null) {
                q.append('&').append(k).append('=').append(enc(r.text(k)));
            }
        }
        if ("var".equals(measure)) {
            x.workbook("Why it moved: every state and step as an Excel formula (.xlsx)", "/api/var/explain.xlsx?" + q, "/api/v1/var/explain.xlsx?" + q,
                    "checked against the engine to the cent");
        }
        boolean pm = measure.startsWith("p");
        x.workbook("Worked in the playground: the grid and its formula workbook", "/play?scope=" + enc(scope) + (pm ? "&measure=parametric" : ""), null,
                "VaR Playground" + (pm ? ", Parametric tab" : "") + ": the export is an .xlsx with live formulas");
    }

    private static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            l.forEach(v -> out.add(String.valueOf(v)));
        }
        return out;
    }

    static int int0(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    static String ordinal(int k) {
        int t = k % 100;
        return k + (t >= 11 && t <= 13 ? "th" : k % 10 == 1 ? "st" : k % 10 == 2 ? "nd" : k % 10 == 3 ? "rd" : "th");
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
