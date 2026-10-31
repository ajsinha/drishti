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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.engine.QueryRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** One visual becomes one dataset query, under the page's slicers, selections, drill path and carried values (BI_DESIGN.md › Rūpaka: the original design and every phase as built, section 8). */
class VisualQueriesTest {

    static final ObjectMapper JSON = new ObjectMapper();
    final List<String> topCalls = new ArrayList<>();

    final VisualQueries.TopValues top = (field, n, by, desc, context, from, to) -> {
        topCalls.add(field + " " + n + " " + by + " " + (desc ? "desc" : "asc") + " with " + context.size() + " filters");
        return List.of("CP-1", "CP-2");
    };

    static Report report(String name) throws IOException {
        return ReportParser.parse(ReportParserTest.report(name)).report();
    }

    static ReportState state(String json) throws IOException {
        return ReportState.of(JSON.readTree(json));
    }

    VisualQueries.Built build(Report r, String page, String visual, String stateJson, String dataset) throws IOException {
        Dataset ds = ReportParserTest.dataset(dataset);
        Report.Page p = r.page(page);
        return VisualQueries.build(r, p, p.visual(visual), state(stateJson), ds, BiReportProperties.defaults(), top);
    }

    @Test
    void aCardIsOneMeasureAndNoFields() throws IOException {
        QueryRequest q = build(report("trades-overview"), "overview", "mtm", "{}", "trades-basic").query();
        assertThat(q.fields()).isEmpty();
        assertThat(q.measures()).containsExactly("MTM");
        assertThat(q.filters()).isEmpty();
        assertThat(q.orderBy()).isEmpty();
    }

    @Test
    void aBarGroupsByItsAxisAndIsRankedByItsFirstMeasure() throws IOException {
        QueryRequest q = build(report("trades-overview"), "overview", "by-desk", "{}", "trades-basic").query();
        assertThat(q.fields()).containsExactly("trade.DESK");
        assertThat(q.measures()).containsExactly("MTM");
        assertThat(q.orderBy()).containsExactly(new QueryRequest.Sort("MTM", true));
        assertThat(q.limit()).isEqualTo(500);
    }

    @Test
    void aDateAxisIsInDateOrderAndATableAsksForItsFieldsAndMeasures() throws IOException {
        Report r = report("trades-overview");
        assertThat(build(r, "overview", "by-date", "{}", "trades-basic").query().orderBy()).containsExactly(new QueryRequest.Sort("trade.TRADE_DATE", false));
        QueryRequest t = build(r, "overview", "rows", "{}", "trades-basic").query();
        assertThat(t.fields()).containsExactly("trade.DESK", "trade.CURRENCY");
        assertThat(t.measures()).containsExactly("Trades", "Notional", "MTM");
        assertThat(t.orderBy()).as("the engine orders a table, and never by a masked column").isEmpty();
        assertThat(t.limit()).isEqualTo(1000);
        QueryRequest m = build(r, "products", "matrix", "{}", "trades-basic").query();
        assertThat(m.fields()).containsExactly("trade.DESK", "trade.CURRENCY");
    }

    @Test
    void aSlicerInScopeFiltersTheOthersButNeverItself() throws IOException {
        Report r = report("trades-overview");
        String s = "{\"slicers\":{\"currency\":[\"USD\",\"EUR\"]}}";
        QueryRequest bar = build(r, "overview", "by-desk", s, "trades-basic").query();
        assertThat(bar.filters()).containsExactly(new QueryRequest.Filter("trade.CURRENCY", "in", List.of("USD", "EUR")));
        QueryRequest self = build(r, "overview", "currency", s, "trades-basic").query();
        assertThat(self.filters()).isEmpty();
        assertThat(self.fields()).containsExactly("trade.CURRENCY");
        assertThat(self.measures()).isEmpty();
        assertThat(self.limit()).isEqualTo(1000);
        // a page-scope slicer does not filter another page; a report-scope one does
        assertThat(build(r, "products", "share", s, "trades-basic").query().filters())                        // only the donut's own top-12 filter: the currency slicer is not of this page
                .containsExactly(new QueryRequest.Filter("trade.PRODUCT_TYPE", "in", List.of("CP-1", "CP-2")));
        Report c = report("cashflow-ladder");
        assertThat(build(c, "legs", "legs-count", "{\"slicers\":{\"source-system\":[\"ENDUR\"]}}", "trading-cashflows").query().filters())
                .containsExactly(new QueryRequest.Filter("trades.SOURCE_SYSTEM", "in", List.of("ENDUR")));
    }

    @Test
    void aRangeSlicerBecomesTwoComparisons() throws IOException {
        Report r = ReportParser.parse("""
                report: r
                dataset: trades-basic
                pages:
                  - id: p
                    visuals:
                      - { id: n, type: slicer, at: { x: 0, y: 0, w: 3, h: 2 }, wells: { field: [trade.NOTIONAL] }, format: { mode: range } }
                      - { id: c, type: card, at: { x: 3, y: 0, w: 3, h: 2 }, wells: { values: [MTM] } }
                """).report();
        QueryRequest q = build(r, "p", "c", "{\"slicers\":{\"n\":{\"from\":1000000,\"to\":3000000}}}", "trades-basic").query();
        assertThat(q.filters()).containsExactly(new QueryRequest.Filter("trade.NOTIONAL", "ge", List.of(1000000L)), new QueryRequest.Filter("trade.NOTIONAL", "le", List.of(3000000L)));
    }

    @Test
    void aMarkSelectedOnAnotherVisualFiltersThisOneButNotItself() throws IOException {
        Report r = report("trades-overview");
        String s = "{\"selections\":[{\"visual\":\"by-desk\",\"field\":\"trade.DESK\",\"values\":[\"DESK-FX\"]}]}";
        assertThat(build(r, "overview", "by-date", s, "trades-basic").query().filters()).containsExactly(new QueryRequest.Filter("trade.DESK", "in", List.of("DESK-FX")));
        assertThat(build(r, "overview", "by-desk", s, "trades-basic").query().filters()).isEmpty();
        assertThat(build(r, "overview", "currency", s, "trades-basic").query().filters()).as("a slicer is not cross-filtered").isEmpty();
        assertThat(build(r, "overview", "by-date", s, "trades-basic").highlight()).isNull();
    }

    @Test
    void highlightKeepsTheWholeAnswerAndAddsTheHighlightedOne() throws IOException {
        Report r = ReportParser.parse("""
                report: r
                dataset: trades-basic
                pages:
                  - id: p
                    visuals:
                      - { id: a, type: bar, at: { x: 0, y: 0, w: 6, h: 4 }, wells: { axis: [trade.DESK], values: [MTM] } }
                      - { id: b, type: column, at: { x: 6, y: 0, w: 6, h: 4 }, wells: { axis: [trade.CURRENCY], values: [MTM] } }
                      - { id: c, type: line, at: { x: 0, y: 4, w: 6, h: 4 }, wells: { axis: [trade.TRADE_DATE], values: [MTM] } }
                      - { id: d, type: column, interacts: false, at: { x: 6, y: 4, w: 6, h: 4 }, wells: { axis: [trade.PORTFOLIO], values: [MTM] } }
                """).report();
        String s = "{\"selections\":[{\"visual\":\"a\",\"field\":\"trade.DESK\",\"values\":[\"DESK-FX\"],\"highlight\":true}]}";
        VisualQueries.Built b = build(r, "p", "b", s, "trades-basic");
        assertThat(b.query().filters()).isEmpty();
        assertThat(b.highlight().filters()).containsExactly(new QueryRequest.Filter("trade.DESK", "in", List.of("DESK-FX")));
        VisualQueries.Built line = build(r, "p", "c", s, "trades-basic");
        assertThat(line.highlight()).as("since 2.4.0 a line draws a highlight too").isNotNull();
        assertThat(line.query().filters()).isEmpty();
        Report asTable = ReportParser.parse(ReportYaml.write(r.toMap()).replace("type: line", "type: table").replace("axis: [trade.TRADE_DATE], values: [MTM]", "columns: [trade.TRADE_DATE, MTM]")).report();
        VisualQueries.Built table = build(asTable, "p", "c", s, "trades-basic");
        assertThat(table.highlight()).as("a table cannot draw a highlight, so it is filtered").isNull();
        assertThat(table.query().filters()).hasSize(1);
        VisualQueries.Built off = build(r, "p", "d", s, "trades-basic");
        assertThat(off.query().filters()).as("interacts: false").isEmpty();
        assertThat(off.highlight()).isNull();
    }

    @Test
    void drillingDownMovesTheAxisDownTheHierarchyAndFiltersByTheValuesClicked() throws IOException {
        Report r = report("trades-overview");
        QueryRequest q = build(r, "overview", "by-desk", "{\"drill\":[\"DESK-FX\"]}", "trades-basic").query();
        assertThat(q.fields()).containsExactly("trade.PORTFOLIO");
        assertThat(q.filters()).containsExactly(new QueryRequest.Filter("trade.DESK", "eq", List.of("DESK-FX")));
        assertThatThrownBy(() -> build(r, "overview", "by-desk", "{\"drill\":[\"DESK-FX\",\"BOOK-1\"]}", "trades-basic")).isInstanceOf(DrishtiException.class)
                .hasMessageContaining("nothing below trade.PORTFOLIO");
        assertThatThrownBy(() -> build(r, "overview", "by-date", "{\"drill\":[\"x\"]}", "trades-basic")).hasMessageContaining("does not drill down");
        // a date hierarchy starts where the axis starts
        QueryRequest y = build(report("cashflow-ladder"), "ladder", "by-pay-date", "{\"drill\":[2027,3]}", "trading-cashflows").query();
        assertThat(y.fields()).containsExactly("cashflows.PAYMENT_DATE.month");
        assertThat(y.filters()).containsExactly(new QueryRequest.Filter("cashflows.PAYMENT_DATE", "not-null", List.of()), new QueryRequest.Filter("cashflows.PAYMENT_DATE.year", "eq", List.of(2027L)),
                new QueryRequest.Filter("cashflows.PAYMENT_DATE.quarter", "eq", List.of(3L)));          // the visual's own filter, then the path clicked
    }

    @Test
    void valuesCarriedByADrillThroughFilterEveryVisualOfThePage() throws IOException {
        Report r = report("counterparty-risk");
        String s = "{\"carry\":[{\"field\":\"counterparties.NAME\",\"values\":[\"Orbital\"]}]}";
        for (String v : List.of("c-ee", "c-trades", "c-trades-table")) {
            assertThat(build(r, "counterparty", v, s, "trading-risk").query().filters()).as(v).containsExactly(new QueryRequest.Filter("counterparties.NAME", "in", List.of("Orbital")));
        }
    }

    @Test
    void aTopNFilterIsResolvedFirstInTheContextOfTheOtherFilters() throws IOException {
        Report r = report("counterparty-risk");
        QueryRequest q = build(r, "exposure", "by-counterparty", "{\"slicers\":{\"sector\":[\"Banks\"]}}", "trading-risk").query();
        assertThat(topCalls).containsExactly("counterparties.id 10 Exposure (EE peak) desc with 1 filters");
        assertThat(q.filters()).containsExactly(new QueryRequest.Filter("counterparties.SECTOR", "in", List.of("Banks")), new QueryRequest.Filter("counterparties.id", "in", List.of("CP-1", "CP-2")));
        assertThat(q.measures()).containsExactly("Exposure (EE peak)", "Exposure (PFE peak)");
        assertThat(q.orderBy()).containsExactly(new QueryRequest.Sort("Exposure (EE peak)", true));
    }

    @Test
    void showTheRowsAsksForEveryColumnOfTheTableUnderTheSameFiltersAndTheMark() throws IOException {
        Report r = report("trades-overview");
        QueryRequest q = build(r, "overview", "by-desk", "{\"slicers\":{\"currency\":[\"USD\"]},\"rows\":{\"field\":\"trade.DESK\",\"values\":[\"DESK-FX\"]}}", "trades-basic").query();
        assertThat(q.measures()).isEmpty();
        assertThat(q.fields()).startsWith("trade.id", "trade.VALUATION_ID", "trade.TRADE_ID").endsWith("trade.MTM_IN_MILLIONS", "trade.businessDate");
        assertThat(q.filters()).containsExactly(new QueryRequest.Filter("trade.CURRENCY", "in", List.of("USD")), new QueryRequest.Filter("trade.DESK", "in", List.of("DESK-FX")));
        assertThat(q.limit()).isEqualTo(1000);
        // a card has no fields: the table comes from the tables its measure reads
        assertThat(build(r, "overview", "mtm", "{\"rows\":true}", "trades-basic").query().fields()).startsWith("trade.id");
        // array tables show the position of the element too
        QueryRequest legs = build(report("cashflow-ladder"), "legs", "leg-matrix", "{\"rows\":true}", "trading-cashflows").query();
        assertThat(legs.fields()).startsWith("legs.id", "legs.index");
    }

    @Test
    void datesComeFromTheStateOrTheReport() throws IOException {
        Report r = ReportParser.parse("report: r\ndataset: trades-basic\ndates: { from: 2026-10-01, to: 2026-10-08 }\npages:\n  - id: p\n    visuals:\n      - { id: c, type: card, at: { x: 0, y: 0, w: 3, h: 2 }, wells: { values: [MTM] } }\n").report();
        QueryRequest q = build(r, "p", "c", "{}", "trades-basic").query();
        assertThat(q.from()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(q.to()).isEqualTo(LocalDate.of(2026, 10, 8));
        assertThat(build(r, "p", "c", "{\"dates\":{\"from\":\"2026-10-05\",\"to\":\"2026-10-05\"}}", "trades-basic").query().from()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThatThrownBy(() -> build(r, "p", "c", "{\"dates\":{\"from\":\"yesterday\"}}", "trades-basic")).hasMessageContaining("yyyy-mm-dd");
    }

    @Test
    void aBadStateIsDrs9035() throws IOException {
        Report r = report("trades-overview");
        assertThatThrownBy(() -> build(r, "overview", "by-desk", "{\"slicers\":{\"nope\":[\"x\"]}}", "trades-basic")).isInstanceOf(DrishtiException.class).hasMessageContaining("no slicer 'nope'");
        assertThatThrownBy(() -> build(r, "overview", "by-desk", "{\"selections\":[{\"visual\":\"ghost\",\"field\":\"trade.DESK\",\"values\":[\"x\"]}]}", "trades-basic")).hasMessageContaining("no visual 'ghost'");
        assertThatThrownBy(() -> state("{\"selections\":[{\"field\":\"a\"}]}")).hasMessageContaining("{ visual, field, values }");
        assertThatThrownBy(() -> state("[1]")).hasMessageContaining("object");
    }
}
