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

import com.ash.drishti.banking.compute.var.ParametricVar;
import com.ash.drishti.server.playground.SheetStream;
import com.ash.drishti.server.playground.WorkbookStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The VaR Playground's Parametric tab (the parametric measure) (docs/guides/VAR_GUIDE.md section 10). The engine's parametric
 * figures for the playground's portfolio and its positions (the check cells' right-hand side), the grid's extra columns and the explanation's
 * steps for the screen, and the workbook's <b>Parametric</b> tab: the named inputs and results ({@code PConfidence}, {@code PEsConfidence},
 * {@code PWeighting}, {@code PLambda}, {@code PN}, {@code PMu}, {@code PSigma}, {@code PZ}, {@code PEsFactor}, {@code PVaR}, {@code PES}) beside
 * the engine's with a check each, one row a scenario (the weight, the portfolio P&amp;L, its deviation and square) and one row a position (μᵢ,
 * σᵢ, cov, ρ, the component VaR and ES, the standalone VaR, the engine's component and a check), every figure a formula of the common set
 * ({@code NORM.S.INV}, {@code PI} included), with its own band.
 */
final class VarPlaygroundParametric {

    static final String ALL_PASS = "PARAMETRIC CHECKS PASS";

    /** The engine's figures (the 1-day portfolio vector; a 10-day lens is said, never mixed into the workbook's 1-day block). */
    record Engine(ParametricVar.Figures scope, List<ParametricVar.Component> parts, ParametricVar.Settings settings) {}

    static Engine engine(double[] scope, List<double[]> parts, double confidence, double esConfidence, ParametricVar.Settings st) {
        ParametricVar.Figures f = ParametricVar.of(scope, confidence, esConfidence, st);
        List<ParametricVar.Component> cs = new ArrayList<>();
        for (double[] p : parts) {
            cs.add(ParametricVar.component(p, scope, f));
        }
        return new Engine(f, cs, st);
    }

    /** What the workbook writer hands over: the PnL tab's ranges and the items. */
    interface Refs {
        boolean columns();

        boolean cached();

        int scenarios();

        int items();

        String item(int i);

        String itemRange(int i);

        String portfolioRange();

        String date(int j);
    }

    /** The grid's {@code parametric} block (the screen's Parametric tab): the figures, the steps, the extra columns, each position's figures. */
    static Map<String, Object> answer(Engine e, double[] scope, List<String> ids, VarParametric describe) {
        ParametricVar.Figures f = e.scope();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mu", f.fit().mu());
        m.put("sigma", f.fit().sigma());
        m.put("z", f.z());
        m.put("esFactor", f.esFactor());
        m.put("var", f.var());
        m.put("es", f.es());
        m.put("n", f.fit().n());
        m.put("weighting", e.settings().weighting().text());
        m.put("lambda", e.settings().weighting() == ParametricVar.Weighting.EWMA ? e.settings().lambda() : null);
        m.put("official", false);
        double[] w = f.fit().weights();
        double[] dev = new double[scope.length];
        double[] sq = new double[scope.length];
        for (int j = 0; j < scope.length; j++) {
            dev[j] = scope[j] - f.fit().mu();
            sq[j] = dev[j] * dev[j];
        }
        Map<String, Object> cols = new LinkedHashMap<>();
        cols.put("w", w);
        cols.put("dev", dev);
        cols.put("sq", sq);
        m.put("columns", cols);
        List<Map<String, Object>> rows = new ArrayList<>();
        double sum = 0;
        for (int i = 0; i < e.parts().size(); i++) {
            ParametricVar.Component c = e.parts().get(i);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", i < ids.size() ? ids.get(i) : "Rest of scope");
            r.put("mu", c.mu());
            r.put("sigma", c.sigma());
            r.put("cov", c.cov());
            r.put("rho", c.rho());
            r.put("componentVar", c.componentVar());
            r.put("componentEs", c.componentEs());
            r.put("standaloneVar", c.standaloneVar());
            rows.add(r);
            sum += c.componentVar();
        }
        m.put("positions", rows);
        m.put("sumComponent", sum);
        List<String> steps = new ArrayList<>();
        steps.add("1 · The vector: the portfolio's P&L under each of the " + f.fit().n() + " scenarios (the PnL tab's Portfolio column).");
        steps.add("2 · μ = Σ wⱼ·vⱼ = " + VarParametric.money(f.fit().mu()) + (e.settings().weighting() == ParametricVar.Weighting.EQUAL
                ? " (equal weights, 1/n each)." : " (EWMA, λ = " + e.settings().lambda() + ")."));
        steps.add("3 · σ = " + (e.settings().weighting() == ParametricVar.Weighting.EQUAL ? "√(Σ (vⱼ − μ)² / (n − 1))" : "√(Σ wⱼ (vⱼ − μ)²)") + " = "
                + VarParametric.money(f.fit().sigma()) + ".");
        steps.add("4 · z = NORM.S.INV(" + f.confidence() + ") = " + VarParametric.fixed(f.z(), 6) + ".");
        steps.add("5 · " + VarParametric.formula(f) + "; ES = σ × " + VarParametric.fixed(f.esFactor(), 6) + " − μ = " + VarParametric.money(f.es()) + ".");
        if (!e.parts().isEmpty()) {
            ParametricVar.Component c = e.parts().get(0);
            steps.add("6 · " + (ids.isEmpty() ? "The first part" : ids.get(0)) + ": cov(Pᵢ, P) = " + VarParametric.sci(c.cov()) + ", ρ = "
                    + VarParametric.fixed(c.rho(), 4) + ".");
            steps.add("7 · Its component = z·cov/σ − μᵢ = " + VarParametric.money(c.componentVar()) + ".");
        }
        steps.add("8 · The components add up to the VaR: Σ = " + VarParametric.money(sum) + " = " + VarParametric.money(f.var()) + ".");
        m.put("steps", steps);
        m.put("describe", describe == null ? List.of() : describe.describe(f, com.ash.drishti.banking.compute.var.VarLens.oneDay(f.confidence(), f.esConfidence()),
                "the playground's portfolio", "the date's set", null, Double.NaN, 0));
        return m;
    }

    // ---- the workbook's tab ----------------------------------------------------------------------------------------------------

    private static final int FIRST_INPUT = 5;

    static void sheet(SheetStream s, WorkbookStream w, Refs refs, Engine e) {
        ParametricVar.Figures f = e.scope();
        boolean cached = refs.cached();
        int n = refs.scenarios();
        s.freeze(4, 0).width(0, 26).width(1, 18).width(2, 18).width(3, 10).widths(4, 9, 16);
        s.row(1).text(0, "Parametric VaR and ES (a cross-check, not the official VaR) · z·σ − μ from the portfolio's scenario P&L", "title");
        s.row(2).text(0, "Every figure below is a formula; the engine's figure beside it; each check ✓ when they agree to the cent. " + (refs.columns()
                ? "" : "Positions are laid out as rows: the per-scenario block is not written (fewer positions lay them out as columns)."), "band");
        if (!refs.columns()) {
            return;
        }
        int scenFirst = 30;
        int scenLast = scenFirst + n - 1;
        String wr = "$C$" + scenFirst + ":$C$" + scenLast;
        String pf = refs.portfolioRange();
        int itemsFirst = scenLast + 4;
        int items = refs.items();
        int itemsLast = itemsFirst + items - 1;
        String sumWeights = "SUM(" + wr + ")";
        boolean all = true;
        double sumCv = 0;
        double sumCe = 0;
        for (ParametricVar.Component c : e.parts()) {
            sumCv += c.componentVar();
            sumCe += c.componentEs();
        }
        boolean cvOk = Math.abs(sumCv - f.var()) < 0.005;
        boolean ceOk = Math.abs(sumCe - f.es()) < 0.005;
        all &= cvOk && ceOk;
        int checksFirst = 21;
        String band = "IF(SUMPRODUCT(($D$" + FIRST_INPUT + ":$D$" + (FIRST_INPUT + 10) + "=\"" + VarPlaygroundBook.PASS + "\")*1)+SUMPRODUCT(($A$"
                + checksFirst + ":$A$" + (checksFirst + 3) + "=\"" + VarPlaygroundBook.PASS + "\")*1)=" + (11 + 4) + ",\"" + ALL_PASS + "\",\"CHECK FAILED\")";
        s.row(3).text(0, "Checks", "label").formulaText(1, band, cached ? (all ? ALL_PASS : "CHECK FAILED") : null, "band.status");
        s.row(4).text(0, "Name", "head").text(1, "Sheet", "head").text(2, "Engine", "head").text(3, "Check", "head").text(4, "Meaning", "head");
        String weighting = e.settings().weighting().text();
        int r = FIRST_INPUT;
        r = input(s, w, r, "PConfidence", f.confidence(), "input.dec", "VaR confidence");
        r = input(s, w, r, "PEsConfidence", f.esConfidence(), "input.dec", "ES confidence");
        w.name("PWeighting", "Parametric", "$B$" + r);
        s.row(r).text(0, "PWeighting", "label").text(1, weighting, "input.text").text(2, weighting, "text")
                .formulaText(3, "IF(B" + r + "=C" + r + ",\"" + VarPlaygroundBook.PASS + "\",\"" + VarPlaygroundBook.FAIL + "\")", cached ? VarPlaygroundBook.PASS : null, "check")
                .text(4, "equal (1/n, σ with n − 1) or ewma", "note");
        r++;
        r = input(s, w, r, "PLambda", e.settings().lambda(), "input.dec", "EWMA's decay (read only with ewma)");
        r = result(s, w, r, "PN", "COUNT(" + pf + ")", n, n, cached, "int", "scenarios", 0.5);
        r = result(s, w, r, "PMu", "SUMPRODUCT(" + wr + "," + pf + ")", f.fit().mu(), f.fit().mu(), cached, "money", "μ = Σ wⱼ·vⱼ", 1e-6);
        r = result(s, w, r, "PSigma", "IF(PWeighting=\"ewma\",SQRT(SUMPRODUCT(" + wr + ",(" + pf + "-PMu)^2)),SQRT(SUMPRODUCT((" + pf + "-PMu)^2)/(PN-1)))",
                f.fit().sigma(), f.fit().sigma(), cached, "money", "σ: sample (n − 1), or EWMA-weighted", 1e-6);
        r = result(s, w, r, "PZ", "_xlfn.NORM.S.INV(PConfidence)", f.z(), f.z(), cached, "rate", "z = Φ⁻¹(confidence)", 1e-9);
        r = result(s, w, r, "PEsFactor", "EXP(-(_xlfn.NORM.S.INV(PEsConfidence)^2)/2)/SQRT(2*PI())/(1-PEsConfidence)", f.esFactor(), f.esFactor(), cached,
                "rate", "φ(z_ES)/(1 − ES confidence)", 1e-9);
        r = result(s, w, r, "PVaR", "PZ*PSigma-PMu", f.var(), f.var(), cached, "money.bold", "parametric VaR = z·σ − μ", 0.005);
        result(s, w, r, "PES", "PSigma*PEsFactor-PMu", f.es(), f.es(), cached, "money.bold", "parametric ES = σ·φ(z_ES)/(1 − c) − μ", 0.005);
        // the checks of the whole
        int c = checksFirst;
        s.row(c - 1).text(0, "Check", "head").text(1, "What is checked", "head");
        String cr = "$F$" + itemsFirst + ":$F$" + itemsLast;
        String er = "$G$" + itemsFirst + ":$G$" + itemsLast;
        s.row(c).formulaText(0, "IF(ABS(SUM(" + cr + ")-PVaR)<0.005,\"" + VarPlaygroundBook.PASS + "\",\"" + VarPlaygroundBook.FAIL + "\")",
                cached ? (cvOk ? VarPlaygroundBook.PASS : VarPlaygroundBook.FAIL) : null, "check").text(1, "Σ component VaR = PVaR (to the cent)", "text");
        s.row(c + 1).formulaText(0, "IF(ABS(SUM(" + er + ")-PES)<0.005,\"" + VarPlaygroundBook.PASS + "\",\"" + VarPlaygroundBook.FAIL + "\")",
                cached ? (ceOk ? VarPlaygroundBook.PASS : VarPlaygroundBook.FAIL) : null, "check").text(1, "Σ component ES = PES (to the cent)", "text");
        s.row(c + 2).formulaText(0, "IF(ABS(" + sumWeights + "-1)<0.000000001,\"" + VarPlaygroundBook.PASS + "\",\"" + VarPlaygroundBook.FAIL + "\")",
                cached ? VarPlaygroundBook.PASS : null, "check").text(1, "Σ weights = 1", "text");
        s.row(c + 3).formulaText(0, "IF(SUMPRODUCT(($J$" + itemsFirst + ":$J$" + itemsLast + "=\"" + VarPlaygroundBook.PASS + "\")*1)=" + items + ",\""
                + VarPlaygroundBook.PASS + "\",\"" + VarPlaygroundBook.FAIL + "\")", cached ? VarPlaygroundBook.PASS : null, "check")
                .text(1, "Every position's component VaR = the engine's (to the cent)", "text");
        // one row a scenario
        s.row(scenFirst - 1).text(0, "Scenario", "head").text(1, "Date", "head").text(2, "Weight wⱼ", "head").text(3, "Portfolio P&L", "head")
                .text(4, "vⱼ − μ", "head").text(5, "(vⱼ − μ)²", "head");
        double[] wv = f.fit().weights();
        for (int j = 0; j < n; j++) {
            int row = scenFirst + j;
            s.row(row).number(0, j + 1, "int").text(1, refs.date(j), "date")
                    .formula(2, "IF(PWeighting=\"ewma\",(1-PLambda)*PLambda^(PN-A" + row + ")/(1-PLambda^PN),1/PN)", cached ? wv[j] : null, "rate")
                    .formula(3, "INDEX(" + pf + ",A" + row + ")", null, "money")
                    .formula(4, "D" + row + "-PMu", null, "money")
                    .formula(5, "E" + row + "^2", null, "money");
        }
        // one row a position
        s.row(itemsFirst - 1).text(0, "Position", "head").text(1, "μᵢ", "head").text(2, "σᵢ", "head").text(3, "cov(Pᵢ, P)", "head")
                .text(4, "ρᵢ", "head").text(5, "Component VaR", "head").text(6, "Component ES", "head").text(7, "Standalone VaR", "head")
                .text(8, "Engine component VaR", "head").text(9, "Check", "head");
        for (int i = 0; i < items; i++) {
            int row = itemsFirst + i;
            String p = refs.itemRange(i);
            ParametricVar.Component cp = i < e.parts().size() ? e.parts().get(i) : null;
            double ecv = cp == null ? 0 : cp.componentVar();
            s.row(row).text(0, refs.item(i), "text")
                    .formula(1, "SUMPRODUCT(" + wr + "," + p + ")", cached && cp != null ? cp.mu() : null, "money")
                    .formula(2, "IF(PWeighting=\"ewma\",SQRT(SUMPRODUCT(" + wr + ",(" + p + "-B" + row + ")^2)),SQRT(SUMPRODUCT((" + p + "-B" + row + ")^2)/(PN-1)))",
                            cached && cp != null ? cp.sigma() : null, "money")
                    .formula(3, "IF(PWeighting=\"ewma\",SUMPRODUCT(" + wr + ",(" + p + "-B" + row + ")*(" + pf + "-PMu)),SUMPRODUCT((" + p + "-B" + row + ")*("
                            + pf + "-PMu))/(PN-1))", cached && cp != null ? cp.cov() : null, "money")
                    .formula(4, "IF(C" + row + "*PSigma=0,0,D" + row + "/(C" + row + "*PSigma))", cached && cp != null ? cp.rho() : null, "rate")
                    .formula(5, "IF(PSigma=0,0,PZ*D" + row + "/PSigma)-B" + row, cached && cp != null ? cp.componentVar() : null, "money")
                    .formula(6, "IF(PSigma=0,0,PEsFactor*D" + row + "/PSigma)-B" + row, cached && cp != null ? cp.componentEs() : null, "money")
                    .formula(7, "PZ*C" + row + "-B" + row, cached && cp != null ? cp.standaloneVar() : null, "money")
                    .number(8, ecv, "money")
                    .formulaText(9, "IF(ABS(F" + row + "-I" + row + ")<0.005,\"" + VarPlaygroundBook.PASS + "\",\"" + VarPlaygroundBook.FAIL + "\")",
                            cached ? VarPlaygroundBook.PASS : null, "check");
        }
        s.checks("D" + FIRST_INPUT + ":D" + (FIRST_INPUT + 10), VarPlaygroundBook.PASS);
        s.checks("A" + checksFirst + ":A" + (checksFirst + 3), VarPlaygroundBook.PASS);
        s.checks("J" + itemsFirst + ":J" + itemsLast, VarPlaygroundBook.PASS);
        s.checks("B3", ALL_PASS);
    }

    private static int input(SheetStream s, WorkbookStream w, int r, String name, double v, String style, String meaning) {
        w.name(name, "Parametric", "$B$" + r);
        s.row(r).text(0, name, "label").number(1, v, style).number(2, v, style)
                .formulaText(3, "IF(B" + r + "=C" + r + ",\"" + VarPlaygroundBook.PASS + "\",\"" + VarPlaygroundBook.FAIL + "\")", VarPlaygroundBook.PASS, "check")
                .text(4, meaning, "note");
        return r + 1;
    }

    private static int result(SheetStream s, WorkbookStream w, int r, String name, String formula, double sheet, double engine, boolean cached, String style,
            String meaning, double tolerance) {
        w.name(name, "Parametric", "$B$" + r);
        String tol = tolerance >= 0.005 ? String.valueOf(tolerance) : tolerance + "*MAX(1,ABS(C" + r + "))";
        s.row(r).text(0, name, "label").formula(1, formula, cached ? sheet : null, style).number(2, engine, style)
                .formulaText(3, "IF(ABS(B" + r + "-C" + r + ")<" + tol + ",\"" + VarPlaygroundBook.PASS + "\",\"" + VarPlaygroundBook.FAIL + "\")",
                        cached ? VarPlaygroundBook.PASS : null, "check")
                .text(4, meaning + " · =" + formula.replace("_xlfn.", ""), "note");
        return r + 1;
    }
}
