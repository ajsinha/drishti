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
package com.ash.drishti.server.dist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.server.bi.report.ReportParser;
import com.ash.drishti.server.bi.report.StatementSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The statement layout on its own (docs/guides/REPORTS.md section 3): page breaks before and after a band, the column titles repeated on every page a
 * table runs onto, keep-together groups and bands, the header and footer with the page count, cells cut at the paper's edge, the page limit; and the period
 * a statement covers. Writes {@code target/statement-sample.pdf} (a picture to look at; nothing reads it back).
 */
class StatementLayoutTest {

    static StatementSpec spec(String yaml) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (yaml != null) {
            ReportParser.Result r = ReportParser.parse("report: x\ndataset: d\nstatement: " + yaml + "\npages:\n  - id: p\n    layout: statement\n    visuals: []\n");
            m.putAll(r.report().statement());
        }
        return StatementSpec.of(m, "A4");
    }

    static StatementLayout.Table table(int rows, boolean grouped) {
        List<List<String>> out = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            out.add(List.of("DESK-" + (grouped ? (char) ('A' + i / 30) : 'A'), String.valueOf(10_000 + i), "BOOK-" + (i % 4), String.format("%,.2f", 1_000_000.0 * (i % 7 + 1))));
        }
        return new StatementLayout.Table(List.of("DESK", "TRADE_ID", "PORTFOLIO", "NOTIONAL"), out, new boolean[] {false, false, false, true}, grouped ? 0 : -1);
    }

    static StatementLayout.Band text(String id, String s, String brk) {
        return new StatementLayout.Band("text", id, "", s, true, null, null, null, 0, 1, brk, false, true);
    }

    static StatementLayout.Band card(String id, String label, String value, double x) {
        return new StatementLayout.Band("card", id, label, null, false, value, null, null, x, 1 / 3.0, "none", false, true);
    }

    static StatementLayout.Band tableBand(String id, StatementLayout.Table t, String brk, boolean keep, boolean repeat) {
        return new StatementLayout.Band("table", id, "Positions", null, false, null, null, t, 0, 1, brk, keep, repeat);
    }

    static StatementPages lay(StatementSpec spec, List<List<StatementLayout.Band>> rows) {
        return new StatementLayout(spec, 50).lay(List.of(rows), Map.of("title", "Monthly statement", "period", "September 2026", "reader", "rita", "run-date", "2026-10-01"));
    }

    @Test
    void aLongTableRunsOverPagesWithItsColumnTitlesOnEachAndTheFooterCountsThem() throws Exception {
        StatementPages p = lay(spec(null), List.of(List.of(text("intro", "Positions of rita", "none")),
                List.of(card("a", "Notional", "28,000,000.00", 0), card("b", "MTM", "-75,000.00", 1 / 3.0), card("c", "Trades", "120", 2 / 3.0)),
                List.of(tableBand("t", table(120, true), "none", false, true)), List.of(text("end", "End of statement", "before"))));
        int n = p.pages().size();
        assertThat(n).isGreaterThanOrEqualTo(4);
        for (StatementPages.Page page : p.pages()) {
            assertThat(page.texts("header")).contains("Monthly statement", "September 2026");
            assertThat(page.texts("footer")).contains("Page " + page.number() + " of " + n);
            if (!page.texts("cell").isEmpty()) {
                assertThat(page.texts("head")).as("page " + page.number()).containsExactly("TRADE_ID", "PORTFOLIO", "NOTIONAL");
            }
        }
        assertThat(p.pages().get(n - 1).texts("text")).as("pageBreak: before starts a new page").containsExactly("End of statement");
        assertThat(p.pages().get(n - 1).texts("cell")).isEmpty();
        Files.write(Path.of("target/statement-sample.pdf"), StatementPdf.write(p, "Monthly statement"));
    }

    @Test
    void aGroupThatFitsOnAPageIsNeverSplitAndAKeepTogetherTableMovesWhole() {
        StatementPages p = lay(spec(null), List.of(List.of(text("intro", "x\nx\nx\nx\nx\nx\nx\nx\nx\nx\nx\nx\nx\nx\nx\nx\nx\nx\nx\nx\nx\nx\nx\nx\nx\nx", "none")),
                List.of(tableBand("t", table(90, true), "none", false, true))));
        for (StatementPages.Page page : p.pages()) {
            List<String> cells = page.texts("cell");
            List<String> groups = page.texts("group");
            // a group heading on a page is followed by the whole group of 30 rows (4 cells each), never part of one
            assertThat(cells.size() % (30 * 3)).as("page " + page.number() + " holds whole groups: " + groups).isZero();
        }
        StatementPages kept = lay(spec(null), List.of(List.of(text("intro", "x\nx\nx\nx\nx\nx\nx\nx\nx\nx\nx\nx\nx\nx\nx\nx\nx\nx\nx\nx\nx\nx\nx\nx\nx\nx\nx\nx\nx\nx", "none")),
                List.of(tableBand("t", table(20, false), "none", true, true))));
        assertThat(kept.pages()).hasSize(2);
        assertThat(kept.pages().get(0).texts("cell")).isEmpty();
        assertThat(kept.pages().get(1).texts("cell")).hasSize(20 * 4);
    }

    @Test
    void withoutRepeatedHeadersTheNextPageStartsWithRowsAndALongCellIsCutAtThePaper() {
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            rows.add(List.of("A very long counterparty name that goes on and on and on, far wider than any column of the paper could hold, " + i, String.valueOf(i)));
        }
        StatementLayout.Table t = new StatementLayout.Table(List.of("COUNTERPARTY", "N"), rows, new boolean[] {false, true}, -1);
        StatementPages p = lay(spec("{ paper: A5, margins: 10 }"), List.of(List.of(tableBand("t", t, "none", false, false))));
        assertThat(p.width()).isCloseTo(419.53, org.assertj.core.data.Offset.offset(0.01));
        assertThat(p.pages().size()).isGreaterThan(1);
        assertThat(p.pages().get(1).texts("head")).isEmpty();
        assertThat(p.pages().get(0).texts("cell").get(0)).endsWith("…");
    }

    /** QA3B-01's matrix: MTM by desk and seven currencies, figures of a billion and more, on A4 portrait. */
    static StatementLayout.Table currencyMatrix(int currencies, double scale) {
        List<String> ccy = List.of("AUD", "CAD", "CHF", "EUR", "GBP", "JPY", "USD", "NOK", "SEK", "SGD", "HKD", "NZD").subList(0, currencies);
        List<String> headers = new ArrayList<>(List.of("DESK"));
        headers.addAll(ccy);
        List<List<String>> rows = new ArrayList<>();
        String[] desks = {"DESK-COMMODITIES", "DESK-RATES", "DESK-CREDIT", "DESK-FX", "DESK-EQUITY-DERIVATIVES"};
        double[] base = {291_648_023.10, -428_350_581.27, -1_196_646_912.55, 3_617_068_619.77, -87_004_118.40};
        for (int d = 0; d < desks.length; d++) {
            List<String> row = new ArrayList<>(List.of(desks[d]));
            for (int c = 0; c < ccy.size(); c++) {
                row.add(String.format(java.util.Locale.ROOT, "%,.2f", base[(d + c) % base.length] * scale * (1 + c / 10.0)));
            }
            rows.add(row);
        }
        boolean[] numeric = new boolean[headers.size()];
        java.util.Arrays.fill(numeric, 1, numeric.length, true);
        return new StatementLayout.Table(headers, rows, numeric, -1);
    }

    static StatementLayout.Band matrixBand(StatementLayout.Table t) {
        return new StatementLayout.Band("table", "m", "MTM by desk and currency", null, false, null, null, t, 0, 1, "none", false, true);
    }

    static StatementLayout.Band wideCard(String label, String value) {
        return new StatementLayout.Band("card", "n", label, null, false, value, null, null, 0, 4 / 12.0, "none", false, true);
    }

    /** Every figure the table holds, and the figures the pages drew. */
    static List<String> figures(StatementLayout.Table t) {
        List<String> out = new ArrayList<>();
        t.rows().forEach(r -> out.addAll(r.subList(1, r.size())));
        return out;
    }

    static List<String> drawnFigures(StatementPages p) {
        List<String> out = new ArrayList<>();
        p.pages().forEach(page -> page.texts("cell").stream().filter(s -> s.matches("[-0-9,.…]+") && s.matches(".*\\d.*")).forEach(out::add));
        return out;
    }

    @Test
    void aSevenCurrencyMatrixAndABigCardOnA4PortraitDrawEveryFigureWhole() {
        // QA3B-01: -428,350,58… read a thousand times smaller than the real figure; a figure is now never cut
        StatementSpec spec = spec("{ paper: A4, orientation: portrait, fontSize: 9, margins: { top: 15, right: 12, bottom: 15, left: 12 } }");
        StatementLayout.Table m = currencyMatrix(7, 1);
        StatementLayout layout = new StatementLayout(spec, 50);
        StatementPages p = layout.lay(List.of(List.of(List.of(wideCard("Notional", "3,617,068,619,773.12"), card("b", "MTM", "-75,000.00", 4 / 12.0),
                card("c", "Trades", "120", 8 / 12.0)), List.of(matrixBand(m)))), Map.of());
        List<String> drawn = drawnFigures(p);
        assertThat(drawn).as("no figure ends in an ellipsis").noneMatch(s -> s.contains("…"));
        assertThat(drawn).containsExactlyInAnyOrderElementsOf(figures(m));
        List<String> values = p.pages().get(0).texts("value");
        assertThat(values.get(0)).doesNotContain("…");
        List<String> exact = p.pages().get(0).texts("exact");
        assertThat(values.get(0).equals("3,617,068,619,773.12") || exact.contains("Exactly 3,617,068,619,773.12"))
                .as("the card reads the whole figure, or compact units with the exact figure under it: " + values + " " + exact).isTrue();
        assertThat(layout.cut()).as("nothing was cut, so nothing says so").isEmpty();
        assertThat(String.join(" ", p.pages().get(0).texts("note"))).doesNotContain("Cut:");
    }

    @Test
    void aMatrixTooWideForThePaperGoesOnInPartsWithItsRowLabelsAndACompactCard() {
        StatementSpec spec = spec("{ paper: A4, orientation: portrait, fontSize: 9, margins: { top: 15, right: 12, bottom: 15, left: 12 } }");
        StatementLayout.Table m = currencyMatrix(12, 1000);                // twelve currencies, figures of a trillion
        StatementLayout layout = new StatementLayout(spec, 50);
        StatementPages p = layout.lay(List.of(List.of(List.of(new StatementLayout.Band("card", "n", "Notional", null, false, "3,617,068,619,773.12", null, null, 0, 2 / 12.0, "none",
                false, true), card("b", "MTM", "1", 2 / 12.0)),
                List.of(matrixBand(m)))), Map.of());
        List<String> drawn = drawnFigures(p);
        assertThat(drawn).noneMatch(s -> s.contains("…"));
        assertThat(drawn).containsExactlyInAnyOrderElementsOf(figures(m));
        List<String> titles = new ArrayList<>();
        p.pages().forEach(page -> titles.addAll(page.texts("title")));
        assertThat(titles).anyMatch(s -> s.startsWith("MTM by desk and currency (part 2 of "));
        List<String> labels = new ArrayList<>();
        p.pages().forEach(page -> page.texts("cell").stream().filter(s -> s.startsWith("DESK-")).forEach(labels::add));
        assertThat(labels.size()).as("each part repeats the row labels").isGreaterThanOrEqualTo(10);
        assertThat(p.pages().get(0).texts("value").get(0)).as("a narrow card: compact units").isEqualTo("3.62tn");
        assertThat(p.pages().get(0).texts("exact")).as("the exact figure under it, whole").hasSize(1).allMatch(s -> s.endsWith("3,617,068,619,773.12"));
        assertThat(String.join(" ", p.pages().get(0).texts("note"))).contains("the table is in").contains("parts");
        assertThat(layout.cut()).isEmpty();
    }

    @Test
    void aFigureWiderThanThePaperItselfIsCutWithANoteOnThePageAndInTheCutList() {
        String huge = "1" + ",234".repeat(40) + ".00";
        StatementLayout.Table t = new StatementLayout.Table(List.of("DESK", "MTM"), List.of(List.of("DESK-FX", huge)), new boolean[] {false, true}, -1);
        StatementLayout layout = new StatementLayout(spec("{ paper: A5, margins: 10 }"), 50);
        StatementPages p = layout.lay(List.of(List.of(List.of(matrixBand(t)))), Map.of());
        assertThat(drawnFigures(p)).allMatch(s -> s.endsWith("…"));
        assertThat(String.join(" ", p.pages().get(0).texts("note"))).contains("Cut: a figure is wider than the paper allows");
        assertThat(layout.cut()).containsExactly("MTM by desk and currency: " + StatementLayout.CUT_NOTE);
    }

    @Test
    void figuresInCompactUnits() {
        assertThat(StatementFit.compact("3,617,068,619,773.12")).isEqualTo("3.62tn");
        assertThat(StatementFit.compact("-428,350,581.27")).isEqualTo("-428.4m");
        assertThat(StatementFit.compact("1,196,646,912.55")).isEqualTo("1.20bn");
        assertThat(StatementFit.compact("12,500")).isEqualTo("12.5k");
        assertThat(StatementFit.compact("999.50")).isEqualTo("999.50");
        assertThat(StatementFit.compact("DESK-FX")).isEqualTo("DESK-FX");
        assertThat(StatementFit.isNumber("-1,234.50")).isTrue();
        assertThat(StatementFit.isNumber("•••")).isFalse();
        assertThat(List.of(StatementService.pages(1), StatementService.pages(2))).as("QA3B-09: the mail subject and the audit").containsExactly("1 page", "2 pages");
    }

    @Test
    void aStatementLongerThanThePageLimitIsRefusedNotCut() {
        assertThatThrownBy(() -> new StatementLayout(spec(null), 2).lay(List.of(List.of(List.of(tableBand("t", table(400, false), "none", false, true)))), Map.of()))
                .isInstanceOf(DrishtiException.class).hasMessageContaining("more than 2 pages");
    }

    /** QA4-03: a note wraps over as many lines as it needs, never cut with an ellipsis, a word wider than a line broken where it must. */
    @Test
    void aNoteWrapsWholeAndIsNeverCut() {
        String note = "Not available: DRS-9020 the as-of date 2026-10-25 is later than the business date 2026-10-20; no data is held for it";
        List<String> lines = StatementLayout.wrapWhole(note, 120, 8, false);
        assertThat(lines).hasSizeGreaterThan(2).noneMatch(l -> l.contains("…"));
        assertThat(String.join(" ", lines)).isEqualTo(note);
        lines.forEach(l -> assertThat(StatementLayout.Metrics.width(l, 8, false)).isLessThanOrEqualTo(120));
        List<String> path = StatementLayout.wrapWhole("see /a/very/long/folder/name/that/is/wider/than/the/card/itself.parquet", 60, 8, false);
        assertThat(path).noneMatch(l -> l.contains("…"));
        assertThat(String.join("", path).replace(" ", "")).isEqualTo("see/a/very/long/folder/name/that/is/wider/than/the/card/itself.parquet");
        path.forEach(l -> assertThat(StatementLayout.Metrics.width(l, 8, false)).isLessThanOrEqualTo(60));
    }

    @Test
    void thePeriodAStatementCoversAndItsTokens() {
        LocalDate d = LocalDate.of(2026, 10, 1);
        assertThat(StatementSpec.period("previous-month", d)).isEqualTo(new StatementSpec.Period(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), "September 2026"));
        assertThat(StatementSpec.period("quarter", d).label()).isEqualTo("2026-Q4");
        assertThat(StatementSpec.period("previous-quarter", d).from()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(StatementSpec.period("none", d).label()).isEqualTo("2026-10-01");
        assertThat(StatementSpec.fill("Page {page} of {pages} · {nope}", Map.of("page", "2", "pages", "5"))).isEqualTo("Page 2 of 5 · {nope}");
        assertThat(StatementService.excel(Map.of("left", "{title} & co", "right", "Page {page} of {pages}"), Map.of("title", "Q&A")))
                .isEqualTo("&LQ&&A && co&RPage &P of &N");
    }
}
