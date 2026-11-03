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

import com.ash.drishti.compute.ccr.FvaMath;
import com.ash.drishti.server.playground.PlaygroundTemplate;
import com.ash.drishti.server.playground.SheetStream;
import com.ash.drishti.server.playground.WorkbookStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The FVA Playground's workbook (docs/guides/FVA_GUIDE.md section 8, COUNTERPARTY_ENGINES.md section 8.3): Main, Config, Data, Terms, Explain
 * and Provenance. The profile (EE and ENE per primary date of each netting set, and today's CE and its negative) is data; every step after
 * it is a formula of the common set: the discount factor log-linear in the curve's points, the spreads linear between theirs, the survival
 * weight, the discounted exposures, each date's FCA and FBA term, the sums, and on Main each figure beside the engine's with its check.
 * The choices are cells of Config with a drop-down (EePoint, FundingSurvival, IncludeFba), so a validator who flips one sees FVA
 * recompute; the engine checks then turn red by design while the add-up checks stay green.
 */
final class FvaPlaygroundBook {

    static final List<String> STYLES = List.of("default", "title", "band", "head", "text", "note", "input.text", "input.dec", "input.rate", "money",
            "money.bold", "rate", "check", "label", "total", "wrap", "label.wrap");
    static final String PASS = "✓";
    static final String FAIL = "✗";
    static final String ALL_PASS = "ALL CHECKS PASS";

    /** One netting set of the book: its id, counterparty, CSA, today's exposure and its negative, and the engine's result. */
    record SetIn(String id, String counterparty, String csa, double ce, double nce, FvaMath.Result r) {}

    /**
     * What the workbook shows.
     *
     * @param dates the primary dates (K), as text
     * @param lend null: symmetric (the lending table follows the borrowing one)
     * @param discount null: the paths are discounted (DF = 1)
     */
    record Input(String scope, String label, String date, String runId, String calibration, List<String> flags, List<String> dates, double[] t,
            FvaMath.Curve borrow, FvaMath.Curve lend, FvaMath.Curve discount, double hazardCounterparty, double hazardBank, FvaMath.Point point,
            boolean survival, boolean fba, List<SetIn> sets, FvaMath.Result total, Map<String, Object> provenance) {}

    private final Input in;
    private final PlaygroundTemplate t;
    private final String user;
    private final String now;
    private final int k;
    private final boolean cached;

    // ---- the plan: where everything is ----
    private static final int CH = 4;            // Config: EePoint B4, FundingSurvival B5, IncludeFba B6, hazards B7 and B8, flat flag B9
    private int borrowFirst;
    private int borrowLast;
    private int lendFirst;
    private int lendLast;
    private int dfFirst;
    private int dfLast;
    private static final int D0 = 4;           // Data and Terms: row 4 is today (k = 0), rows 5 .. 4 + K the primary dates
    private static final int SET0 = 7;         // Terms: the first set's first column (EE*, ENE*, FCA, FBA per set)

    FvaPlaygroundBook(Input in, PlaygroundTemplate t, String user, String now) {
        this(in, t, user, now, true);
    }

    /** {@code cached} false: no cached results (a reader must recalculate: the tests' own check by LibreOffice). */
    FvaPlaygroundBook(Input in, PlaygroundTemplate t, String user, String now, boolean cached) {
        this.cached = cached;
        this.in = in;
        this.t = t;
        this.user = user;
        this.now = now;
        this.k = in.t().length;
        borrowFirst = 13;
        borrowLast = borrowFirst + Math.max(1, in.borrow().t().length) - 1;
        lendFirst = borrowLast + 4;
        int nl = (in.lend() == null ? in.borrow() : in.lend()).t().length;
        lendLast = lendFirst + Math.max(1, nl) - 1;
        dfFirst = lendLast + 4;
        dfLast = dfFirst + dfPoints().size();      // the anchor (0, 1) first
    }

    private Double c(double v) {
        return cached && Double.isFinite(v) ? v : null;
    }

    private String ct(String v) {
        return cached ? v : null;
    }

    private static String col(int c) {
        return com.ash.drishti.server.dist.XlsxWriter.col(c);
    }

    private int last() {
        return D0 + k;
    }

    private List<double[]> dfPoints() {
        List<double[]> out = new ArrayList<>();
        if (in.discount() == null) {
            out.add(new double[] {1.0, 1.0});
            return out;
        }
        for (int i = 0; i < in.discount().t().length; i++) {
            if (in.discount().t()[i] > 0) {
                out.add(new double[] {in.discount().t()[i], in.discount().v()[i]});
            }
        }
        if (out.isEmpty()) {
            out.add(new double[] {1.0, 1.0});
        }
        return out;
    }

    private static String range(String sheet, int c, int r1, int r2) {
        return sheet + "!$" + col(c) + "$" + r1 + ":$" + col(c) + "$" + r2;
    }

    /** Linear in t between points, flat beyond (the spreads). */
    private static String linear(String x, String ts, String vs, int n) {
        return "IF(" + x + "<=INDEX(" + ts + ",1),INDEX(" + vs + ",1),IF(" + x + ">=INDEX(" + ts + "," + n + "),INDEX(" + vs + "," + n + "),INDEX(" + vs
                + ",MATCH(" + x + "," + ts + ",1))+(" + x + "-INDEX(" + ts + ",MATCH(" + x + "," + ts + ",1)))/(INDEX(" + ts + ",MATCH(" + x + "," + ts
                + ",1)+1)-INDEX(" + ts + ",MATCH(" + x + "," + ts + ",1)))*(INDEX(" + vs + ",MATCH(" + x + "," + ts + ",1)+1)-INDEX(" + vs + ",MATCH(" + x + ","
                + ts + ",1)))))";
    }

    /** Log-linear in DF between the points (the anchor (0, 1) first), the last segment's rate beyond. */
    private String logLinear(String x) {
        int n = dfLast - dfFirst + 1;
        String ts = range("Config", 1, dfFirst, dfLast);
        String vs = range("Config", 2, dfFirst, dfLast);
        String j = "MIN(MATCH(" + x + "," + ts + ",1)," + (n - 1) + ")";
        return "IF(" + x + "<=0,1,EXP(LN(INDEX(" + vs + "," + j + "))+(" + x + "-INDEX(" + ts + "," + j + "))/(INDEX(" + ts + "," + j + "+1)-INDEX(" + ts
                + "," + j + "))*(LN(INDEX(" + vs + "," + j + "+1))-LN(INDEX(" + vs + "," + j + ")))))";
    }

    void write(OutputStream out) {
        Map<String, String> v = new LinkedHashMap<>(t.brandingValues(com.ash.drishti.common.Branding.current()));
        v.put("TITLE", "FVA Playground · " + in.scope() + " · " + in.date());
        v.put("SUBJECT", "FVA worked in formulas: FCA on EE, FBA on ENE, discounted, by the funding spread");
        v.put("USER", user);
        v.put("NOW", now);
        try (WorkbookStream w = new WorkbookStream(out, t, v)) {
            main(w.sheet("Main", "1F3A5F"));
            config(w.sheet("Config", "B08A2E"));
            data(w.sheet("Data", "B08A2E"));
            terms(w.sheet("Terms", "2E5A88"));
            explain(w.sheet("Explain", "6B7686"));
            provenance(w.sheet("Provenance", "6B7686"));
            w.name("EePoint", "Config", "$B$" + CH);
            w.name("FundingSurvival", "Config", "$B$" + (CH + 1));
            w.name("IncludeFba", "Config", "$B$" + (CH + 2));
            int tot = 6 + in.sets().size();
            w.name("FCA", "Main", "$D$" + tot);
            w.name("FBA", "Main", "$F$" + tot);
            w.name("FVA", "Main", "$H$" + tot);
        }
    }

    // ---- Main ------------------------------------------------------------------------------------------------------------------------

    private String check(String... pairs) {
        List<String> c = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            c.add("ABS(" + pairs[i] + "-" + pairs[i + 1] + ")<0.005");
        }
        return "IF(AND(" + String.join(",", c) + "),\"" + PASS + "\",\"" + FAIL + "\")";
    }

    private void main(SheetStream s) {
        s.width(0, 30).width(1, 22).width(2, 22).widths(3, 8, 16).width(9, 10).freeze(5, 0);
        s.row(1).text(0, com.ash.drishti.common.Branding.current().productOr("") + " · FVA Playground", "title");
        for (int i = 1; i < 10; i++) {
            s.blank(i, "title");
        }
        s.row(2).text(0, in.label() + " · " + in.date() + " · run " + in.runId() + " (" + (in.calibration().isEmpty() ? "calibration not stated" : in.calibration())
                + ")" + (in.flags().isEmpty() ? "" : " · " + String.join(", ", in.flags())) + " · USD", "note");
        int n = in.sets().size();
        int first = 6;
        int tot = first + n;
        int addUp = tot + 1;
        String checks = "J" + first + ":J" + addUp;
        s.row(3).formulaText(0, "IF(SUMPRODUCT((" + checks + "=\"" + FAIL + "\")*1)=0,\"" + ALL_PASS + "\",\"A CHECK FAILS\")", ct(ALL_PASS), "band");
        s.checks("A3", ALL_PASS);
        s.row(5).text(0, "Netting set", "head").text(1, "Counterparty", "head").text(2, "CSA", "head").text(3, "FCA", "head").text(4, "FCA engine", "head")
                .text(5, "FBA", "head").text(6, "FBA engine", "head").text(7, "FVA", "head").text(8, "FVA engine", "head").text(9, "Check", "head");
        int row = first;
        for (int j = 0; j < n; j++) {
            SetIn si = in.sets().get(j);
            int fc = SET0 + 4 * j + 2;
            s.row(row).text(0, si.id(), "label").text(1, si.counterparty() == null ? "" : si.counterparty(), "text").text(2, si.csa(), "text");
            s.formula(3, "SUM(" + range("Terms", fc, D0, last()) + ")", c(si.r().fca()), "money");
            s.number(4, si.r().fca(), "money");
            s.formula(5, "SUM(" + range("Terms", fc + 1, D0, last()) + ")", c(si.r().fba()), "money");
            s.number(6, si.r().fba(), "money");
            s.formula(7, "D" + row + "+F" + row, c(si.r().fva()), "money.bold");
            s.number(8, si.r().fva(), "money.bold");
            s.formulaText(9, check("D" + row, "E" + row, "F" + row, "G" + row, "H" + row, "I" + row), ct(PASS), "check");
            row++;
        }
        s.row(tot).text(0, n == 1 ? "The netting set" : "The scope (the sets added)", "label").text(1, in.scope(), "text");
        s.formula(3, n == 0 ? "0" : "SUM(D" + first + ":D" + (tot - 1) + ")", c(in.total().fca()), "total");
        s.number(4, in.total().fca(), "total");
        s.formula(5, n == 0 ? "0" : "SUM(F" + first + ":F" + (tot - 1) + ")", c(in.total().fba()), "total");
        s.number(6, in.total().fba(), "total");
        s.formula(7, "D" + tot + "+F" + tot, c(in.total().fva()), "total");
        s.number(8, in.total().fva(), "total");
        s.formulaText(9, check("D" + tot, "E" + tot, "F" + tot, "G" + tot, "H" + tot, "I" + tot), ct(PASS), "check");
        s.row(addUp).text(0, "FVA = FCA + FBA; the engine's parts add up", "label");
        s.formulaText(9, check("H" + tot, "D" + tot + "+F" + tot, "I" + tot, "E" + tot + "+G" + tot), ct(PASS), "check");
        s.checks(checks, PASS);
        // the EPE reading (an explanation, not a result)
        int r0 = addUp + 2;
        String ee = sumEeDt();
        s.row(r0).text(0, "The EPE reading (before discounting)", "head").text(1, "Value", "head").text(2, "What it is", "head");
        s.row(r0 + 1).text(0, "Σ EE × Δt", "label").formula(1, ee, c(in.total().sumEeDt()), "money").text(2, "the exposure over the life, in dollar-years", "text");
        s.row(r0 + 2).text(0, "T_life", "label").formula(1, "Terms!$B$" + last(), c(in.total().life()), "rate").text(2, "the last primary date, in years", "text");
        s.row(r0 + 3).text(0, "EPE_life", "label").formula(1, "IF(B" + (r0 + 2) + ">0,B" + (r0 + 1) + "/B" + (r0 + 2) + ",0)", c(in.total().epeLife()), "money")
                .text(2, "EE averaged over the whole life (not the regulatory first year)", "text");
        boolean flat = in.borrow().isFlat();
        s.row(r0 + 4).text(0, "s_F × T_life × EPE_life", "label");
        s.formula(1, "IF(Config!$B$" + (CH + 5) + "=\"yes\",Config!$C$" + borrowFirst + "*B" + (r0 + 2) + "*B" + (r0 + 3) + ",0)", c(flat ? in.borrow().v()[0] * in.total().life() * in.total().epeLife() : 0.0), "money");
        s.text(2, flat ? "with no discounting this is −FCA: FVA in one line" : "the spread is a curve: no one-line reading", "text");
        s.row(r0 + 6).text(0, "CVA", "label").text(1, "planned", "text").text(2, "the CVA family joins this engine later, on the same EE profile", "text");
    }

    private String sumEeDt() {
        List<String> parts = new ArrayList<>();
        for (int j = 0; j < in.sets().size(); j++) {
            parts.add("SUMPRODUCT(" + range("Data", 3 + 2 * j, D0 + 1, last()) + "," + range("Terms", 2, D0 + 1, last()) + ")");
        }
        return parts.isEmpty() ? "0" : String.join("+", parts);
    }

    // ---- Config ----------------------------------------------------------------------------------------------------------------------

    private void config(SheetStream s) {
        s.width(0, 34).width(1, 16).width(2, 16).width(3, 18).width(4, 90);
        s.row(1).text(0, "Config: the choices, the funding spreads and the discount curve", "title");
        s.row(3).text(0, "Choice", "head").text(1, "Value (choose)", "head").text(2, "Default", "head").text(3, "Options", "head").text(4, "Rule", "head");
        s.row(CH).text(0, "EePoint", "label").text(1, in.point().text(), "input.text").text(2, "end", "text").text(3, "end, mid", "text")
                .text(4, "end: the exposure at the period's end; mid: the average of both ends (today's end: CE and its negative)", "wrap");
        s.list("B" + CH, List.of("end", "mid"));
        s.row(CH + 1).text(0, "FundingSurvival", "label").text(1, in.survival() ? "on" : "off", "input.text").text(2, "off", "text").text(3, "on, off", "text")
                .text(4, "on: each term × S_C(t) × S_B(t) = e^(−(h_C + h_B) t): funding stops at either default", "wrap");
        s.list("B" + (CH + 1), List.of("on", "off"));
        s.row(CH + 2).text(0, "IncludeFba", "label").text(1, in.fba() ? "on" : "off", "input.text").text(2, "on", "text").text(3, "on, off", "text")
                .text(4, "off: FCA only (asymmetric funding), FBA = 0", "wrap");
        s.list("B" + (CH + 2), List.of("on", "off"));
        s.row(CH + 3).text(0, "Hazard rate, counterparty (h_C)", "label").number(1, in.hazardCounterparty(), "input.rate").text(4, "flat, per year (survival weighting)", "text");
        s.row(CH + 4).text(0, "Hazard rate, the bank (h_B)", "label").number(1, in.hazardBank(), "input.rate").text(4, "flat, per year (survival weighting)", "text");
        s.row(CH + 5).text(0, "Borrowing spread is flat", "label").text(1, in.borrow().isFlat() ? "yes" : "no", "input.text")
                .text(4, "yes: Main shows the EPE reading (spread × life × EPE_life)", "text");
        s.row(borrowFirst - 2).text(0, "Borrowing (funding) spread: " + in.borrow().id() + " · " + in.borrow().source(), "head");
        s.row(borrowFirst - 1).text(0, "Tenor", "head").text(1, "t (years)", "head").text(2, "Spread", "head");
        FvaMath.Curve b = in.borrow();
        for (int i = 0; i < Math.max(1, b.t().length); i++) {
            s.row(borrowFirst + i).text(0, i < b.tenors().size() ? b.tenors().get(i) : "flat", "label").number(1, b.t()[i], "input.dec")
                    .number(2, b.v()[i], "input.rate");
        }
        FvaMath.Curve l = in.lend() == null ? b : in.lend();
        s.row(lendFirst - 2).text(0, "Lending spread (FBA): " + (in.lend() == null ? "the borrowing spread (symmetric funding: these cells follow it)"
                : l.id() + " · " + l.source()), "head");
        s.row(lendFirst - 1).text(0, "Tenor", "head").text(1, "t (years)", "head").text(2, "Spread", "head");
        for (int i = 0; i < Math.max(1, l.t().length); i++) {
            s.row(lendFirst + i).text(0, i < l.tenors().size() ? l.tenors().get(i) : "flat", "label");
            if (in.lend() == null) {
                s.formula(1, "B" + (borrowFirst + i), c(l.t()[i]), "rate").formula(2, "C" + (borrowFirst + i), c(l.v()[i]), "rate");
            } else {
                s.number(1, l.t()[i], "input.dec").number(2, l.v()[i], "input.rate");
            }
        }
        s.row(dfFirst - 2).text(0, in.discount() == null ? "Discount: none (the paths are discounted, VALUE_BASIS DISCOUNTED: DF = 1)"
                : "Discount curve: " + in.discount().id() + " · " + in.discount().source() + " (log-linear in DF)", "head");
        s.row(dfFirst - 1).text(0, "Tenor", "head").text(1, "t (years)", "head").text(2, "DF", "head");
        s.row(dfFirst).text(0, "today (the anchor)", "label").number(1, 0, "rate").number(2, 1, "rate");
        List<double[]> pts = dfPoints();
        List<String> tenors = in.discount() == null ? List.of() : in.discount().tenors();
        int ti = 0;
        for (int i = 0; i < pts.size(); i++) {
            String tenor = in.discount() == null ? "flat" : "";
            if (in.discount() != null) {
                while (ti < in.discount().t().length && in.discount().t()[ti] <= 0) {
                    ti++;
                }
                tenor = ti < tenors.size() ? tenors.get(ti) : "";
                ti++;
            }
            s.row(dfFirst + 1 + i).text(0, tenor, "label").number(1, pts.get(i)[0], "input.dec").number(2, pts.get(i)[1], "input.rate");
        }
    }

    // ---- Data ------------------------------------------------------------------------------------------------------------------------

    private void data(SheetStream s) {
        s.width(0, 6).width(1, 12).width(2, 10).widths(3, 2 + 2 * Math.max(1, in.sets().size()), 16).freeze(3, 3);
        s.row(1).text(0, "Data: the exposure profile of each netting set as the exposure engine computed it (after collateral), USD", "title");
        s.row(3).text(0, "k", "head").text(1, "Date", "head").text(2, "t", "head");
        for (int j = 0; j < in.sets().size(); j++) {
            s.text(3 + 2 * j, "EE " + in.sets().get(j).id(), "head").text(4 + 2 * j, "ENE " + in.sets().get(j).id(), "head");
        }
        s.row(D0).number(0, 0, "text").text(1, in.date(), "text").number(2, 0, "rate");
        for (int j = 0; j < in.sets().size(); j++) {
            s.number(3 + 2 * j, in.sets().get(j).ce(), "money").number(4 + 2 * j, in.sets().get(j).nce(), "money");
        }
        for (int i = 0; i < k; i++) {
            s.row(D0 + 1 + i).number(0, i + 1, "text").text(1, i < in.dates().size() ? in.dates().get(i) : "", "text").number(2, in.t()[i], "rate");
            for (int j = 0; j < in.sets().size(); j++) {
                s.number(3 + 2 * j, in.sets().get(j).r().ee()[i], "money").number(4 + 2 * j, in.sets().get(j).r().ene()[i], "money");
            }
        }
    }

    // ---- Terms -----------------------------------------------------------------------------------------------------------------------

    private void terms(SheetStream s) {
        s.width(0, 6).width(1, 10).widths(2, 6, 12).widths(SET0, SET0 + 4 * Math.max(1, in.sets().size()), 14).freeze(3, 2);
        s.row(1).text(0, "Terms: per primary date, the discount factor, the spreads, the survival weight, the discounted exposures and the FCA and FBA terms",
                "title");
        s.row(3).text(0, "k", "head").text(1, "t", "head").text(2, "Δt", "head").text(3, "DF", "head").text(4, "s_B", "head").text(5, "s_L", "head")
                .text(6, "S_C·S_B", "head");
        for (int j = 0; j < in.sets().size(); j++) {
            String id = in.sets().get(j).id();
            s.text(SET0 + 4 * j, "EE* " + id, "head").text(SET0 + 4 * j + 1, "ENE* " + id, "head").text(SET0 + 4 * j + 2, "FCA " + id, "head")
                    .text(SET0 + 4 * j + 3, "FBA " + id, "head");
        }
        int nb = borrowLast - borrowFirst + 1;
        int nl = lendLast - lendFirst + 1;
        String bt = range("Config", 1, borrowFirst, borrowLast);
        String bv = range("Config", 2, borrowFirst, borrowLast);
        String lt = range("Config", 1, lendFirst, lendLast);
        String lv = range("Config", 2, lendFirst, lendLast);
        String mid = "Config!$B$" + CH + "=\"mid\"";
        String surv = "Config!$B$" + (CH + 1) + "=\"on\"";
        String fba = "Config!$B$" + (CH + 2) + "=\"on\"";
        FvaMath.Curve lc = in.lend() == null ? in.borrow() : in.lend();
        for (int i = -1; i < k; i++) {
            int r = D0 + 1 + i;
            String x = "$B" + r;
            double tt = i < 0 ? 0 : in.t()[i];
            double df = i < 0 ? 1 : in.total().df()[i];
            double w = i < 0 || !in.survival() || in.total().survival() == null ? 1.0 : in.total().survival()[i];
            s.row(r).number(0, i + 1, "text").formula(1, "Data!$C$" + r, c(tt), "rate");
            s.formula(2, i < 0 ? "0" : "B" + r + "-B" + (r - 1), c(i < 0 ? 0 : in.total().dt()[i]), "rate");
            s.formula(3, logLinear(x), c(df), "rate");
            s.formula(4, linear(x, bt, bv, nb), c(i < 0 ? in.borrow().linear(0) : in.total().sb()[i]), "rate");
            s.formula(5, linear(x, lt, lv, nl), c(i < 0 ? lc.linear(0) : in.total().sl()[i]), "rate");
            s.formula(6, "IF(" + surv + ",EXP(-(Config!$B$" + (CH + 3) + "+Config!$B$" + (CH + 4) + ")*" + x + "),1)", c(w), "rate");
            for (int j = 0; j < in.sets().size(); j++) {
                SetIn si = in.sets().get(j);
                int c = SET0 + 4 * j;
                String ec = col(c);
                String nc = col(c + 1);
                s.formula(c, "Data!" + col(3 + 2 * j) + r + "*$D" + r, c(i < 0 ? si.ce() : si.r().eeStar()[i]), "money");
                s.formula(c + 1, "Data!" + col(4 + 2 * j) + r + "*$D" + r, c(i < 0 ? si.nce() : si.r().eneStar()[i]), "money");
                if (i < 0) {
                    s.formula(c + 2, "0", c(0.0), "money").formula(c + 3, "0", c(0.0), "money");
                    continue;
                }
                String eePt = "IF(" + mid + ",(" + ec + (r - 1) + "+" + ec + r + ")/2," + ec + r + ")";
                String enePt = "IF(" + mid + ",(ABS(" + nc + (r - 1) + ")+ABS(" + nc + r + "))/2,ABS(" + nc + r + "))";
                s.formula(c + 2, "-" + eePt + "*$E" + r + "*$G" + r + "*$C" + r, c(si.r().fcaTerm()[i]), "money");
                s.formula(c + 3, "IF(" + fba + "," + enePt + "*$F" + r + "*$G" + r + "*$C" + r + ",0)", c(si.r().fbaTerm()[i]), "money");
            }
        }
    }

    // ---- Explain, Provenance -----------------------------------------------------------------------------------------------------------

    private void explain(SheetStream s) {
        s.width(0, 44).width(1, 150);
        s.row(1).text(0, com.ash.drishti.common.Branding.current().productOr("") + " · FVA Playground · how each figure is worked out", "title");
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
        Map<String, Object> prov = new LinkedHashMap<>(in.provenance());
        prov.put("Scope", in.scope() + " · " + in.label());
        prov.put("Business date", in.date());
        prov.put("Run and calibration", in.runId() + " · " + (in.calibration().isEmpty() ? "not stated" : in.calibration()));
        prov.put("Flags", in.flags());
        prov.put("Borrowing spread", in.borrow().id() + " · " + in.borrow().source());
        prov.put("Lending spread", in.lend() == null ? "the borrowing spread (symmetric)" : in.lend().id() + " · " + in.lend().source());
        prov.put("Discount curve", in.discount() == null ? "none: the paths are discounted" : in.discount().id() + " · " + in.discount().source());
        prov.put("Sign convention", "the bank's side: FCA ≤ 0 (a cost), FBA ≥ 0 (a benefit), FVA = FCA + FBA; USD");
        prov.put("Exported by", user + " at " + now);
        int row = 3;
        for (Map.Entry<String, Object> en : prov.entrySet()) {
            Object v = en.getValue();
            s.row(row++).text(0, en.getKey(), "label");
            if (v instanceof List<?> list) {
                s.text(1, list.isEmpty() ? "none" : String.join(", ", list.stream().map(String::valueOf).toList()), "text");
            } else {
                s.text(1, v == null ? "" : String.valueOf(v), "text");
            }
        }
    }
}
