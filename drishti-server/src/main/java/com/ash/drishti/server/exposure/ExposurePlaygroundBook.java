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
package com.ash.drishti.server.exposure;

import com.ash.drishti.common.Branding;
import com.ash.drishti.compute.ccr.ExposureGrid;
import com.ash.drishti.server.dist.XlsxWriter;
import com.ash.drishti.server.playground.ExportSizing;
import com.ash.drishti.server.playground.PlaygroundTemplate;
import com.ash.drishti.server.playground.SheetStream;
import com.ash.drishti.server.playground.WorkbookStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Exposure Playground's workbook (docs/architecture/COUNTERPARTY_ENGINES.md section 8.2, docs/guides/EXPOSURE_GUIDE.md section 29):
 * Main, Config, Data, Netting, Collateral, Exposure, Stats, Contributions, Explain and Provenance. The simulated values are the only data
 * (Data, one row per path, one column per grid date and trade, the grid dates side by side); every step after them is a formula of the
 * common set: V a {@code SUM} across a date's trade columns, the CSA's call and its rounding and minimum transfer amount, E, N and
 * max(V, 0), EE and ENE as {@code AVERAGE}, PFE as {@code SMALL} at the rank {@code ROUNDUP(P q - 1E-9, 0)} (the engine's kth rule,
 * exactly; the interpolated rule as the weighted pair of neighbouring ranks), EEE as a running {@code MAX}, EPE and EEPE as
 * {@code SUMPRODUCT} with the weights {@code MAX(0, MIN(t, T) - t_prev)}, the peaks with {@code INDEX}/{@code MATCH} (the earliest on a
 * tie), and the Euler contributions at the date chosen. Three choices are cells of Main the formulas read (the rank rule, the collateral
 * model, the contribution date) and the confidences too, so a validator who changes one sees the result move: the engine checks turn red
 * by design, the add-up checks stay green.
 */
final class ExposurePlaygroundBook {

    static final List<String> STYLES = List.of("default", "title", "band", "head", "text", "note", "input.money", "input.text", "input.int", "input.dec",
            "money", "money.bold", "int", "rate", "check", "label", "total", "wrap", "label.wrap");
    static final String PASS = "✓";
    static final String FAIL = "✗";
    static final String ALL_PASS = "ALL CHECKS PASS";
    static final String FAILS = "A CHECK FAILS";

    private final ExposurePlaygroundModel m;
    private final PlaygroundTemplate t;
    private final boolean cached;
    private final String user;
    private final String now;

    // ---- the plan ----
    private final int g;
    private final int gp;
    private final int r;
    private final int n;
    private final int nq;
    /** First path row of Data, Netting, Collateral and Exposure. */
    static final int PR = 4;
    /** First path row of Contributions. */
    static final int CR = 9;
    /** Stats: the q parameter rows, the header and the first date row. */
    static final int SQ = 3;
    static final int SH = 9;
    static final int SR = 10;
    /** Config rows. */
    static final int C_CSA = 4;
    static final int C_RUN = 22;
    static final int C_TRADES = 30;
    private final int cGrid;
    // Main rows
    private final int mConf = 6;
    private final int mRule;
    private final int mModel;
    private final int mDate;
    private final int mHead;
    private final int mFirst;
    private int mLast;
    private int pHead;
    private int pFirst;

    ExposurePlaygroundBook(ExposurePlaygroundModel m, PlaygroundTemplate t, boolean cached, String user, String now) {
        this.m = m;
        this.t = t;
        this.cached = cached;
        this.user = user;
        this.now = now;
        this.g = m.grid.size();
        this.gp = m.primaryCount();
        this.r = m.columns.size();
        this.n = m.n;
        this.nq = m.qs.length;
        this.cGrid = C_TRADES + r + 2;
        this.mRule = mConf + nq;
        this.mModel = mRule + 1;
        this.mDate = mModel + 1;
        this.mHead = mDate + 2;
        this.mFirst = mHead + 1;
    }

    // ---- the sizes, before a byte is written -------------------------------------------------------------------------------------

    /** The widest sheet's columns: Data's path column and one per grid date and trade column. */
    static int dataColumns(int grid, int cols) {
        return 1 + grid * cols;
    }

    /** What the workbook of {@code paths} paths and {@code cols} Data columns (trades shown, Rest of set included) will be. */
    static ExportSizing.Estimate estimate(int paths, int cols, int grid, int primaries, int qs, long maxCells) {
        long values = (long) paths * grid * cols + paths + 40L + cols * 5L + grid * 6L;
        long formulas = (long) paths * grid + 6L * primaries * paths + (long) paths * (4 + cols) + (long) primaries * (10 + qs) + 60L + 3L * cols;
        long comparisons = (long) primaries * qs * paths * 2;
        int columns = Math.max(dataColumns(grid, cols), 5 + cols);
        ExportSizing.Estimate e = ExportSizing.estimate(cols, true, columns, paths + CR + 1, values, formulas, comparisons, maxCells);
        String refused = e.refused() == null ? null : e.refused().replace("drishti.var.playground.export-max-cells",
                "drishti.ccr.exposure.playground.export-max-cells");
        if (refused == null && columns > 16_384) {
            refused = String.format("%,d columns in Data (%d grid dates x %d trade columns): more than any spreadsheet holds (16,384); choose fewer "
                    + "trades, the rest go to Rest of set", columns, grid, cols);
        }
        return new ExportSizing.Estimate(e.items(), e.orientation(), e.columns(), e.rows(), e.cells(), e.formulas(), e.bytes(), e.recalcSeconds(), e.apps(),
                refused);
    }

    /** The formula behind each column the screen shows, as the workbook writes it (the screen's formula bar). */
    static Map<String, String> formulas() {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("v", "Netting: =SUM(Data!<the date's trade columns>)");
        f.put("l", "Netting, the look-back column: =SUM(Data!<the look-back date's trade columns>)");
        f.put("target", "Collateral: =MAX(L-ThThem,0)-MAX(-L-ThUs,0)+IA");
        f.put("rounded", "Collateral: =IF(Rounding>0,C0+IF(target-C0>0,ROUNDUP((target-C0)/Rounding,0),ROUNDDOWN((target-C0)/Rounding,0))*Rounding,target)");
        f.put("c", "Collateral: =IF(OR(CsaPresent<>\"yes\",CollateralModel=\"none\"),0,IF(Margined<>\"yes\",IA,IF(CollateralModel=\"current\",C0,"
                + "IF(rounded>0,MAX(rounded-MtaThem,0),rounded))))");
        f.put("e", "Exposure: =MAX(V-C-IF(CollateralModel=\"none\",0,IM),0)");
        f.put("n", "Exposure: =MIN(V-C,0)");
        f.put("uncollateralised", "Exposure: =MAX(V,0)");
        f.put("ee", "Stats: =AVERAGE(Exposure!<E at the date, every path>)");
        f.put("ene", "Stats: =AVERAGE(Exposure!<N at the date, every path>)");
        f.put("pfe", "Stats: =IF(RankRule=\"interpolated\",(1-w)*SMALL(E,lo)+w*SMALL(E,hi),SMALL(E,MAX(1,MIN(P,ROUNDUP(P*q-1E-9,0)))))");
        f.put("eee", "Stats: =MAX(<the previous date's EEE, or CE>,EE)");
        f.put("eeUncollateralised", "Stats: =AVERAGE(Exposure!<max(V, 0) at the date>)");
        f.put("collateralEffect", "Stats: =EE-EE uncollateralised");
        f.put("weight", "Stats: =MAX(0,MIN(t,T)-t_previous)");
        f.put("ce", "Main: =IF(OR(CsaPresent<>\"yes\",CollateralModel=\"none\"),MAX(V0,0),MAX(V0-IF(Margined=\"yes\",C0,IA)-IM,0))");
        f.put("epe", "Main: =IF(T>0,SUMPRODUCT(Stats!EE,Stats!weight)/T,0)");
        f.put("eepe", "Main: =IF(T>0,SUMPRODUCT(Stats!EEE,Stats!weight)/T,0)");
        f.put("peakPfe", "Main: =MAX(Stats!PFE) and its date =INDEX(Stats!Date,MATCH(MAX(Stats!PFE),Stats!PFE,0))");
        f.put("eadImm", "Main: =Alpha*EEPE");
        f.put("eeShare", "Contributions: =AVERAGE(a) with a = MTM*IF(AND(E>0,V<>0),1-(C+IM)/V,0) on each path");
        return f;
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------------------------

    private Double c(double v) {
        return cached && Double.isFinite(v) ? v : null;
    }

    private String ct(String s) {
        return cached ? s : null;
    }

    private static String col(int c) {
        return XlsxWriter.col(c);
    }

    private static String a(String sheet, int c, int row) {
        return WorkbookStream.quote(sheet) + "!$" + col(c) + "$" + row;
    }

    private static String q(String s) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    /** Data's column of grid date k (0-based) and Data column x. */
    private int dataCol(int k, int x) {
        return 1 + k * r + x;
    }

    private String imEff() {
        return "IF(CollateralModel=\"none\",0,IM)";
    }

    private String check(String cell, String engine) {
        return "IF(ABS(" + cell + "-" + engine + ")<0.005,\"" + PASS + "\",\"" + FAIL + "\")";
    }

    private String pass(double sheet, double engine) {
        return Math.abs(sheet - engine) < 0.005 ? PASS : FAIL;
    }

    // ---- the writing -------------------------------------------------------------------------------------------------------------

    void write(OutputStream out) {
        ExposurePlaygroundModel.About ab = m.about;
        Map<String, String> v = new LinkedHashMap<>(t.brandingValues(Branding.current()));
        v.put("TITLE", "Exposure Playground · " + ab.nettingSet() + " · " + ab.businessDate());
        v.put("SUBJECT", "EE, ENE, PFE, EPE and EEPE of one netting set worked in formulas from " + n + " simulated paths");
        v.put("USER", user);
        v.put("NOW", now);
        try (WorkbookStream w = new WorkbookStream(out, t, v)) {
            main(w.sheet("Main", "1F3A5F"));
            config(w.sheet("Config", "B08A2E"));
            data(w.sheet("Data", "B08A2E"));
            netting(w.sheet("Netting", "2E5A88"));
            collateral(w.sheet("Collateral", "2E5A88"));
            exposure(w.sheet("Exposure", "2E5A88"));
            stats(w.sheet("Stats", "2E5A88"));
            contributions(w.sheet("Contributions", "2E5A88"));
            explain(w.sheet("Explain", "6B7686"));
            provenance(w.sheet("Provenance", "6B7686"));
            for (int a = 0; a < nq; a++) {
                w.name("Confidence" + (a + 1), "Main", "$B$" + (mConf + a));
            }
            w.name("RankRule", "Main", "$B$" + mRule);
            w.name("CollateralModel", "Main", "$B$" + mModel);
            w.name("ContributionDate", "Main", "$B$" + mDate);
            w.name("P", "Main", "$C$" + mFirst);
            w.name("T", "Main", "$C$" + (mFirst + 2));
            w.name("CE", "Main", "$C$" + (mFirst + 1));
            w.name("CsaPresent", "Config", "$B$" + (C_CSA + 1));
            w.name("Margined", "Config", "$B$" + (C_CSA + 2));
            w.name("ThThem", "Config", "$B$" + (C_CSA + 3));
            w.name("ThUs", "Config", "$B$" + (C_CSA + 4));
            w.name("MtaThem", "Config", "$B$" + (C_CSA + 5));
            w.name("MtaUs", "Config", "$B$" + (C_CSA + 6));
            w.name("IA", "Config", "$B$" + (C_CSA + 7));
            w.name("Rounding", "Config", "$B$" + (C_CSA + 8));
            w.name("C0", "Config", "$B$" + (C_CSA + 14));
            w.name("IM", "Config", "$B$" + (C_CSA + 15));
            w.name("Alpha", "Config", "$B$" + C_RUN);
            w.name("HorizonCap", "Config", "$B$" + (C_RUN + 1));
            w.name("Life", "Config", "$B$" + (C_RUN + 2));
            w.name("V0", "Config", "$C$" + (C_TRADES + r));
            w.name("CI", "Contributions", "$D$2");
            w.name("CK", "Contributions", "$G$2");
        }
    }

    // ---- Main ------------------------------------------------------------------------------------------------------------------------

    private void main(SheetStream s) {
        ExposurePlaygroundModel.About ab = m.about;
        int width = 6 + nq;
        s.width(0, 34).width(1, 18).widths(2, width * 2 + 2, 14).freeze(3, 0);
        s.row(1).text(0, Branding.current().productOr("") + " · Exposure Playground", "title");
        for (int i = 1; i < 8; i++) {
            s.blank(i, "title");
        }
        s.row(2).text(0, "netting set " + ab.nettingSet() + " · counterparty " + ab.counterparty() + " · " + ab.businessDate() + " · run " + ab.runId()
                + (ab.calibration() == null ? "" : " (" + ab.calibration() + ")") + " · the first " + n + " of " + ab.runPaths() + " paths · USD", "note");
        // the checks, for the band
        int lastHeadline = mFirst + 7 + 2 * nq + 3;
        pHead = lastHeadline + 3;
        pFirst = pHead + 1;
        int pLast = pFirst + gp - 1;
        int checkCol = 13 + 2 * nq;                       // the profile's Check: after the workbook's 5 + nq, a gap and the engine's 4 + nq columns
        String checks = "SUMPRODUCT((E" + mFirst + ":E" + lastHeadline + "=\"" + FAIL + "\")*1)+SUMPRODUCT((" + col(checkCol) + pFirst + ":" + col(checkCol) + pLast
                + "=\"" + FAIL + "\")*1)";
        s.row(3).formulaText(0, "IF(" + checks + "=0,\"" + ALL_PASS + "\",\"" + FAILS + "\")", ct(ALL_PASS), "band");
        s.checks("A3", ALL_PASS);
        s.row(5).text(0, "Choice", "head").text(1, "Value", "head").text(2, "What it does", "head");
        for (int a = 0; a < nq; a++) {
            s.row(mConf + a).text(0, "Confidence " + (a + 1) + " (q)", "label").number(1, m.qs[a], "input.dec")
                    .text(2, "PFE at this confidence (Stats, PFE); any q above 0.5 and below 1", "note");
        }
        s.row(mRule).text(0, "Rank rule", "label").text(1, m.ruleText(), "input.text").text(2, "kth: the ROUNDUP(P q)-th smallest; interpolated", "note");
        s.list("B" + mRule, List.of("kth", "interpolated"));
        s.row(mModel).text(0, "Collateral model", "label").text(1, m.modelText(), "input.text").text(2, "lagged, current or none (Collateral)", "note");
        s.list("B" + mModel, List.of("lagged", "current", "none"));
        s.row(mDate).text(0, "Contribution date", "label").text(1, m.tenor(m.contributionAt), "input.text")
                .text(2, "the primary date the Contributions tab splits EE at" + (m.contributionPeak ? " (the peak EE date when exported)" : ""), "note");
        java.util.List<String> tenors = new java.util.ArrayList<>();
        for (int i = 0; i < gp; i++) {
            tenors.add(m.tenor(i));
        }
        if (String.join(",", tenors).length() < 250) {
            s.list("B" + mDate, tenors);
        }
        // the headline figures
        s.row(mHead).text(0, "Figure", "head").text(1, "How", "head").text(2, "Workbook", "head").text(3, "Engine", "head").text(4, "Check", "head");
        int row = mFirst;
        String pathRange = "Data!$A$" + PR + ":$A$" + (PR + n - 1);
        s.row(row).text(0, "Paths (P)", "label").text(1, "COUNT of Data's paths", "text");
        s.formula(2, "COUNT(" + pathRange + ")", c(n), "int").number(3, n, "int").formulaText(4, check("C" + row, "D" + row), ct(PASS), "check");
        row++;
        String held = "IF(Margined=\"yes\",C0,IA)";
        s.row(row).text(0, "CE", "label").text(1, "max(V0 − C0 − IM, 0)", "text");
        s.formula(2, "IF(OR(CsaPresent<>\"yes\",CollateralModel=\"none\"),MAX(V0,0),MAX(V0-" + held + "-IM,0))", c(m.stats.ce), "money")
                .number(3, m.stats.ce, "money").formulaText(4, check("C" + row, "D" + row), ct(PASS), "check");
        row++;
        s.row(row).text(0, "T (years)", "label").text(1, "min(1 year, longest maturity)", "text");
        s.formula(2, "MIN(HorizonCap,Life)", c(m.horizon()), "rate").number(3, m.horizon(), "rate").formulaText(4, check("C" + row, "D" + row), ct(PASS), "check");
        row++;
        String ee = a("Stats", 4, SR) + ":" + "$" + col(4) + "$" + (SR + gp - 1);
        String eee = statsRange(6 + nq);
        String wts = statsRange(9 + nq);
        String tenorsR = statsRange(0);
        s.row(row).text(0, "EPE", "label").text(1, "Σ EE · weight / T", "text");
        s.formula(2, "IF(T>0,SUMPRODUCT(" + ee + "," + wts + ")/T,0)", c(m.stats.epe), "money.bold").number(3, m.stats.epe, "money.bold")
                .formulaText(4, check("C" + row, "D" + row), ct(PASS), "check");
        row++;
        s.row(row).text(0, "EEPE", "label").text(1, "Σ EEE · weight / T", "text");
        s.formula(2, "IF(T>0,SUMPRODUCT(" + eee + "," + wts + ")/T,0)", c(m.stats.eepe), "money.bold").number(3, m.stats.eepe, "money.bold")
                .formulaText(4, check("C" + row, "D" + row), ct(PASS), "check");
        int eepeRow = row;
        row++;
        s.row(row).text(0, "Peak EE", "label").text(1, "MAX of EE", "text");
        s.formula(2, "MAX(" + ee + ")", c(m.stats.peakEe), "money").number(3, m.stats.peakEe, "money").formulaText(4, check("C" + row, "D" + row), ct(PASS), "check");
        row++;
        String peakEeTenor = m.tenor(m.peakEeAt);
        s.row(row).text(0, "Peak EE date", "label").text(1, "the earliest on a tie", "text");
        s.formulaText(2, "INDEX(" + tenorsR + ",MATCH(MAX(" + ee + ")," + ee + ",0))", ct(peakEeTenor), "text").text(3, peakEeTenor, "text")
                .formulaText(4, "IF(C" + row + "=D" + row + ",\"" + PASS + "\",\"" + FAIL + "\")", ct(PASS), "check");
        row++;
        for (int a = 0; a < nq; a++) {
            String pfe = statsRange(6 + a);
            String label = "Peak PFE " + pct(m.qs[a]);
            s.row(row).text(0, label, "label").text(1, "MAX of PFE at Confidence " + (a + 1), "text");
            s.formula(2, "MAX(" + pfe + ")", c(m.stats.peakPfe[a]), "money.bold").number(3, m.stats.peakPfe[a], "money.bold")
                    .formulaText(4, check("C" + row, "D" + row), ct(PASS), "check");
            row++;
            String tn = m.stats.peakAt[a] < 0 ? "" : m.tenor(m.stats.peakAt[a]);
            s.row(row).text(0, label + " date", "label").text(1, "the earliest on a tie", "text");
            s.formulaText(2, "INDEX(" + tenorsR + ",MATCH(MAX(" + pfe + ")," + pfe + ",0))", ct(tn), "text").text(3, tn, "text")
                    .formulaText(4, "IF(C" + row + "=D" + row + ",\"" + PASS + "\",\"" + FAIL + "\")", ct(PASS), "check");
            row++;
        }
        s.row(row).text(0, "EAD_IMM (informational)", "label").text(1, "alpha × EEPE", "text");
        s.formula(2, "Alpha*C" + eepeRow, c(m.eadImm()), "money").number(3, m.eadImm(), "money").formulaText(4, check("C" + row, "D" + row), ct(PASS), "check");
        row++;
        double sumShares = 0;
        for (double x : m.share) {
            sumShares += x;
        }
        double eeAt = m.stats.ee[m.contributionAt];
        s.row(row).text(0, "Contributions add up to EE", "label").text(1, "Σ shares against EE at the date", "text");
        s.formula(2, "Contributions!$C$5", c(sumShares), "money").formula(3, "Contributions!$C$6", c(eeAt), "money")
                .formulaText(4, check("C" + row, "D" + row), ct(PASS), "check");
        row++;
        long cells = (long) n * g * r;
        s.row(row).text(0, "Every Data cell a number", "label").text(1, "COUNT of Data against P × dates × columns", "text");
        s.formula(2, "COUNT(Data!$B$" + PR + ":$" + col(dataCol(g - 1, r - 1)) + "$" + (PR + n - 1) + ")", c(cells), "int")
                .formula(3, "P*" + g + "*" + r, c(cells), "int").formulaText(4, check("C" + row, "D" + row), ct(PASS), "check");
        row++;
        s.row(row).text(0, "Trades shown + Rest of set = the set", "label").text(1, "Config, the trades", "text");
        s.formula(2, "SUM(Config!$E$" + C_TRADES + ":$E$" + (C_TRADES + r - 1) + ")", c(m.tradesCounted()), "int").number(3, m.about.setTrades(), "int")
                .formulaText(4, check("C" + row, "D" + row), ct(m.tradesCounted() == m.about.setTrades() ? PASS : FAIL), "check");
        mLast = row;
        s.checks("E" + mFirst + ":E" + mLast, PASS);
        // the profile: the workbook's figures, the engine's beside them, one check per date
        int wc = 3;                                         // workbook columns: D.. (EE, ENE, PFE.., EEE, EE uncoll, effect)
        int ec = wc + 6 + nq;                               // engine columns: EE, ENE, PFE.., EEE, EE uncoll
        s.row(pHead).text(0, "Date", "head").text(1, "t (years)", "head").text(2, "Δt", "head").text(wc, "EE", "head").text(wc + 1, "ENE", "head");
        for (int a = 0; a < nq; a++) {
            s.text(wc + 2 + a, "PFE " + pct(m.qs[a]), "head");
        }
        s.text(wc + 2 + nq, "EEE", "head").text(wc + 3 + nq, "EE uncollateralised", "head").text(wc + 4 + nq, "Collateral effect", "head")
                .text(wc + 5 + nq, "", "head");
        s.text(ec, "Engine EE", "head").text(ec + 1, "Engine ENE", "head");
        for (int a = 0; a < nq; a++) {
            s.text(ec + 2 + a, "Engine PFE " + pct(m.qs[a]), "head");
        }
        s.text(ec + 2 + nq, "Engine EEE", "head").text(ec + 3 + nq, "Engine EE uncoll.", "head").text(checkCol, "Check", "head");
        for (int i = 0; i < gp; i++) {
            int sr = SR + i;
            s.row(pFirst + i).formulaText(0, "Stats!$A$" + sr, ct(m.tenor(i)), "label").formula(1, "Stats!$C$" + sr, c(m.primaryTimes[i]), "rate")
                    .formula(2, "Stats!$D$" + sr, c(m.dt(i)), "rate");
            double[] wv = new double[5 + nq];
            wv[0] = m.stats.ee[i];
            wv[1] = m.stats.ene[i];
            for (int a = 0; a < nq; a++) {
                wv[2 + a] = m.stats.pfe[a][i];
            }
            wv[2 + nq] = m.stats.eee[i];
            wv[3 + nq] = m.stats.eeUncoll[i];
            wv[4 + nq] = m.stats.ee[i] - m.stats.eeUncoll[i];
            int[] statsCols = new int[5 + nq];
            statsCols[0] = 4;
            statsCols[1] = 5;
            for (int a = 0; a < nq; a++) {
                statsCols[2 + a] = 6 + a;
            }
            statsCols[2 + nq] = 6 + nq;
            statsCols[3 + nq] = 7 + nq;
            statsCols[4 + nq] = 8 + nq;
            for (int x = 0; x < 5 + nq; x++) {
                s.formula(wc + x, "Stats!$" + col(statsCols[x]) + "$" + sr, c(wv[x]), "money");
            }
            StringBuilder all = new StringBuilder("AND(");
            for (int x = 0; x < 4 + nq; x++) {
                s.number(ec + x, wv[x], "money");
                all.append(x == 0 ? "" : ",").append("ABS(").append(col(wc + x)).append(pFirst + i).append('-').append(col(ec + x)).append(pFirst + i)
                        .append(")<0.005");
            }
            s.formulaText(checkCol, "IF(" + all + "),\"" + PASS + "\",\"" + FAIL + "\")", ct(PASS), "check");
        }
        s.checks(col(checkCol) + pFirst + ":" + col(checkCol) + pLast, PASS);
        int row2 = pLast + 2;
        if (m.about.runFigures() != null && !m.about.runFigures().isEmpty()) {
            s.row(row2++).text(0, "The run's own figures over all " + m.about.runPaths() + " paths (for information; the workbook has the first " + n + ")", "note");
            for (Map.Entry<String, Object> en : m.about.runFigures().entrySet()) {
                s.row(row2).text(0, en.getKey(), "label");
                if (en.getValue() instanceof Number num) {
                    s.number(3, num.doubleValue(), "money");
                } else {
                    s.text(3, String.valueOf(en.getValue()), "text");
                }
                row2++;
            }
        }
        s.row(row2 + 1).text(0, "Change a choice above or a value in Config or Data and every figure recalculates; the Engine columns stay the "
                + "engine's answer to the exported question, so their checks turn red by design, while the add-up checks stay green.", "note");
    }

    private static String pct(double q) {
        double p = Math.round(q * 10_000) / 100.0;
        return (p == Math.rint(p) ? String.valueOf((long) p) : String.valueOf(p)) + "%";
    }

    private String statsRange(int c) {
        return a("Stats", c, SR) + ":$" + col(c) + "$" + (SR + gp - 1);
    }

    // ---- Config ----------------------------------------------------------------------------------------------------------------------

    private void config(SheetStream s) {
        ExposurePlaygroundModel.About ab = m.about;
        var csa = m.csa;
        s.width(0, 34).width(1, 20).width(2, 18).width(3, 16).width(4, 14).width(5, 14);
        s.row(1).text(0, "Config: the CSA, the run and its grid (typed; every formula reads them)", "title");
        for (int i = 1; i < 6; i++) {
            s.blank(i, "title");
        }
        s.row(3).text(0, "The CSA (USD)", "head").text(1, "Value", "head").text(2, "Note", "head");
        s.row(C_CSA).text(0, "CSA", "label").text(1, csa.present() ? csa.csaId() : "none", "input.text");
        s.row(C_CSA + 1).text(0, "CSA present", "label").text(1, csa.present() ? "yes" : "no", "input.text").text(2, "no: no collateral at all", "note");
        s.list("B" + (C_CSA + 1), List.of("yes", "no"));
        s.row(C_CSA + 2).text(0, "Margined (variation margin called)", "label").text(1, csa.margined() ? "yes" : "no", "input.text")
                .text(2, "no: the independent amount is held on every path", "note");
        s.list("B" + (C_CSA + 2), List.of("yes", "no"));
        s.row(C_CSA + 3).text(0, "THRESHOLD_THEM", "label").number(1, csa.thThem(), "input.money");
        s.row(C_CSA + 4).text(0, "THRESHOLD_US", "label").number(1, csa.thUs(), "input.money");
        s.row(C_CSA + 5).text(0, "MTA_THEM", "label").number(1, csa.mtaThem(), "input.money").text(2, "taken off what is held", "note");
        s.row(C_CSA + 6).text(0, "MTA_US", "label").number(1, csa.mtaUs(), "input.money").text(2, "not taken off what is posted (conservative)", "note");
        s.row(C_CSA + 7).text(0, "INDEPENDENT_AMOUNT", "label").number(1, csa.ia(), "input.money").text(2, "held +, posted −", "note");
        s.row(C_CSA + 8).text(0, "ROUNDING", "label").number(1, csa.rounding(), "input.money").text(2, "0: none", "note");
        s.row(C_CSA + 9).text(0, "ROUNDING_METHOD", "label").text(1, "deliveries up, returns down", "text").text(2, "of the change from C0", "note");
        s.row(C_CSA + 10).text(0, "MPOR_DAYS", "label");
        intOr(s, ab.mporDays() != null ? ab.mporDays() : csa.present() ? csa.mporDays() : null);
        s.row(C_CSA + 11).text(0, "MARGIN_FREQUENCY_DAYS", "label");
        intOr(s, ab.frequencyDays() != null ? ab.frequencyDays() : csa.present() ? csa.frequencyDays() : null);
        s.row(C_CSA + 12).text(0, "LOOKBACK_DAYS (the run)", "label");
        intOr(s, ab.lookbackDays());
        boolean mismatch = csa.present() && ab.lookbackDays() != null && csa.mporDays() + csa.frequencyDays() - 1 != ab.lookbackDays();
        s.row(C_CSA + 13).text(0, "MPOR_MISMATCH", "label").text(1, mismatch ? "yes" : "no", "text")
                .text(2, "MPOR + frequency − 1 against the run's look-back; the run's is used", "note");
        s.row(C_CSA + 14).text(0, "Today's collateral (C0)", "label").number(1, csa.heldToday(), "input.money").text(2, "held − posted, after haircut", "note");
        s.row(C_CSA + 15).text(0, "IM held", "label").number(1, csa.imHeld(), "input.money").text(2, "kept on every path", "note");
        s.row(C_RUN - 1).text(0, "The run", "head").text(1, "Value", "head").text(2, "Note", "head");
        s.row(C_RUN).text(0, "Alpha", "label").number(1, m.alpha, "input.dec").text(2, "EAD_IMM = alpha × EEPE (CRE53)", "note");
        s.row(C_RUN + 1).text(0, "Horizon cap (years)", "label").number(1, 1, "input.dec");
        s.row(C_RUN + 2).text(0, "Longest remaining maturity (years)", "label").number(1, m.life, "input.dec");
        s.row(C_RUN + 3).text(0, "Paths in the run", "label").number(1, ab.runPaths(), "int");
        s.row(C_RUN + 4).text(0, "Paths in this workbook", "label").number(1, n, "int");
        s.row(C_RUN + 5).text(0, "Trades in the netting set", "label").number(1, ab.setTrades(), "int");
        s.row(C_TRADES - 1).text(0, "Trade (Data column)", "head").text(1, "Label", "head").text(2, "Today's MTM", "head").text(3, "Maturity (years)", "head")
                .text(4, "Trades counted", "head");
        for (int x = 0; x < r; x++) {
            ExposurePlaygroundModel.Column cl = m.columns.get(x);
            s.row(C_TRADES + x).text(0, cl.id(), "label").text(1, cl.label(), "text").number(2, cl.mtm0(), "input.money").number(3, cl.lifeYears(), "input.dec")
                    .number(4, cl.trades(), "int");
        }
        s.row(C_TRADES + r).text(0, "V0 (today's value of the set)", "label").formula(2, "SUM(C" + C_TRADES + ":C" + (C_TRADES + r - 1) + ")", c(m.v0), "money.bold");
        ExposureGrid gr = m.grid;
        s.row(cGrid).text(0, "Grid date (k)", "head").text(1, "Date", "head").text(2, "Role", "head").text(3, "Look-back of", "head").text(4, "Tenor", "head")
                .text(5, "t (years)", "head");
        for (int k = 0; k < g; k++) {
            s.row(cGrid + 1 + k).number(0, k + 1, "int").text(1, gr.date(k).toString(), "text").text(2, gr.primary(k) ? "PRIMARY" : "LOOKBACK", "text");
            if (!gr.primary(k)) {
                s.text(3, gr.tenor(gr.lookbackOf(k)) + " (k " + (gr.lookbackOf(k) + 1) + ")", "text");
            } else if (gr.lookbackFor(k) < 0) {
                s.text(3, "no look-back: C0", "note");
            }
            if (gr.primary(k)) {
                s.text(4, gr.tenor(k), "text");
            }
            s.number(5, m.times[k], "input.dec");
        }
    }

    private static void intOr(SheetStream s, Integer v) {
        if (v == null) {
            s.text(1, "—", "text");
        } else {
            s.number(1, v, "input.int");
        }
    }

    /** Config's row of grid date k (0-based). */
    private int gridRow(int k) {
        return cGrid + 1 + k;
    }

    // ---- Data, Netting, Collateral, Exposure -------------------------------------------------------------------------------------

    private String dateHead(int k) {
        ExposureGrid gr = m.grid;
        return gr.primary(k) ? gr.tenor(k) + " · " + gr.date(k) : "look-back of " + gr.tenor(gr.lookbackOf(k)) + " · " + gr.date(k);
    }

    private void data(SheetStream s) {
        s.width(0, 8).widths(1, Math.min(dataColumns(g, r) - 1, 16_383), 13).freeze(PR - 1, 1);
        s.row(1).text(0, "Data: the simulated values MTM(p, k) per trade, as delivered by the bank's simulator (USD); the only data of the workbook",
                "title");
        s.row(2).text(0, "Date", "head");
        for (int k = 0; k < g; k++) {
            for (int x = 0; x < r; x++) {
                s.text(dataCol(k, x), dateHead(k), "head");
            }
        }
        s.row(3).text(0, "Path", "head");
        for (int k = 0; k < g; k++) {
            for (int x = 0; x < r; x++) {
                s.text(dataCol(k, x), m.columns.get(x).id(), "head");
            }
        }
        for (int j = 0; j < n; j++) {
            s.row(PR + j).number(0, j + 1, "int");
            for (int k = 0; k < g; k++) {
                for (int x = 0; x < r; x++) {
                    s.number(dataCol(k, x), m.columns.get(x).at(k, j), "input.money");
                }
            }
        }
    }

    private void netting(SheetStream s) {
        s.width(0, 8).widths(1, g, 16).freeze(PR - 1, 1);
        s.row(1).text(0, "Netting: the set's value on each path, V(p, k) = Σ trades (L at a look-back date)", "title");
        s.row(2).text(0, "Date", "head");
        for (int k = 0; k < g; k++) {
            s.text(1 + k, dateHead(k), "head");
        }
        s.row(3).text(0, "Path", "head");
        for (int k = 0; k < g; k++) {
            s.text(1 + k, m.grid.primary(k) ? "V" : "L", "head");
        }
        for (int j = 0; j < n; j++) {
            int row = PR + j;
            s.row(row).formula(0, "Data!$A$" + row, c(j + 1), "int");
            for (int k = 0; k < g; k++) {
                s.formula(1 + k, "SUM(Data!" + col(dataCol(k, 0)) + row + ":" + col(dataCol(k, r - 1)) + row + ")", c(m.v[k * n + j]), "money");
            }
        }
    }

    private void collateral(SheetStream s) {
        s.width(0, 8).widths(1, 3 * gp, 14).freeze(PR - 1, 1);
        s.row(1).text(0, "Collateral: the CSA's call at the look-back value (target), rounded as a change from C0, then C of the model chosen "
                + "(the counterparty's MTA off what is held)", "title");
        s.row(2).text(0, "Date", "head");
        for (int b = 0; b < 3; b++) {
            for (int i = 0; i < gp; i++) {
                s.text(1 + b * gp + i, m.tenor(i), "head");
            }
        }
        s.row(3).text(0, "Path", "head");
        for (int i = 0; i < gp; i++) {
            boolean none = m.grid.lookbackFor(m.grid.primaryAt(i)) < 0;
            s.text(1 + i, none ? "target (none: C0)" : "target", "head").text(1 + gp + i, none ? "rounded (none)" : "rounded", "head").text(1 + 2 * gp + i, "C",
                    "head");
        }
        for (int j = 0; j < n; j++) {
            int row = PR + j;
            s.row(row).formula(0, "Data!$A$" + row, c(j + 1), "int");
            for (int i = 0; i < gp; i++) {
                int lb = m.grid.lookbackFor(m.grid.primaryAt(i));
                if (lb >= 0) {
                    String l = "Netting!" + col(1 + lb) + row;
                    s.formula(1 + i, "MAX(" + l + "-ThThem,0)-MAX(-" + l + "-ThUs,0)+IA", c(m.target[i * n + j]), "money");
                }
            }
            for (int i = 0; i < gp; i++) {
                int lb = m.grid.lookbackFor(m.grid.primaryAt(i));
                if (lb >= 0) {
                    String tg = col(1 + i) + row;
                    s.formula(1 + gp + i, "IF(Rounding>0,C0+IF(" + tg + "-C0>0,ROUNDUP((" + tg + "-C0)/Rounding,0),ROUNDDOWN((" + tg + "-C0)/Rounding,0))*Rounding,"
                            + tg + ")", c(m.rounded[i * n + j]), "money");
                }
            }
            for (int i = 0; i < gp; i++) {
                int lb = m.grid.lookbackFor(m.grid.primaryAt(i));
                String rd = col(1 + gp + i) + row;
                String lagged = lb < 0 ? "C0" : "IF(" + rd + ">0,MAX(" + rd + "-MtaThem,0)," + rd + ")";
                s.formula(1 + 2 * gp + i, "IF(OR(CsaPresent<>\"yes\",CollateralModel=\"none\"),0,IF(Margined<>\"yes\",IA,IF(CollateralModel=\"current\",C0,"
                        + lagged + ")))", c(m.c[i * n + j]), "money");
            }
        }
    }

    private void exposure(SheetStream s) {
        s.width(0, 8).widths(1, 3 * gp, 14).freeze(PR - 1, 1);
        s.row(1).text(0, "Exposure: E = max(V − C − IM, 0), N = min(V − C, 0) and the uncollateralised max(V, 0) on each path", "title");
        s.row(2).text(0, "Date", "head");
        for (int b = 0; b < 3; b++) {
            for (int i = 0; i < gp; i++) {
                s.text(1 + b * gp + i, m.tenor(i), "head");
            }
        }
        s.row(3).text(0, "Path", "head");
        for (int i = 0; i < gp; i++) {
            s.text(1 + i, "E", "head").text(1 + gp + i, "N", "head").text(1 + 2 * gp + i, "max(V, 0)", "head");
        }
        String ime = imEff();
        for (int j = 0; j < n; j++) {
            int row = PR + j;
            s.row(row).formula(0, "Data!$A$" + row, c(j + 1), "int");
            for (int b = 0; b < 3; b++) {
                for (int i = 0; i < gp; i++) {
                    String vv = "Netting!" + col(1 + m.grid.primaryAt(i)) + row;
                    String cc = "Collateral!" + col(1 + 2 * gp + i) + row;
                    int at = i * n + j;
                    switch (b) {
                        case 0 -> s.formula(1 + i, "MAX(" + vv + "-" + cc + "-" + ime + ",0)", c(m.e[at]), "money");
                        case 1 -> s.formula(1 + gp + i, "MIN(" + vv + "-" + cc + ",0)", c(m.neg[at]), "money");
                        default -> s.formula(1 + 2 * gp + i, "MAX(" + vv + ",0)", c(m.pos[at]), "money");
                    }
                }
            }
        }
    }

    // ---- Stats -------------------------------------------------------------------------------------------------------------------------

    private void stats(SheetStream s) {
        s.width(0, 12).width(1, 10).widths(2, 3, 11).widths(4, 9 + nq, 14).freeze(SH, 1);
        s.row(1).text(0, "Stats: each primary date summarised across the paths (EE = AVERAGE, PFE = SMALL at the rank, EEE never falls)", "title");
        // the rank of each confidence: kth ROUNDUP(P q - 1E-9, 0); interpolated lo, hi and w
        String[] lab = {"q", "rank (kth)", "lo (interpolated)", "hi (interpolated)", "w (interpolated)"};
        for (int x = 0; x < 5; x++) {
            s.row(SQ + x).text(5, lab[x], "label");
            for (int a = 0; a < nq; a++) {
                String qc = col(6 + a) + SQ;
                String lo = col(6 + a) + (SQ + 2);
                double qv = m.qs[a];
                double pos = n * qv;
                int loV = Math.max(1, Math.min(n, (int) Math.floor(pos)));
                switch (x) {
                    case 0 -> s.formula(6 + a, "Confidence" + (a + 1), c(qv), "rate");
                    case 1 -> s.formula(6 + a, "MAX(1,MIN(P,ROUNDUP(P*" + qc + "-1E-9,0)))", c(com.ash.drishti.compute.ccr.ExposureMath.rank(n, qv)), "int");
                    case 2 -> s.formula(6 + a, "MAX(1,MIN(P,ROUNDDOWN(P*" + qc + ",0)))", c(loV), "int");
                    case 3 -> s.formula(6 + a, "MIN(P," + lo + "+1)", c(Math.min(n, loV + 1)), "int");
                    default -> s.formula(6 + a, "MIN(1,MAX(0,P*" + qc + "-" + lo + "))", c(Math.min(1.0, Math.max(0.0, pos - loV))), "rate");
                }
            }
        }
        s.row(SH).text(0, "Date", "head").text(1, "Grid k", "head").text(2, "t (years)", "head").text(3, "Δt", "head").text(4, "EE", "head")
                .text(5, "ENE", "head");
        for (int a = 0; a < nq; a++) {
            s.text(6 + a, "PFE (Confidence " + (a + 1) + ")", "head");
        }
        s.text(6 + nq, "EEE", "head").text(7 + nq, "EE uncollateralised", "head").text(8 + nq, "Collateral effect", "head").text(9 + nq, "EPE weight", "head");
        for (int i = 0; i < gp; i++) {
            int row = SR + i;
            int k = m.grid.primaryAt(i);
            String eR = "Exposure!" + col(1 + i) + PR + ":" + col(1 + i) + (PR + n - 1);
            String nR = "Exposure!" + col(1 + gp + i) + PR + ":" + col(1 + gp + i) + (PR + n - 1);
            String uR = "Exposure!" + col(1 + 2 * gp + i) + PR + ":" + col(1 + 2 * gp + i) + (PR + n - 1);
            s.row(row).text(0, m.tenor(i), "label").number(1, k + 1, "int").formula(2, "Config!$F$" + gridRow(k), c(m.primaryTimes[i]), "rate");
            s.formula(3, i == 0 ? "C" + row : "C" + row + "-C" + (row - 1), c(m.dt(i)), "rate");
            s.formula(4, "AVERAGE(" + eR + ")", c(m.stats.ee[i]), "money").formula(5, "AVERAGE(" + nR + ")", c(m.stats.ene[i]), "money");
            for (int a = 0; a < nq; a++) {
                String cq = col(6 + a);
                String kth = "SMALL(" + eR + "," + cq + "$" + (SQ + 1) + ")";
                String interp = "(1-" + cq + "$" + (SQ + 4) + ")*SMALL(" + eR + "," + cq + "$" + (SQ + 2) + ")+" + cq + "$" + (SQ + 4) + "*SMALL(" + eR + "," + cq
                        + "$" + (SQ + 3) + ")";
                s.formula(6 + a, "IF(RankRule=\"interpolated\"," + interp + "," + kth + ")", c(m.stats.pfe[a][i]), "money");
            }
            s.formula(6 + nq, "MAX(" + (i == 0 ? "CE" : col(6 + nq) + (row - 1)) + ",E" + row + ")", c(m.stats.eee[i]), "money");
            s.formula(7 + nq, "AVERAGE(" + uR + ")", c(m.stats.eeUncoll[i]), "money");
            s.formula(8 + nq, "E" + row + "-" + col(7 + nq) + row, c(m.stats.ee[i] - m.stats.eeUncoll[i]), "money");
            s.formula(9 + nq, "MAX(0,MIN(C" + row + ",T)-" + (i == 0 ? "0" : "C" + (row - 1)) + ")", c(m.weight(i)), "rate");
        }
    }

    // ---- Contributions -------------------------------------------------------------------------------------------------------------

    private void contributions(SheetStream s) {
        s.width(0, 10).widths(1, 4, 13).widths(5, 4 + r, 14).freeze(CR - 1, 1);
        s.row(1).text(0, "Contributions: each trade's share of EE at the contribution date (Euler): a = MTM × (1 − (C + IM) / V) where E > 0, "
                + "averaged over the paths", "title");
        int ci = m.contributionAt;
        int ck = m.grid.primaryAt(ci);
        s.row(2).text(0, "Date", "label").formulaText(1, "ContributionDate", ct(m.tenor(ci)), "text").text(2, "primary point", "label")
                .formula(3, "MATCH(ContributionDate,Stats!$A$" + SR + ":$A$" + (SR + gp - 1) + ",0)", c(ci + 1), "int").text(5, "grid k", "label")
                .formula(6, "INDEX(Stats!$B$" + SR + ":$B$" + (SR + gp - 1) + ",CI)", c(ck + 1), "int");
        s.row(4).text(0, "", "head").text(1, "", "head").text(2, "", "head").text(3, "", "head").text(4, "", "head");
        for (int x = 0; x < r; x++) {
            s.text(5 + x, m.columns.get(x).id(), "head");
        }
        double sum = 0;
        for (double x : m.share) {
            sum += x;
        }
        String first = col(5) + "5";
        String last = col(4 + r) + "5";
        s.row(5).text(0, "EE share", "label").text(1, "Σ shares", "label").formula(2, "SUM(" + first + ":" + last + ")", c(sum), "money.bold");
        for (int x = 0; x < r; x++) {
            String cc = col(5 + x);
            s.formula(5 + x, "AVERAGE(" + cc + CR + ":" + cc + (CR + n - 1) + ")", c(m.share[x]), "money.bold");
        }
        s.row(6).text(0, "Engine", "label").text(1, "EE at the date", "label")
                .formula(2, "INDEX(Stats!$E$" + SR + ":$E$" + (SR + gp - 1) + ",CI)", c(m.stats.ee[ci]), "money");
        for (int x = 0; x < r; x++) {
            s.number(5 + x, m.share[x], "money");
        }
        s.row(7).text(0, "Check", "label");
        for (int x = 0; x < r; x++) {
            String cc = col(5 + x);
            s.formulaText(5 + x, check(cc + "5", cc + "6"), ct(PASS), "check");
        }
        s.checks(col(5) + "7:" + col(4 + r) + "7", PASS);
        s.row(CR - 1).text(0, "Path", "head").text(1, "V", "head").text(2, "C", "head").text(3, "E", "head").text(4, "1 − (C + IM) / V", "head");
        for (int x = 0; x < r; x++) {
            s.text(5 + x, "a: " + m.columns.get(x).id(), "head");
        }
        String ime = imEff();
        String dLast = col(dataCol(g - 1, r - 1));
        for (int j = 0; j < n; j++) {
            int row = CR + j;
            int pr = PR + j;
            s.row(row).formula(0, "Data!$A$" + pr, c(j + 1), "int");
            s.formula(1, "INDEX(Netting!$B$" + pr + ":$" + col(g) + "$" + pr + ",1,CK)", c(m.v[ck * n + j]), "money");
            s.formula(2, "INDEX(Collateral!$" + col(1 + 2 * gp) + "$" + pr + ":$" + col(3 * gp) + "$" + pr + ",1,CI)", c(m.c[ci * n + j]), "money");
            s.formula(3, "INDEX(Exposure!$B$" + pr + ":$" + col(gp) + "$" + pr + ",1,CI)", c(m.e[ci * n + j]), "money");
            s.formula(4, "IF(AND(D" + row + ">0,B" + row + "<>0),1-(C" + row + "+" + ime + ")/B" + row + ",0)", c(m.factor[j]), "rate");
            for (int x = 0; x < r; x++) {
                double val = m.columns.get(x).at(ck, j) * m.factor[j];
                s.formula(5 + x, "INDEX(Data!$B$" + pr + ":$" + dLast + "$" + pr + ",1,(CK-1)*" + r + "+" + (x + 1) + ")*$E" + row, c(val), "money");
            }
        }
    }

    // ---- Explain and Provenance ------------------------------------------------------------------------------------------------------

    private void explain(SheetStream s) {
        s.width(0, 44).width(1, 150);
        s.row(1).text(0, Branding.current().productOr("") + " · Exposure Playground · how each figure is worked out", "title");
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
        Map<String, Object> prov = new LinkedHashMap<>(m.about.provenance() == null ? Map.of() : m.about.provenance());
        prov.put("Netting set", m.about.nettingSet() + " · counterparty " + m.about.counterparty());
        prov.put("Paths in this workbook", "the first " + n + " of the run's " + m.about.runPaths());
        prov.put("Trade columns", (m.hasRest() ? (r - 1) + " trades shown, the other " + m.columns.get(r - 1).trades() + " in Rest of set" : r + " trades, every trade")
                + " (the largest by |EE contribution| at the peak EE date)");
        prov.put("Choices exported", "confidences " + java.util.Arrays.toString(m.qs) + ", rank rule " + m.ruleText() + ", collateral " + m.modelText()
                + ", contribution date " + m.tenor(m.contributionAt));
        prov.put("Template", t.name() + " " + t.version() + " (" + t.origin() + ")");
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
