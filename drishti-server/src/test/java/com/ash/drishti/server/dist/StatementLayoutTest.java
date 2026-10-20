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
 * The statement layout on its own (docs/guides/STATEMENTS.md section 3): page breaks before and after a band, the column titles repeated on every page a
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

    @Test
    void aStatementLongerThanThePageLimitIsRefusedNotCut() {
        assertThatThrownBy(() -> new StatementLayout(spec(null), 2).lay(List.of(List.of(List.of(tableBand("t", table(400, false), "none", false, true)))), Map.of()))
                .isInstanceOf(DrishtiException.class).hasMessageContaining("more than 2 pages");
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
