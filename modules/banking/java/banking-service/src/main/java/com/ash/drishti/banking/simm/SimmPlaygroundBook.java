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
package com.ash.drishti.banking.simm;

import com.ash.drishti.banking.PlaygroundSheets;
import com.ash.drishti.banking.compute.simm.Simm;
import com.ash.drishti.banking.compute.simm.SimmChoices;
import com.ash.drishti.banking.compute.simm.SimmInput;
import com.ash.drishti.banking.compute.simm.SimmParams;
import com.ash.drishti.server.dist.XlsxWriter;
import com.ash.drishti.server.playground.PlaygroundTemplate;
import com.ash.drishti.server.playground.SheetStream;
import com.ash.drishti.server.playground.WorkbookStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The SIMM Playground's workbook (docs/guides/FRTB_SA_GUIDE.md sections 12.1a and 12.2, docs/guides/SIMM_GUIDE.md section 8): Main,
 * Config, Data, WS, Buckets, Classes, Curvature, Add-ons, Explain and Provenance. Every derived cell is a formula of the common set and
 * every parameter a cell of Config, named by its key. Three switches are cells of Config with a drop-down, read by the formulas: the
 * concentration factors and the HVRs on or off, and the side (collect, or post with every sensitivity negated), so a validator who flips
 * one sees the alternative recompute live. Main holds the engine's figures for both sides and for each one-switch alternative, each with
 * its check. The shipped template (the hand-built examples on the empty structure) has every parameter cell empty: only the bank's own
 * export, made under its licence, fills them.
 */
final class SimmPlaygroundBook {

    static final List<String> STYLES = List.of("default", "title", "band", "head", "text", "note", "input.money", "input.text", "input.dec", "input.rate",
            "money", "money.bold", "int", "rate", "check", "label", "total", "wrap", "label.wrap");
    static final String PASS = "✓";
    static final String FAIL = "✗";
    static final String ALL_PASS = "ALL CHECKS PASS";
    static final String CHANGED = "SWITCHES CHANGED: SEE THE ALTERNATIVES";
    static final String TEMPLATE = "TEMPLATE: FILL CONFIG FROM THE BANK'S LICENSED FILE";

    /** An engine run with one switch flipped, for Main's alternatives block. */
    record Alternative(String name, String value, double collect, double post) {}

    private final SimmService.Run run;
    private final Simm.Result r;
    private final Simm.Result post;
    private final SimmParams p;
    private final SimmChoices ch;
    private final List<Alternative> alternatives;
    private final PlaygroundTemplate t;
    private final boolean cached;
    private final String side;
    private final String user;
    private final String now;
    private final String recipe;
    private final boolean empty;

    // ---- the plan: where everything is ----
    private final Map<String, String> cfg = new LinkedHashMap<>();
    private final List<String> cfgKeys = new ArrayList<>();
    private static final int SW_FIRST = 4;
    private static final int CONST_FIRST = 9;
    private static final int PARAM_FIRST = 15;
    private static final int FIRST = 4;
    private final int[] wsRow;
    private final int[] cvRow;
    private final Map<String, String[]> bucketCells = new HashMap<>();       // charge|bucket → K, net, S
    private final Map<String, String> chargeMargin = new LinkedHashMap<>();  // pc|rc|measure → margin cell
    private final Map<String, String> productCell = new LinkedHashMap<>();   // pc → SIMM_p cell
    private String addOnTotal;

    /**
     * @param run the computation (its collect result is the base, its choices the exported switches)
     * @param side the side Config starts on: {@code collect} or {@code post}
     */
    SimmPlaygroundBook(SimmService.Run run, List<Alternative> alternatives, PlaygroundTemplate t, boolean cached, String side, String user, String now,
            String recipe) {
        this.run = run;
        this.side = side == null || !side.equals("post") ? "collect" : "post";
        this.r = this.side.equals("post") ? run.post() : run.collect();
        this.post = run.post();
        this.p = r.params();
        this.ch = r.choices();
        this.empty = !p.complete();
        this.alternatives = List.copyOf(alternatives);
        this.t = t;
        this.cached = cached && !empty;
        this.user = user;
        this.now = now;
        this.recipe = recipe;
        this.wsRow = new int[r.risks().size()];
        this.cvRow = new int[r.risks().size()];
        planRows();
        planConfig();
    }

    private Double c(double v) {
        return cached && Double.isFinite(v) ? v : null;
    }

    private static String col(int c) {
        return XlsxWriter.col(c);
    }

    private static String abs(String sheet, int c, int row) {
        return WorkbookStream.quote(sheet) + "!$" + col(c) + "$" + row;
    }

    private static String q(String s) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private String cf(String key) {
        if (key.equals(SimmParams.ONE)) {
            return "1";
        }
        String ref = cfg.get(key);
        if (ref == null) {
            throw new IllegalStateException("no Config row " + key);
        }
        return ref;
    }

    private void use(String key) {
        if (key != null && !key.equals(SimmParams.ONE) && !cfg.containsKey(key)) {
            cfg.put(key, "Config!$B$" + (PARAM_FIRST + cfgKeys.size()));
            cfgKeys.add(key);
        }
    }

    private static String sw(String name) {
        int row = switch (name) {
            case "concentration" -> SW_FIRST;
            case "hvr" -> SW_FIRST + 1;
            default -> SW_FIRST + 2;
        };
        return "Config!$B$" + row;
    }

    private static final String PHI99 = "Config!$B$" + CONST_FIRST;
    private static final String PHI995 = "Config!$B$" + (CONST_FIRST + 1);
    private static final String TFX = "Config!$B$" + (CONST_FIRST + 2);

    private static String sign() {
        return "IF(" + sw("side") + "=\"post\",-1,1)";
    }

    private String corr(Simm.Corr k) {
        String v = k.b() == null ? cf(k.a()) : cf(k.a()) + "*" + cf(k.b());
        return k.squared() ? "(" + v + ")^2" : v;
    }

    // ---- planning ------------------------------------------------------------------------------------------------------------------

    private boolean curv(Simm.Risk x) {
        return x.measure.equals(Simm.CURVATURE);
    }

    private void planRows() {
        int ws = FIRST;
        int cv = FIRST;
        for (int k = 0; k < r.risks().size(); k++) {
            if (curv(r.risks().get(k))) {
                cvRow[k] = cv++;
            } else {
                wsRow[k] = ws++;
            }
        }
    }

    private void planConfig() {
        for (Simm.Risk x : r.risks()) {
            use(x.rwKey);
            use(x.thresholdKey);
            use(x.hvrKey);
            if (x.measure.equals(Simm.VEGA) && !List.of("InterestRate", "CreditQ", "CreditNonQ").contains(x.riskClass)) {
                use(sigmaKey(x));
            }
        }
        for (Simm.Charge c : r.charges()) {
            for (Simm.Bucket b : c.buckets) {
                for (Simm.Corr[] row : b.rho) {
                    for (Simm.Corr k : row) {
                        if (k != null) {
                            use(k.a());
                            use(k.b());
                        }
                    }
                }
            }
            for (Simm.Corr[] row : c.gamma) {
                for (Simm.Corr k : row) {
                    if (k != null) {
                        use(k.a());
                    }
                }
            }
            if (c.measure.equals(Simm.CURVATURE) && c.riskClass.equals("InterestRate")) {
                use("InterestRate.historical-volatility-ratio");
            }
        }
        for (int a = 0; a < SimmParams.RISK_CLASSES.size(); a++) {
            for (int b = 0; b < SimmParams.RISK_CLASSES.size(); b++) {
                use(Simm.psiKey(a, b));
            }
        }
    }

    /** The delta risk weight the vega σ of an equity, commodity or FX factor is derived from. */
    private String sigmaKey(Simm.Risk x) {
        return x.riskClass.equals("FX") ? Simm.fxRiskWeightKey(p, x.qualifier, r.input().calculationCurrency()) : x.riskClass + ".delta-risk-weights." + x.bucket;
    }

    // ---- the writing -------------------------------------------------------------------------------------------------------------

    void write(OutputStream out) {
        Map<String, String> v = new LinkedHashMap<>(t.brandingValues(com.ash.drishti.common.Branding.current()));
        v.put("TITLE", "SIMM Playground · " + run.scope() + " · " + run.date());
        v.put("SUBJECT", "ISDA SIMM worked in formulas, " + run.calculationCurrency() + ", SIMM " + p.version);
        v.put("USER", user);
        v.put("NOW", now);
        planBuckets();
        planClasses();
        planCurvature();
        addOnTotal = abs("Add-ons", 4, addOnPlan().totalRow());
        try (WorkbookStream w = new WorkbookStream(out, t, v)) {
            main(w.sheet("Main", "1F3A5F"));
            config(w.sheet("Config", "B08A2E"));
            data(w.sheet("Data", "B08A2E"));
            ws(w.sheet("WS", "2E5A88"));
            buckets(w.sheet("Buckets", "2E5A88"));
            classes(w.sheet("Classes", "2E5A88"));
            curvature(w.sheet("Curvature", "2E5A88"));
            addOns(w.sheet("Add-ons", "2E5A88"));
            explain(w.sheet("Explain", "6B7686"));
            provenance(w.sheet("Provenance", "6B7686"));
            w.name("IM", "Main", "$C$" + imRow);
            w.name("SIMM", "Main", "$C$" + simmRow);
        }
    }

    // ---- Main ------------------------------------------------------------------------------------------------------------------------

    private int simmRow;
    private int imRow;

    private String check(int row, String same) {
        return "IF(" + same + ",IF(ABS(C" + row + "-IF(" + sw("side") + "=\"post\",E" + row + ",D" + row + "))<0.005,\"" + PASS + "\",\"" + FAIL + "\"),\"changed\")";
    }

    private void engineRow(SheetStream s, int row, double collect, double posted, String same, String style) {
        s.number(3, collect, style).number(4, posted, style);
        if (empty) {
            s.text(5, "—", "check");
        } else {
            s.formulaText(5, check(row, same), cached ? PASS : null, "check");
        }
    }

    private void main(SheetStream s) {
        s.width(0, 30).width(1, 16).widths(2, 4, 18).width(5, 10).width(6, 18).width(7, 10).freeze(4, 0);
        s.row(1).text(0, com.ash.drishti.common.Branding.current().productOr("") + " · SIMM Playground", "title");
        for (int i = 1; i < 8; i++) {
            s.blank(i, "title");
        }
        String kind = run.kind().equals(SimmService.NETTING_SET) ? "netting set" : run.kind();
        s.row(2).text(0, kind + " " + run.scope() + (run.nettingSet() == null ? "" : " · counterparty " + run.nettingSet().counterparty() + " · CSA "
                + run.nettingSet().csa()) + " · " + (run.date() == null ? "the hand-built examples" : run.date()) + " · " + run.calculationCurrency() + " · "
                + p.describe() + " · collect " + run.collectRegime() + ", post " + run.postRegime() + (r.regulatory() ? "" : " · NOT THE REGULATORY FIGURE"), "note");
        List<Simm.Charge> charges = run.collect().charges();
        int first = 6;
        int row = first;
        int prodFirst = first + charges.size();
        simmRow = prodFirst + r.products().size();
        int addRow = simmRow + 1;
        imRow = addRow + 1;
        int swFirst = imRow + 3;
        int sameRow = swFirst + 3;
        String same = "$C$" + sameRow;
        String checks = "F" + first + ":F" + imRow;
        int altFirst = sameRow + 3;
        String altChecks = "H" + altFirst + ":H" + Math.max(altFirst, altFirst + alternatives.size() - 1);
        if (empty) {
            s.row(3).text(0, TEMPLATE, "band");
        } else {
            s.row(3).formulaText(0, "IF(SUMPRODUCT((" + checks + "=\"" + FAIL + "\")*1)+SUMPRODUCT((" + altChecks + "=\"" + FAIL + "\")*1)=0,IF(" + same + ",\""
                    + ALL_PASS + "\",\"" + CHANGED + "\"),\"A CHECK FAILS\")", cached ? ALL_PASS : null, "band");
            s.checks("A3", ALL_PASS);
        }
        s.row(5).text(0, "Product and risk class", "head").text(1, "Measure", "head").text(2, "Workbook (Config's side)", "head").text(3, "Engine collect", "head")
                .text(4, "Engine post", "head").text(5, "Check", "head");
        for (Simm.Charge c : charges) {
            Simm.Charge pc = run.post().charge(c.productClass, c.riskClass, c.measure);
            Simm.Charge mine = r.charge(c.productClass, c.riskClass, c.measure);
            s.row(row).text(0, c.productClass + " · " + c.riskClass, "label").text(1, c.measure.toLowerCase(Locale.ROOT), "text");
            s.formula(2, chargeMargin.get(c.productClass + "|" + c.riskClass + "|" + c.measure), c(mine == null ? 0 : mine.margin), "money");
            engineRow(s, row, c.margin, pc == null ? 0 : pc.margin, same, "money");
            row++;
        }
        for (Simm.ProductIm pm : r.products()) {
            Simm.ProductIm a = run.collect().product(pm.productClass());
            Simm.ProductIm b = run.post().product(pm.productClass());
            s.row(row).text(0, "SIMM " + pm.productClass(), "label").text(1, "ψ across risk classes", "text");
            s.formula(2, productCell.get(pm.productClass()), c(pm.simm()), "money.bold");
            engineRow(s, row, a == null ? 0 : a.simm(), b == null ? 0 : b.simm(), same, "money.bold");
            row++;
        }
        s.row(simmRow).text(0, "SIMM", "label").text(1, "the product classes added", "text");
        s.formula(2, r.products().isEmpty() ? "0" : "SUM(C" + prodFirst + ":C" + (simmRow - 1) + ")", c(r.simm()), "money.bold");
        engineRow(s, simmRow, run.collect().simm(), run.post().simm(), same, "money.bold");
        s.row(addRow).text(0, "Add-ons", "label").text(1, "the CSA's (Add-ons)", "text");
        s.formula(2, addOnTotal, c(r.addOn().total()), "money");
        engineRow(s, addRow, run.collect().addOn().total(), run.post().addOn().total(), same, "money");
        s.row(imRow).text(0, "Initial margin", "label").text(1, "SIMM + add-ons", "text");
        s.formula(2, "C" + simmRow + "+C" + addRow, c(r.total()), "total");
        engineRow(s, imRow, run.collect().total(), run.post().total(), same, "total");
        // the switches as exported and as they stand now
        s.row(swFirst - 1).text(0, "Switch", "head").text(1, "Exported", "head").text(2, "Now (Config)", "head");
        List<String> eq = new ArrayList<>();
        int sr = swFirst;
        for (String name : List.of("concentration", "hvr", "side")) {
            String exported = name.equals("side") ? side : ch.get(name);
            s.row(sr).text(0, name, "label").text(1, exported, "text");
            s.formulaText(2, sw(name), cached ? exported : null, "text");
            if (!name.equals("side")) {
                eq.add("C" + sr + "=B" + sr);
            }
            sr++;
        }
        s.row(sameRow).text(0, "Switches as exported (the side apart)", "label");
        s.formula(2, "AND(" + String.join(",", eq) + ")", null, "text");
        s.row(altFirst - 1).text(0, "Alternative", "head").text(1, "Switch", "head").text(2, "Value", "head").text(3, "Engine collect", "head")
                .text(4, "Engine post", "head").text(5, "Workbook IM", "head").text(6, "Difference", "head").text(7, "Check", "head");
        int ar = altFirst;
        for (Alternative a : alternatives) {
            List<String> cond = new ArrayList<>();
            int k = swFirst;
            for (String name : List.of("concentration", "hvr")) {
                cond.add(name.equals(a.name()) ? "C" + k + "=" + q(a.value()) : "C" + k + "=B" + k);
                k++;
            }
            s.row(ar).text(0, a.name() + " = " + a.value(), "label").text(1, a.name(), "text").text(2, a.value(), "text");
            s.number(3, a.collect(), "money").number(4, a.post(), "money");
            s.formula(5, "C" + imRow, c(r.total()), "money");
            s.formula(6, "F" + ar + "-IF(" + sw("side") + "=\"post\",E" + ar + ",D" + ar + ")", null, "money");
            s.formulaText(7, "IF(AND(" + String.join(",", cond) + "),IF(ABS(G" + ar + ")<0.005,\"" + PASS + "\",\"" + FAIL + "\"),\"—\")", cached ? "—" : null, "check");
            ar++;
        }
        s.row(ar + 1).text(0, "Recipe", "label").text(1, recipe == null ? "not saved (Save recipe in the playground, or PLAY SIMM RUN <id>)" : recipe, "text");
        if (!empty) {
            s.checks(checks, PASS);
            if (!alternatives.isEmpty()) {
                s.checks(altChecks, PASS);
            }
        }
    }

    // ---- Config ----------------------------------------------------------------------------------------------------------------------

    private void config(SheetStream s) {
        s.width(0, 52).width(1, 18).width(2, 22).width(3, 22).width(4, 34).width(5, 90);
        s.row(1).text(0, "Config: the switches, the constants and the parameters of SIMM " + p.version + (empty ? " (empty: ISDA's numbers are licensed; the bank's "
                + "own export fills them)" : " (licensed: confidential to the bank)"), "title");
        PlaygroundSheets.switchHead(s);
        int row = SW_FIRST;
        for (SimmChoices.Switch x : SimmChoices.SWITCHES) {
            PlaygroundSheets.switchRow(s, row, x, ch.get(x.name()));
            row++;
        }
        s.row(row).text(0, "side", "label").text(1, side, "input.text").text(2, "collect", "text").text(3, "collect, post", "text").text(4, "SIMM.md section 6.8", "text")
                .text(5, "collect from the bank's sensitivities; post from the counterparty's side, every sensitivity negated (curvature differs)", "wrap");
        s.list("B" + row, List.of("collect", "post"));
        s.row(CONST_FIRST - 1).text(0, "Constant", "head").text(1, "Value", "head").text(2, "Meaning", "head");
        s.row(CONST_FIRST).text(0, "Φ⁻¹(99 %)", "label").number(1, SimmParams.PHI99, "input.dec").text(2, "the vega σ of equity, FX, commodity: RW × √(365/14) / Φ⁻¹(99 %)", "text");
        s.row(CONST_FIRST + 1).text(0, "Φ⁻¹(99.5 %)", "label").number(1, SimmParams.PHI995, "input.dec").text(2, "λ = (Φ⁻¹(99.5 %)² − 1)(1 + θ) − θ", "text");
        s.row(CONST_FIRST + 2).text(0, "Threshold FX", "label").number(1, r.input().thresholdFx(), "input.dec")
                .text(2, run.calculationCurrency() + " per " + p.calculationCurrency + " (the thresholds' currency)", "text");
        s.row(PARAM_FIRST - 1).text(0, "Parameter (its key)", "head").text(1, "Value", "head").text(2, "What it is", "head");
        row = PARAM_FIRST;
        for (String k : cfgKeys) {
            s.row(row++).text(0, k, "label").number(1, p.v(k), "input.rate").text(2, p.note(k), "text");
        }
    }

    // ---- Data ------------------------------------------------------------------------------------------------------------------------

    private int dataLast() {
        return FIRST + Math.max(1, r.input().elements().size()) - 1;
    }

    private String dcol(int c) {
        return "Data!$" + col(c) + "$" + FIRST + ":$" + col(c) + "$" + dataLast();
    }

    private void data(SheetStream s) {
        s.width(0, 26).width(1, 12).width(2, 34).width(3, 10).width(4, 16).widths(5, 6, 11).width(7, 44).width(8, 12).width(9, 16).width(10, 44)
                .width(11, 10).width(12, 16).freeze(3, 0);
        s.row(1).text(0, "Data: the sensitivities in " + run.calculationCurrency() + " (converted at the date's FX), each into its SIMM risk; the side switch negates them", "title");
        s.row(3).text(0, "Trade", "head").text(1, "Product class", "head").text(2, "Factor", "head").text(3, "Measure", "head").text(4, "Value", "head")
                .text(5, "Implied vol", "head").text(6, "Expiry (years)", "head").text(7, "Risk", "head").text(8, "Conversion", "head").text(9, "s part", "head")
                .text(10, "Curvature risk", "head").text(11, "SF(t)", "head").text(12, "CVR part", "head");
        List<SimmInput.Element> els = r.input().elements();
        for (int i = 0; i < els.size(); i++) {
            SimmInput.Element e = els.get(i);
            int row = FIRST + i;
            SimmInput.Trade tr = r.input().trades().get(e.trade());
            s.row(row).text(0, tr.id(), "input.text").text(1, tr.productClass(), "input.text").text(2, e.factor() == null ? "(not in the dictionary)" : e.factor().name(),
                    "input.text").text(3, e.measure(), "input.text").number(4, e.value(), "input.money");
            if (Double.isFinite(e.impliedVol())) {
                s.number(5, e.impliedVol(), "input.rate");
            }
            if (e.factor() != null && Double.isFinite(e.factor().years())) {
                s.number(6, e.factor().years(), "input.dec");
            }
            int k = r.elementRisk()[i];
            double sgn = r.post() ? -1 : 1;
            if (k >= 0) {
                Simm.Risk x = r.risks().get(k);
                s.text(7, x.id(), "text");
                if (x.measure.equals(Simm.VEGA)) {
                    String conv = List.of("InterestRate", "CreditQ", "CreditNonQ").contains(x.riskClass) ? "100*F" + row
                            : cf(sigmaKey(x)) + "*SQRT(365/14)/" + PHI99;
                    s.formula(8, conv, c(r.elementConv()[i]), "rate");
                } else {
                    s.number(8, 1, "int");
                }
                s.formula(9, "E" + row + "*I" + row + "*" + sign(), c(e.value() * r.elementConv()[i] * sgn), "money");
            }
            int cv = r.elementCurv()[i];
            if (cv >= 0) {
                s.text(10, r.risks().get(cv).id(), "text");
                s.formula(11, "0.5*MIN(1,14/(G" + row + "*365))", c(r.elementSf()[i]), "rate");
                s.formula(12, "E" + row + "*I" + row + "*L" + row + "*" + sign(), c(e.value() * r.elementConv()[i] * r.elementSf()[i] * sgn), "money");
            }
        }
    }

    // ---- WS --------------------------------------------------------------------------------------------------------------------------

    private int wsLast() {
        int n = 0;
        for (Simm.Risk x : r.risks()) {
            n += curv(x) ? 0 : 1;
        }
        return FIRST + Math.max(1, n) - 1;
    }

    private static String groupKey(Simm.Risk x) {
        return x.group == null ? "" : x.productClass + "|" + x.riskClass + "|" + x.measure + "|" + x.group;
    }

    private void ws(SheetStream s) {
        s.width(0, 10).width(1, 12).width(2, 10).width(3, 9).width(4, 30).width(5, 44).width(6, 44).widths(7, 15, 15).freeze(3, 0);
        s.row(1).text(0, "WS: each factor's netted sensitivity, risk weight, HVR and concentration factor CR = max(1, √(|Σ| / T)) at the rules' grain", "title");
        s.row(3).text(0, "Product", "head").text(1, "Risk class", "head").text(2, "Measure", "head").text(3, "Bucket", "head").text(4, "Factor", "head")
                .text(5, "Risk", "head").text(6, "Concentration grain", "head").text(7, "s (netted)", "head").text(8, "RW", "head").text(9, "HVR", "head")
                .text(10, "HVR × s", "head").text(11, "Grain's sum", "head").text(12, "Threshold", "head").text(13, "CR", "head").text(14, "WS", "head")
                .text(15, "Engine WS", "head");
        String last = String.valueOf(wsLast());
        for (int k = 0; k < r.risks().size(); k++) {
            Simm.Risk x = r.risks().get(k);
            if (curv(x)) {
                continue;
            }
            int row = wsRow[k];
            s.row(row).text(0, x.productClass, "label").text(1, x.riskClass, "text").text(2, x.measure, "text").text(3, x.bucket, "text").text(4, x.key, "text")
                    .text(5, x.id(), "text").text(6, groupKey(x), "text");
            s.formula(7, "SUMIFS(" + dcol(9) + "," + dcol(7) + ",$F" + row + ")", c(x.s), "money");
            s.formula(8, cf(x.rwKey), c(x.rw), "rate");
            if (x.hvrKey != null) {
                s.formula(9, "IF(" + sw("hvr") + "=\"off\",1," + cf(x.hvrKey) + ")", c(x.hvr), "rate");
            } else {
                s.number(9, 1, "rate");
            }
            s.formula(10, "J" + row + "*H" + row, c(x.hvr * x.s), "money");
            if (x.group != null) {
                Simm.Group g = r.groups().stream().filter(y -> groupKey(x).equals(y.productClass + "|" + y.riskClass + "|" + y.measure + "|" + y.group)).findFirst()
                        .orElseThrow();
                s.formula(11, "SUMIFS($K$" + FIRST + ":$K$" + last + ",$G$" + FIRST + ":$G$" + last + ",$G" + row + ")", c(g.sum), "money");
                s.formula(12, cf(x.thresholdKey) + "*" + TFX, c(g.threshold), "money");
                s.formula(13, "IF(" + sw("concentration") + "=\"off\",1,MAX(1,SQRT(ABS(L" + row + ")/M" + row + ")))", c(x.cr), "rate");
            } else {
                s.number(13, 1, "rate");
            }
            s.formula(14, "I" + row + "*K" + row + "*N" + row, c(x.ws), "money");
            s.number(15, x.ws, "money");
        }
    }

    // ---- bucket blocks (Buckets for delta, vega, base correlation; Curvature for curvature) -----------------------------------------------

    private record Block(Simm.Charge c, Simm.Bucket b, int top) {}

    private final List<Block> blocks = new ArrayList<>();
    private final List<Block> curvBlocks = new ArrayList<>();

    private static String id(Simm.Charge c) {
        return c.productClass + "|" + c.riskClass + "|" + c.measure;
    }

    private void planBuckets() {
        int row = 3;
        for (Simm.Charge c : r.charges()) {
            if (c.measure.equals(Simm.CURVATURE)) {
                continue;
            }
            for (Simm.Bucket b : c.buckets) {
                blocks.add(new Block(c, b, row));
                int n = b.members.length;
                int k = row + 3 + n;
                bucketCells.put(id(c) + "|" + b.label, new String[] {abs("Buckets", 3 + n, k), abs("Buckets", 1, k + 1), abs("Buckets", 1, k + 2)});
                row = k + 4;
            }
        }
    }

    /** One bucket block on {@code sheet}: the members' values, ρ (× f) matrix, the terms, K, the net and the capped S. */
    private void block(SheetStream s, Block bl, boolean curvature) {
        Simm.Bucket b = bl.b();
        Simm.Charge ch2 = bl.c();
        int n = b.members.length;
        int top = bl.top();
        int termCol = 3 + n;
        boolean f = !curvature && (ch2.measure.equals(Simm.DELTA) || ch2.measure.equals(Simm.VEGA)) && !ch2.riskClass.equals("InterestRate");
        s.row(top).text(0, ch2.productClass + " · " + ch2.riskClass + " " + ch2.measure.toLowerCase(Locale.ROOT) + " · bucket " + b.label
                + (b.residual ? " (residual: outside the root)" : ""), "label");
        s.row(top + 1).text(0, "Factor", "head").text(1, curvature ? "CVR" : "WS", "head").text(2, "CR", "head");
        for (int j = 0; j < n; j++) {
            s.text(3 + j, (curvature ? "ρ² with " : f ? "ρ·f with " : "ρ with ") + (j + 1), "head");
        }
        s.text(termCol, "Term", "head");
        s.row(top + 2).text(0, "across", "note");
        for (int j = 0; j < n; j++) {
            s.formula(3 + j, "$B$" + (top + 3 + j), c(r.risks().get(b.members[j]).ws), "money");
        }
        String across = "$D$" + (top + 2) + ":$" + col(2 + n) + "$" + (top + 2);
        for (int i = 0; i < n; i++) {
            int ri = b.members[i];
            Simm.Risk a = r.risks().get(ri);
            int row = top + 3 + i;
            s.row(row).text(0, (i + 1) + ". " + a.key, "text");
            if (curvature) {
                s.formula(1, abs("Curvature", 5, cvRow[ri]), c(a.ws), "money");
                s.number(2, 1, "rate");
            } else {
                s.formula(1, abs("WS", 14, wsRow[ri]), c(a.ws), "money");
                s.formula(2, abs("WS", 13, wsRow[ri]), c(a.cr), "rate");
            }
            for (int j = 0; j < n; j++) {
                if (i == j) {
                    s.number(3 + j, 1, "rate");
                    continue;
                }
                String rho = corr(b.rho[i][j]);
                if (f) {
                    rho += "*MIN($C$" + row + ",$C$" + (top + 3 + j) + ")/MAX($C$" + row + ",$C$" + (top + 3 + j) + ")";
                }
                s.formula(3 + j, rho, c(b.rhoValue[i][j] * b.f[i][j]), "rate");
            }
            s.formula(termCol, "$B$" + row + "*SUMPRODUCT($D$" + row + ":$" + col(2 + n) + "$" + row + "," + across + ")", null, "money");
        }
        int k = top + 3 + n;
        String tc = col(termCol);
        s.row(k).text(0, "K_b", "label").formula(termCol, "SQRT(MAX(0,SUM(" + tc + (top + 3) + ":" + tc + (k - 1) + ")))", c(b.k), "money.bold");
        s.row(k + 1).text(0, "Σ (the bucket's net)", "label").formula(1, "SUM(B" + (top + 3) + ":B" + (k - 1) + ")", c(b.net), "money");
        s.row(k + 2).text(0, "S_b = max(min(Σ, K_b), −K_b)", "label").formula(1, "MAX(MIN(B" + (k + 1) + "," + tc + k + "),-" + tc + k + ")", c(b.s), "money.bold");
    }

    private void buckets(SheetStream s) {
        s.width(0, 40).width(1, 16).width(2, 10).freeze(0, 2);
        s.row(1).text(0, "Buckets: K_b = √max(0, Σ_k Σ_l ρ_kl · f_kl · WS_k · WS_l), f_kl = min(CR_k, CR_l) / max(CR_k, CR_l) (1 for rates); S_b capped at ±K_b", "title");
        for (Block bl : blocks) {
            block(s, bl, false);
        }
    }

    // ---- Classes -----------------------------------------------------------------------------------------------------------------------

    private record ClassBlock(Simm.Charge c, int top) {}

    private record ProductBlock(Simm.ProductIm pm, int top) {}

    private final List<ClassBlock> classBlocks = new ArrayList<>();
    private final List<ProductBlock> productBlocks = new ArrayList<>();

    private void planClasses() {
        int row = 3;
        for (Simm.Charge c : r.charges()) {
            if (c.measure.equals(Simm.CURVATURE)) {
                continue;
            }
            int nb = c.core().size();
            classBlocks.add(new ClassBlock(c, row));
            int rootRow = row + 3 + nb;
            chargeMargin.put(id(c), abs("Classes", 1, rootRow + 2));
            row = rootRow + 5;
        }
        for (Simm.ProductIm pm : r.products()) {
            productBlocks.add(new ProductBlock(pm, row));
            int n = SimmParams.RISK_CLASSES.size();
            productCell.put(pm.productClass(), abs("Classes", 6 + n, row + 3 + n));
            row = row + n + 6;
        }
    }

    /** The CR a rates bucket brings to g_bc: the CR of its currency's grain (a member with a grain), else 1. */
    private String bucketCr(Simm.Bucket b) {
        for (int m : b.members) {
            if (r.risks().get(m).group != null) {
                return abs("WS", 13, wsRow[m]);
            }
        }
        return "1";
    }

    private void across(SheetStream s, Simm.Charge c, int top, boolean curvature) {
        List<Simm.Bucket> core = c.core();
        int nb = core.size();
        boolean g = c.riskClass.equals("InterestRate") && !curvature;
        int g0 = 4;
        int termCol = g0 + nb;
        s.row(top).text(0, c.productClass + " · " + c.riskClass + " " + c.measure.toLowerCase(Locale.ROOT) + " across buckets", "label");
        s.row(top + 1).text(0, "Bucket", "head").text(1, "K_b", "head").text(2, "S_b (capped)", "head").text(3, "CR (g_bc)", "head");
        for (int j = 0; j < nb; j++) {
            s.text(g0 + j, (curvature ? "γ² with " : g ? "γ·g with " : "γ with ") + core.get(j).label, "head");
        }
        s.text(termCol, "Term", "head");
        int first = top + 3;
        s.row(top + 2).text(0, "S across", "note");
        for (int j = 0; j < nb; j++) {
            s.formula(g0 + j, "$C$" + (first + j), c(core.get(j).s), "money");
        }
        String sAcross = "$" + col(g0) + "$" + (top + 2) + ":$" + col(Math.max(g0, g0 + nb - 1)) + "$" + (top + 2);
        for (int i = 0; i < nb; i++) {
            Simm.Bucket b = core.get(i);
            int row = first + i;
            String[] bc = curvature ? curvCells.get(id(c) + "|" + b.label) : bucketCells.get(id(c) + "|" + b.label);
            s.row(row).text(0, b.label, "label").formula(1, bc[0], c(b.k), "money").formula(2, bc[2], c(b.s), "money");
            if (g) {
                s.formula(3, bucketCr(b), c(b.cr), "rate");
            } else {
                s.number(3, 1, "rate");
            }
            for (int j = 0; j < nb; j++) {
                if (i == j) {
                    s.number(g0 + j, 0, "rate");
                    continue;
                }
                Simm.Corr k = c.gamma[i][j];
                String gf = k == null ? "0" : corr(k);
                if (g) {
                    gf += "*MIN($D$" + row + ",$D$" + (first + j) + ")/MAX($D$" + row + ",$D$" + (first + j) + ")";
                }
                s.formula(g0 + j, gf, c(c.gammaValue[i][j] * c.g[i][j]), "rate");
            }
            s.formula(termCol, "$B$" + row + "^2+$C$" + row + "*SUMPRODUCT($" + col(g0) + "$" + row + ":$" + col(Math.max(g0, g0 + nb - 1)) + "$" + row + ","
                    + sAcross + ")", null, "money");
        }
        int rootRow = first + nb;
        String tc = col(termCol);
        s.row(rootRow).text(0, "√(Σ K_b² + Σ γ S_b S_c)", "label").formula(1, nb == 0 ? "0" : "SQRT(MAX(0,SUM(" + tc + first + ":" + tc + (rootRow - 1) + ")))",
                c(c.root), "money");
        Simm.Bucket res = c.residualBucket();
        if (!curvature) {
            s.row(rootRow + 1).text(0, "Residual bucket's K (outside the root)", "label")
                    .formula(1, res == null ? "0" : bucketCells.get(id(c) + "|" + res.label)[0], c(c.residual), "money");
            s.row(rootRow + 2).text(0, "Margin", "label").formula(1, "B" + rootRow + "+B" + (rootRow + 1), c(c.margin), "money.bold");
        }
    }

    private void classes(SheetStream s) {
        s.width(0, 34).widths(1, 3, 16).widths(4, 30, 14);
        s.row(1).text(0, "Classes: Delta = √(Σ K_b² + Σ γ_bc · g_bc · S_b · S_c) + K_residual; then SIMM_p = √(Σ_r Σ_s ψ_rs · IM_r · IM_s) per product class", "title");
        for (ClassBlock cb : classBlocks) {
            across(s, cb.c(), cb.top(), false);
        }
        int n = SimmParams.RISK_CLASSES.size();
        for (ProductBlock pb : productBlocks) {
            String pc = pb.pm().productClass();
            int top = pb.top();
            s.row(top).text(0, "Product class " + pc + ": IM per risk class, ψ, SIMM_p", "label");
            s.row(top + 1).text(0, "Risk class", "head").text(1, "Delta", "head").text(2, "Vega", "head").text(3, "Curvature", "head").text(4, "Base corr", "head")
                    .text(5, "IM_r", "head");
            for (int j = 0; j < n; j++) {
                s.text(6 + j, "ψ with " + SimmParams.RISK_CLASSES.get(j), "head");
            }
            s.text(6 + n, "Term", "head");
            int first = top + 3;
            s.row(top + 2).text(0, "IM across", "note");
            for (int j = 0; j < n; j++) {
                s.formula(6 + j, "$F$" + (first + j), c(pb.pm().im()[j]), "money");
            }
            String imAcross = "$G$" + (top + 2) + ":$" + col(5 + n) + "$" + (top + 2);
            for (int i = 0; i < n; i++) {
                String rc = SimmParams.RISK_CLASSES.get(i);
                int row = first + i;
                s.row(row).text(0, rc, "label");
                for (int m = 0; m < 4; m++) {
                    String cell = chargeMargin.get(pc + "|" + rc + "|" + Simm.MEASURES.get(m));
                    Simm.Charge c = r.charge(pc, rc, Simm.MEASURES.get(m));
                    if (cell != null) {
                        s.formula(1 + m, cell, c(c.margin), "money");
                    } else {
                        s.number(1 + m, 0, "money");
                    }
                }
                s.formula(5, "SUM(B" + row + ":E" + row + ")", c(pb.pm().im()[i]), "money.bold");
                for (int j = 0; j < n; j++) {
                    s.formula(6 + j, cf(Simm.psiKey(i, j)), c(p.v(Simm.psiKey(i, j))), "rate");
                }
                s.formula(6 + n, "$F$" + row + "*SUMPRODUCT($G$" + row + ":$" + col(5 + n) + "$" + row + "," + imAcross + ")", null, "money");
            }
            String tc = col(6 + n);
            s.row(first + n).text(0, "SIMM_" + pc, "label").formula(6 + n, "SQRT(MAX(0,SUM(" + tc + first + ":" + tc + (first + n - 1) + ")))", c(pb.pm().simm()),
                    "money.bold");
        }
    }

    // ---- Curvature ---------------------------------------------------------------------------------------------------------------------

    private final Map<String, String[]> curvCells = new HashMap<>();
    private record CurvClass(Simm.Charge c, int acrossTop, int summaryTop, int first, int last, int resFirst, int resLast) {}
    private final List<CurvClass> curvClasses = new ArrayList<>();

    private void planCurvature() {
        int cvCount = 0;
        for (Simm.Risk x : r.risks()) {
            cvCount += curv(x) ? 1 : 0;
        }
        int row = FIRST + cvCount + 2;
        for (Simm.Charge c : r.charges()) {
            if (!c.measure.equals(Simm.CURVATURE)) {
                continue;
            }
            int first = Integer.MAX_VALUE;
            int last = -1;
            int resFirst = Integer.MAX_VALUE;
            int resLast = -1;
            for (Simm.Bucket b : c.buckets) {
                curvBlocks.add(new Block(c, b, row));
                int n = b.members.length;
                int k = row + 3 + n;
                curvCells.put(id(c) + "|" + b.label, new String[] {abs("Curvature", 3 + n, k), abs("Curvature", 1, k + 1), abs("Curvature", 1, k + 2)});
                row = k + 4;
                for (int m : b.members) {
                    if (b.residual) {
                        resFirst = Math.min(resFirst, cvRow[m]);
                        resLast = Math.max(resLast, cvRow[m]);
                    } else {
                        first = Math.min(first, cvRow[m]);
                        last = Math.max(last, cvRow[m]);
                    }
                }
            }
            int acrossTop = row;
            row += 3 + c.core().size() + 2;
            int summaryTop = row;
            chargeMargin.put(id(c), abs("Curvature", 1, summaryTop + 10));
            curvClasses.add(new CurvClass(c, acrossTop, summaryTop, first, last, resFirst, resLast));
            row = summaryTop + 13;
        }
    }

    private void curvature(SheetStream s) {
        s.width(0, 44).width(1, 18).width(2, 16).width(3, 30).width(4, 44).widths(5, 30, 14).freeze(0, 2);
        s.row(1).text(0, "Curvature: CVR_k = Σ SF(t) · σ · ∂V/∂σ (Data); K_b with ρ²; θ = min(Σ CVR / Σ |CVR|, 0); λ = (Φ⁻¹(99.5 %)² − 1)(1 + θ) − θ; "
                + "max(Σ CVR + λ · √(Σ K_b² + Σ γ² S_b S_c), 0); the residual bucket alone; rates × HVR_IR⁻²", "title");
        s.row(3).text(0, "Product", "head").text(1, "Risk class", "head").text(2, "Bucket", "head").text(3, "Factor", "head").text(4, "Risk", "head")
                .text(5, "CVR", "head").text(6, "Engine CVR", "head");
        for (int k = 0; k < r.risks().size(); k++) {
            Simm.Risk x = r.risks().get(k);
            if (!curv(x)) {
                continue;
            }
            int row = cvRow[k];
            s.row(row).text(0, x.productClass, "label").text(1, x.riskClass, "text").text(2, x.bucket, "text").text(3, x.key, "text").text(4, x.id(), "text");
            s.formula(5, "SUMIFS(" + dcol(12) + "," + dcol(10) + ",$E" + row + ")", c(x.s), "money");
            s.number(6, x.s, "money");
        }
        for (CurvClass cc : curvClasses) {
            Simm.Charge c = cc.c();
            for (Block bl : curvBlocks) {
                if (bl.c() == c) {
                    block(s, bl, true);
                }
            }
            across(s, c, cc.acrossTop(), true);
            int top = cc.summaryTop();
            int rootRow = cc.acrossTop() + 3 + c.core().size();
            String nonres = cc.last() < 0 ? "0" : "F" + cc.first() + ":F" + cc.last();
            String res = cc.resLast() < 0 ? null : "F" + cc.resFirst() + ":F" + cc.resLast();
            s.row(top).text(0, c.productClass + " · " + c.riskClass + " curvature margin", "label");
            s.row(top + 1).text(0, "Σ CVR (non-residual)", "label").formula(1, cc.last() < 0 ? "0" : "SUM(" + nonres + ")", c(c.sumCvr), "money");
            s.row(top + 2).text(0, "Σ |CVR| (non-residual)", "label").formula(1, cc.last() < 0 ? "0" : "SUMPRODUCT(ABS(" + nonres + "))", c(c.sumAbsCvr), "money");
            s.row(top + 3).text(0, "θ", "label").formula(1, "IF(B" + (top + 2) + "=0,0,MIN(B" + (top + 1) + "/B" + (top + 2) + ",0))", c(c.theta), "rate");
            s.row(top + 4).text(0, "λ", "label").formula(1, "(" + PHI995 + "^2-1)*(1+B" + (top + 3) + ")-B" + (top + 3), c(c.lambda), "rate");
            s.row(top + 5).text(0, "Non-residual: max(Σ CVR + λ · K, 0)", "label").formula(1, cc.last() < 0 ? "0" : "MAX(B" + (top + 1) + "+B" + (top + 4) + "*B"
                    + rootRow + ",0)", c(c.nonResidual), "money");
            Simm.Bucket rb = c.residualBucket();
            if (res != null) {
                String sum = "SUM(" + res + ")";
                String sumAbs = "SUMPRODUCT(ABS(" + res + "))";
                String theta = "IF(" + sumAbs + "=0,0,MIN(" + sum + "/" + sumAbs + ",0))";
                s.row(top + 6).text(0, "Residual θ", "label").formula(1, theta, c(c.thetaResidual), "rate");
                s.row(top + 7).text(0, "Residual λ", "label").formula(1, "(" + PHI995 + "^2-1)*(1+B" + (top + 6) + ")-B" + (top + 6), c(c.lambdaResidual), "rate");
                s.row(top + 8).text(0, "Residual: max(Σ CVR + λ · K_res, 0)", "label").formula(1, "MAX(" + sum + "+B" + (top + 7) + "*"
                        + curvCells.get(id(c) + "|" + rb.label)[0] + ",0)", c(c.residual), "money");
            } else {
                s.row(top + 8).text(0, "Residual", "label").number(1, 0, "money");
            }
            s.row(top + 9).text(0, "Scale (rates: HVR_IR⁻²)", "label");
            if (c.riskClass.equals("InterestRate")) {
                s.formula(1, "IF(" + sw("hvr") + "=\"off\",1,1/" + cf("InterestRate.historical-volatility-ratio") + "^2)", c(c.scale), "rate");
            } else {
                s.number(1, 1, "rate");
            }
            s.row(top + 10).text(0, "Curvature margin", "label").formula(1, "(B" + (top + 5) + "+B" + (top + 8) + ")*B" + (top + 9), c(c.margin), "money.bold");
        }
    }

    // ---- Add-ons ------------------------------------------------------------------------------------------------------------------

    private record AddOnPlan(int fixedRow, int lineFirst, int lineLast, int multFirst, int multLast, int totalRow) {}

    private AddOnPlan addOnPlan() {
        SimmInput.AddOns a = r.input().addOns();
        int lineFirst = 7;
        int lineLast = lineFirst + a.lines().size() - 1;
        int multFirst = Math.max(lineLast, lineFirst - 1) + 4;
        int multLast = multFirst + r.products().size() - 1;
        return new AddOnPlan(4, lineFirst, lineLast, multFirst, multLast, Math.max(multLast, multFirst - 1) + 2);
    }

    private void addOns(SheetStream s) {
        AddOnPlan ap = addOnPlan();
        SimmInput.AddOns a = r.input().addOns();
        s.width(0, 34).width(1, 22).widths(2, 4, 18);
        s.row(1).text(0, "Add-ons (the CSA's, SIMM section L): fixed amount + Σ factor × notional + Σ_p (MS_p − 1) × SIMM_p", "title");
        s.row(ap.fixedRow()).text(0, "Fixed amount", "label").number(1, a.fixed(), "input.money");
        s.row(ap.lineFirst() - 1).text(0, "Trade", "head").text(1, "Product", "head").text(2, "Notional", "head").text(3, "Factor", "head").text(4, "Amount", "head");
        int row = ap.lineFirst();
        for (SimmInput.Line l : a.lines()) {
            Double f = a.factors().get(l.product());
            s.row(row).text(0, l.trade(), "text").text(1, l.product(), "text").number(2, l.notional(), "input.money").number(3, f == null ? 0 : f, "input.rate");
            s.formula(4, "ABS(C" + row + ")*D" + row, c(f == null ? 0 : f * Math.abs(l.notional())), "money");
            row++;
        }
        s.row(ap.multFirst() - 1).text(0, "Product class", "head").text(1, "Multiplier MS_p (≥ 1)", "head").text(2, "SIMM_p", "head").text(4, "Amount", "head");
        row = ap.multFirst();
        for (Simm.ProductIm pm : r.products()) {
            Double ms = a.multipliers().get(pm.productClass());
            s.row(row).text(0, pm.productClass(), "label").number(1, ms == null ? 1 : ms, "input.rate").formula(2, productCell.get(pm.productClass()), c(pm.simm()), "money");
            s.formula(4, "(B" + row + "-1)*C" + row, c(ms == null ? 0 : (ms - 1) * pm.simm()), "money");
            row++;
        }
        String lines = a.lines().isEmpty() ? "0" : "SUM(E" + ap.lineFirst() + ":E" + ap.lineLast() + ")";
        String mult = r.products().isEmpty() ? "0" : "SUM(E" + ap.multFirst() + ":E" + ap.multLast() + ")";
        s.row(ap.totalRow()).text(0, "Add-ons", "label").formula(4, "B" + ap.fixedRow() + "+" + lines + "+" + mult, c(r.addOn().total()), "money.bold");
    }

    // ---- Explain, Provenance -----------------------------------------------------------------------------------------------------

    private void explain(SheetStream s) {
        PlaygroundSheets.explain(s, "SIMM Playground", t.explain());
    }

    private void provenance(SheetStream s) {
        Map<String, Object> prov = new LinkedHashMap<>(run.provenance());
        prov.put("Scope", run.scope() + " (" + run.kind() + ")");
        prov.put("Calculation currency", run.calculationCurrency());
        prov.put("Inputs digest (SHA-256)", run.inputsDigest());
        prov.put("Licence", empty ? "the shared template: every parameter cell empty (ISDA's numbers are licensed)"
                : "contains the bank's licensed SIMM parameters: confidential, never a shared template");
        prov.put("Recipe", recipe == null ? "not saved" : recipe);
        prov.put("Exported by", user + " at " + now);
        PlaygroundSheets.provenance(s, prov);
    }
}
