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

import com.ash.drishti.banking.compute.var.VarLens;
import com.ash.drishti.banking.compute.var.VarMath;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The VaR Playground's screen answer (VAR_PLAYGROUND.md section 2): the grid's columns (each position's P&amp;L in the reporting currency,
 * or its scenario PVs, or the FX), the portfolio, rank and marks, every figure the explanation panel walks through, the engine's figures
 * and the checks, the formula bar's formulas, and the export's choices with their estimates. Also the empty workbook (the template).
 */
final class VarPlaygroundAnswer {

    private VarPlaygroundAnswer() {}

    static Map<String, Object> grid(VarPlayground.Built b, VarPlayground pg, String view) {
        VarPlaygroundModel m = b.model();
        VarPlaygroundBook.Engine e = b.engine();
        VarPlaygroundBook book = new VarPlaygroundBook(m, e, b.about(), pg.template(), true);
        String v = view == null || view.isBlank() ? "pnl" : view.strip().toLowerCase(java.util.Locale.ROOT);
        Map<String, Object> out = new LinkedHashMap<>();
        VarService.Scoped s = b.scoped();
        out.put("canonical", s.res().canonical());
        out.put("label", b.about().scope());
        out.put("businessDate", s.date().date().toString());
        out.put("currency", m.reporting);
        Map<String, Object> lens = new LinkedHashMap<>();
        lens.put("horizon", m.set.horizon());
        lens.put("method", "1D".equals(m.set.horizon()) ? null : m.set.method());
        lens.put("confidence", m.set.confidence());
        lens.put("esConfidence", m.set.esConfidence());
        lens.put("set", s.lens() != null && s.lens().stressed() ? "STRESSED" : "NORMAL");
        lens.put("confidences", m.set.confidences());
        lens.put("esConfidences", m.set.esConfidences());
        lens.put("rankRule", m.set.interpolated() ? "interpolated" : "kth-worst");
        lens.put("componentWindow", m.set.window());
        out.put("lens", lens);
        out.put("view", v);
        out.put("total", b.total());
        out.put("shown", m.positions.size());
        out.put("truncated", b.total() > m.positions.size());
        out.put("maxPositions", pg.settings().maxPositions());
        out.put("incrementalPositions", Math.min(pg.settings().incremental(), m.positions.size()));
        List<String> dates = new ArrayList<>();
        for (LocalDate d : m.dates) {
            dates.add(d.toString());
        }
        out.put("scenarios", dates);
        List<Map<String, Object>> cols = new ArrayList<>();
        for (int i = 0; i < m.positions.size(); i++) {
            VarPlaygroundModel.Pos p = m.positions.get(i);
            VarPlaygroundModel.Fig f = m.figs[i];
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", p.id());
            c.put("kind", p.kind());
            c.put("label", p.label());
            c.put("currency", p.currency());
            c.put("basePv", round(p.basePv()));
            c.put("baseFx", p.baseFx());
            c.put("form", p.form());
            c.put("source", p.source());
            c.put("todayUsd", round(p.today()));
            figures(c, f, e, i, m);
            c.put("pnl", cents(row(m, i)));
            if (v.equals("pv")) {
                c.put("pv", cents(p.data()));
            }
            c.put("pnlRange", book.pnlRangeOf(i));
            if (i < pg.settings().incremental()) {
                c.put("helperRange", book.helperRangeOf(i));
            }
            cols.add(c);
        }
        out.put("positions", cols);
        if (m.rest != null) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", "Rest of scope");
            r.put("count", b.total() - m.positions.size());
            r.put("currency", m.reporting);
            r.put("form", "Rest");
            figures(r, m.restFig, e, m.positions.size(), m);
            r.put("pnl", cents(m.rest));
            r.put("pnlRange", book.pnlRangeOf(m.positions.size()));
            out.put("rest", r);
        }
        out.put("portfolio", cents(m.portfolio));
        out.put("rank", m.rank);
        List<String> marks = new ArrayList<>();
        for (int j = 0; j < m.n; j++) {
            marks.add(m.rank[j] == m.k ? "V" : m.rank[j] <= m.kEs ? "E" : "");
        }
        out.put("mark", marks);
        Map<String, Object> fx = new LinkedHashMap<>();                // the scenario FX, small: always (the explanation works the cell from it)
        for (VarPlaygroundModel.Fx x : m.fx) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("base", x.base());
            one.put("source", x.source());
            one.put("rates", x.rates());
            fx.put(x.currency(), one);
        }
        out.put("fx", fx);
        out.put("figures", headline(m));
        out.put("engine", engine(m, e));
        out.put("checks", checks(m, e, b));
        out.put("formulas", book.formulaTemplates());
        out.put("export", Map.of("choices", pg.choices(b.total(), m.n, m.fx.size()), "default", pg.settings().exportDefault()));
        out.put("provenance", b.about().provenance());
        return out;
    }

    private static double[] row(VarPlaygroundModel m, int i) {
        double[] out = new double[m.n];
        for (int j = 0; j < m.n; j++) {
            out[j] = m.pnl(i, j);
        }
        return out;
    }

    private static void figures(Map<String, Object> c, VarPlaygroundModel.Fig f, VarPlaygroundBook.Engine e, int item, VarPlaygroundModel m) {
        c.put("standaloneVar", round(f.standaloneVar()));
        c.put("standaloneEs", round(f.standaloneEs()));
        c.put("componentVar", round(f.componentVar()));
        c.put("componentEs", round(f.componentEs()));
        c.put("pct", m.var1 == 0 ? 0 : f.componentVar() / m.var1);
        c.put("incrementalVar", Double.isNaN(f.incremental()) ? null : round(f.incremental()));
        c.put("component10", Double.isNaN(f.component10()) ? null : round(f.component10()));
        c.put("engineComponentVar", round(e.componentVar()[item]));
        c.put("engineComponentEs", round(e.componentEs()[item]));
    }

    private static Map<String, Object> headline(VarPlaygroundModel m) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("scenarioCount", m.n);
        f.put("k", m.k);
        f.put("kEs", m.kEs);
        f.put("lo", m.lo);
        f.put("hi", m.hi);
        f.put("smoothed", round(m.smoothed));
        f.put("scale", m.scale);
        f.put("var", round(m.var1));
        f.put("es", round(m.es1));
        f.put("headlineVar", round(m.headlineVar()));
        f.put("headlineEs", round(m.headlineEs()));
        f.put("var10sqrt", round(m.var1 * VarPlaygroundModel.SQRT10));
        f.put("es10sqrt", round(m.es1 * VarPlaygroundModel.SQRT10));
        f.put("var10sum", round(m.var10));
        f.put("es10sum", round(m.es10));
        f.put("windows", m.n10);
        f.put("k10", m.k10);
        f.put("diversification", round(m.diversification()));
        int j = m.varScenario();
        f.put("varScenario", j);
        f.put("varScenarioDate", j < 0 ? null : m.dates.get(j).toString());
        List<Map<String, Object>> conf = new ArrayList<>();
        for (double[] c : m.everyConfidence()) {
            conf.add(Map.of("confidence", c[0], "k", (int) c[1], "var", round(c[2]), "var10sqrt", round(c[3]), "var10sum", round(c[4])));
        }
        f.put("confidences", conf);
        List<Map<String, Object>> esc = new ArrayList<>();
        for (double[] c : m.everyEsConfidence()) {
            esc.add(Map.of("confidence", c[0], "k", (int) c[1], "es", round(c[2]), "es10sqrt", round(c[3]), "es10sum", round(c[4])));
        }
        f.put("esConfidences", esc);
        double sum = m.restFig == null ? 0 : m.restFig.componentVar();
        for (VarPlaygroundModel.Fig x : m.figs) {
            sum += x.componentVar();
        }
        f.put("sumComponentVar", round(sum));
        return f;
    }

    private static Map<String, Object> engine(VarPlaygroundModel m, VarPlaygroundBook.Engine e) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("var", round(e.var1()));
        f.put("es", round(e.es1()));
        f.put("var10sqrt", round(e.var1() * VarLens.SQRT10));
        f.put("var10sum", round(e.var10sum()));
        f.put("es10sum", round(e.es10sum()));
        f.put("varScenario", e.varScenario());
        f.put("diversification", round(e.diversification()));
        return f;
    }

    private static List<Map<String, Object>> checks(VarPlaygroundModel m, VarPlaygroundBook.Engine e, VarPlayground.Built b) {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(check("VaR: sheet = engine to the cent", Math.abs(m.var1 - e.var1()) < 0.005));
        out.add(check("ES: sheet = engine to the cent", Math.abs(m.es1 - e.es1()) < 0.005));
        out.add(check("10-day sum VaR and ES = the engine's", Math.abs(m.var10 - e.var10sum()) < 0.005 && Math.abs(m.es10 - e.es10sum()) < 0.005));
        double sum = m.restFig == null ? 0 : m.restFig.componentVar();
        double sumEs = m.restFig == null ? 0 : m.restFig.componentEs();
        for (VarPlaygroundModel.Fig x : m.figs) {
            sum += x.componentVar();
            sumEs += x.componentEs();
        }
        out.add(check("Σ component VaR = VaR", Math.abs(sum - (m.set.scaleToVar() ? m.var1 : m.smoothed)) < 0.005));
        out.add(check("Σ component ES = ES", Math.abs(sumEs - m.es1) < 0.005));
        out.add(check("positions shown + Rest of scope = the scope", true));
        return out;
    }

    private static Map<String, Object> check(String name, boolean ok) {
        return Map.of("name", name, "ok", ok);
    }

    static double round(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? 0 : Math.round(v * 100) / 100.0;
    }

    private static double[] cents(double[] v) {
        double[] out = new double[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = round(v[i]);
        }
        return out;
    }

    /**
     * The empty workbook (VAR_PLAYGROUND.md section 5): {@code positions} USD positions of zero scenario PVs over {@code scenarios} business
     * days, every formula in place, for a person to paste PVs into Data, fill Config and FX, and recalculate offline.
     */
    static VarPlaygroundBook blank(VarPlayground pg, int positions, int scenarios, String user) {
        List<LocalDate> dates = new ArrayList<>();
        LocalDate d = LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1);
        while (dates.size() < scenarios) {
            if (d.getDayOfWeek().getValue() < 6) {
                dates.add(0, d);
            }
            d = d.minusDays(1);
        }
        List<VarPlaygroundModel.Pos> ps = new ArrayList<>();
        for (int i = 0; i < positions; i++) {
            ps.add(new VarPlaygroundModel.Pos("POSITION-" + (i + 1), "position", "POSITION-" + (i + 1), "USD", 0, 1, "PV", "typed", new double[scenarios]));
        }
        double[] ones = new double[scenarios];
        java.util.Arrays.fill(ones, 1.0);
        VarPlaygroundModel.Settings st = new VarPlaygroundModel.Settings(0.99, 0.975, List.of(0.95, 0.975, 0.99, 0.995), List.of(0.95, 0.975, 0.99),
                VarMath.RankRule.KTH_WORST, 5, true, "1D", "SQRT10", Math.min(50, positions));
        VarPlaygroundModel m = new VarPlaygroundModel(dates, ps, null, List.of(new VarPlaygroundModel.Fx("USD", 1, ones, "base")), "USD", st);
        double[] z = new double[positions];
        double[] nan = new double[positions];
        java.util.Arrays.fill(nan, Double.NaN);
        for (int i = 0; i < Math.min(50, positions); i++) {
            nan[i] = 0;
        }
        List<double[]> conf = new ArrayList<>();
        for (double c : st.confidences()) {
            conf.add(new double[] {c, 0, 0, 0});
        }
        List<double[]> esc = new ArrayList<>();
        for (double c : st.esConfidences()) {
            esc.add(new double[] {c, 0, 0, 0});
        }
        VarPlaygroundBook.Engine e = new VarPlaygroundBook.Engine(0, 0, m.varScenario(), 0, 0, 0, conf, esc, z, z, z, nan, 0);
        Map<String, Object> prov = new LinkedHashMap<>();
        prov.put("What this is", "the empty VaR Playground workbook: paste scenario PVs into Data, fill Config and FX, and every figure recalculates");
        prov.put("Engine figures", "none (the Engine columns are 0): the engine checks turn red once data is pasted, the add-up checks stay green");
        VarPlaygroundBook.About about = new VarPlaygroundBook.About("template", "template", "template", dates.get(dates.size() - 1).toString(), user,
                java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString(), positions, 0, prov);
        return new VarPlaygroundBook(m, e, about, pg.template(), true);
    }
}
