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
package com.ash.drishti.server.bi.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.engine.QueryRequest;
import com.ash.drishti.server.bi.report.ReportParser.Problem;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Streaming BI in the report model (RUPAKA.md section 12): the Perspective visuals and the rolling chart, what they ask of the engine and what the validator refuses. */
class LiveVisualsTest {

    static final ObjectMapper JSON = new ObjectMapper();

    static VisualQueries.Built build(String page, String visual, String state, String dataset, String report) throws IOException {
        Report r = ReportParser.parse(ReportParserTest.report(report)).report();
        Dataset ds = ReportParserTest.dataset(dataset);
        Report.Page p = r.page(page);
        return VisualQueries.build(r, p, p.visual(visual), ReportState.of(JSON.readTree(state)), ds, BiReportProperties.defaults(), (f, n, by, desc, ctx, from, to) -> List.of());
    }

    static List<Problem> problems(String yaml, String dataset) throws IOException {
        ReportParser.Result r = ReportParser.parse(yaml);
        List<Problem> all = new java.util.ArrayList<>(r.problems());
        if (r.report() != null) {
            all.addAll(ReportValidator.check(r.report(), ReportParserTest.dataset(dataset)));
        }
        return all;
    }

    @Test
    void theSampleLiveReportsAreCleanAndRoundTrip() throws IOException {
        for (String name : List.of("live-trading-desk", "live-exposure")) {
            String text = ReportParserTest.report(name);
            ReportParser.Result r = ReportParser.parse(text);
            assertThat(r.ok()).as(name + ": " + r.firstError()).isTrue();
            String dataset = r.report().dataset();
            assertThat(ReportValidator.validate(r.report(), ReportParserTest.dataset(dataset))).as(name).isEmpty();
            assertThat(ReportParser.parse(ReportYaml.write(r.report().toMap())).report().toMap()).as(name + " is written back as it was read").isEqualTo(r.report().toMap());
        }
    }

    @Test
    void aPerspectiveGridAsksForTheTablesRowsWithTheKeyAndTheChangeCounterOfALiveTable() throws IOException {
        QueryRequest q = build("grid", "all-trades", "{}", "live-trading", "live-trading-desk").query();
        assertThat(q.measures()).as("Perspective pivots the rows in the browser: the server sends rows, not aggregates").isEmpty();
        assertThat(q.fields()).startsWith("trade.TRADE_ID", "trade.DESK").endsWith("trade.id", "trade.updateSeq");
        assertThat(q.filters()).isEmpty();
        assertThat(q.limit()).isEqualTo(20_000);
        QueryRequest pivot = build("pivot", "desk-currency", "{}", "live-trading", "live-trading-desk").query();
        assertThat(pivot.fields()).containsExactlyInAnyOrder("trade.DESK", "trade.CURRENCY", "trade.MTM", "trade.NOTIONAL", "trade.id", "trade.updateSeq").endsWith("trade.id", "trade.updateSeq");
    }

    @Test
    void sinceAsksOnlyForTheRowsThatChangedAfterTheCounterTheClientHolds() throws IOException {
        QueryRequest q = build("grid", "all-trades", "{\"since\":41}", "live-trading", "live-trading-desk").query();
        assertThat(q.filters()).containsExactly(new QueryRequest.Filter("trade.updateSeq", "gt", List.of(41L)));
        assertThat(q.limit()).isEqualTo(20_000);
        assertThat(build("grid", "all-trades", "{\"limit\":50}", "live-trading", "live-trading-desk").query().limit()).as("a client may ask for fewer rows, never more").isEqualTo(50);
        assertThat(build("grid", "all-trades", "{\"since\":\"x\"}", "live-trading", "live-trading-desk").query().filters()).as("a since that is not a number is not used").isEmpty();
    }

    @Test
    void aTableThatIsNotLiveHasNoChangeCounterAndIgnoresSince() throws IOException {
        String yaml = """
                report: bench
                dataset: trades-basic
                pages:
                  - id: g
                    visuals:
                      - { id: g, type: perspective-grid, at: { x: 0, y: 0, w: 12, h: 8 }, wells: { columns: [trade.DESK, trade.MTM] } }
                """;
        Report r = ReportParser.parse(yaml).report();
        VisualQueries.Built b = VisualQueries.build(r, r.page("g"), r.page("g").visual("g"), ReportState.of(JSON.readTree("{\"since\":5}")), ReportParserTest.dataset("trades-basic"),
                BiReportProperties.defaults(), (f, n, by, desc, ctx, from, to) -> List.of());
        assertThat(b.query().fields()).containsExactly("trade.DESK", "trade.MTM", "trade.id");
        assertThat(b.query().filters()).isEmpty();
    }

    @Test
    void theRollingChartAsksForItsMeasuresAloneAndMayHaveNoAxis() throws IOException {
        QueryRequest q = build("desk", "rolling", "{}", "live-trading", "live-trading-desk").query();
        assertThat(q.fields()).isEmpty();
        assertThat(q.measures()).containsExactly("MTM", "Losses");
    }

    @Test
    void whatIsWrongWithAPerspectiveVisualIsRefusedWithItsCodeAndPath() throws IOException {
        String head = "report: bad\ndataset: live-trading\npages:\n  - id: p\n    visuals:\n";
        List<Problem> across = problems(head + "      - { id: g, type: perspective-grid, title: T, at: { x: 0, y: 0, w: 12, h: 8 }, wells: { columns: [trade.DESK] }, format: { aggregates: { trade.DESK: total } } }\n",
                "live-trading");
        assertThat(across).anyMatch(p -> p.error() && p.code().equals("DRS-9031") && p.path().endsWith("aggregates.trade.DESK"));
        assertThat(across).anyMatch(p -> p.error() && p.code().equals("DRS-9032") && p.message().contains("is not an aggregate"));
        List<Problem> part = problems(head + "      - { id: g, type: perspective-grid, title: T, at: { x: 0, y: 0, w: 12, h: 8 }, wells: { columns: [trade.updatedAt.month] } }\n", "live-trading");
        assertThat(part).anyMatch(p -> p.error() && p.code().equals("DRS-9032") && p.message().contains("date part"));
        List<Problem> measure = problems(head + "      - { id: g, type: perspective-grid, title: T, at: { x: 0, y: 0, w: 12, h: 8 }, wells: { columns: [MTM] } }\n", "live-trading");
        assertThat(measure).anyMatch(p -> p.error() && p.code().equals("DRS-9032") && p.message().contains("is a measure"));
        List<Problem> empty = problems(head + "      - { id: p, type: perspective-pivot, title: T, at: { x: 0, y: 0, w: 12, h: 8 }, wells: { rows: [trade.DESK] } }\n", "live-trading");
        assertThat(empty).anyMatch(p -> p.error() && p.code().equals("DRS-9032") && p.path().endsWith("wells.values"));
    }

    @Test
    void theLiveColumnsAreNamesTheModelAcceptsOnLiveTablesOnly() throws IOException {
        String live = "report: r\ndataset: live-trading\npages:\n  - id: p\n    visuals:\n      - { id: t, type: table, title: T, at: { x: 0, y: 0, w: 6, h: 4 }, wells: { columns: [trade.updatedAt, trade.updateSeq, MTM] } }\n";
        assertThat(problems(live, "live-trading").stream().filter(Problem::error)).isEmpty();
        String cached = live.replace("dataset: live-trading", "dataset: trades-basic");
        assertThat(problems(cached, "trades-basic")).anyMatch(p -> p.error() && p.message().contains("updatedAt"));
    }

    @Test
    void theRollingChartWindowIsBoundedAndALiveVisualNeedsATitleOrAlt() throws IOException {
        String head = "report: r\ndataset: live-trading\npages:\n  - id: p\n    visuals:\n";
        assertThat(problems(head + "      - { id: c, type: live-chart, title: C, at: { x: 0, y: 0, w: 6, h: 4 }, wells: { values: [MTM] }, format: { window: 2 } }\n", "live-trading"))
                .anyMatch(p -> p.error() && p.path().endsWith("format.window"));
        assertThat(problems(head + "      - { id: c, type: live-chart, title: C, at: { x: 0, y: 0, w: 6, h: 4 }, wells: { values: [MTM] }, format: { window: 600 } }\n", "live-trading").stream().filter(Problem::error)).isEmpty();
        assertThat(problems(head + "      - { id: g, type: perspective-grid, at: { x: 0, y: 0, w: 6, h: 4 }, wells: { columns: [trade.DESK] } }\n", "live-trading"))
                .anyMatch(p -> p.level().equals("warning") && p.code().equals("A11Y"));
        assertThat(VisualCatalogue.type("perspective-pivot").wells()).containsOnlyKeys("rows", "columns", "values");
        assertThat(VisualCatalogue.RESERVED).doesNotContain("live-chart", "perspective-grid");
    }
}
