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

import com.ash.drishti.server.dist.XlsxWriter;
import com.ash.drishti.server.playground.ExportSizing;
import com.ash.drishti.server.playground.PlaygroundTemplate;
import com.ash.drishti.server.playground.SeriesLayout;
import com.ash.drishti.server.playground.SheetStream;
import com.ash.drishti.server.playground.WorkbookStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * The VaR Playground's workbook (docs/guides/VAR_GUIDE.md section 18.16), generated for the positions chosen: Main (the named
 * inputs, the results beside the engine's with a check each, every confidence, the per-position table, the check band), Config (one row a
 * position), Data (values only: the scenario PVs or P&amp;L as delivered, and Rest of scope), FX (one column a currency), PnL (every cell a
 * formula), Explain and Provenance. Positions are columns while they fit (16,384 columns), rows beyond. Every formula comes from here,
 * checked against the common function set ({@link com.ash.drishti.server.dist.XlsxFormulas}), each with its cached result from
 * {@link VarPlaygroundModel}.
 */
final class VarPlaygroundBook {

    /** The template's style names this workbook uses (a bank's template must keep them). */
    static final List<String> STYLES = List.of("default", "title", "band", "head", "text", "note", "input.money", "input.text", "input.int",
            "input.dec", "input.rate", "money", "money.bold", "int", "pct", "rate", "check", "date", "label", "mark", "total", "band.status",
            "wrap", "label.wrap");
    static final String PASS = "✓";
    static final String FAIL = "✗";
    static final String ALL_PASS = "ALL CHECKS PASS";
    private static final int MAX_COLUMNS = 16_384;
    /** The PnL tab's series (one value a scenario) before the incremental helpers. */
    static final List<String> SERIES = List.of("Portfolio", "Rank", "Mark", "VaR window", "ES tail", "10-day sum", "10-day rank",
            "10-day VaR window", "10-day ES tail");

    /** The engine's own figures, written beside the sheet's (VAR_GUIDE.md section 18.16). Per-item arrays follow the items. */
    record Engine(double var1, double es1, int varScenario, double var10sum, double es10sum, double smoothed, List<double[]> confidences,
            List<double[]> esConfidences, double[] componentVar, double[] componentEs, double[] component10, double[] incremental,
            double diversification) {}

    /** What the export is of, for the title band and Provenance. */
    record About(String scope, String canonical, String path, String date, String user, String now, int scopePositions, int restPositions,
            Map<String, Object> provenance) {}

    private final VarPlaygroundModel m;
    private final Engine e;
    private final About about;
    private final PlaygroundTemplate t;
    private final boolean cached;
    private final int items;
    private final boolean hasRest;
    private final int inc;
    private final SeriesLayout data;
    private final SeriesLayout pnl;
    private final List<String> fxCols;
    private final Map<String, String> names = new LinkedHashMap<>();
    /** The Parametric tab: the engine's parametric figures, and the Parametric tab written after PnL (null: the historical playground). */
    private VarPlaygroundParametric.Engine parametric;

    VarPlaygroundBook parametric(VarPlaygroundParametric.Engine p) {
        this.parametric = p;
        return this;
    }
    private final Map<String, Object> values = new LinkedHashMap<>();

    VarPlaygroundBook(VarPlaygroundModel m, Engine e, About about, PlaygroundTemplate t, boolean cached) {
        this(m, e, about, t, cached, false);
    }

    /** The same, positions laid out as rows whatever their count when {@code rows} (the tests of that layout). */
    VarPlaygroundBook(VarPlaygroundModel m, Engine e, About about, PlaygroundTemplate t, boolean cached, boolean rows) {
        this.m = m;
        this.e = e;
        this.about = about;
        this.t = t;
        this.cached = cached;
        this.hasRest = m.rest != null;
        this.items = m.positions.size() + (hasRest ? 1 : 0);
        this.inc = Math.min(m.set.incremental(), m.positions.size());
        boolean cols = !rows && columnsFit(items, inc);
        List<String> series = new ArrayList<>(SERIES);
        for (int i = 0; i < inc; i++) {
            series.add("Without " + (i + 1));
        }
        SeriesLayout.Orientation o = cols ? SeriesLayout.Orientation.ITEMS_AS_COLUMNS : SeriesLayout.Orientation.ITEMS_AS_ROWS;
        this.data = new SeriesLayout(o, items, m.n, 3, List.of());
        this.pnl = new SeriesLayout(o, items, m.n, 3, series);
        List<String> f = new ArrayList<>();
        m.fx.forEach(x -> f.add(x.currency()));
        this.fxCols = f;
    }

    /** True when positions fit as columns: the PnL tab's label, items, series and incremental helpers within 16,384 columns. */
    static boolean columnsFit(int items, int incremental) {
        return 1 + items + SERIES.size() + incremental <= MAX_COLUMNS;
    }

    /**
     * What a workbook of {@code positions} positions (and Rest of scope) over {@code scenarios} scenarios is, without making it: the counts
     * follow the sheets this class writes.
     */
    static ExportSizing.Estimate estimate(int positions, boolean rest, int scenarios, int currencies, int incremental, int confidences,
            long maxCells) {
        int items = positions + (rest ? 1 : 0);
        int inc = Math.min(incremental, positions);
        boolean cols = columnsFit(items, inc);
        int series = SERIES.size() + inc;
        long values = (long) items * scenarios + (long) (currencies + 1) * (scenarios + 3) + items * 9L + 200;
        long formulas = (long) items * scenarios + (long) series * scenarios + items * 16L + confidences * 9L + 80;
        long comparisons = 2L * scenarios * scenarios + 2L * scenarios * Math.max(0, scenarios - 9) + (long) items * scenarios * 4;
        int columns = cols ? 1 + items + series : 3 + scenarios;
        int rows = Math.max(cols ? 3 + scenarios : 1 + series + items, items + 120);
        return ExportSizing.estimate(positions, cols, columns, rows, values, formulas, comparisons, maxCells);
    }

    // ------------------------------------------------------------------------------------------------------------------- the writing

    void write(OutputStream out) {
        Map<String, String> v = new LinkedHashMap<>(t.brandingValues(com.ash.drishti.common.Branding.current()));
        v.put("TITLE", "VaR Playground · " + about.scope() + " · " + about.date());
        v.put("SUBJECT", "VaR, ES and components worked in formulas, " + m.reporting + ", " + m.n + " scenarios");
        v.put("USER", about.user());
        v.put("NOW", about.now());
        try (WorkbookStream w = new WorkbookStream(out, t, v)) {
            main(w.sheet("Main", "1F3A5F"), w);
            config(w.sheet("Config", "B08A2E"));
            data(w.sheet("Data", "B08A2E"));
            fx(w.sheet("FX", "B08A2E"));
            pnl(w.sheet("PnL", "2E5A88"));
            if (parametric != null) {
                VarPlaygroundParametric.sheet(w.sheet("Parametric", "2E5A88"), w, refs(), parametric);
            }
            explain(w.sheet("Explain", "6B7686"));
            provenance(w.sheet("Provenance", "6B7686"));
            names.forEach((n, ref) -> w.name(n, "Main", ref));
        }
    }

    private Double c(double v) {
        return cached ? v : null;
    }

    private String ct(String s) {
        return cached ? s : null;
    }

    /** The PnL tab's ranges and the items, for the Parametric tab. */
    private VarPlaygroundParametric.Refs refs() {
        return new VarPlaygroundParametric.Refs() {
            public boolean columns() {
                return pnl.itemsAsColumns();
            }

            public boolean cached() {
                return cached;
            }

            public int scenarios() {
                return m.n;
            }

            public int items() {
                return items;
            }

            public String item(int i) {
                return itemId(i);
            }

            public String itemRange(int i) {
                return pnlRange(i);
            }

            public String portfolioRange() {
                return series("Portfolio");
            }

            public String date(int j) {
                return m.dates.get(j).toString();
            }
        };
    }

    // ---- references ----------------------------------------------------------------------------------------------------------------

    private int configRow(int item) {
        return 2 + item;
    }

    private int fxRow(int point) {
        return 4 + point;
    }

    private String fxLast() {
        return XlsxWriter.col(fxCols.size());
    }

    private String fxRep() {
        return XlsxWriter.col(fxCols.size() + 1);
    }

    private String pnlRange(int item) {
        return "PnL!" + pnl.itemRange(item);
    }

    private String series(String s) {
        return "PnL!" + pnl.seriesRange(s);
    }

    private String tenRange(String s) {
        return "PnL!" + pnl.seriesRange(s, 9, m.n - 1);
    }

    private double pnlValue(int item, int j) {
        return item < m.positions.size() ? m.pnl(item, j) : m.rest[j];
    }

    private double[] pnlVector(int item) {
        double[] out = new double[m.n];
        for (int j = 0; j < m.n; j++) {
            out[j] = pnlValue(item, j);
        }
        return out;
    }

    private String itemId(int item) {
        return item < m.positions.size() ? m.positions.get(item).id() : "Rest of scope";
    }

    // ---- Main --------------------------------------------------------------------------------------------------------------------

    /** Main's cells, collected by row and column then written in order (its blocks sit side by side). */
    private final TreeMap<Integer, TreeMap<Integer, Consumer<SheetStream>>> grid = new TreeMap<>();

    private void put(int r, int col, Consumer<SheetStream> cell) {
        grid.computeIfAbsent(r, k -> new TreeMap<>()).put(col, cell);
    }

    private void text(int r, int col, String s, String style) {
        put(r, col, x -> x.text(col, s, style));
    }

    private void num(int r, int col, double v, String style) {
        put(r, col, x -> x.number(col, v, style));
    }

    private void f(int r, int col, String formula, double value, String style) {
        put(r, col, x -> x.formula(col, formula, c(value), style));
    }

    private void ft(int r, int col, String formula, String value, String style) {
        put(r, col, x -> x.formulaText(col, formula, ct(value), style));
    }

    /** A named cell in column B of Main: its name, label and meaning beside it. */
    private void named(int r, String name, String meaning, Consumer<SheetStream> value, Object v) {
        names.put(name, "$B$" + r);
        values.put(name, v);
        text(r, 0, name, "label");
        put(r, 1, value);
        text(r, 2, meaning, "note");
    }

    private void main(SheetStream s, WorkbookStream w) {
        VarPlaygroundModel.Settings st = m.set;
        String title = com.ash.drishti.common.Branding.current().productOr("") + " · VaR Playground · " + about.scope() + " · " + about.date() + " · "
                + m.reporting + " · " + lensLabel();
        text(1, 0, title, "title");
        for (int col = 1; col <= 13; col++) {
            int cc = col;
            put(1, cc, x -> x.blank(cc, "title"));
        }
        String band = (about.path() == null || about.path().isBlank() ? about.canonical() : about.path()) + " · " + m.positions.size() + " of "
                + about.scopePositions() + " positions shown" + (hasRest ? ", " + about.restPositions() + " combined in Rest of scope" : "") + " · "
                + m.n + " scenarios (" + m.dates.get(0) + " to " + m.dates.get(m.n - 1) + ")";
        text(2, 0, band, "band");
        for (int col = 1; col <= 13; col++) {
            int cc = col;
            put(2, cc, x -> x.blank(cc, "band"));
        }
        // inputs (named), column A..C
        int r = 4;
        text(r, 0, "Inputs (named cells)", "head");
        text(r, 1, "Value", "head");
        text(r, 2, "Meaning", "head");
        r++;
        named(r++, "Confidence", "VaR confidence", x -> x.number(1, st.confidence(), "input.dec"), st.confidence());
        named(r++, "EsConfidence", "ES confidence", x -> x.number(1, st.esConfidence(), "input.dec"), st.esConfidence());
        named(r++, "Horizon", "1D or 10D: the headline's horizon", x -> x.text(1, st.horizon(), "input.text"), st.horizon());
        named(r++, "TenDayMethod", "SQRT10 or SUM: the headline's 10-day method", x -> x.text(1, st.method(), "input.text"), st.method());
        String rule = st.interpolated() ? "interpolated" : "kth-worst";
        named(r++, "RankRule", "kth-worst or interpolated", x -> x.text(1, rule, "input.text"), rule);
        named(r++, "ComponentWindow", "scenarios around rank k the components average", x -> x.number(1, st.window(), "input.int"), st.window());
        named(r++, "ScaleToVar", "1: components scaled to add up to VaR; 0: to the smoothed VaR", x -> x.number(1, st.scaleToVar() ? 1 : 0, "input.int"),
                st.scaleToVar() ? 1 : 0);
        named(r++, "ReportingCurrency", "every cross through USD (FX tab)", x -> x.text(1, m.reporting, "input.text"), m.reporting);
        String pf = series("Portfolio");
        named(r++, "ScenarioCount", "COUNT of the portfolio's P&L", x -> x.formula(1, "COUNT(" + pf + ")", c(m.n), "int"), m.n);
        r++;
        text(r, 0, "Derived (named cells)", "head");
        text(r, 1, "Value", "head");
        text(r, 2, "Formula in words", "head");
        r++;
        int afterDerived = derived(r);
        int confRow = Math.max(afterDerived, 4 + 12) + 1;
        int checksRow = confRow + 4 + m.set.confidences().size() + m.set.esConfidences().size();
        tableFirst = checksRow + 11;
        results(4);                                               // column E..H, beside the inputs
        confidences(confRow);
        positions(checks(checksRow));
        s.freeze(2, 0).width(0, 24).width(1, 16).width(2, 30).width(3, 4).width(4, 34).widths(5, 7, 18).width(8, 8).widths(9, 13, 18);
        for (var row : grid.entrySet()) {
            s.row(row.getKey());
            row.getValue().values().forEach(cell -> cell.accept(s));
        }
        s.merge("A1:N1");
        s.merge("A2:N2");
        s.checks("H5:H15", PASS);
        s.checks("I" + confFirst + ":I" + esLast, PASS);
        s.checks("B3", ALL_PASS);
        s.checks("A" + checksFirst + ":A" + checksLast, PASS);
    }

    private String lensLabel() {
        String h = "1D".equals(m.set.horizon()) ? "1-day" : "SUM".equals(m.set.method()) ? "10-day sum" : "10-day √10";
        return h + " " + com.ash.drishti.compute.var.VarLens.pct(m.set.confidence());
    }

    /** The derived named cells: k, the component window, the scale, the interpolation's ranks, the headline 1-day and 10-day figures. */
    private int derived(int r) {
        String pf = series("Portfolio");
        String wv = series("VaR window");
        String we = series("ES tail");
        String t10 = tenRange("10-day sum");
        String r10 = tenRange("10-day rank");
        int n = m.n;
        int n10 = m.n10;
        double pos = n * (1 - m.set.confidence());
        int ilo = Math.max(1, (int) Math.floor(pos));
        int ihi = Math.min(n, ilo + 1);
        double iw = Math.min(1, Math.max(0, pos - ilo));
        double pos10 = n10 * (1 - m.set.confidence());
        int ilo10 = Math.max(1, (int) Math.floor(pos10));
        int ihi10 = Math.min(n10, ilo10 + 1);
        double iw10 = Math.min(1, Math.max(0, pos10 - ilo10));
        d(r++, "VarRank", "MAX(1,MIN(ScenarioCount,ROUNDUP(ScenarioCount*(1-Confidence)-1E-9,0)))", m.k, "int", "k = ⌈N × (1 − Confidence)⌉");
        d(r++, "EsRank", "MAX(1,MIN(ScenarioCount,ROUNDUP(ScenarioCount*(1-EsConfidence)-1E-9,0)))", m.kEs, "int", "k_ES = ⌈N × (1 − EsConfidence)⌉");
        d(r++, "VarLo", "MAX(1,VarRank-ROUNDDOWN(ComponentWindow/2,0))", m.lo, "int", "the component window's first rank");
        d(r++, "VarHi", "MIN(ScenarioCount,VarRank+ROUNDDOWN(ComponentWindow/2,0))", m.hi, "int", "its last rank");
        d(r++, "IPos", "ScenarioCount*(1-Confidence)", pos, "rate", "interpolated rule: the rank as a fraction");
        d(r++, "ILo", "MAX(1,ROUNDDOWN(IPos,0))", ilo, "int", "interpolated rule: the lower rank");
        d(r++, "IHi", "MIN(ScenarioCount,ILo+1)", ihi, "int", "interpolated rule: the upper rank");
        d(r++, "IW", "MIN(1,MAX(0,IPos-ILo))", iw, "rate", "interpolated rule: the upper rank's weight");
        d(r++, "VaROneDay", varOf(pf, "VarRank", "ILo", "IHi", "IW"), m.var1, "money.bold", "−(the k-th worst portfolio P&L)");
        d(r++, "ESOneDay", "-SUMPRODUCT(" + we + "," + pf + ")/EsRank", m.es1, "money.bold", "−(the mean of the k_ES worst)");
        d(r++, "Smoothed", "-SUMPRODUCT(" + wv + "," + pf + ")/(VarHi-VarLo+1)", m.smoothed, "money", "S: −(the mean over the window)");
        d(r++, "Scale", "IF(ScaleToVar=1,IF(ABS(Smoothed)>1E-9,VaROneDay/Smoothed,1),1)", m.scale, "rate", "c = VaR / S");
        d(r++, "TenCount", "COUNT(" + t10 + ")", n10, "int", "10-day windows: N − 9");
        d(r++, "TenRank", "MAX(1,MIN(TenCount,ROUNDUP(TenCount*(1-Confidence)-1E-9,0)))", m.k10, "int", "k over the windows");
        d(r++, "TenEsRank", "MAX(1,MIN(TenCount,ROUNDUP(TenCount*(1-EsConfidence)-1E-9,0)))", m.kEs10, "int", "k_ES over the windows");
        d(r++, "TenLo", "MAX(1,TenRank-ROUNDDOWN(ComponentWindow/2,0))", m.lo10, "int", "the 10-day component window's first rank");
        d(r++, "TenHi", "MIN(TenCount,TenRank+ROUNDDOWN(ComponentWindow/2,0))", m.hi10, "int", "its last rank");
        d(r++, "TenPos", "TenCount*(1-Confidence)", pos10, "rate", "interpolated rule over the windows");
        d(r++, "TenILo", "MAX(1,ROUNDDOWN(TenPos,0))", ilo10, "int", "its lower rank");
        d(r++, "TenIHi", "MIN(TenCount,TenILo+1)", ihi10, "int", "its upper rank");
        d(r++, "TenIW", "MIN(1,MAX(0,TenPos-TenILo))", iw10, "rate", "its upper rank's weight");
        d(r++, "VaRTenSum", varOf(t10, "TenRank", "TenILo", "TenIHi", "TenIW"), m.var10, "money.bold", "VaR over the 10-day sums");
        d(r++, "ESTenSum", "-SUMPRODUCT((" + r10 + "<=TenEsRank)*" + t10 + ")/TenEsRank", m.es10, "money.bold", "ES over the 10-day sums");
        d(r++, "TenSmoothed", "-SUMPRODUCT((" + r10 + ">=TenLo)*(" + r10 + "<=TenHi)*" + t10 + ")/(TenHi-TenLo+1)", m.smoothed10, "money",
                "S over the 10-day window");
        d(r++, "TenScale", "IF(ScaleToVar=1,IF(ABS(TenSmoothed)>1E-9,VaRTenSum/TenSmoothed,1),1)", m.scale10, "rate", "c over the 10-day window");
        return r;
    }

    private void d(int r, String name, String formula, double v, String style, String meaning) {
        named(r, name, meaning, x -> x.formula(1, formula, c(v), style), v);
    }

    /** VaR of a range by the rank rule ({@code RankRule}): the k-th worst, or the interpolated order statistics. */
    private static String varOf(String range, String k, String lo, String hi, String w) {
        return "IF(RankRule=\"interpolated\",-((1-" + w + ")*SMALL(" + range + "," + lo + ")+" + w + "*SMALL(" + range + "," + hi + ")),-SMALL(" + range
                + "," + k + "))";
    }

    /** ES of a range with no rank column: the values below the k-th worst, plus the k-th worst as often as needed to make k. */
    private static String esOf(String range, String k) {
        String kth = "SMALL(" + range + "," + k + ")";
        return "-(SUMPRODUCT((" + range + "<" + kth + ")*" + range + ")+(" + k + "-SUMPRODUCT((" + range + "<" + kth + ")*1))*" + kth + ")/" + k;
    }

    private void results(int r) {
        text(r, 4, "Results", "head");
        text(r, 5, "Sheet", "head");
        text(r, 6, "Engine", "head");
        text(r, 7, "Check", "head");
        r++;
        double head = m.headlineVar();
        double headEs = m.headlineEs();
        double eHead = "1D".equals(m.set.horizon()) ? e.var1() : "SUM".equals(m.set.method()) ? e.var10sum() : e.var1() * VarPlaygroundModel.SQRT10;
        double eHeadEs = "1D".equals(m.set.horizon()) ? e.es1() : "SUM".equals(m.set.method()) ? e.es10sum() : e.es1() * VarPlaygroundModel.SQRT10;
        r = result(r, "VaR " + lensLabel() + " (the headline)", "IF(Horizon=\"1D\",VaROneDay,IF(TenDayMethod=\"SUM\",VaRTenSum,VaROneDay*SQRT(10)))",
                head, eHead, "money.bold");
        r = result(r, "ES " + com.ash.drishti.compute.var.VarLens.pct(m.set.esConfidence()) + " (the headline's horizon)",
                "IF(Horizon=\"1D\",ESOneDay,IF(TenDayMethod=\"SUM\",ESTenSum,ESOneDay*SQRT(10)))", headEs, eHeadEs, "money.bold");
        r = result(r, "VaR 1-day", "VaROneDay", m.var1, e.var1(), "money");
        r = result(r, "ES 1-day", "ESOneDay", m.es1, e.es1(), "money");
        r = result(r, "VaR scenario (index, rank k)", "MATCH(VarRank," + series("Rank") + ",0)", m.varScenario() + 1, e.varScenario() + 1, "int");
        r = result(r, "VaR 10-day √10", "VaROneDay*SQRT(10)", m.var1 * VarPlaygroundModel.SQRT10, e.var1() * VarPlaygroundModel.SQRT10, "money");
        r = result(r, "ES 10-day √10", "ESOneDay*SQRT(10)", m.es1 * VarPlaygroundModel.SQRT10, e.es1() * VarPlaygroundModel.SQRT10, "money");
        r = result(r, "VaR 10-day sum (" + m.n10 + " windows)", "VaRTenSum", m.var10, e.var10sum(), "money");
        r = result(r, "ES 10-day sum", "ESTenSum", m.es10, e.es10sum(), "money");
        r = result(r, "Smoothed VaR (S)", "Smoothed", m.smoothed, e.smoothed(), "money");
        result(r, "Diversification (Σ standalone − VaR)", "SUM(" + col(4) + ")-VaROneDay", m.diversification(), e.diversification(), "money");
    }

    private int tableFirst;

    /** The per-position table's column {@code c} (0-based within the table) from its first to its last item row, absolute. */
    private String col(int c) {
        return "$" + XlsxWriter.col(c) + "$" + (tableFirst) + ":$" + XlsxWriter.col(c) + "$" + (tableFirst + items - 1);
    }

    private int result(int r, String label, String formula, double sheet, double engine, String style) {
        text(r, 4, label, "text");
        f(r, 5, formula, sheet, style);
        num(r, 6, engine, style);
        check(r, 7, "ABS(F" + r + "-G" + r + ")<0.005", Math.abs(sheet - engine) < 0.005);
        return r + 1;
    }

    private void check(int r, int col, String cond, boolean ok) {
        ft(r, col, "IF(" + cond + ",\"" + PASS + "\",\"" + FAIL + "\")", ok ? PASS : FAIL, "check");
    }

    /** Every configured confidence (VaR) and ES confidence, side by side with the engine's; returns the next free row. */
    private int confidences(int r) {
        String pf = series("Portfolio");
        String t10 = tenRange("10-day sum");
        text(r, 0, "Every confidence", "head");
        String[] heads = {"k", "VaR 1-day", "VaR 10-day √10", "VaR 10-day sum", "Engine 1-day", "Engine √10", "Engine sum", "Check"};
        for (int i = 0; i < heads.length; i++) {
            text(r, 1 + i, heads[i], "head");
        }
        r++;
        List<double[]> sheet = m.everyConfidence();
        for (int i = 0; i < sheet.size(); i++, r++) {
            double[] s = sheet.get(i);
            double[] en = e.confidences().get(i);
            num(r, 0, s[0], "input.dec");
            String a = "$A$" + r;
            String k = "MAX(1,MIN(ScenarioCount,ROUNDUP(ScenarioCount*(1-" + a + ")-1E-9,0)))";
            f(r, 1, k, s[1], "int");
            String lo = "MAX(1,ROUNDDOWN(ScenarioCount*(1-" + a + "),0))";
            String w = "MIN(1,MAX(0,ScenarioCount*(1-" + a + ")-" + lo + "))";
            String hi = "MIN(ScenarioCount," + lo + "+1)";
            f(r, 2, varOf(pf, "B" + r, lo, hi, w), s[2], "money");
            f(r, 3, "C" + r + "*SQRT(10)", s[3], "money");
            String k10 = "MAX(1,MIN(TenCount,ROUNDUP(TenCount*(1-" + a + ")-1E-9,0)))";
            String lo10 = "MAX(1,ROUNDDOWN(TenCount*(1-" + a + "),0))";
            String w10 = "MIN(1,MAX(0,TenCount*(1-" + a + ")-" + lo10 + "))";
            String hi10 = "MIN(TenCount," + lo10 + "+1)";
            f(r, 4, varOf(t10, k10, lo10, hi10, w10), s[4], "money");
            num(r, 5, en[1], "money");
            num(r, 6, en[2], "money");
            num(r, 7, en[3], "money");
            boolean ok = Math.abs(s[2] - en[1]) < 0.005 && Math.abs(s[3] - en[2]) < 0.005 && Math.abs(s[4] - en[3]) < 0.005;
            check(r, 8, "AND(ABS(C" + r + "-F" + r + ")<0.005,ABS(D" + r + "-G" + r + ")<0.005,ABS(E" + r + "-H" + r + ")<0.005)", ok);
        }
        confFirst = r - sheet.size();
        confLast = r - 1;
        r++;
        text(r, 0, "Every ES confidence", "head");
        String[] eh = {"k", "ES 1-day", "ES 10-day √10", "ES 10-day sum", "Engine 1-day", "Engine √10", "Engine sum", "Check"};
        for (int i = 0; i < eh.length; i++) {
            text(r, 1 + i, eh[i], "head");
        }
        r++;
        List<double[]> es = m.everyEsConfidence();
        for (int i = 0; i < es.size(); i++, r++) {
            double[] s = es.get(i);
            double[] en = e.esConfidences().get(i);
            num(r, 0, s[0], "input.dec");
            String a = "$A$" + r;
            f(r, 1, "MAX(1,MIN(ScenarioCount,ROUNDUP(ScenarioCount*(1-" + a + ")-1E-9,0)))", s[1], "int");
            f(r, 2, esOf(pf, "B" + r), s[2], "money");
            f(r, 3, "C" + r + "*SQRT(10)", s[3], "money");
            f(r, 4, esOf(t10, "MAX(1,MIN(TenCount,ROUNDUP(TenCount*(1-" + a + ")-1E-9,0)))"), s[4], "money");
            num(r, 5, en[1], "money");
            num(r, 6, en[2], "money");
            num(r, 7, en[3], "money");
            boolean ok = Math.abs(s[2] - en[1]) < 0.005 && Math.abs(s[3] - en[2]) < 0.005 && Math.abs(s[4] - en[3]) < 0.005;
            check(r, 8, "AND(ABS(C" + r + "-F" + r + ")<0.005,ABS(D" + r + "-G" + r + ")<0.005,ABS(E" + r + "-H" + r + ")<0.005)", ok);
        }
        esFirst = r - es.size();
        esLast = r - 1;
        return r + 1;
    }

    private int confFirst;
    private int confLast;
    private int esFirst;
    private int esLast;
    private int checksFirst;
    private int checksLast;

    /** The add-up and count checks (section 8), each a ✓ in column A; returns the per-position table's heading row. */
    private int checks(int r) {
        text(r, 0, "Check", "head");
        text(r, 1, "What is checked", "head");
        put(r, 2, x -> x.blank(2, "head"));
        r++;
        checksFirst = r;
        double sumComp = sumOf(f -> f.componentVar());
        double sumCompEs = sumOf(f -> f.componentEs());
        double sumComp10 = sumOf(f -> f.component10());
        double target = m.set.scaleToVar() ? m.var1 : m.smoothed;
        double target10 = m.set.scaleToVar() ? m.var10 : m.smoothed10;
        check(r, 0, "ABS(SUM(" + col(6) + ")-IF(ScaleToVar=1,VaROneDay,Smoothed))<0.005", Math.abs(sumComp - target) < 0.005);
        text(r++, 1, "Σ component VaR = VaR (to the cent)", "text");
        check(r, 0, "ABS(SUM(" + col(8) + ")-ESOneDay)<0.005", Math.abs(sumCompEs - m.es1) < 0.005);
        text(r++, 1, "Σ component ES = ES", "text");
        check(r, 0, "ABS(SUM(" + col(10) + ")-IF(ScaleToVar=1,VaRTenSum,TenSmoothed))<0.005", Math.abs(sumComp10 - target10) < 0.005);
        text(r++, 1, "Σ component VaR, 10-day sum = VaR, 10-day sum", "text");
        boolean posOk = componentsMatch();
        check(r, 0, "SUMPRODUCT((ABS(" + col(6) + "-" + col(11) + ")>=0.005)*1)+SUMPRODUCT((ABS(" + col(8) + "-" + col(12) + ")>=0.005)*1)+SUMPRODUCT((ABS("
                + col(10) + "-" + col(13) + ")>=0.005)*1)+SUMPRODUCT((ABS(" + col(9) + "-" + col(14) + ")>=0.005)*1)=0", posOk);
        text(r++, 1, "Every position's component VaR, component ES and incremental VaR = the engine's", "text");
        int shown = m.positions.size();
        check(r, 0, "C" + r + "+D" + r + "=E" + r, shown + about.restPositions() == about.scopePositions());
        text(r, 1, "Positions shown + in Rest of scope = the scope's count", "text");
        num(r, 2, shown, "int");
        num(r, 3, about.restPositions(), "int");
        num(r++, 4, about.scopePositions(), "int");
        check(r, 0, "COUNT(Data!" + data.cell(0, 0) + ":" + lastData() + ")=ScenarioCount*" + items, true);
        text(r++, 1, "Every Data column has a value for every scenario", "text");
        check(r, 0, "COUNT(Config!$I$2:$I$" + (1 + items) + ")=" + items, true);
        text(r++, 1, "Every position's currency has an FX column", "text");
        check(r, 0, "SUMPRODUCT(($H$5:$H$15=\"" + PASS + "\")*1)+SUMPRODUCT(($I$" + confFirst + ":$I$" + esLast + "=\"" + PASS + "\")*1)=" + (11 + (confLast
                - confFirst + 1) + (esLast - esFirst + 1)), allEngineOk());
        text(r++, 1, "Every result and confidence = the engine's, to the cent", "text");
        checksLast = r - 1;
        int nChecks = checksLast - checksFirst + 1;
        boolean all = allEngineOk() && posOk && Math.abs(sumComp - target) < 0.005 && Math.abs(sumCompEs - m.es1) < 0.005
                && Math.abs(sumComp10 - target10) < 0.005 && shown + about.restPositions() == about.scopePositions();
        text(3, 0, "Checks", "label");
        ft(3, 1, "IF(SUMPRODUCT(($A$" + checksFirst + ":$A$" + checksLast + "=\"" + PASS + "\")*1)=" + nChecks + ",\"" + ALL_PASS + "\",\"CHECK FAILED\")",
                all ? ALL_PASS : "CHECK FAILED", "band.status");
        text(3, 2, "sheet = engine to the cent · Σ components = VaR and ES · positions shown + Rest of scope = the scope · every scenario · every currency",
                "note");
        return r + 1;
    }

    /** What the Rest of scope column holds, in words. */
    String restSource() {
        return about.restPositions() > 0 ? "the engine's scope P&L less the positions shown, in " + m.reporting
                : "no positions: the scope's figure (summed from the rows as read) less its positions' stored rows, in " + m.reporting;
    }

    private String lastData() {
        return data.cell(items - 1, m.n - 1);
    }

    private double sumOf(java.util.function.ToDoubleFunction<VarPlaygroundModel.Fig> f) {
        double s = m.restFig == null ? 0 : f.applyAsDouble(m.restFig);
        for (VarPlaygroundModel.Fig x : m.figs) {
            s += f.applyAsDouble(x);
        }
        return s;
    }

    private VarPlaygroundModel.Fig fig(int item) {
        return item < m.figs.length ? m.figs[item] : m.restFig;
    }

    private boolean componentsMatch() {
        for (int i = 0; i < items; i++) {
            VarPlaygroundModel.Fig g = fig(i);
            if (Math.abs(g.componentVar() - e.componentVar()[i]) >= 0.005 || Math.abs(g.componentEs() - e.componentEs()[i]) >= 0.005
                    || Math.abs(nz(g.component10()) - nz(e.component10()[i])) >= 0.005 || Math.abs(nz(g.incremental()) - nz(e.incremental()[i])) >= 0.005) {
                return false;
            }
        }
        return true;
    }

    private static double nz(double v) {
        return Double.isNaN(v) ? 0 : v;
    }

    private boolean allEngineOk() {
        if (Math.abs(m.var1 - e.var1()) >= 0.005 || Math.abs(m.es1 - e.es1()) >= 0.005 || Math.abs(m.var10 - e.var10sum()) >= 0.005
                || Math.abs(m.es10 - e.es10sum()) >= 0.005 || m.varScenario() != e.varScenario() || Math.abs(m.smoothed - e.smoothed()) >= 0.005
                || Math.abs(m.diversification() - e.diversification()) >= 0.005) {
            return false;
        }
        List<double[]> a = m.everyConfidence();
        for (int i = 0; i < a.size(); i++) {
            double[] en = e.confidences().get(i);
            if (Math.abs(a.get(i)[2] - en[1]) >= 0.005 || Math.abs(a.get(i)[4] - en[3]) >= 0.005) {
                return false;
            }
        }
        List<double[]> b = m.everyEsConfidence();
        for (int i = 0; i < b.size(); i++) {
            double[] en = e.esConfidences().get(i);
            if (Math.abs(b.get(i)[2] - en[1]) >= 0.005 || Math.abs(b.get(i)[4] - en[3]) >= 0.005) {
                return false;
            }
        }
        return true;
    }

    /** The per-position table: one row a position (and Rest of scope), then the totals. */
    private void positions(int r) {
        String[] heads = {"Position", "Kind", "Currency", "Input form", "Standalone VaR", "Standalone ES", "Component VaR", "% of VaR", "Component ES",
            "Incremental VaR (top " + inc + ")", "Component VaR, 10-day sum", "Engine component VaR", "Engine component ES",
            "Engine component VaR, 10-day sum", "Engine incremental VaR"};
        for (int i = 0; i < heads.length; i++) {
            text(r, i, heads[i], "head");
        }
        r++;
        for (int i = 0; i < items; i++, r++) {
            int item = i;
            boolean isRest = i >= m.positions.size();
            VarPlaygroundModel.Fig g = fig(i);
            String p = pnlRange(i);
            text(r, 0, itemId(i), "text");
            text(r, 1, isRest ? "rest of scope" : m.positions.get(i).kind(), "text");
            text(r, 2, isRest ? m.reporting : m.positions.get(i).currency(), "text");
            text(r, 3, isRest ? "Rest" : m.positions.get(i).form(), "text");
            f(r, 4, varOf(p, "VarRank", "ILo", "IHi", "IW"), g.standaloneVar(), "money");
            f(r, 5, esOf(p, "EsRank"), g.standaloneEs(), "money");
            f(r, 6, "-Scale*SUMPRODUCT(" + series("VaR window") + "," + p + ")/(VarHi-VarLo+1)", g.componentVar(), "money");
            f(r, 7, "IF(VaROneDay=0,0,G" + r + "/VaROneDay)", m.var1 == 0 ? 0 : g.componentVar() / m.var1, "pct");
            f(r, 8, "-SUMPRODUCT(" + series("ES tail") + "," + p + ")/EsRank", g.componentEs(), "money");
            if (!isRest && i < inc) {
                String x = "PnL!" + pnl.seriesRange("Without " + (i + 1));
                f(r, 9, "VaROneDay-(" + varOf(x, "VarRank", "ILo", "IHi", "IW") + ")", g.incremental(), "money");
                num(r, 14, e.incremental()[item], "money");
            }
            f(r, 10, "-TenScale*SUMPRODUCT(" + series("10-day VaR window") + "," + p + ")/(TenHi-TenLo+1)", g.component10(), "money");
            num(r, 11, e.componentVar()[item], "money");
            num(r, 12, e.componentEs()[item], "money");
            num(r, 13, e.component10()[item], "money");
        }
        text(r, 0, "Total", "label");
        for (int c : new int[] {4, 5, 6, 8, 10, 11, 12, 13}) {
            double tot = 0;
            for (int i = 0; i < items; i++) {
                VarPlaygroundModel.Fig g = fig(i);
                tot += switch (c) {
                    case 4 -> g.standaloneVar();
                    case 5 -> g.standaloneEs();
                    case 6 -> g.componentVar();
                    case 8 -> g.componentEs();
                    case 10 -> g.component10();
                    case 11 -> e.componentVar()[i];
                    case 12 -> e.componentEs()[i];
                    default -> e.component10()[i];
                };
            }
            f(r, c, "SUM(" + col(c) + ")", tot, "total");
        }
        f(r, 7, "SUM(" + col(7) + ")", m.var1 == 0 ? 0 : sumOf(x -> x.componentVar()) / m.var1, "pct");
    }

    // ---- Config, Data, FX ----------------------------------------------------------------------------------------------------------

    private void config(SheetStream s) {
        String[] heads = {"Position", "Kind", "Currency", "Base PV (own currency)", "Base FX (USD per unit)", "Input form", "Source", "Value today (USD)",
            "FX column"};
        s.freeze(1, 1).width(0, 30).width(1, 12).width(2, 9).widths(3, 4, 18).width(5, 10).width(6, 30).width(7, 18).width(8, 9);
        s.row(1);
        for (int i = 0; i < heads.length; i++) {
            s.text(i, heads[i], "head");
        }
        String hdr = "FX!$B$1:$" + fxLast() + "$1";
        for (int i = 0; i < items; i++) {
            int r = configRow(i);
            s.row(r);
            boolean isRest = i >= m.positions.size();
            VarPlaygroundModel.Pos p = isRest ? null : m.positions.get(i);
            String ccy = isRest ? m.reporting : p.currency();
            double base = isRest ? 0 : p.basePv();
            double bfx = isRest ? 1 : p.baseFx();
            s.text(0, itemId(i), "text");
            s.text(1, isRest ? "rest (" + about.restPositions() + " positions)" : p.kind(), "input.text");
            s.text(2, ccy, "input.text");
            s.number(3, base, "input.money");
            s.number(4, bfx, "input.rate");
            s.text(5, isRest ? "Rest" : p.form(), "input.text");
            s.text(6, isRest ? restSource() : p.source(), "input.text");
            s.formula(7, "D" + r + "*E" + r, c(base * bfx), "money");
            s.formula(8, "MATCH(C" + r + "," + hdr + ",0)", c(fxCols.indexOf(ccy) + 1), "int");
        }
    }

    private void data(SheetStream s) {
        s.freeze(data.itemsAsColumns() ? 3 : 1, data.itemsAsColumns() ? 1 : 3).width(0, 22);
        if (data.itemsAsColumns()) {
            s.widths(1, Math.min(items, 16_000), 16);
        } else {
            s.widths(1, 2, 10).widths(3, Math.min(2 + m.n, 16_000), 14);
        }
        data.emit(s, new SeriesLayout.Cells() {
            @Override
            public void headTitle(SheetStream x, int col, int line) {
                x.text(col, new String[] {"Position", "Currency", "Input form"}[line], "head");
            }

            @Override
            public void itemHead(SheetStream x, int col, int item, int line) {
                boolean isRest = item >= m.positions.size();
                String v = switch (line) {
                    case 0 -> itemId(item);
                    case 1 -> isRest ? m.reporting : m.positions.get(item).currency();
                    default -> isRest ? "Rest" : m.positions.get(item).form();
                };
                x.text(col, v, line == 0 ? "head" : "note");
            }

            @Override
            public void pointLabel(SheetStream x, int col, int point) {
                x.text(col, (point + 1) + " · " + m.dates.get(point), data.itemsAsColumns() ? "text" : "head");
            }

            @Override
            public void seriesTitle(SheetStream x, int col, String series) {}

            @Override
            public void item(SheetStream x, int col, int item, int point) {
                x.number(col, item < m.positions.size() ? m.positions.get(item).data()[point] : m.rest[point], "input.money");
            }

            @Override
            public void series(SheetStream x, int col, String series, int point) {}
        });
    }

    private void fx(SheetStream s) {
        s.freeze(3, 1).width(0, 22).widths(1, fxCols.size() + 1, 13);
        s.row(1);
        s.text(0, "Scenario", "head");
        for (int i = 0; i < fxCols.size(); i++) {
            s.text(1 + i, fxCols.get(i), "head");
        }
        s.text(1 + fxCols.size(), "Reporting (" + m.reporting + ")", "head");
        String hdr = "$B$1:$" + fxLast() + "$1";
        s.row(2);
        s.text(0, "Today (base), USD per unit", "label");
        for (int i = 0; i < fxCols.size(); i++) {
            s.number(1 + i, m.fx.get(i).base(), "input.rate");
        }
        s.formula(1 + fxCols.size(), "INDEX($B$2:$" + fxLast() + "$2,MATCH(ReportingCurrency," + hdr + ",0))", c(m.rate(m.reporting, -1)), "rate");
        s.row(3);
        s.text(0, "Source", "label");
        for (int i = 0; i < fxCols.size(); i++) {
            s.text(1 + i, m.fx.get(i).source(), "note");
        }
        for (int j = 0; j < m.n; j++) {
            int r = fxRow(j);
            s.row(r);
            s.text(0, (j + 1) + " · " + m.dates.get(j), "text");
            for (int i = 0; i < fxCols.size(); i++) {
                s.number(1 + i, m.fx.get(i).rates()[j], "input.rate");
            }
            s.formula(1 + fxCols.size(), "INDEX($B$" + r + ":$" + fxLast() + "$" + r + ",MATCH(ReportingCurrency," + hdr + ",0))", c(m.rate(m.reporting, j)),
                    "rate");
        }
    }

    // ---- PnL ---------------------------------------------------------------------------------------------------------------------

    /** A position's P&amp;L cell: USD from its input form, then into the reporting currency (section 7.1, 7.2). */
    String pnlFormula(int item, int j) {
        String d = "Data!" + data.cell(item, j);
        if (item >= m.positions.size()) {
            return d;
        }
        return pnlFormula(d, String.valueOf(configRow(item)), String.valueOf(fxRow(j)));
    }

    /** The P&amp;L formula with its Data cell ({@code Data!B4}), Config row and FX row given as text (the screen's template: tokens). */
    String pnlFormula(String d, String cr, String fr) {
        String rate = "INDEX(FX!$B$" + fr + ":$" + fxLast() + "$" + fr + ",Config!$I$" + cr + ")";
        String today = "Config!$H$" + cr;
        String usd = "IF(Config!$F$" + cr + "=\"PV\"," + d + "*" + rate + "-" + today + ",IF(Config!$C$" + cr + "=\"USD\"," + d + "," + d + "*" + rate + "))";
        return "(" + today + "+" + usd + ")/FX!$" + fxRep() + "$" + fr + "-" + today + "/FX!$" + fxRep() + "$2";
    }

    /**
     * The formulas the screen's formula bar shows (positions as columns, the screen's layout), with the first position and scenario in
     * place: {@code pnl} the first position's first scenario; the others name {@code {P}} for a position's PnL range and {@code {X}} for its
     * incremental helper range, which the screen fills in.
     */
    Map<String, String> formulaTemplates() {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("pnl", pnlFormula("Data!{D}", "{C}", "{F}"));
        f.put("pnlCell", "PnL!" + pnl.cell(0, 0));
        f.put("portfolio", "SUM(" + pnl.across(0, 0, items - 1) + ")");
        f.put("standaloneVar", varOf("{P}", "VarRank", "ILo", "IHi", "IW"));
        f.put("standaloneEs", esOf("{P}", "EsRank"));
        f.put("componentVar", "-Scale*SUMPRODUCT(" + series("VaR window") + ",{P})/(VarHi-VarLo+1)");
        f.put("componentEs", "-SUMPRODUCT(" + series("ES tail") + ",{P})/EsRank");
        f.put("incrementalVar", "VaROneDay-(" + varOf("{X}", "VarRank", "ILo", "IHi", "IW") + ")");
        f.put("component10", "-TenScale*SUMPRODUCT(" + series("10-day VaR window") + ",{P})/(TenHi-TenLo+1)");
        f.put("var", varOf(series("Portfolio"), "VarRank", "ILo", "IHi", "IW"));
        f.put("es", "-SUMPRODUCT(" + series("ES tail") + "," + series("Portfolio") + ")/EsRank");
        f.put("k", "MAX(1,MIN(ScenarioCount,ROUNDUP(ScenarioCount*(1-Confidence)-1E-9,0)))");
        f.put("rank", "SUMPRODUCT((" + series("Portfolio") + "<PnL!" + pnl.seriesCell("Portfolio", 0) + ")*1)+1");
        f.put("dataFirst", "Data!" + data.cell(0, 0));
        f.put("pnlFirstColumn", XlsxWriter.col(pnl.itemCol(0, 0)));
        f.put("pnlFirstRow", String.valueOf(pnl.itemRow(0, 0)));
        f.put("seriesFirstColumn", XlsxWriter.col(pnl.seriesCol("Portfolio", 0)));
        return f;
    }

    /** A position's PnL range as the sheet's formulas name it ({@code PnL!$B$4:$B$512}). */
    String pnlRangeOf(int item) {
        return pnlRange(item);
    }

    /** A position's incremental helper range. */
    String helperRangeOf(int item) {
        return "PnL!" + pnl.seriesRange("Without " + (item + 1));
    }

    private void pnl(SheetStream s) {
        s.freeze(pnl.itemsAsColumns() ? 3 : 1 + pnl.series().size(), pnl.itemsAsColumns() ? 1 : 3).width(0, 22);
        if (pnl.itemsAsColumns()) {
            s.widths(1, Math.min(pnl.columns() - 1, 16_000), 16);
        } else {
            s.widths(1, 2, 10).widths(3, Math.min(2 + m.n, 16_000), 14);
        }
        String pfAll = pnl.seriesRange("Portfolio");
        String t10All = pnl.seriesRange("10-day sum", 9, m.n - 1);
        pnl.emit(s, new SeriesLayout.Cells() {
            @Override
            public void headTitle(SheetStream x, int col, int line) {
                x.text(col, new String[] {"Position", "Currency", "Input form"}[line], "head");
            }

            @Override
            public void itemHead(SheetStream x, int col, int item, int line) {
                boolean isRest = item >= m.positions.size();
                String v = switch (line) {
                    case 0 -> itemId(item);
                    case 1 -> m.reporting;
                    default -> isRest ? "Rest" : m.positions.get(item).form();
                };
                x.text(col, v, line == 0 ? "head" : "note");
            }

            @Override
            public void pointLabel(SheetStream x, int col, int point) {
                x.text(col, (point + 1) + " · " + m.dates.get(point), pnl.itemsAsColumns() ? "text" : "head");
            }

            @Override
            public void seriesTitle(SheetStream x, int col, String series) {
                String t = series.startsWith("Without ") ? "Without " + itemId(Integer.parseInt(series.substring(8)) - 1) : series;
                x.text(col, t, "head");
            }

            @Override
            public void item(SheetStream x, int col, int item, int point) {
                x.formula(col, pnlFormula(item, point), c(pnlValue(item, point)), "money");
            }

            @Override
            public void series(SheetStream x, int col, String series, int j) {
                String pf = pnl.seriesCell("Portfolio", j);
                switch (series) {
                    case "Portfolio" -> x.formula(col, "SUM(" + pnl.across(j, 0, items - 1) + ")", c(m.portfolio[j]), "money.bold");
                    case "Rank" -> {
                        String f = "SUMPRODUCT((" + pfAll + "<" + pf + ")*1)" + (j == 0 ? "" : "+SUMPRODUCT((" + pnl.seriesRange("Portfolio", 0, j - 1) + "=" + pf + ")*1)")
                                + "+1";
                        x.formula(col, f, c(m.rank[j]), "int");
                    }
                    case "Mark" -> {
                        String rk = pnl.seriesCell("Rank", j);
                        String mark = m.rank[j] == m.k ? "V" : m.rank[j] <= m.kEs ? "E" : "";
                        x.formulaText(col, "IF(" + rk + "=VarRank,\"V\",IF(" + rk + "<=EsRank,\"E\",\"\"))", mark, "mark");
                    }
                    case "VaR window" -> {
                        String rk = pnl.seriesCell("Rank", j);
                        x.formula(col, "(" + rk + ">=VarLo)*(" + rk + "<=VarHi)", c(m.wv[j]), "int");
                    }
                    case "ES tail" -> x.formula(col, "(" + pnl.seriesCell("Rank", j) + "<=EsRank)*1", c(m.we[j]), "int");
                    case "10-day sum" -> {
                        if (j >= 9) {
                            x.formula(col, "SUM(" + pnl.seriesRange("Portfolio", j - 9, j) + ")", c(m.t10[j - 9]), "money");
                        }
                    }
                    case "10-day rank" -> {
                        if (j >= 9) {
                            String t = pnl.seriesCell("10-day sum", j);
                            String f = "SUMPRODUCT((" + t10All + "<" + t + ")*1)" + (j == 9 ? "" : "+SUMPRODUCT((" + pnl.seriesRange("10-day sum", 9, j - 1) + "=" + t
                                    + ")*1)") + "+1";
                            x.formula(col, f, c(m.r10[j - 9]), "int");
                        }
                    }
                    case "10-day VaR window" -> {
                        String rr = pnl.seriesRange("10-day rank", Math.max(j, 9), Math.min(j + 9, m.n - 1));
                        x.formula(col, "SUMPRODUCT((" + rr + ">=TenLo)*(" + rr + "<=TenHi))", c(m.w10v[j]), "int");
                    }
                    case "10-day ES tail" -> {
                        String rr = pnl.seriesRange("10-day rank", Math.max(j, 9), Math.min(j + 9, m.n - 1));
                        x.formula(col, "SUMPRODUCT((" + rr + "<=TenEsRank)*1)", c(m.w10e[j]), "int");
                    }
                    default -> {
                        int i = Integer.parseInt(series.substring(8)) - 1;
                        x.formula(col, pf + "-" + pnl.cell(i, j), c(m.portfolio[j] - pnlValue(i, j)), "money");
                    }
                }
            }
        });
    }

    // ---- Explain, Provenance -------------------------------------------------------------------------------------------------------

    private void explain(SheetStream s) {
        s.width(0, 44).width(1, 150);
        s.row(1);
        s.text(0, com.ash.drishti.common.Branding.current().productOr("") + " · VaR Playground · how each figure is worked out", "title");
        s.blank(1, "title");
        Map<String, String> refs = new LinkedHashMap<>(names);
        refs.replaceAll((k, v) -> "Main!" + v.replace("$", ""));
        refs.put("PnLFirst", "PnL!" + pnl.cell(0, 0));
        refs.put("PortfolioFirst", "PnL!" + pnl.seriesCell("Portfolio", 0));
        refs.put("RankFirst", "PnL!" + pnl.seriesCell("Rank", 0));
        refs.put("VaR1", refs.get("VaROneDay"));
        refs.put("ES1", refs.get("ESOneDay"));
        refs.put("K", refs.get("VarRank"));
        refs.put("KEs", refs.get("EsRank"));
        refs.put("VaR10Sum", refs.get("VaRTenSum"));
        refs.put("VaR10Sqrt", "Main!F" + 10);
        Map<String, Object> vals = new LinkedHashMap<>(values);
        vals.put("VaR1", m.var1);
        vals.put("ES1", m.es1);
        vals.put("K", m.k);
        vals.put("KEs", m.kEs);
        vals.put("VaR10Sum", m.var10);
        vals.put("VaR10Sqrt", m.var1 * VarPlaygroundModel.SQRT10);
        int r = 3;
        for (String[] step : t.explain()) {
            s.row(r++);
            s.text(0, fill(step[0], vals, refs), "label.wrap");
            s.text(1, fill(step[1], vals, refs), "wrap");
        }
    }

    /** A step's text with {@code {Name:v}} its value and {@code {Name}} its cell. */
    private static String fill(String text, Map<String, Object> vals, Map<String, String> refs) {
        for (Map.Entry<String, Object> v : vals.entrySet()) {
            text = text.replace("{" + v.getKey() + ":v}", shown(v.getValue()));
        }
        for (Map.Entry<String, String> ref : refs.entrySet()) {
            text = text.replace("{" + ref.getKey() + "}", ref.getValue());
        }
        return text;
    }

    private static String shown(Object v) {
        if (v instanceof Double d) {
            return d == Math.rint(d) && Math.abs(d) < 1e12 ? String.format(Locale.ROOT, "%,d", d.longValue()) : String.format(Locale.ROOT, "%,.2f", d);
        }
        if (v instanceof Integer i) {
            return String.format(Locale.ROOT, "%,d", i);
        }
        return String.valueOf(v);
    }

    private void provenance(SheetStream s) {
        s.width(0, 34).width(1, 120);
        s.row(1);
        s.text(0, "Provenance", "title");
        s.blank(1, "title");
        int r = 3;
        for (Map.Entry<String, Object> en : about.provenance().entrySet()) {
            Object v = en.getValue();
            if (v instanceof List<?> list) {
                s.row(r++);
                s.text(0, en.getKey(), "label");
                s.text(1, list.isEmpty() ? "none" : list.size() + "", "text");
                for (Object o : list) {
                    s.row(r++);
                    s.text(1, String.valueOf(o), "text");
                }
                continue;
            }
            s.row(r++);
            s.text(0, en.getKey(), "label");
            s.text(1, v == null ? "" : String.valueOf(v), "text");
        }
    }
}
