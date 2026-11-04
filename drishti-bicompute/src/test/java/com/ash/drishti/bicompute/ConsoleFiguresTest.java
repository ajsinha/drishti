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
package com.ash.drishti.bicompute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import com.ash.drishti.bicompute.cache.StateCache;
import com.ash.drishti.bicompute.core.PivotCore;
import com.ash.drishti.bicompute.core.PivotView;
import com.ash.drishti.bicompute.rows.RowsProvider;
import com.ash.drishti.bicompute.spi.PivotProvider;
import com.ash.drishti.bicompute.spi.Viewer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The browser computes nothing (2.9.11.b, ONE_GRAMMAR.md section 5): the figures the console used to add up itself are the pivot engine's,
 * asked through {@code POST /api/v1/pivot/inline} (drishti-console/core/engine_figures.py) with the rows the console read. Each case here
 * runs the very path the endpoint runs (a {@link RowsProvider} of the rows, {@link PivotCore} with the default settings) on the console
 * tests' fixtures and proves the engine gives the figures the console showed before: the trading landing's MTM, P&amp;L, DV01, by book and
 * the largest counterparty's share (trading_fake.py), the market landing's CDS median, its move and a move's sigma (market_fake.py), a
 * column filter's average (test_colfilter_browser.py), the facts' sum and fund overlap (test_home_facts.py), and the pivot chart's
 * diversification at every column (the gap the engine sends, never added up in the browser).
 */
class ConsoleFiguresTest {

    private static PivotProvider.Field f(String name, String type) {
        return new PivotProvider.Field(name, type, name, "");
    }

    /** What PivotController.inline does with a body: the rows as a provider, the default engine, the view over source "inline". */
    private static Map<String, Object> inline(List<PivotProvider.Field> fields, List<Object[]> rows, Map<String, Object> view) {
        Map<String, Object> spec = new LinkedHashMap<>(view);
        spec.put("source", "inline");
        PivotCore core = new PivotCore(List.of(new RowsProvider("inline", "Rows", fields, rows)), StateCache.none(), PivotCore.Settings.defaults());
        return core.query(Viewer.of("rita"), PivotView.parse(spec));
    }

    @SuppressWarnings("unchecked")
    private static Double v(Map<String, Object> answer, String cell, String as) {
        Map<String, Object> c = ((Map<String, Map<String, Object>>) answer.get("cells")).get(cell);
        Object x = c == null ? null : ((Map<String, Object>) c.get("values")).get(as);
        return x == null ? null : ((Number) x).doubleValue();
    }

    private static Map<String, Object> m(String measure, String as) {
        return Map.of("measure", measure, "as", as);
    }

    /** trading_fake.py: A. Shah's eight trades (i = 0, 1, 3, 4, 6, 7, 9, 10), as trading_landing.figure_rows sends them. */
    private static List<Object[]> tradingRows() {
        String[] books = {"BOOK-RATES-1", "BOOK-RATES-2", "BOOK-RATES-3"};
        String[] cps = {"CP-ALDERSHOT", "CP-NORTHPLAINS", "CP-MERIDIAN"};
        List<Object[]> rows = new ArrayList<>();
        for (int i : new int[] {0, 1, 3, 4, 6, 7, 9, 10}) {
            double mtm = 1_000_000.0 * (i + 1) * (i % 2 == 0 ? 1 : -1);
            double before = mtm - 10_000.0 * (i + 1);
            boolean positive = mtm > 0;
            rows.add(new Object[] {books[i % 3], "TRDR-ASHAH", positive ? cps[i % 3] : null, mtm, mtm - before, -1000.0 * (i + 1),
                positive ? mtm : null});
        }
        return rows;
    }

    private static final List<PivotProvider.Field> TRADING = List.of(f("BOOK", "string"), f("TRADER", "string"), f("CP", "string"),
            f("MTM", "number"), f("CHANGE", "number"), f("DV01", "number"), f("POS", "number"));

    @Test
    void theTradingLandingsTotalsAreTheEnginesAndEqualWhatTheConsoleShowed() {
        Map<String, Object> a = inline(TRADING, tradingRows(), Map.of("rows", List.of("BOOK"), "columns", List.of("TRADER"),
                "values", List.of(m("sum(MTM)", "mtm"), m("sum(CHANGE)", "pnl"), m("sum(DV01)", "dv01"), m("count(DV01)", "dv01Trades"))));
        assertThat(v(a, "*|*", "mtm")).isEqualTo(0.0);
        assertThat(v(a, "*|*", "pnl")).isEqualTo(480_000.0);
        assertThat(v(a, "*|*", "dv01")).isEqualTo(-48_000.0);
        assertThat(v(a, "*|*", "dv01Trades")).isEqualTo(8.0);
        assertThat(v(a, "BOOK=BOOK-RATES-1|*", "pnl")).isEqualTo(220_000.0);
        assertThat(v(a, "BOOK=BOOK-RATES-2|*", "pnl")).isEqualTo(260_000.0);
        assertThat(v(a, "BOOK=BOOK-RATES-1|*", "mtm")).isEqualTo(-6_000_000.0);
        assertThat(v(a, "BOOK=BOOK-RATES-2|*", "mtm")).isEqualTo(6_000_000.0);
        assertThat(v(a, "*|TRADER=TRDR-ASHAH", "pnl")).isEqualTo(480_000.0);

        Map<String, Object> c = inline(TRADING, tradingRows(), Map.of("rows", List.of("CP"), "top", Map.of("CP", Map.of("n", 1, "by", "positive")),
                "values", List.of(m("sum(POS)", "positive"), Map.of("measure", "sum(POS)", "as", "share", "showAs", "pct-grand"))));
        assertThat(v(c, "CP=CP-NORTHPLAINS|*", "positive")).isEqualTo(16_000_000.0);
        assertThat(v(c, "CP=CP-NORTHPLAINS|*", "share")).isCloseTo(16.0 / 24.0, offset(1e-12));
        assertThat(v(c, "*|*", "positive")).isEqualTo(24_000_000.0);
    }

    @Test
    void theMarketLandingsMedianItsMoveAndSigmaAreTheEngines() {
        List<Object[]> spreads = List.of(new Object[] {60.0}, new Object[] {70.0}, new Object[] {300.0});
        assertThat(v(inline(List.of(f("S", "number")), spreads, Map.of("values", List.of(m("median(S)", "median")))), "*|*", "median"))
                .isEqualTo(70.0);
        List<Object[]> moves = List.of(new Object[] {58.0, 60.0}, new Object[] {69.0, 70.0}, new Object[] {296.0, 300.0});
        assertThat(v(inline(List.of(f("FROM", "number"), f("TO", "number")), moves,
                Map.of("values", List.of(m("median(TO) - median(FROM)", "delta")))), "*|*", "delta")).isCloseTo(1.0, offset(1e-12));

        List<Object[]> rows = new ArrayList<>();
        double[] hist = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        for (double h : hist) {
            rows.add(new Object[] {"ir-curve/CRV-EUR-OIS", h, null});
        }
        rows.add(new Object[] {"ir-curve/CRV-EUR-OIS", null, -31.0});
        Map<String, Object> s = inline(List.of(f("ITEM", "string"), f("H", "number"), f("LAST", "number")), rows, Map.of("rows", List.of("ITEM"),
                "values", List.of(m("stddev_pop(H)", "sd"), m("count(H)", "days"), m("abs(sum(LAST)) / stddev_pop(H)", "sigma"))));
        double mean = Arrays.stream(hist).average().orElseThrow();
        double pstdev = Math.sqrt(Arrays.stream(hist).map(x -> (x - mean) * (x - mean)).sum() / hist.length);   // statistics.pstdev, as before
        assertThat(v(s, "ITEM=ir-curve/CRV-EUR-OIS|*", "sd")).isCloseTo(pstdev, offset(1e-12));
        assertThat(v(s, "ITEM=ir-curve/CRV-EUR-OIS|*", "days")).isEqualTo(10.0);
        assertThat(v(s, "ITEM=ir-curve/CRV-EUR-OIS|*", "sigma")).isCloseTo(31.0 / pstdev, offset(1e-9));
    }

    @Test
    void aColumnFiltersAverageAndTheFactsSumAndOverlapAreTheEngines() {
        List<Object[]> col = List.of(new Object[] {1000.0}, new Object[] {250_000.0}, new Object[] {5_000_000.0}, new Object[] {-12_000.0});
        assertThat(v(inline(List.of(f("V", "number")), col, Map.of("values", List.of(m("avg(V)", "avg")))), "*|*", "avg")).isEqualTo(1_309_750.0);

        List<Object[]> pct = List.of(new Object[] {60.5}, new Object[] {70.25});
        assertThat(v(inline(List.of(f("V", "number")), pct, Map.of("values", List.of(m("sum(V)", "v")))), "*|*", "v")).isEqualTo(130.75);

        // overlap(a, b, 'K', 'W') with a = x 3, y 2, x 1 and b = x 2, z 9: the keys both hold (x), each side's lines (facts_expr.overlap_rows)
        List<Object[]> lines = List.of(new Object[] {"x", 3.0, null}, new Object[] {"x", 1.0, null}, new Object[] {"x", null, 2.0});
        Map<String, Object> perKey = inline(List.of(f("KEY", "string"), f("A", "number"), f("B", "number")), lines,
                Map.of("rows", List.of("KEY"), "values", List.of(m("least(sum(A), sum(B))", "m"))));
        assertThat(v(perKey, "KEY=x|*", "m")).isEqualTo(2.0);
        List<Object[]> smaller = new ArrayList<>();
        smaller.add(new Object[] {v(perKey, "KEY=x|*", "m")});
        assertThat(v(inline(List.of(f("V", "number")), smaller, Map.of("values", List.of(m("sum(V)", "v")))), "*|*", "v")).isEqualTo(2.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theDiversificationOfAHolisticMeasureIsSentForEveryColumn() {
        List<Object[]> rows = new ArrayList<>();
        String[] desks = {"A", "B"};
        String[] ccys = {"USD", "EUR"};
        for (int i = 0; i < 40; i++) {
            rows.add(new Object[] {desks[i % 2], ccys[(i / 2) % 2], (double) ((i * 37) % 101)});
        }
        Map<String, Object> a = inline(List.of(f("DESK", "string"), f("CCY", "string"), f("X", "number")), rows,
                Map.of("rows", List.of("DESK"), "columns", List.of("CCY"), "values", List.of(m("median(X)", "Median"))));
        Map<String, Object> grand = ((List<Map<String, Object>>) a.get("rows")).stream().filter(r -> "grand".equals(r.get("kind"))).findFirst()
                .orElseThrow();
        Map<String, Map<String, Object>> byColumn = (Map<String, Map<String, Object>>) grand.get("diversificationByColumn");
        assertThat(byColumn).containsKeys("CCY=USD", "CCY=EUR", "*");
        for (String col : List.of("CCY=USD", "CCY=EUR", "*")) {
            double parts = v(a, "DESK=A|" + col, "Median") + v(a, "DESK=B|" + col, "Median");
            assertThat(((Number) byColumn.get(col).get("Median")).doubleValue()).as(col)
                    .isCloseTo(parts - v(a, "*|" + col, "Median"), offset(1e-9));
        }
        assertThat(grand.get("diversification")).isEqualTo(byColumn.get("*"));      // the column total's, as before
    }
}
