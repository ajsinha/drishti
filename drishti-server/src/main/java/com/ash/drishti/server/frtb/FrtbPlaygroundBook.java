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
package com.ash.drishti.server.frtb;

import com.ash.drishti.compute.frtb.Drc;
import com.ash.drishti.compute.frtb.FrtbChoices;
import com.ash.drishti.compute.frtb.FrtbInput;
import com.ash.drishti.compute.frtb.FrtbParams;
import com.ash.drishti.compute.frtb.FrtbSa;
import com.ash.drishti.compute.frtb.Rrao;
import com.ash.drishti.compute.frtb.Sbm;
import com.ash.drishti.compute.frtb.SbmModel;
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
 * The FRTB-SA Playground's workbook (SENSITIVITIES_AND_FRTB_SA.md sections 12.1 and 12.1a, FRTB_SA_GUIDE.md section 8): Main, Config, Data,
 * WS, Buckets, Classes, Curvature, DRC, RRAO, Explain and Provenance. Every derived cell is a formula of the common set; the method's
 * switches are cells of Config with a drop-down, read by the formulas ({@code IF} on the switch), so flipping one recomputes the
 * alternative live; Main holds the engine's figures for the choices exported and for every one-switch alternative, each with its check.
 */
final class FrtbPlaygroundBook {

    static final List<String> STYLES = List.of("default", "title", "band", "head", "text", "note", "input.money", "input.text", "input.int",
            "input.dec", "input.rate", "money", "money.bold", "int", "pct", "rate", "check", "label", "total", "wrap", "label.wrap");
    static final String PASS = "✓";
    static final String FAIL = "✗";
    static final String ALL_PASS = "ALL CHECKS PASS";
    static final String CHANGED = "SWITCHES CHANGED: SEE THE ALTERNATIVES";

    /** An engine run with one switch flipped, for Main's alternatives block. */
    record Alternative(String name, String value, double sbm, double total) {}

    private final FrtbService.Run run;
    private final FrtbSa.Result r;
    private final SbmModel m;
    private final FrtbParams p;
    private final FrtbChoices ch;
    private final List<Alternative> alternatives;
    private final PlaygroundTemplate t;
    private final boolean cached;
    private final String user;
    private final String now;
    private final String recipe;

    // ---- the plan: where everything is ----
    private final Map<String, String> cfg = new LinkedHashMap<>();          // key → Config!$B$n
    private final List<Object[]> cfgRows = new ArrayList<>();               // key, value, rule
    private final Map<String, Integer> switchRow = new LinkedHashMap<>();
    private int girrTableRow;
    private int csrTableRow;
    private final int dataFirst = 4;
    private final int[] wsRow;
    private final Map<String, String[]> bucketK = new HashMap<>();          // cls|measure|bucket → K cells low, medium, high (Buckets!)
    private final Map<String, String> bucketS = new HashMap<>();
    private final Map<String, String[]> classCharge = new LinkedHashMap<>(); // cls|measure → charge cells (Classes! or Curvature!)
    private String drcTotal;
    private String rraoTotal;

    FrtbPlaygroundBook(FrtbService.Run run, List<Alternative> alternatives, PlaygroundTemplate t, boolean cached, String user, String now, String recipe) {
        this.run = run;
        this.r = run.result();
        this.m = r.sbm().model;
        this.p = r.params();
        this.ch = r.choices();
        this.alternatives = List.copyOf(alternatives);
        this.t = t;
        this.cached = cached;
        this.user = user;
        this.now = now;
        this.recipe = recipe;
        this.wsRow = new int[m.risks.size()];
        planConfig();
    }

    private Double c(double v) {
        return cached && Double.isFinite(v) ? v : null;
    }

    private static String col(int c) {
        return XlsxWriter.col(c);
    }

    private static String abs(String sheet, int c, int row) {
        return sheet + "!$" + col(c) + "$" + row;
    }

    private static String q(String s) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    // ---- Config's plan --------------------------------------------------------------------------------------------------------------

    private void param(String key, double value, String rule) {
        if (!cfg.containsKey(key)) {
            int row = 12 + cfgRows.size();
            cfgRows.add(new Object[] {key, value, rule});
            cfg.put(key, "Config!$B$" + row);
        }
    }

    private String cf(String key) {
        String ref = cfg.get(key);
        if (ref == null) {
            throw new IllegalStateException("no Config row " + key);
        }
        return ref;
    }

    private String sw(String name) {
        return "Config!$B$" + switchRow.get(name);
    }

    private void planConfig() {
        int row = 4;
        for (FrtbChoices.Switch s : FrtbChoices.SWITCHES) {
            switchRow.put(s.name(), row++);
        }
        FrtbParams.Scenarios sc = p.scenarios;
        param("HighMult", sc.highMultiplier(), "MAR21.6: high = min(HighMult × ρ, HighCap)");
        param("HighCap", sc.highCap(), "MAR21.6");
        param("LowMult", sc.lowMultiplier(), "MAR21.6: low = max(LowMult × ρ − LowMinus, LowFloor × ρ)");
        param("LowMinus", sc.lowMinus(), "MAR21.6");
        param("LowFloor", sc.lowFloorMultiplier(), "MAR21.6");
        param("GirrTheta", p.girrTheta, "MAR21.46: θ of the tenor correlation");
        param("GirrFloor", p.girrFloor, "MAR21.46: the floor of the tenor correlation");
        param("GirrDiffCurve", p.girrDifferentCurve, "MAR21.47: between curves of one currency");
        param("GirrInflToCurve", p.girrInflationToCurve, "MAR21.48: inflation to a curve");
        param("GirrBasisToAny", p.girrBasisToAnything, "MAR21.49: cross-currency basis to anything");
        param("GirrGamma", p.girrGamma, "MAR21.50: γ across currencies");
        param("GirrVegaAlpha", p.girrVegaAlpha, "MAR21.93: α of the maturity correlations");
        param("GirrCurvShock", p.girrCurvatureShock, "MAR21.99: the GIRR curvature shock (the 0.25-year weight)");
        param("GirrDivisor", p.girrDivisor, "MAR21.44: the specified currencies' divisor (√2)");
        param("CsrName", p.csrName, "MAR21.54: between names");
        param("CsrNameIndex", p.csrNameIndex, "MAR21.55: between names in the index buckets 17 and 18");
        param("CsrTenor", p.csrTenor, "MAR21.54: between tenors");
        param("CsrBasis", p.csrBasis, "MAR21.54: bond curve against CDS curve");
        param("CsrRating", p.csrRating, "MAR21.57: between IG and HY buckets of 1 to 15");
        param("CsrVegaAlpha", p.csrVegaAlpha, "MAR21.94");
        param("EqSpotRepo", p.eqSpotRepo, "MAR21.78: spot to repo");
        param("EqGammaDefault", p.eqGammaDefault, "MAR21.80: buckets 1 to 10");
        param("EqGammaOther", p.eqGammaOther, "MAR21.80: with bucket 11");
        param("EqGammaIndex", p.eqGammaIndex, "MAR21.80: buckets 12 and 13");
        param("EqGammaIndexName", p.eqGammaIndexName, "MAR21.80: 12 or 13 with 1 to 10");
        param("EqVegaAlpha", p.eqVegaAlpha, "MAR21.94");
        p.eqName.forEach((b, v) -> param("EqName." + b, v, "MAR21.78: between names in bucket " + b));
        param("CmTenor", p.cmTenor, "MAR21.83: between tenors");
        param("CmLocation", p.cmLocation, "MAR21.83: between locations");
        param("CmGamma", p.cmGamma, "MAR21.85");
        param("CmGammaOther", p.cmGammaOther, "MAR21.85: with bucket 11");
        param("CmVegaAlpha", p.cmVegaAlpha, "MAR21.94");
        p.cmCommodity.forEach((b, v) -> param("CmCty." + b, v, "MAR21.83: between commodities in bucket " + b));
        param("FxGamma", p.fxGamma, "MAR21.89");
        param("FxDivisor", p.fxDivisor, "MAR21.88: the specified pairs' divisor");
        param("FxCurvDivisor", p.fxCurvatureDivisor, "MAR21.98: the FX curvature divisor");
        param("FxVegaAlpha", p.fxVegaAlpha, "MAR21.94");
        for (SbmModel.Risk k : m.risks) {
            rwParam(base(k.rwSource));
        }
        for (SbmModel.Curv cv : m.curvs) {
            switch (cv.cls) {
                case "CSR_NS" -> rwParam("CSR_NS.delta.risk-weights." + cv.bucket);
                case "EQ" -> rwParam("EQ.delta.spot-risk-weights." + cv.bucket);
                case "COMM" -> rwParam("COMM.delta.risk-weights." + cv.bucket);
                case "FX" -> rwParam("FX.delta.risk-weight");
                default -> {
                }
            }
        }
        p.drcLgd.forEach((k, v) -> param("LGD." + k, v, "MAR22.12: LGD of " + k));
        p.drcRw.forEach((k, v) -> param("DrcRw." + k, v, "MAR22.24: risk weight of " + k));
        param("DrcFloor", p.drcMaturityFloor, "MAR22.17: the maturity floor (years)");
        param("DrcCap", p.drcMaturityCap, "MAR22.17: the maturity cap (years)");
        param("DrcCashEquity", p.drcCashEquityYears, "MAR22.16: cash equity maturity (0.25 or 1)");
        param("DrcSovZero", p.drcSovereignZero ? 1 : 0, "MAR22.7: sovereigns at 0% (1) or by quality (0)");
        param("RraoExotic", p.rraoExotic, "MAR23.8(1): exotic underlyings");
        param("RraoOther", p.rraoOther, "MAR23.8(2): other residual risks");
        int after = 12 + cfgRows.size() + 2;
        girrTableRow = after;                                  // tenor header row, then 10 rows
        csrTableRow = girrTableRow + p.girrTenors.length + 3;
    }

    private static String base(String rwSource) {
        int i = rwSource.indexOf(" / ");
        return i < 0 ? rwSource : rwSource.substring(0, i);
    }

    private void rwParam(String key) {
        if (cfg.containsKey(key)) {
            return;
        }
        param(key, rwValue(key), "risk weight: " + key);
    }

    /** A risk weight by its parameter path. */
    private double rwValue(String key) {
        if (key.startsWith("GIRR.delta.risk-weights[")) {
            return p.girrRw[Integer.parseInt(key.substring(key.indexOf('[') + 1, key.indexOf(']')))];
        }
        String last = key.substring(key.lastIndexOf('.') + 1);
        return switch (key.substring(0, key.lastIndexOf('.'))) {
            case "GIRR.delta" -> last.equals("inflation-risk-weight") ? p.girrInflationRw : p.girrBasisRw;
            case "CSR_NS.delta.risk-weights" -> p.csrRw.get(Integer.parseInt(last));
            case "EQ.delta.spot-risk-weights" -> p.eqSpotRw.get(Integer.parseInt(last));
            case "EQ.delta.repo-risk-weights" -> p.eqRepoRw.get(Integer.parseInt(last));
            case "COMM.delta.risk-weights" -> p.cmRw.get(Integer.parseInt(last));
            case "EQ.vega.risk-weights" -> p.eqVegaRw.get(Integer.parseInt(last));
            case "FX.delta" -> p.fxRw;
            case "GIRR.vega" -> p.girrVegaRw;
            case "CSR_NS.vega" -> p.csrVegaRw;
            case "COMM.vega" -> p.cmVegaRw;
            case "FX.vega" -> p.fxVegaRw;
            default -> throw new IllegalStateException("no risk weight " + key);
        };
    }

    // ---- the writing -------------------------------------------------------------------------------------------------------------

    void write(OutputStream out) {
        Map<String, String> v = new LinkedHashMap<>(t.brandingValues(com.ash.drishti.common.Branding.current()));
        v.put("TITLE", "FRTB-SA Playground · " + run.scope() + " · " + run.date());
        v.put("SUBJECT", "FRTB standardised approach worked in formulas, " + run.reporting());
        v.put("USER", user);
        v.put("NOW", now);
        // the cells Main and the formula tabs read are planned before any sheet is written
        planBuckets();
        planClasses();
        planCurvature();
        Map<String, String[]> planned = new LinkedHashMap<>(classCharge);   // Main lists the classes in the engine's order
        classCharge.clear();
        r.sbm().charges.forEach(cc -> classCharge.put(cc.cls + "|" + cc.measure, planned.get(cc.cls + "|" + cc.measure)));
        planDrc();
        planRrao();
        try (WorkbookStream w = new WorkbookStream(out, t, v)) {
            main(w.sheet("Main", "1F3A5F"));
            config(w.sheet("Config", "B08A2E"));
            data(w.sheet("Data", "B08A2E"));
            ws(w.sheet("WS", "2E5A88"));
            buckets(w.sheet("Buckets", "2E5A88"));
            classes(w.sheet("Classes", "2E5A88"));
            curvature(w.sheet("Curvature", "2E5A88"));
            drc(w.sheet("DRC", "2E5A88"));
            rrao(w.sheet("RRAO", "2E5A88"));
            explain(w.sheet("Explain", "6B7686"));
            provenance(w.sheet("Provenance", "6B7686"));
            w.name("Total", "Main", "$C$" + totalRow);
            w.name("SBM", "Main", "$C$" + sbmRow);
        }
    }

    // ---- Main ------------------------------------------------------------------------------------------------------------------------

    private int sbmRow;
    private int totalRow;

    private void main(SheetStream s) {
        s.width(0, 24).width(1, 16).widths(2, 7, 16).width(8, 10).width(9, 40).freeze(4, 0);
        s.row(1).text(0, com.ash.drishti.common.Branding.current().productOr("") + " · FRTB-SA Playground", "title");
        for (int i = 1; i < 10; i++) {
            s.blank(i, "title");
        }
        s.row(2).text(0, "Scope " + run.scope() + " (" + run.canonical() + ") · " + (run.date() == null ? "the hand-built cases" : run.date()) + " · "
                + run.reporting() + " · " + p.describe() + (ch.regulatory() ? "" : " · NOT THE REGULATORY FIGURE (switches or overrides)"), "note");
        List<String[]> rows = new ArrayList<>(classCharge.entrySet().stream().map(e -> new String[] {e.getKey()}).toList());
        int first = 6;
        int last = first + rows.size() - 1;
        sbmRow = last + 1;
        int bindRow = sbmRow + 1;
        int repRow = bindRow + 1;
        int drcRow = repRow + 1;
        int rraoRow = drcRow + 1;
        totalRow = rraoRow + 1;
        int swFirst = totalRow + 3;
        int sameRow = swFirst + FrtbChoices.SWITCHES.size();
        String same = "$C$" + sameRow;
        String checks = "I" + first + ":I" + totalRow;
        int altFirst = sameRow + 3;
        String altChecks = "H" + altFirst + ":H" + Math.max(altFirst, altFirst + alternatives.size() - 1);
        s.row(3).formulaText(0, "IF(SUMPRODUCT((" + checks + "=\"" + FAIL + "\")*1)+SUMPRODUCT((" + altChecks + "=\"" + FAIL + "\")*1)=0,IF(" + same + ",\""
                + ALL_PASS + "\",\"" + CHANGED + "\"),\"A CHECK FAILS\")", cached ? ALL_PASS : null, "band");
        s.checks("A3", ALL_PASS);
        s.row(5).text(0, "Class", "head").text(1, "Measure", "head").text(2, "Low", "head").text(3, "Medium", "head").text(4, "High", "head")
                .text(5, "Engine low", "head").text(6, "Engine medium", "head").text(7, "Engine high", "head").text(8, "Check", "head");
        int row = first;
        for (Map.Entry<String, String[]> e : classCharge.entrySet()) {
            String[] k = e.getKey().split("\\|");
            Sbm.ClassCharge cc = r.sbm().classCharge(k[0], k[1]);
            s.row(row).text(0, k[0], "label").text(1, k[1].toLowerCase(Locale.ROOT), "text");
            for (int sc = 0; sc < 3; sc++) {
                s.formula(2 + sc, e.getValue()[sc].replace("$", ""), c(cc.charge[sc]), "money");
            }
            for (int sc = 0; sc < 3; sc++) {
                s.number(5 + sc, cc.charge[sc], "money");
            }
            s.formulaText(8, "IF(" + same + ",IF(MAX(ABS(C" + row + "-F" + row + "),ABS(D" + row + "-G" + row + "),ABS(E" + row + "-H" + row + "))<0.005,\""
                    + PASS + "\",\"" + FAIL + "\"),\"changed\")", cached ? PASS : null, "check");
            row++;
        }
        s.row(sbmRow).text(0, "SBM", "label").text(1, "the classes added", "text");
        for (int sc = 0; sc < 3; sc++) {
            String cl = col(2 + sc);
            s.formula(2 + sc, rows.isEmpty() ? "0" : "SUM(" + cl + first + ":" + cl + last + ")", c(r.sbm().total[sc]), "money.bold");
        }
        for (int sc = 0; sc < 3; sc++) {
            s.number(5 + sc, r.sbm().total[sc], "money.bold");
        }
        s.formulaText(8, "IF(" + same + ",IF(MAX(ABS(C" + sbmRow + "-F" + sbmRow + "),ABS(D" + sbmRow + "-G" + sbmRow + "),ABS(E" + sbmRow + "-H" + sbmRow
                + "))<0.005,\"" + PASS + "\",\"" + FAIL + "\"),\"changed\")", cached ? PASS : null, "check");
        String l = "C" + sbmRow;
        String md = "D" + sbmRow;
        String h = "E" + sbmRow;
        s.row(bindRow).text(0, "Binding scenario", "label").text(1, "the largest (MAR21.6)", "text");
        s.formulaText(2, "IF(AND(" + l + ">" + md + "," + l + ">=" + h + "),\"low\",IF(" + h + ">" + md + ",\"high\",\"medium\"))",
                cached ? Sbm.SCENARIOS[r.sbm().binding] : null, "text");
        s.text(5, Sbm.SCENARIOS[r.sbm().binding], "text");
        s.formulaText(8, "IF(" + same + ",IF(C" + bindRow + "=F" + bindRow + ",\"" + PASS + "\",\"" + FAIL + "\"),\"changed\")", cached ? PASS : null, "check");
        String scn = sw("scenario");
        s.row(repRow).text(0, "SBM (reported)", "label").text(1, "the scenario switch", "text");
        s.formula(2, "IF(" + scn + "=\"low\"," + l + ",IF(" + scn + "=\"medium\"," + md + ",IF(" + scn + "=\"high\"," + h + ",MAX(" + l + "," + md + "," + h
                + "))))", c(r.sbm().sbm()), "money.bold");
        s.number(5, r.sbm().sbm(), "money.bold");
        single(s, repRow, same);
        s.row(drcRow).text(0, "DRC", "label").text(1, "MAR22", "text");
        if (r.drc().delivered()) {
            s.formula(2, drcTotal, c(r.drc().total()), "money");
            s.number(5, r.drc().total(), "money");
            single(s, drcRow, same);
        } else {
            s.text(2, "not computed: no default-risk inputs", "note");
        }
        s.row(rraoRow).text(0, "RRAO", "label").text(1, "MAR23", "text");
        if (r.rrao().delivered()) {
            s.formula(2, rraoTotal, c(r.rrao().total()), "money");
            s.number(5, r.rrao().total(), "money");
            single(s, rraoRow, same);
        } else {
            s.text(2, "not computed: no residual-risk inputs", "note");
        }
        s.row(totalRow).text(0, "Total FRTB-SA", "label").text(1, "SBM + DRC + RRAO", "text");
        s.formula(2, "C" + repRow + "+IF(ISNUMBER(C" + drcRow + "),C" + drcRow + ",0)+IF(ISNUMBER(C" + rraoRow + "),C" + rraoRow + ",0)", c(r.total()), "total");
        s.number(5, r.total(), "total");
        single(s, totalRow, same);
        // the switches as exported and as they stand now
        s.row(swFirst - 1).text(0, "Switch", "head").text(1, "Exported", "head").text(2, "Now (Config)", "head");
        int sr = swFirst;
        List<String> eq = new ArrayList<>();
        for (FrtbChoices.Switch x : FrtbChoices.SWITCHES) {
            s.row(sr).text(0, x.name(), "label").text(1, ch.get(x.name()), "text");
            s.formulaText(2, sw(x.name()).replace("Config!", "Config!"), cached ? ch.get(x.name()) : null, "text");
            eq.add("C" + sr + "=B" + sr);
            sr++;
        }
        s.row(sameRow).text(0, "Switches as exported", "label").text(1, "", "text");
        s.formula(2, "AND(" + String.join(",", eq) + ")", null, "text");
        // the alternatives: each one switch away, the engine's figure, checked when that switch alone is flipped in Config
        s.row(altFirst - 1).text(0, "Alternative", "head").text(1, "Switch", "head").text(2, "Value", "head").text(3, "Engine SBM", "head")
                .text(4, "Engine total", "head").text(5, "Workbook total", "head").text(6, "Difference", "head").text(7, "Check", "head");
        int ar = altFirst;
        for (Alternative a : alternatives) {
            List<String> cond = new ArrayList<>();
            int k = swFirst;
            for (FrtbChoices.Switch x : FrtbChoices.SWITCHES) {
                cond.add(x.name().equals(a.name()) ? "C" + k + "=" + q(a.value()) : "C" + k + "=B" + k);
                k++;
            }
            s.row(ar).text(0, a.name() + " = " + a.value(), "label").text(1, a.name(), "text").text(2, a.value(), "text");
            s.number(3, a.sbm(), "money").number(4, a.total(), "money");
            s.formula(5, "C" + totalRow, c(r.total()), "money");
            s.formula(6, "E" + ar + "-E$" + totalRow, null, "money");
            s.formulaText(7, "IF(AND(" + String.join(",", cond) + "),IF(ABS(F" + ar + "-E" + ar + ")<0.005,\"" + PASS + "\",\"" + FAIL + "\"),\"—\")",
                    cached ? "—" : null, "check");
            ar++;
        }
        s.row(ar + 1).text(0, "Recipe", "label").text(1, recipe == null ? "not saved (Save recipe in the playground, or PLAY FRTB RUN <id>)" : recipe, "text");
        s.checks(checks, PASS);
        if (!alternatives.isEmpty()) {
            s.checks(altChecks, PASS);
        }
    }

    private void single(SheetStream s, int row, String same) {
        s.formulaText(8, "IF(" + same + ",IF(ABS(C" + row + "-F" + row + ")<0.005,\"" + PASS + "\",\"" + FAIL + "\"),\"changed\")", cached ? PASS : null, "check");
    }

    // ---- Config ----------------------------------------------------------------------------------------------------------------------

    private void config(SheetStream s) {
        s.width(0, 34).width(1, 22).width(2, 22).width(3, 34).width(4, 18).width(5, 90);
        s.row(1).text(0, "Config: the switches and the parameters actually used", "title");
        s.row(3).text(0, "Switch", "head").text(1, "Value (choose)", "head").text(2, "Default", "head").text(3, "Options", "head").text(4, "Rule", "head")
                .text(5, "Why the default", "head");
        for (FrtbChoices.Switch x : FrtbChoices.SWITCHES) {
            int row = switchRow.get(x.name());
            s.row(row).text(0, x.name(), "label").text(1, ch.get(x.name()), "input.text").text(2, x.dflt(), "text").text(3, String.join(", ", x.options()), "text")
                    .text(4, x.rule(), "text").text(5, x.why(), "wrap");
            s.list("B" + row, x.options());
        }
        s.row(11).text(0, "Parameter", "head").text(1, "Value", "head").text(2, "Rule", "head");
        int row = 12;
        for (Object[] o : cfgRows) {
            s.row(row++).text(0, (String) o[0], "label").number(1, (Double) o[1], "input.rate").text(2, (String) o[2], "text");
        }
        s.row(girrTableRow - 1).text(0, "GIRR tenor correlation, MAR21.46's published table", "head");
        s.row(girrTableRow).text(0, "tenor", "label");
        for (int j = 0; j < p.girrTenors.length; j++) {
            s.number(1 + j, p.girrTenors[j], "input.dec");
        }
        for (int i = 0; i < p.girrTenors.length; i++) {
            s.row(girrTableRow + 1 + i).number(0, p.girrTenors[i], "input.dec");
            for (int j = 0; j < p.girrTenors.length; j++) {
                s.number(1 + j, p.girrTable[i][j], "input.rate");
            }
        }
        s.row(csrTableRow - 1).text(0, "CSR sector correlation, MAR21.57 (sectors " + String.join(", ", p.csrSectors) + ")", "head");
        for (int i = 0; i < p.csrSectors.size(); i++) {
            s.row(csrTableRow + i).text(0, "sector " + p.csrSectors.get(i), "label");
            for (int j = 0; j < p.csrSectors.size(); j++) {
                s.number(1 + j, p.csrSectorTable[i][j], "input.rate");
            }
        }
    }

    private String girrTable() {
        int n = p.girrTenors.length;
        return "Config!$B$" + (girrTableRow + 1) + ":$" + col(n) + "$" + (girrTableRow + n);
    }

    private String girrTenorsRow() {
        return "Config!$B$" + girrTableRow + ":$" + col(p.girrTenors.length) + "$" + girrTableRow;
    }

    private String csrSector(int a, int b) {
        return "Config!$" + col(1 + b) + "$" + (csrTableRow + a);
    }

    // ---- Data ------------------------------------------------------------------------------------------------------------------------

    private static final int D_POS = 0;
    private static final int D_VAL = 3;
    private static final int D_IV = 5;
    private static final int D_KIND = 6;
    private static final int D_RISK = 7;
    private static final int D_CURV = 8;
    private static final int D_S = 10;
    private static final int D_IDX = 11;

    private int dataLast() {
        return dataFirst + Math.max(1, r.input().elements().size()) - 1;
    }

    private String dcol(int c) {
        return "Data!$" + col(c) + "$" + dataFirst + ":$" + col(c) + "$" + dataLast();
    }

    static String kind(String measure) {
        return switch (measure) {
            case "CURVATURE_UP" -> "UP";
            case "CURVATURE_DOWN" -> "DOWN";
            case "GAMMA" -> "GAMMA";
            case "VEGA" -> "VEGA";
            default -> "DELTA";
        };
    }

    private void data(SheetStream s) {
        s.width(0, 34).width(1, 34).width(2, 14).width(3, 16).widths(4, 6, 11).width(7, 40).width(8, 26).width(9, 11).width(10, 18).width(11, 9).freeze(3, 0);
        s.row(1).text(0, "Data: the scope's sensitivity elements in " + run.reporting() + " (converted at the date's FX; Provenance names the rates)", "title");
        s.row(3).text(0, "Position", "head").text(1, "Factor", "head").text(2, "Measure", "head").text(3, "Value", "head").text(4, "Shock size", "head")
                .text(5, "Implied vol", "head").text(6, "Kind", "head").text(7, "Risk factor key", "head").text(8, "Curvature key", "head")
                .text(9, "Conversion", "head").text(10, "Regulatory s", "head").text(11, "Index", "head");
        List<FrtbInput.Element> els = r.input().elements();
        for (int i = 0; i < els.size(); i++) {
            FrtbInput.Element e = els.get(i);
            int row = dataFirst + i;
            String kind = kind(e.measure());
            s.row(row).text(D_POS, r.input().positions().get(e.position()).id(), "input.text").text(1, e.factor() == null ? "(not in the dictionary)" : e.factor().name(),
                    "input.text").text(2, e.measure(), "input.text").number(D_VAL, e.value(), "input.money");
            if (Double.isFinite(e.shockSize())) {
                s.number(4, e.shockSize(), "input.dec");
            }
            if (Double.isFinite(e.impliedVol())) {
                s.number(D_IV, e.impliedVol(), "input.rate");
            }
            s.text(D_KIND, kind, "text");
            if (m.elementRisk[i] != null) {
                s.text(D_RISK, m.elementRisk[i], "text");
            }
            if (m.elementCurv[i] != null) {
                s.text(D_CURV, m.elementCurv[i], "text");
            }
            if (m.elementRisk[i] != null) {
                if (kind.equals("VEGA")) {
                    s.text(9, "100 × σ", "text");
                    s.formula(D_S, "D" + row + "*100*F" + row, c(e.value() * m.elementConversion[i]), "money");
                } else {
                    s.number(9, m.elementConversion[i], "int");
                    s.formula(D_S, "D" + row + "*J" + row, c(e.value() * m.elementConversion[i]), "money");
                }
            }
            s.number(D_IDX, e.position(), "int");
        }
    }

    // ---- WS --------------------------------------------------------------------------------------------------------------------------

    private void ws(SheetStream s) {
        s.width(0, 9).width(1, 9).width(2, 9).width(3, 40).widths(4, 6, 14).widths(7, 8, 8).width(9, 18).width(10, 40).width(11, 8).width(12, 12)
                .width(13, 18).width(14, 18).freeze(3, 0);
        s.row(1).text(0, "WS: each factor's netted sensitivity, its risk weight and its weighted sensitivity (MAR21.4(2), (3))", "title");
        s.row(3).text(0, "Class", "head").text(1, "Measure", "head").text(2, "Bucket", "head").text(3, "Factor", "head").text(4, "Type", "head")
                .text(5, "Name", "head").text(6, "Curve", "head").text(7, "Tenor", "head").text(8, "Underlying", "head").text(9, "s (netted)", "head")
                .text(10, "Risk weight source", "head").text(11, "÷", "head").text(12, "RW", "head").text(13, "WS", "head").text(14, "Engine WS", "head");
        int first = 12;
        String rwKeys = "Config!$A$" + first + ":$A$" + (11 + cfgRows.size());
        String rwVals = "Config!$B$" + first + ":$B$" + (11 + cfgRows.size());
        Integer[] order = new Integer[m.risks.size()];
        for (int k = 0; k < order.length; k++) {
            order[k] = k;
        }
        java.util.Arrays.sort(order, java.util.Comparator.comparingInt(k -> wsRow[k]));
        for (int k : order) {
            SbmModel.Risk x = m.risks.get(k);
            int row = wsRow[k];
            boolean div = x.rwSource.contains(" / ");
            s.row(row).text(0, x.cls, "label").text(1, x.measure, "text").text(2, x.bucket, "text").text(3, x.key, "text").text(4, x.type, "text")
                    .text(5, x.name, "text").text(6, x.curve, "text");
            if (Double.isFinite(x.t1)) {
                s.number(7, x.t1, "input.dec");
            }
            if (Double.isFinite(x.t2)) {
                s.number(8, x.t2, "input.dec");
            }
            s.formula(9, "SUMIFS(" + dcol(D_S) + "," + dcol(D_RISK) + ",$D" + row + ")", c(x.s), "money");
            s.text(10, base(x.rwSource), "text").number(11, div ? 1 : 0, "int");
            String divisor = x.cls.equals("FX") ? cf("FxDivisor") : cf("GirrDivisor");
            s.formula(12, "INDEX(" + rwVals + ",MATCH($K" + row + "," + rwKeys + ",0))/IF($L" + row + "=1," + divisor + ",1)", c(x.rw), "rate");
            s.formula(13, "M" + row + "*J" + row, c(x.ws()), "money");
            s.number(14, x.ws(), "money");
        }
    }

    // ---- Buckets (delta and vega) ----------------------------------------------------------------------------------------------------

    /** One bucket block's place: its first row, member rows, columns. */
    private record Block(Sbm.ClassCharge cc, Sbm.Bucket b, int top, int n) {}

    private final List<Block> blocks = new ArrayList<>();

    private void planBuckets() {
        int row = 3;
        int ws = 4;
        for (Sbm.ClassCharge cc : r.sbm().charges) {
            if (cc.measure.equals(SbmModel.CURVATURE)) {
                continue;
            }
            for (Sbm.Bucket b : cc.buckets) {
                for (int mbr : b.members) {
                    wsRow[mbr] = ws++;
                }
                int n = b.members.length;
                blocks.add(new Block(cc, b, row, n));
                int kRow = row + 3 + n;
                int termCol = 2 + n;
                String key = cc.cls + "|" + cc.measure + "|" + b.bucket;
                bucketK.put(key, new String[] {abs("Buckets", termCol, kRow), abs("Buckets", termCol + 1, kRow), abs("Buckets", termCol + 2, kRow)});
                bucketS.put(key, abs("Buckets", 1, kRow + 1));
                row = kRow + 3;
            }
        }
    }

    /** {@code x} (a range or a cell) in scenario {@code s}, elementwise inside SUMPRODUCT (MAR21.6). */
    private String scen(int s, String x) {
        String hm = cf("HighMult");
        String hc = cf("HighCap");
        String lm = cf("LowMult");
        String lmin = cf("LowMinus");
        String lf = cf("LowFloor");
        return switch (s) {
            case 0 -> "((" + lm + "*" + x + "-" + lmin + ">" + lf + "*" + x + ")*(" + lm + "*" + x + "-" + lmin + ")+(" + lm + "*" + x + "-" + lmin + "<=" + lf
                    + "*" + x + ")*" + lf + "*" + x + ")";
            case 2 -> "((" + hm + "*" + x + ">" + hc + ")*" + hc + "+(" + hm + "*" + x + "<=" + hc + ")*" + hm + "*" + x + ")";
            default -> x;
        };
    }

    private String wsAttr(int risk, int c) {
        return "WS!$" + col(c) + "$" + wsRow[risk];
    }

    /** The medium ρ between two factors of a bucket, as a formula on WS's attribute cells and Config. */
    private String rhoFormula(SbmModel.Risk a, int ia, SbmModel.Risk b, int ib) {
        String ta = wsAttr(ia, 4);
        String tb = wsAttr(ib, 4);
        String na = wsAttr(ia, 5);
        String nb = wsAttr(ib, 5);
        String ca = wsAttr(ia, 6);
        String cb = wsAttr(ib, 6);
        String t1a = wsAttr(ia, 7);
        String t1b = wsAttr(ib, 7);
        String t2a = wsAttr(ia, 8);
        String t2b = wsAttr(ib, 8);
        int bucket = bucketNo(a.bucket);
        boolean vega = a.measure.equals(SbmModel.VEGA);
        return switch (a.cls) {
            case "GIRR" -> {
                if (vega) {
                    String alpha = cf("GirrVegaAlpha");
                    yield "MIN(EXP(-" + alpha + "*ABS(" + t1a + "-" + t1b + ")/MIN(" + t1a + "," + t1b + "))*EXP(-" + alpha + "*ABS(" + t2a + "-" + t2b + ")/MIN("
                            + t2a + "," + t2b + ")),1)";
                }
                String exact = "MAX(EXP(-" + cf("GirrTheta") + "*ABS(" + t1a + "-" + t1b + ")/MIN(" + t1a + "," + t1b + "))," + cf("GirrFloor") + ")";
                String table = "INDEX(" + girrTable() + ",MATCH(" + t1a + "," + girrTenorsRow() + ",0),MATCH(" + t1b + "," + girrTenorsRow() + ",0))";
                yield "IF(OR(" + ta + "=\"BASIS\"," + tb + "=\"BASIS\")," + cf("GirrBasisToAny") + ",IF(OR(" + ta + "=\"INFLATION\"," + tb + "=\"INFLATION\"),"
                        + cf("GirrInflToCurve") + ",IF(" + sw("girr-correlation") + "=\"exact\"," + exact + "," + table + ")*IF(" + ca + "=" + cb + ",1,"
                        + cf("GirrDiffCurve") + ")))";
            }
            case "CSR_NS" -> {
                String name = "IF(" + na + "=" + nb + ",1," + (p.csrIndex.contains(bucket) ? cf("CsrNameIndex") : cf("CsrName")) + ")";
                if (vega) {
                    yield "MIN(" + name + "*EXP(-" + cf("CsrVegaAlpha") + "*ABS(" + t1a + "-" + t1b + ")/MIN(" + t1a + "," + t1b + ")),1)";
                }
                yield name + "*IF(" + t1a + "=" + t1b + ",1," + cf("CsrTenor") + ")*IF(" + ca + "=" + cb + ",1," + cf("CsrBasis") + ")";
            }
            case "EQ" -> {
                String nameRho = cfg.containsKey("EqName." + bucket) ? cf("EqName." + bucket) : "0";
                if (vega) {
                    yield "MIN(IF(" + na + "=" + nb + ",1," + nameRho + ")*EXP(-" + cf("EqVegaAlpha") + "*ABS(" + t1a + "-" + t1b + ")/MIN(" + t1a + "," + t1b + ")),1)";
                }
                String mixed = "IF(" + ta + "=" + tb + ",1," + cf("EqSpotRepo") + ")";
                yield "IF(" + na + "=" + nb + "," + mixed + "," + nameRho + "*" + mixed + ")";
            }
            case "COMM" -> {
                String cty = "IF(" + na + "=" + nb + ",1," + (cfg.containsKey("CmCty." + bucket) ? cf("CmCty." + bucket) : "0") + ")";
                if (vega) {
                    yield "MIN(" + cty + "*EXP(-" + cf("CmVegaAlpha") + "*ABS(" + t1a + "-" + t1b + ")/MIN(" + t1a + "," + t1b + ")),1)";
                }
                yield cty + "*IF(" + t1a + "=" + t1b + ",1," + cf("CmTenor") + ")*IF(" + ca + "=" + cb + ",1," + cf("CmLocation") + ")";
            }
            default -> vega ? "MIN(EXP(-" + cf("FxVegaAlpha") + "*ABS(" + t1a + "-" + t1b + ")/MIN(" + t1a + "," + t1b + ")),1)" : "1";
        };
    }

    private static int bucketNo(String b) {
        try {
            return Integer.parseInt(b.strip());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void buckets(SheetStream s) {
        s.width(0, 40).width(1, 16).freeze(0, 2);
        s.row(1).text(0, "Buckets: K_b = √max(0, Σ_k Σ_l ρ_kl · WS_k · WS_l) per scenario (MAR21.4(4)); other-sector buckets Σ|WS| (MAR21.56, MAR21.79)", "title");
        for (Block bl : blocks) {
            Sbm.Bucket b = bl.b();
            int n = bl.n();
            int top = bl.top();
            int termCol = 2 + n;
            s.row(top).text(0, bl.cc().cls + " " + bl.cc().measure.toLowerCase(Locale.ROOT) + " · bucket " + b.bucket + (b.absSum ? " (absolute sum)" : ""), "label");
            s.row(top + 1).text(0, "Factor", "head").text(1, "WS", "head");
            for (int j = 0; j < n; j++) {
                s.text(2 + j, "ρ with " + (j + 1), "head");
            }
            s.text(termCol, "Low term", "head").text(termCol + 1, "Medium term", "head").text(termCol + 2, "High term", "head");
            s.row(top + 2).text(0, "WS across", "note");
            for (int j = 0; j < n; j++) {
                s.formula(2 + j, "$B$" + (top + 3 + j), c(m.risks.get(b.members[j]).ws()), "money");
            }
            String wsRowRange = "$C$" + (top + 2) + ":$" + col(1 + n) + "$" + (top + 2);
            for (int i = 0; i < n; i++) {
                int ri = b.members[i];
                SbmModel.Risk a = m.risks.get(ri);
                int row = top + 3 + i;
                s.row(row).text(0, (i + 1) + ". " + a.key, "text");
                s.formula(1, "WS!$N$" + wsRow[ri], c(a.ws()), "money");
                if (!b.absSum) {
                    for (int j = 0; j < n; j++) {
                        if (i == j) {
                            s.number(2 + j, 1, "rate");
                        } else {
                            s.formula(2 + j, rhoFormula(a, ri, m.risks.get(b.members[j]), b.members[j]), b.rho == null ? null : c(b.rho[i][j]), "rate");
                        }
                    }
                }
                String rhoRow = "$C$" + row + ":$" + col(1 + n) + "$" + row;
                for (int sc = 0; sc < 3; sc++) {
                    s.formula(termCol + sc, b.absSum ? "ABS($B$" + row + ")" : "$B$" + row + "*SUMPRODUCT(" + scen(sc, rhoRow) + "," + wsRowRange + ")", null, "money");
                }
            }
            int kRow = top + 3 + n;
            s.row(kRow).text(0, "K_b", "label");
            for (int sc = 0; sc < 3; sc++) {
                String tc = col(termCol + sc);
                String sum = "SUM(" + tc + (top + 3) + ":" + tc + (kRow - 1) + ")";
                s.formula(termCol + sc, b.absSum ? sum : "SQRT(MAX(0," + sum + "))", c(b.k[sc]), "money.bold");
            }
            s.row(kRow + 1).text(0, "S_b = Σ WS", "label").formula(1, "SUM(B" + (top + 3) + ":B" + (kRow - 1) + ")", c(b.net), "money.bold");
        }
    }

    // ---- Classes (delta and vega across buckets) -------------------------------------------------------------------------------------

    private record ClassBlock(Sbm.ClassCharge cc, int top) {}

    private final List<ClassBlock> classBlocks = new ArrayList<>();

    private void planClasses() {
        int row = 3;
        for (Sbm.ClassCharge cc : r.sbm().charges) {
            if (cc.measure.equals(SbmModel.CURVATURE)) {
                continue;
            }
            int nb = cc.buckets.size();
            classBlocks.add(new ClassBlock(cc, row));
            int totals = row + 6 + nb;
            int gCols = 5 + nb;
            classCharge.put(cc.cls + "|" + cc.measure, new String[] {abs("Classes", gCols + 9, totals + 3), abs("Classes", gCols + 10, totals + 3),
                    abs("Classes", gCols + 11, totals + 3)});
            row = totals + 6;
        }
    }

    /** γ (medium) between two buckets as a formula on Config. */
    private String gammaFormula(String cls, String x, String y) {
        int a = bucketNo(x);
        int b = bucketNo(y);
        return switch (cls) {
            case "GIRR" -> cf("GirrGamma");
            case "FX" -> cf("FxGamma");
            case "COMM" -> a == p.cmOther || b == p.cmOther ? cf("CmGammaOther") : cf("CmGamma");
            case "EQ" -> {
                if (a == p.eqOther || b == p.eqOther) {
                    yield cf("EqGammaOther");
                }
                boolean ia = a == 12 || a == 13;
                boolean ib = b == 12 || b == 13;
                yield ia && ib ? cf("EqGammaIndex") : ia || ib ? cf("EqGammaIndexName") : cf("EqGammaDefault");
            }
            default -> {
                if (a == p.csrOther || b == p.csrOther) {
                    yield "0";
                }
                int sa = p.csrSector(a);
                int sb = p.csrSector(b);
                if (sa < 0 || sb < 0) {
                    yield "0";
                }
                boolean both = a >= 1 && a <= 15 && b >= 1 && b <= 15;
                boolean differ = (a <= 8) != (b <= 8);
                yield csrSector(sa, sb) + (both && differ ? "*" + cf("CsrRating") : "");
            }
        };
    }

    private void classes(SheetStream s) {
        s.width(0, 12).widths(1, 4, 16);
        s.row(1).text(0, "Classes: Δ = √(Σ K_b² + Σ_{b≠c} γ_bc · S_b · S_c), with the alternative specification when the radicand is negative (MAR21.4(5))", "title");
        for (ClassBlock cb : classBlocks) {
            Sbm.ClassCharge cc = cb.cc();
            int nb = cc.buckets.size();
            int top = cb.top();
            int g0 = 5;
            int pt = g0 + nb;
            int cs = pt + 3;
            int ct = cs + 3;
            s.row(top).text(0, cc.cls + " " + cc.measure.toLowerCase(Locale.ROOT), "label");
            s.row(top + 1).text(0, "Bucket", "head").text(1, "K low", "head").text(2, "K medium", "head").text(3, "K high", "head").text(4, "S_b", "head");
            for (int j = 0; j < nb; j++) {
                s.text(g0 + j, "γ with " + cc.buckets.get(j).bucket, "head");
            }
            for (int sc = 0; sc < 3; sc++) {
                s.text(pt + sc, "Term " + Sbm.SCENARIOS[sc], "head").text(cs + sc, "Capped S " + Sbm.SCENARIOS[sc], "head").text(ct + sc,
                        "Capped term " + Sbm.SCENARIOS[sc], "head");
            }
            int first = top + 6;
            s.row(top + 2).text(0, "S across", "note");
            for (int j = 0; j < nb; j++) {
                s.formula(g0 + j, "$E$" + (first + j), c(cc.buckets.get(j).net), "money");
            }
            for (int sc = 0; sc < 3; sc++) {
                s.row(top + 3 + sc).text(0, "Capped " + Sbm.SCENARIOS[sc], "note");
                for (int j = 0; j < nb; j++) {
                    s.formula(g0 + j, "$" + col(cs + sc) + "$" + (first + j), null, "money");
                }
            }
            String sRow = "$" + col(g0) + "$" + (top + 2) + ":$" + col(g0 + nb - 1) + "$" + (top + 2);
            for (int i = 0; i < nb; i++) {
                Sbm.Bucket b = cc.buckets.get(i);
                int row = first + i;
                String key = cc.cls + "|" + cc.measure + "|" + b.bucket;
                s.row(row).text(0, b.bucket, "label");
                for (int sc = 0; sc < 3; sc++) {
                    s.formula(1 + sc, bucketK.get(key)[sc], c(b.k[sc]), "money");
                }
                s.formula(4, bucketS.get(key), c(b.net), "money");
                for (int j = 0; j < nb; j++) {
                    if (i == j) {
                        s.number(g0 + j, 0, "rate");
                    } else {
                        s.formula(g0 + j, gammaFormula(cc.cls, b.bucket, cc.buckets.get(j).bucket), c(cc.gamma[i][j]), "rate");
                    }
                }
                String gRow = "$" + col(g0) + "$" + row + ":$" + col(g0 + nb - 1) + "$" + row;
                for (int sc = 0; sc < 3; sc++) {
                    String k = col(1 + sc) + row;
                    s.formula(pt + sc, k + "^2+$E" + row + "*SUMPRODUCT(" + scen(sc, gRow) + "," + sRow + ")", null, "money");
                }
                for (int sc = 0; sc < 3; sc++) {
                    String k = col(1 + sc) + row;
                    s.formula(cs + sc, "MAX(MIN($E" + row + "," + k + "),-" + k + ")", null, "money");
                }
                for (int sc = 0; sc < 3; sc++) {
                    String k = col(1 + sc) + row;
                    String capRow = "$" + col(g0) + "$" + (top + 3 + sc) + ":$" + col(g0 + nb - 1) + "$" + (top + 3 + sc);
                    s.formula(ct + sc, k + "^2+" + col(cs + sc) + row + "*SUMPRODUCT(" + scen(sc, gRow) + "," + capRow + ")", null, "money");
                }
            }
            int totals = first + nb;
            int last = totals - 1;
            s.row(totals).text(0, "Radicand", "label");
            for (int sc = 0; sc < 3; sc++) {
                s.formula(g0 + nb + 9 + sc, "SUM(" + col(pt + sc) + first + ":" + col(pt + sc) + last + ")", null, "money");
            }
            s.row(totals + 1).text(0, "Alternative", "label");
            for (int sc = 0; sc < 3; sc++) {
                s.formulaText(g0 + nb + 9 + sc, "IF(" + col(g0 + nb + 9 + sc) + totals + "<0,\"yes\",\"no\")", cached ? (cc.alternative[sc] ? "yes" : "no") : null, "text");
            }
            s.row(totals + 2).text(0, "Radicand capped", "label");
            for (int sc = 0; sc < 3; sc++) {
                s.formula(g0 + nb + 9 + sc, "SUM(" + col(ct + sc) + first + ":" + col(ct + sc) + last + ")", null, "money");
            }
            s.row(totals + 3).text(0, "Charge", "label");
            for (int sc = 0; sc < 3; sc++) {
                String cl = col(g0 + nb + 9 + sc);
                s.formula(g0 + nb + 9 + sc, "SQRT(MAX(0,IF(" + cl + totals + "<0," + cl + (totals + 2) + "," + cl + totals + ")))", c(cc.charge[sc]), "money.bold");
            }
        }
    }

    // ---- Curvature -----------------------------------------------------------------------------------------------------------------

    private record Part(int curv, int position, boolean reval, boolean gamma) {}

    private final List<Part> parts = new ArrayList<>();
    private int partFirst;
    private int factorFirst;
    private final Map<Integer, Integer> factorRow = new HashMap<>();
    private record CurvBlock(Sbm.ClassCharge cc, Sbm.Bucket b, int top) {}
    private final List<CurvBlock> curvBlocks = new ArrayList<>();
    private record CurvClass(Sbm.ClassCharge cc, int top) {}
    private final List<CurvClass> curvClasses = new ArrayList<>();
    private final Map<String, Integer> curvBucketSummary = new HashMap<>();
    private final Map<Integer, Boolean> nonReporting = new HashMap<>();

    private void planCurvature() {
        // which positions delivered which curvature inputs (as the engine read them)
        Map<String, boolean[]> flags = new HashMap<>();
        Map<Integer, java.util.Set<String>> fx = new HashMap<>();
        List<FrtbInput.Element> els = r.input().elements();
        for (int i = 0; i < els.size(); i++) {
            String key = m.elementCurv[i];
            String kind = kind(els.get(i).measure());
            if (key == null || kind.equals("DELTA")) {
                continue;
            }
            boolean[] f = flags.computeIfAbsent(key + "#" + els.get(i).position(), x -> new boolean[3]);
            f[kind.equals("UP") ? 0 : kind.equals("DOWN") ? 1 : 2] = true;
            if (key.startsWith("FX|") && !kind.equals("GAMMA")) {
                fx.computeIfAbsent(els.get(i).position(), x -> new java.util.HashSet<>()).add(key);
            }
        }
        fx.forEach((pos, set) -> nonReporting.put(pos, set.size() >= 2));
        int row = 4;
        partFirst = row;
        for (int k = 0; k < m.curvs.size(); k++) {
            SbmModel.Curv cv = m.curvs.get(k);
            for (Integer pos : cv.parts.keySet()) {
                boolean[] f = flags.getOrDefault(cv.key + "#" + pos, new boolean[3]);
                parts.add(new Part(k, pos, f[0] && f[1], f[2]));
            }
        }
        factorFirst = partFirst + Math.max(1, parts.size()) + 3;
        row = factorFirst;
        for (int k = 0; k < m.curvs.size(); k++) {
            factorRow.put(k, row++);
        }
        row += 2;
        for (Sbm.ClassCharge cc : r.sbm().charges) {
            if (!cc.measure.equals(SbmModel.CURVATURE)) {
                continue;
            }
            for (Sbm.Bucket b : cc.buckets) {
                curvBlocks.add(new CurvBlock(cc, b, row));
                int n = b.members.length;
                int sum = row + 4 + n;
                curvBucketSummary.put(cc.cls + "|" + b.bucket, sum);
                row = sum + 8;
            }
        }
        for (Sbm.ClassCharge cc : r.sbm().charges) {
            if (!cc.measure.equals(SbmModel.CURVATURE)) {
                continue;
            }
            int nb = cc.buckets.size();
            curvClasses.add(new CurvClass(cc, row));
            int first = row + 5;
            int charge = first + nb;
            int g0 = 7;
            classCharge.put(cc.cls + "|" + cc.measure, new String[] {abs("Curvature", g0 + nb, charge), abs("Curvature", g0 + nb + 1, charge),
                    abs("Curvature", g0 + nb + 2, charge)});
            row = charge + 3;
        }
    }

    private String partCol(int c) {
        return "$" + col(c) + "$" + partFirst + ":$" + col(c) + "$" + (partFirst + Math.max(1, parts.size()) - 1);
    }

    private void curvature(SheetStream s) {
        s.width(0, 30).width(1, 26).widths(2, 20, 15);
        s.row(1).text(0, "Curvature: CVR± per position and factor (MAR21.5(2)), K_b± and the side per bucket (MAR21.5(3)), the class across buckets (MAR21.5(4))",
                "title");
        s.row(3).text(0, "Position", "head").text(1, "Curvature factor", "head").text(2, "Class", "head").text(3, "UP", "head").text(4, "DOWN", "head")
                .text(5, "GAMMA", "head").text(6, "Delta s", "head").text(7, "Revalued", "head").text(8, "Gamma", "head").text(9, "Specified", "head")
                .text(10, "Not reporting ccy", "head").text(11, "Shock RW", "head").text(12, "h", "head").text(13, "Use gamma", "head").text(14, "Divisor", "head")
                .text(15, "CVR+", "head").text(16, "CVR−", "head");
        String swCurv = sw("curvature-source");
        String swFx = sw("fx-curvature-divisor");
        for (int i = 0; i < parts.size(); i++) {
            Part pt = parts.get(i);
            SbmModel.Curv cv = m.curvs.get(pt.curv());
            int row = partFirst + i;
            String crit = "," + dcol(D_IDX) + "," + pt.position() + "," + dcol(D_CURV) + ",$B" + row + "," + dcol(D_KIND) + ",";
            s.row(row).text(0, r.input().positions().get(pt.position()).id(), "text").text(1, cv.key, "text").text(2, cv.cls, "text");
            s.formula(3, "SUMIFS(" + dcol(D_VAL) + crit + "\"UP\")", null, "money");
            s.formula(4, "SUMIFS(" + dcol(D_VAL) + crit + "\"DOWN\")", null, "money");
            s.formula(5, "SUMIFS(" + dcol(D_VAL) + crit + "\"GAMMA\")", null, "money");
            s.formula(6, "SUMIFS(" + dcol(D_S) + crit + "\"DELTA\")", null, "money");
            s.number(7, pt.reval() ? 1 : 0, "int").number(8, pt.gamma() ? 1 : 0, "int");
            boolean spec = switch (cv.cls) {
                case "GIRR" -> p.girrUseDivisor && (p.girrSpecified.contains(cv.name) || cv.name.equals(m.reporting));
                case "FX" -> p.fxSpecifiedPairs.contains(cv.name + m.reporting) || p.fxSpecifiedPairs.contains(m.reporting + cv.name);
                default -> false;
            };
            s.number(9, spec ? 1 : 0, "int").number(10, nonReporting.getOrDefault(pt.position(), false) ? 1 : 0, "int");
            String shock = switch (cv.cls) {
                case "GIRR" -> cf("GirrCurvShock") + "/IF(AND(" + sw("girr-curvature-shock") + "=\"divided\",J" + row + "=1)," + cf("GirrDivisor") + ",1)";
                case "CSR_NS" -> cf("CSR_NS.delta.risk-weights." + cv.bucket);
                case "EQ" -> cf("EQ.delta.spot-risk-weights." + cv.bucket);
                case "COMM" -> cf("COMM.delta.risk-weights." + cv.bucket);
                default -> cf("FX.delta.risk-weight") + "/IF(J" + row + "=1," + cf("FxDivisor") + ",1)";
            };
            s.formula(11, shock, c(cv.rw), "rate");
            s.formula(12, "L" + row + "*" + (cv.cls.equals("GIRR") || cv.cls.equals("CSR_NS") ? "10000" : "100"), c(cv.h), "rate");
            s.formula(13, "IF(AND(I" + row + "=1,OR(H" + row + "=0," + swCurv + "=\"gamma\")),1,0)", null, "int");
            s.formula(14, cv.cls.equals("FX") ? "IF(" + swFx + "=\"all\"," + cf("FxCurvDivisor") + ",IF(AND(" + swFx + "=\"non-reporting-options\",K" + row + "=1),"
                    + cf("FxCurvDivisor") + ",1))" : "1", null, "rate");
            double[] cvr = cv.parts.get(pt.position());
            s.formula(15, "IF(N" + row + "=1,-0.5*F" + row + "*M" + row + "^2,-(D" + row + "-L" + row + "*G" + row + "))/O" + row, c(cvr[0]), "money");
            s.formula(16, "IF(N" + row + "=1,-0.5*F" + row + "*M" + row + "^2,-(E" + row + "+L" + row + "*G" + row + "))/O" + row, c(cvr[1]), "money");
        }
        s.row(factorFirst - 1).text(0, "Curvature factor", "head").text(1, "Name", "head").text(2, "Class", "head").text(3, "Bucket", "head")
                .text(4, "CVR+", "head").text(5, "CVR−", "head").text(6, "Estimated from gamma", "head");
        for (int k = 0; k < m.curvs.size(); k++) {
            SbmModel.Curv cv = m.curvs.get(k);
            int row = factorRow.get(k);
            s.row(row).text(0, cv.key, "text").text(1, cv.name, "text").text(2, cv.cls, "text").text(3, cv.bucket, "text");
            s.formula(4, "SUMIFS(" + partCol(15) + "," + partCol(1) + ",$A" + row + ")", c(cv.cvrUp), "money");
            s.formula(5, "SUMIFS(" + partCol(16) + "," + partCol(1) + ",$A" + row + ")", c(cv.cvrDown), "money");
            s.text(6, cv.estimated ? "estimate (an analysis figure, MAR21.17)" : "", "note");
        }
        for (CurvBlock cb : curvBlocks) {
            curvBucket(s, cb);
        }
        for (CurvClass cc : curvClasses) {
            curvClass(s, cc);
        }
    }

    private String curvRho(SbmModel.Curv a, int fa, SbmModel.Curv b, int fb) {
        String na = "$B$" + factorRow.get(fa);
        String nb = "$B$" + factorRow.get(fb);
        int bucket = bucketNo(a.bucket);
        String other = switch (a.cls) {
            case "CSR_NS" -> p.csrIndex.contains(bucket) ? cf("CsrNameIndex") : cf("CsrName");
            case "EQ" -> cfg.containsKey("EqName." + bucket) ? cf("EqName." + bucket) : "0";
            case "COMM" -> cfg.containsKey("CmCty." + bucket) ? cf("CmCty." + bucket) : "0";
            default -> "1";
        };
        return "IF(" + na + "=" + nb + ",1," + other + ")^2";
    }

    private void curvBucket(SheetStream s, CurvBlock cb) {
        Sbm.Bucket b = cb.b();
        int n = b.members.length;
        int top = cb.top();
        int r0 = 3;                                                // ρ² from column D
        int tc = r0 + n;                                           // six term columns: up low..high, down low..high
        s.row(top).text(0, cb.cc().cls + " curvature · bucket " + b.bucket + (b.absSum ? " (absolute sum)" : ""), "label");
        s.row(top + 1).text(0, "Factor", "head").text(1, "CVR+", "head").text(2, "CVR−", "head");
        for (int j = 0; j < n; j++) {
            s.text(r0 + j, "ρ² with " + (j + 1), "head");
        }
        for (int sc = 0; sc < 3; sc++) {
            s.text(tc + sc, "+ term " + Sbm.SCENARIOS[sc], "head").text(tc + 3 + sc, "− term " + Sbm.SCENARIOS[sc], "head");
        }
        int first = top + 4;
        s.row(top + 2).text(0, "CVR+ across", "note");
        for (int j = 0; j < n; j++) {
            s.formula(r0 + j, "$B$" + (first + j), null, "money");
        }
        s.row(top + 3).text(0, "CVR− across", "note");
        for (int j = 0; j < n; j++) {
            s.formula(r0 + j, "$C$" + (first + j), null, "money");
        }
        String upRow = "$" + col(r0) + "$" + (top + 2) + ":$" + col(r0 + n - 1) + "$" + (top + 2);
        String dnRow = "$" + col(r0) + "$" + (top + 3) + ":$" + col(r0 + n - 1) + "$" + (top + 3);
        for (int i = 0; i < n; i++) {
            int fi = b.members[i];
            SbmModel.Curv a = m.curvs.get(fi);
            int row = first + i;
            s.row(row).text(0, (i + 1) + ". " + a.key, "text");
            s.formula(1, "Curvature!$E$" + factorRow.get(fi), c(a.cvrUp), "money");
            s.formula(2, "Curvature!$F$" + factorRow.get(fi), c(a.cvrDown), "money");
            if (!b.absSum) {
                for (int j = 0; j < n; j++) {
                    if (i == j) {
                        s.number(r0 + j, 0, "rate");
                    } else {
                        s.formula(r0 + j, curvRho(a, fi, m.curvs.get(b.members[j]), b.members[j]), null, "rate");
                    }
                }
            }
            String rr = "$" + col(r0) + "$" + row + ":$" + col(r0 + n - 1) + "$" + row;
            for (int sc = 0; sc < 3; sc++) {
                for (int side = 0; side < 2; side++) {
                    String x = "$" + (side == 0 ? "B" : "C") + row;
                    String across = side == 0 ? upRow : dnRow;
                    String f = b.absSum ? "MAX(" + x + ",0)" : "MAX(" + x + ",0)^2+" + x + "*SUMPRODUCT(" + scen(sc, rr) + "," + across + ",1-(" + x + "<0)*(" + across
                            + "<0))";
                    s.formula(tc + side * 3 + sc, f, null, "money");
                }
            }
        }
        int sum = curvBucketSummary.get(cb.cc().cls + "|" + b.bucket);
        int last = sum - 1;
        String[] labels = {"K+", "K−", "Σ CVR+", "Σ CVR−", "Side", "K_b", "S_b"};
        for (int li = 0; li < labels.length; li++) {
            s.row(sum + li).text(0, labels[li], "label");
            for (int sc = 0; sc < 3; sc++) {
                int cl = tc + sc;
                String ku = col(tc + sc) + sum;
                String kd = col(tc + sc) + (sum + 1);
                String su = col(tc + sc) + (sum + 2);
                String sd = col(tc + sc) + (sum + 3);
                String side = col(tc + sc) + (sum + 4);
                switch (li) {
                    case 0, 1 -> {
                        String tcol = col(tc + li * 3 + sc);
                        String total = "SUM(" + tcol + first + ":" + tcol + last + ")";
                        s.formula(cl, b.absSum ? total : "SQRT(MAX(0," + total + "))", c(li == 0 ? b.kUp[sc] : b.kDown[sc]), "money");
                    }
                    case 2 -> s.formula(cl, "SUM($B" + first + ":$B" + last + ")", c(b.sumUp), "money");
                    case 3 -> s.formula(cl, "SUM($C" + first + ":$C" + last + ")", c(b.sumDown), "money");
                    case 4 -> s.formulaText(cl, "IF(OR(" + ku + ">" + kd + ",AND(" + ku + "=" + kd + "," + su + ">" + sd + ")),\"up\",\"down\")",
                            cached ? b.side[sc] : null, "text");
                    case 5 -> s.formula(cl, "MAX(" + ku + "," + kd + ")", c(b.k[sc]), "money.bold");
                    default -> s.formula(cl, "IF(" + side + "=\"up\"," + su + "," + sd + ")", c(b.s[sc]), "money.bold");
                }
            }
        }
    }

    private String curvCell(String cls, String bucket, int line, int sc) {
        CurvBlock cb = curvBlocks.stream().filter(x -> x.cc().cls.equals(cls) && x.b().bucket.equals(bucket)).findFirst().orElseThrow();
        int tc = 3 + cb.b().members.length;
        return "Curvature!$" + col(tc + sc) + "$" + (curvBucketSummary.get(cls + "|" + bucket) + line);
    }

    private void curvClass(SheetStream s, CurvClass cl) {
        Sbm.ClassCharge cc = cl.cc();
        int nb = cc.buckets.size();
        int top = cl.top();
        int g0 = 7;
        s.row(top).text(0, cc.cls + " curvature across buckets: √max(0, Σ K_b² + Σ γ²_bc · S_b · S_c · ψ)", "label");
        s.row(top + 1).text(0, "Bucket", "head").text(1, "K low", "head").text(2, "K medium", "head").text(3, "K high", "head").text(4, "S low", "head")
                .text(5, "S medium", "head").text(6, "S high", "head");
        for (int j = 0; j < nb; j++) {
            s.text(g0 + j, "γ² with " + cc.buckets.get(j).bucket, "head");
        }
        for (int sc = 0; sc < 3; sc++) {
            s.text(g0 + nb + sc, "Term " + Sbm.SCENARIOS[sc], "head");
        }
        int first = top + 5;
        for (int sc = 0; sc < 3; sc++) {
            s.row(top + 2 + sc).text(0, "S " + Sbm.SCENARIOS[sc] + " across", "note");
            for (int j = 0; j < nb; j++) {
                s.formula(g0 + j, "$" + col(4 + sc) + "$" + (first + j), null, "money");
            }
        }
        for (int i = 0; i < nb; i++) {
            Sbm.Bucket b = cc.buckets.get(i);
            int row = first + i;
            s.row(row).text(0, b.bucket, "label");
            for (int sc = 0; sc < 3; sc++) {
                s.formula(1 + sc, curvCell(cc.cls, b.bucket, 5, sc), c(b.k[sc]), "money");
            }
            for (int sc = 0; sc < 3; sc++) {
                s.formula(4 + sc, curvCell(cc.cls, b.bucket, 6, sc), c(b.s[sc]), "money");
            }
            for (int j = 0; j < nb; j++) {
                if (i == j) {
                    s.number(g0 + j, 0, "rate");
                } else {
                    s.formula(g0 + j, "(" + gammaFormula(cc.cls, b.bucket, cc.buckets.get(j).bucket) + ")^2", c(cc.gamma[i][j]), "rate");
                }
            }
            String gRow = "$" + col(g0) + "$" + row + ":$" + col(g0 + nb - 1) + "$" + row;
            for (int sc = 0; sc < 3; sc++) {
                String sRow = "$" + col(g0) + "$" + (top + 2 + sc) + ":$" + col(g0 + nb - 1) + "$" + (top + 2 + sc);
                String x = "$" + col(4 + sc) + row;
                s.formula(g0 + nb + sc, col(1 + sc) + row + "^2+" + x + "*SUMPRODUCT(" + scen(sc, gRow) + "," + sRow + ",1-(" + x + "<0)*(" + sRow + "<0))", null,
                        "money");
            }
        }
        int charge = first + nb;
        s.row(charge).text(0, "Charge", "label");
        for (int sc = 0; sc < 3; sc++) {
            String tcol = col(g0 + nb + sc);
            s.formula(g0 + nb + sc, "SQRT(MAX(0,SUM(" + tcol + first + ":" + tcol + (charge - 1) + ")))", c(cc.charge[sc]), "money.bold");
        }
    }

    // ---- DRC -------------------------------------------------------------------------------------------------------------------------

    private int drcLineFirst;
    private int drcObligorFirst;
    private int drcBucketFirst;

    private void planDrc() {
        Drc.Result d = r.drc();
        if (!d.delivered()) {
            return;
        }
        drcLineFirst = 4;
        drcObligorFirst = drcLineFirst + Math.max(1, d.lines().size()) + 3;
        drcBucketFirst = drcObligorFirst + Math.max(1, d.obligors().size()) + 3;
        drcTotal = "DRC!$H$" + (drcBucketFirst + d.buckets().size());
    }

    private void drc(SheetStream s) {
        Drc.Result d = r.drc();
        s.width(0, 26).widths(1, 40, 14);
        s.row(1).text(0, "DRC: jump-to-default, maturity scaling, netting by seniority, the hedge benefit ratio and the bucket charges (MAR22)", "title");
        if (!d.delivered()) {
            s.row(3).text(0, "DRC not computed: no default-risk inputs", "note");
            return;
        }
        s.row(3).text(0, "Position", "head").text(1, "Obligor", "head").text(2, "Bucket", "head").text(3, "Seniority", "head").text(4, "Quality", "head")
                .text(5, "Notional", "head").text(6, "Market value", "head").text(7, "Maturity (y)", "head").text(8, "Cash equity", "head")
                .text(9, "LGD override", "head").text(10, "LGD", "head").text(11, "P&L", "head").text(12, "JTD", "head").text(13, "Gross JTD", "head")
                .text(14, "Scale", "head").text(15, "Scaled JTD", "head");
        String lgdKeys = "Config!$A$12:$A$" + (11 + cfgRows.size());
        String lgdVals = "Config!$B$12:$B$" + (11 + cfgRows.size());
        for (int i = 0; i < d.lines().size(); i++) {
            Drc.Line l = d.lines().get(i);
            FrtbInput.DrcPosition in = l.in();
            int row = drcLineFirst + i;
            s.row(row).text(0, in.id(), "input.text").text(1, in.obligor(), "input.text").text(2, in.bucket(), "input.text").text(3, in.seniority(), "input.text")
                    .text(4, in.creditQuality(), "input.text").number(5, in.notional(), "input.money").number(6, in.marketValue(), "input.money")
                    .number(7, in.maturityYears(), "input.dec").number(8, in.cashEquity() ? 1 : 0, "input.int");
            if (Double.isFinite(in.lgd())) {
                s.number(9, in.lgd(), "input.rate");
            }
            s.formula(10, "IF(ISNUMBER(J" + row + "),J" + row + ",INDEX(" + lgdVals + ",MATCH(\"LGD.\"&D" + row + "," + lgdKeys + ",0)))", c(l.lgd()), "rate");
            s.formula(11, "G" + row + "-F" + row, null, "money");
            s.formula(12, "K" + row + "*F" + row + "+L" + row, null, "money");
            s.formula(13, "IF(F" + row + ">=0,MAX(M" + row + ",0),MIN(M" + row + ",0))", c(l.gross()), "money");
            s.formula(14, "IF(I" + row + "=1," + cf("DrcCashEquity") + ",MIN(MAX(H" + row + "," + cf("DrcFloor") + ")," + cf("DrcCap") + "))", c(l.scale()), "rate");
            s.formula(15, "N" + row + "*O" + row, c(l.scaled()), "money");
        }
        int lastLine = drcLineFirst + Math.max(1, d.lines().size()) - 1;
        String ob = "$B$" + drcLineFirst + ":$B$" + lastLine;
        String sen = "$D$" + drcLineFirst + ":$D$" + lastLine;
        String scaled = "$P$" + drcLineFirst + ":$P$" + lastLine;
        // obligors: per seniority (most senior first) the longs and shorts, then the netting chain
        List<String> levels = p.drcSeniority;
        int nl = levels.size();
        int hdr = drcObligorFirst - 1;
        s.row(hdr).text(0, "Obligor", "head").text(1, "Bucket", "head").text(2, "Quality", "head").text(3, "RW", "head");
        for (int j = 0; j < nl; j++) {
            s.text(4 + j * 4, "Long " + levels.get(j), "head").text(5 + j * 4, "Short " + levels.get(j), "head").text(6 + j * 4, "Carried long", "head")
                    .text(7 + j * 4, "Short left", "head");
        }
        int netL = 4 + nl * 4;
        s.text(netL, "Net long", "head").text(netL + 1, "Net short", "head");
        for (int i = 0; i < d.obligors().size(); i++) {
            Drc.Obligor o = d.obligors().get(i);
            int row = drcObligorFirst + i;
            s.row(row).text(0, o.obligor(), "text").text(1, o.bucket(), "text").text(2, o.quality(), "text");
            s.formula(3, "IF(AND(B" + row + "=\"SOVEREIGN\"," + cf("DrcSovZero") + "=1),0,INDEX(" + lgdVals + ",MATCH(\"DrcRw.\"&C" + row + "," + lgdKeys + ",0)))",
                    c(o.rw()), "rate");
            String prevCarry = "0";
            for (int j = 0; j < nl; j++) {
                String lv = q(levels.get(j));
                String lc = col(4 + j * 4) + row;
                String sc = col(5 + j * 4) + row;
                s.formula(4 + j * 4, "SUMIFS(" + scaled + "," + ob + ",$A" + row + "," + sen + "," + lv + "," + scaled + ",\">0\")", null, "money");
                s.formula(5 + j * 4, "-SUMIFS(" + scaled + "," + ob + ",$A" + row + "," + sen + "," + lv + "," + scaled + ",\"<0\")", null, "money");
                String avail = "(" + prevCarry + "+" + lc + ")";
                s.formula(6 + j * 4, avail + "-MIN(" + avail + "," + sc + ")", null, "money");
                s.formula(7 + j * 4, sc + "-MIN(" + avail + "," + sc + ")", null, "money");
                prevCarry = col(6 + j * 4) + row;
            }
            s.formula(netL, prevCarry, c(o.netLong()), "money");
            List<String> left = new ArrayList<>();
            for (int j = 0; j < nl; j++) {
                left.add(col(7 + j * 4) + row);
            }
            s.formula(netL + 1, "-(" + String.join("+", left) + ")", c(o.netShort()), "money");
        }
        int lastOb = drcObligorFirst + Math.max(1, d.obligors().size()) - 1;
        String bcol = "$B$" + drcObligorFirst + ":$B$" + lastOb;
        String rw = "$D$" + drcObligorFirst + ":$D$" + lastOb;
        String nlong = "$" + col(netL) + "$" + drcObligorFirst + ":$" + col(netL) + "$" + lastOb;
        String nshort = "$" + col(netL + 1) + "$" + drcObligorFirst + ":$" + col(netL + 1) + "$" + lastOb;
        s.row(drcBucketFirst - 1).text(0, "Bucket", "head").text(1, "Σ net long", "head").text(2, "Σ |net short|", "head").text(3, "HBR", "head")
                .text(4, "Σ RW × long", "head").text(5, "Σ RW × |short|", "head").text(6, "", "head").text(7, "DRC_b", "head");
        for (int i = 0; i < d.buckets().size(); i++) {
            Drc.Bucket b = d.buckets().get(i);
            int row = drcBucketFirst + i;
            String bq = "(" + bcol + "=$A" + row + ")";
            s.row(row).text(0, b.bucket(), "label");
            s.formula(1, "SUMPRODUCT(" + bq + "*" + nlong + ")", c(b.sumLong()), "money");
            s.formula(2, "SUMPRODUCT(" + bq + "*ABS(" + nshort + "))", c(b.sumShort()), "money");
            s.formula(3, "IF(B" + row + "+C" + row + "=0,0,B" + row + "/(B" + row + "+C" + row + "))", c(b.hbr()), "rate");
            s.formula(4, "SUMPRODUCT(" + bq + "*" + rw + "*" + nlong + ")", c(b.weightedLong()), "money");
            s.formula(5, "SUMPRODUCT(" + bq + "*" + rw + "*ABS(" + nshort + "))", c(b.weightedShort()), "money");
            s.formula(7, "MAX(E" + row + "-D" + row + "*F" + row + ",0)", c(b.charge()), "money.bold");
        }
        int tot = drcBucketFirst + d.buckets().size();
        s.row(tot).text(0, "DRC", "label").formula(7, d.buckets().isEmpty() ? "0" : "SUM(H" + drcBucketFirst + ":H" + (tot - 1) + ")", c(d.total()), "total");
    }

    // ---- RRAO ------------------------------------------------------------------------------------------------------------------------

    private void planRrao() {
        if (r.rrao().delivered()) {
            rraoTotal = "RRAO!$H$" + (4 + r.rrao().lines().size());
        }
    }

    private void rrao(SheetStream s) {
        Rrao.Result rr = r.rrao();
        s.width(0, 34).width(1, 22).widths(2, 7, 16);
        s.row(1).text(0, "RRAO: gross notional × 1.0% (exotic underlying) or × 0.1% (other residual risks), MAR23", "title");
        if (!rr.delivered()) {
            s.row(3).text(0, "RRAO not computed: no residual-risk inputs", "note");
            return;
        }
        s.row(3).text(0, "Instrument", "head").text(1, "Product type", "head").text(2, "Class", "head").text(3, "Gross notional", "head")
                .text(4, "Back-to-back", "head").text(5, "Listed or cleared", "head").text(6, "Weight", "head").text(7, "Add-on", "head");
        for (int i = 0; i < rr.lines().size(); i++) {
            Rrao.Line l = rr.lines().get(i);
            int row = 4 + i;
            s.row(row).text(0, l.in().id(), "input.text").text(1, l.in().productType() == null ? "" : l.in().productType(), "input.text")
                    .text(2, l.rraoClass(), "input.text").number(3, l.in().grossNotional(), "input.money").number(4, l.in().backToBack() ? 1 : 0, "input.int")
                    .number(5, l.in().listedOrCleared() ? 1 : 0, "input.int");
            s.formula(6, "IF(E" + row + "=1,0,IF(C" + row + "=\"EXOTIC\"," + cf("RraoExotic") + ",IF(C" + row + "=\"OTHER\",IF(F" + row + "=1,0," + cf("RraoOther")
                    + "),0)))", c(l.weight()), "rate");
            s.formula(7, "G" + row + "*ABS(D" + row + ")", c(l.addOn()), "money");
        }
        int tot = 4 + rr.lines().size();
        s.row(tot).text(0, "RRAO", "label").formula(7, rr.lines().isEmpty() ? "0" : "SUM(H4:H" + (tot - 1) + ")", c(rr.total()), "total");
    }

    // ---- Explain, Provenance -----------------------------------------------------------------------------------------------------

    private void explain(SheetStream s) {
        s.width(0, 44).width(1, 150);
        s.row(1).text(0, com.ash.drishti.common.Branding.current().productOr("") + " · FRTB-SA Playground · how each figure is worked out", "title");
        s.blank(1, "title");
        int row = 3;
        for (String[] step : t.explain()) {
            s.row(row++).text(0, step[0], "label.wrap").text(1, step[1], "wrap");
        }
    }

    private void provenance(SheetStream s) {
        s.width(0, 38).width(1, 120);
        s.row(1).text(0, "Provenance", "title");
        s.blank(1, "title");
        Map<String, Object> prov = new LinkedHashMap<>(run.provenance());
        prov.put("Scope", run.scope() + " (" + run.canonical() + ")");
        prov.put("Reporting currency", run.reporting());
        prov.put("Inputs digest (SHA-256)", run.inputsDigest());
        prov.put("Recipe", recipe == null ? "not saved" : recipe);
        prov.put("Exported by", user + " at " + now);
        int row = 3;
        for (Map.Entry<String, Object> en : prov.entrySet()) {
            Object v = en.getValue();
            s.row(row++).text(0, en.getKey(), "label");
            if (v instanceof List<?> list) {
                s.text(1, list.isEmpty() ? "none" : String.valueOf(list.size()), "text");
                for (Object o : list) {
                    s.row(row++).text(1, String.valueOf(o), "text");
                }
            } else {
                s.text(1, v == null ? "" : String.valueOf(v), "text");
            }
        }
    }
}
