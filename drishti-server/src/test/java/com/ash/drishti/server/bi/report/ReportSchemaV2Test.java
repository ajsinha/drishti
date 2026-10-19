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
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Report schema v2 (R6, 2.4.0): the typed format of each visual family, conditional formatting rules, the interactions matrix, sync groups, tooltip pages,
 * small multiples and matrix depth; what the parser and the validator say about each, what each does to a visual's query, and that the YAML writer keeps it.
 */
class ReportSchemaV2Test {

    static final ObjectMapper JSON = new ObjectMapper();

    static String page(String visuals) {
        return "report: v2\ndataset: trades-basic\npages:\n  - id: p\n    title: P\n    visuals:\n" + visuals;
    }

    static List<Problem> all(String yaml) throws IOException {
        ReportParser.Result r = ReportParser.parse(yaml);
        List<Problem> out = new java.util.ArrayList<>(r.problems());
        if (r.report() != null) {
            out.addAll(ReportValidator.validate(r.report(), ReportParserTest.dataset("trades-basic")));
        }
        return out;
    }

    static ReportState state(String json) throws IOException {
        return ReportState.of(JSON.readTree(json));
    }

    static QueryRequest query(String yaml, String page, String visual, String stateJson) throws IOException {
        Report r = ReportParser.parse(yaml).report();
        Dataset ds = ReportParserTest.dataset("trades-basic");
        return VisualQueries.build(r, r.page(page), r.page(page).visual(visual), state(stateJson), ds, BiReportProperties.defaults(), (f, n, by, d, c, a, b) -> List.of()).query();
    }

    // ---- typed format --------------------------------------------------------------------------------------------------

    @Test
    void everyVisualTypeHasAFamilyAndTheSchemasResolve() {
        FormatSchemas s = FormatSchemas.get();
        for (String type : VisualCatalogue.names()) {
            assertThat(s.family(type)).as(type).isNotNull();
        }
        Map<String, Object> m = s.toMap();
        @SuppressWarnings("unchecked")
        Map<String, Object> chart = (Map<String, Object>) ((Map<String, Object>) m.get("families")).get("chart");
        assertThat(chart.get("order").toString()).contains("Axes", "Small multiples");
        assertThat(m.get("families").toString()).doesNotContain("$ref");
        assertThat(((Map<?, ?>) chart.get("properties")).keySet().stream().map(String::valueOf).toList()).contains("subtitle", "labels", "valueAxis", "multiples", "seriesColours", "border");
    }

    @Test
    void aWrongNewValueIsAnErrorAWrongTwoThreeValueAWarningAndAnUnknownKeyAWarning() throws IOException {
        List<Problem> p = all(page("      - { id: c, type: column, at: { x: 0, y: 0, w: 6, h: 4 }, wells: { axis: [trade.DESK], values: [MTM] },\n"
                + "          format: { valueAxis: { min: low }, legend: sideways, sparkle: true, padding: 99 } }\n"));
        assertThat(p).anyMatch(x -> x.error() && x.path().equals("pages[0].visuals[0].format.valueAxis.min"));
        assertThat(p).anyMatch(x -> x.error() && x.path().equals("pages[0].visuals[0].format.padding"));
        assertThat(p).anyMatch(x -> !x.error() && x.path().equals("pages[0].visuals[0].format.legend") && x.message().contains("ignored"));
        assertThat(p).anyMatch(x -> !x.error() && x.path().equals("pages[0].visuals[0].format.sparkle"));
    }

    @Test
    void aKeyOfTheFamilyThatTheTypeDoesNotUseIsAWarning() throws IOException {
        List<Problem> p = all(page("      - { id: l, type: line, at: { x: 0, y: 0, w: 6, h: 4 }, wells: { axis: [trade.DESK], values: [MTM] }, format: { stacked: true } }\n"));
        assertThat(p).anyMatch(x -> !x.error() && x.message().contains("does not use `stacked`"));
        assertThat(p).noneMatch(Problem::error);
    }

    // ---- conditional formatting -----------------------------------------------------------------------------------------

    @Test
    void rulesArePickedByTheirTypeAndNameWhatTheVisualShows() throws IOException {
        String ok = page("      - { id: t, type: table, at: { x: 0, y: 0, w: 6, h: 4 }, wells: { columns: [trade.DESK, MTM, Notional] }, format: { rules: [\n"
                + "          { type: gradient, apply: MTM, min: \"#c62828\", mid: \"#ffffff\", max: \"#2e7d32\" },\n"
                + "          { type: rule, apply: MTM, on: Trades, op: gt, value: 10, colour: \"#b3261e\" },\n"
                + "          { type: icons, apply: Notional, icons: arrows, thresholds: [0, 1000000] },\n"
                + "          { type: values, apply: trade.DESK, colours: { DESK-FX: \"#1565c0\" } } ] } }\n");
        assertThat(all(ok).stream().filter(Problem::error).toList()).isEmpty();
        assertThat(query(ok, "p", "t", "{}").measures()).as("a rule on another measure asks for it too").containsExactly("MTM", "Notional", "Trades");
        List<Problem> bad = all(page("      - { id: t, type: table, at: { x: 0, y: 0, w: 6, h: 4 }, wells: { columns: [trade.DESK, MTM] }, format: { rules: [\n"
                + "          { type: sparkle, apply: MTM },\n"
                + "          { type: gradient, apply: MTM, min: red, max: \"#2e7d32\" },\n"
                + "          { type: rule, apply: Notional, op: gt, value: 1 },\n"
                + "          { type: rule, apply: MTM, on: Nope, op: gt, value: 1 },\n"
                + "          { type: values, apply: MTM, colours: {} } ] } }\n"));
        assertThat(bad.stream().filter(Problem::error).map(Problem::path).toList()).contains("pages[0].visuals[0].format.rules[0].type", "pages[0].visuals[0].format.rules[1].min",
                "pages[0].visuals[0].format.rules[2].apply", "pages[0].visuals[0].format.rules[3].on", "pages[0].visuals[0].format.rules[4].apply");
    }

    // ---- interactions, sync groups, tooltip pages ----------------------------------------------------------------------------

    static final String TWO = "report: v2\ndataset: trades-basic\npages:\n  - id: p\n    title: P\n    interactions:\n      bars: { trend: highlight, total: none }\n      s: { total: none }\n"
            + "    visuals:\n"
            + "      - { id: bars, type: bar, at: { x: 0, y: 0, w: 6, h: 4 }, wells: { axis: [trade.DESK], values: [MTM] } }\n"
            + "      - { id: trend, type: line, at: { x: 6, y: 0, w: 6, h: 4 }, wells: { axis: [trade.CURRENCY], values: [MTM] } }\n"
            + "      - { id: total, type: card, at: { x: 0, y: 4, w: 3, h: 2 }, wells: { values: [MTM] } }\n"
            + "      - { id: table, type: table, at: { x: 3, y: 4, w: 6, h: 4 }, wells: { columns: [trade.CURRENCY, MTM] } }\n"
            + "      - { id: s, type: slicer, at: { x: 9, y: 4, w: 3, h: 4 }, wells: { field: [trade.CURRENCY] }, format: { syncGroup: ccy } }\n"
            + "  - id: q\n    title: Q\n    visuals:\n"
            + "      - { id: s2, type: slicer, at: { x: 0, y: 0, w: 3, h: 4 }, wells: { field: [trade.CURRENCY] }, format: { syncGroup: ccy } }\n"
            + "      - { id: c2, type: card, at: { x: 3, y: 0, w: 3, h: 2 }, wells: { values: [MTM] } }\n"
            + "  - id: r\n    title: R\n    visuals:\n"
            + "      - { id: c3, type: card, at: { x: 3, y: 0, w: 3, h: 2 }, wells: { values: [MTM] } }\n";

    @Test
    void theInteractionsMatrixFiltersHighlightsOrLeavesAlone() throws IOException {
        assertThat(all(TWO).stream().filter(Problem::error).toList()).isEmpty();
        String sel = "{\"selections\": [{\"visual\": \"bars\", \"field\": \"trade.DESK\", \"values\": [\"DESK-FX\"], \"highlight\": false}]}";
        Report r = ReportParser.parse(TWO).report();
        Dataset ds = ReportParserTest.dataset("trades-basic");
        VisualQueries.Built trend = VisualQueries.build(r, r.page("p"), r.page("p").visual("trend"), state(sel), ds, BiReportProperties.defaults(), (f, n, by, d, c, a, b) -> List.of());
        assertThat(trend.highlight()).as("a line highlights (2.4.0)").isNotNull();
        assertThat(trend.query().filters()).isEmpty();
        assertThat(query(TWO, "p", "total", sel).filters()).as("none: left alone").isEmpty();
        assertThat(query(TWO, "p", "table", sel).filters()).as("not listed: the viewer's choice, filter").containsExactly(new QueryRequest.Filter("trade.DESK", "in", List.of("DESK-FX")));
        String sliced = "{\"slicers\": {\"s\": [\"USD\"]}}";
        assertThat(query(TWO, "p", "total", sliced).filters()).as("a slicer left out by the matrix").isEmpty();
        assertThat(query(TWO, "p", "table", sliced).filters()).hasSize(1);
    }

    @Test
    void aSyncGroupCarriesASlicersChoiceToThePagesWithASlicerOfTheGroup() throws IOException {
        String onP = "{\"slicers\": {\"s\": [\"USD\"]}}";
        assertThat(query(TWO, "q", "c2", onP).filters()).as("page q has a slicer of the group").containsExactly(new QueryRequest.Filter("trade.CURRENCY", "in", List.of("USD")));
        assertThat(query(TWO, "r", "c3", onP).filters()).as("page r has none").isEmpty();
        assertThat(query(TWO, "q", "s2", onP).filters()).as("the group's own slicer keeps its whole list").isEmpty();
        assertThat(query(TWO, "q", "c2", "{\"slicers\": {\"s\": [\"USD\"], \"s2\": [\"EUR\"]}}").filters()).containsExactly(new QueryRequest.Filter("trade.CURRENCY", "in", List.of("EUR")));
        List<Problem> bad = all(TWO.replace("wells: { field: [trade.CURRENCY] }, format: { syncGroup: ccy } }\n      - { id: c2", "wells: { field: [trade.DESK] }, format: { syncGroup: ccy } }\n      - { id: c2"));
        assertThat(bad).anyMatch(x -> x.error() && x.path().equals("pages[1].visuals[0].format.syncGroup"));
    }

    @Test
    void aSlicerIdOfThisPageWinsOverTheSameIdOnAnotherPageAndATooltipPageTakesTheChoicesItIsSent() throws IOException {
        String yaml = "report: v2\ndataset: trades-basic\npages:\n"
                + "  - id: p\n    title: P\n    visuals:\n      - { id: slicer-1, type: slicer, at: { x: 0, y: 0, w: 3, h: 4 }, wells: { field: [trade.DESK] } }\n"
                + "  - id: q\n    title: Q\n    visuals:\n      - { id: slicer-1, type: slicer, at: { x: 0, y: 0, w: 3, h: 4 }, wells: { field: [trade.CURRENCY] } }\n"
                + "      - { id: c, type: card, at: { x: 3, y: 0, w: 3, h: 2 }, wells: { values: [MTM] } }\n"
                + "  - id: tip\n    title: Tip\n    tooltip: true\n    visuals:\n      - { id: t, type: card, at: { x: 0, y: 0, w: 3, h: 2 }, wells: { values: [MTM] } }\n";
        assertThat(query(yaml, "q", "c", "{\"slicers\": {\"slicer-1\": [\"USD\"]}}").filters()).containsExactly(new QueryRequest.Filter("trade.CURRENCY", "in", List.of("USD")));
        assertThat(query(yaml, "tip", "t", "{\"slicers\": {\"slicer-1\": [\"USD\"]}}").filters()).hasSize(1);
    }

    @Test
    void theInteractionsMatrixNamesVisualsOfItsPage() throws IOException {
        List<Problem> p = all(TWO.replace("bars: { trend: highlight, total: none }", "bars: { trend: glow, nope: none }\n      ghost: { bars: none }"));
        assertThat(p.stream().filter(Problem::error).map(Problem::path).toList()).contains("pages[0].interactions.bars.trend", "pages[0].interactions.bars.nope", "pages[0].interactions.ghost");
    }

    @Test
    void aTooltipPageIsAPageMarkedSoAndAVisualNamesIt() throws IOException {
        String yaml = TWO.replace("      - { id: bars, type: bar,", "      - { id: bars, type: bar, tooltip: tip,") + "  - id: tip\n    title: Tip\n    tooltip: true\n    visuals:\n"
                + "      - { id: tc, type: card, at: { x: 0, y: 0, w: 4, h: 2 }, wells: { values: [Notional] } }\n";
        assertThat(all(yaml).stream().filter(Problem::error).toList()).isEmpty();
        Report r = ReportParser.parse(yaml).report();
        assertThat(r.page("tip").tooltip()).isTrue();
        assertThat(r.page("p").visual("bars").tooltip()).isEqualTo("tip");
        assertThat(query(yaml, "tip", "tc", "{\"carry\": [{\"field\": \"trade.DESK\", \"values\": [\"DESK-FX\"]}]}").filters()).as("the hovered mark is carried")
                .containsExactly(new QueryRequest.Filter("trade.DESK", "in", List.of("DESK-FX")));
        assertThat(all(yaml.replace("tooltip: tip,", "tooltip: q,"))).anyMatch(x -> x.error() && x.path().equals("pages[0].visuals[0].tooltip"));
        String yaml2 = ReportYaml.write(r.toMap());
        assertThat(yaml2).contains("tooltip: true").contains("tooltip: tip").contains("interactions:");
        assertThat(ReportParser.parse(yaml2).report().toMap()).isEqualTo(r.toMap());
    }

    // ---- small multiples, matrix depth -----------------------------------------------------------------------------------

    @Test
    void aSplitFieldGroupsTheChartForSmallMultiples() throws IOException {
        String yaml = page("      - { id: c, type: column, at: { x: 0, y: 0, w: 12, h: 6 }, wells: { axis: [trade.DESK], values: [MTM], split: [trade.CURRENCY] }, format: { multiples: { columns: 3, sharedAxis: true } } }\n");
        assertThat(all(yaml).stream().filter(Problem::error).toList()).isEmpty();
        assertThat(query(yaml, "p", "c", "{}").fields()).containsExactly("trade.DESK", "trade.CURRENCY");
        assertThat(all(yaml.replace("split: [trade.CURRENCY]", "split: [MTM]"))).anyMatch(x -> x.error() && x.path().contains("wells.split"));
    }

    @Test
    void aMatrixWithSubtotalsAsksForATotalAtEveryLevelOfItsRows() throws IOException {
        String yaml = page("      - { id: m, type: matrix, at: { x: 0, y: 0, w: 12, h: 8 }, wells: { rows: [trade.DESK, trade.PORTFOLIO, trade.PRODUCT_TYPE], columns: [trade.CURRENCY], values: [MTM, Average MTM per trade] },\n"
                + "          format: { subtotals: true, stepped: false, expandTo: 1, valuesOn: rows } }\n");
        assertThat(all(yaml).stream().filter(Problem::error).toList()).isEmpty();
        Report r = ReportParser.parse(yaml).report();
        Dataset ds = ReportParserTest.dataset("trades-basic");
        QueryRequest base = query(yaml, "p", "m", "{}");
        List<List<String>> by = Totals.matrix(ds, r.page("p").visual("m"), base, 100).stream().map(Totals.Level::by).toList();
        assertThat(by).contains(List.of(), List.of("trade.DESK"), List.of("trade.DESK", "trade.PORTFOLIO"), List.of("trade.DESK", "trade.CURRENCY"),
                List.of("trade.DESK", "trade.PORTFOLIO", "trade.CURRENCY"), List.of("trade.DESK", "trade.PORTFOLIO", "trade.PRODUCT_TYPE"));
        assertThat(all(yaml.replace("valuesOn: rows", "valuesOn: diagonal"))).anyMatch(x -> x.error() && x.path().endsWith("format.valuesOn"));
    }

    // ---- themes ------------------------------------------------------------------------------------------------------------

    @Test
    void aThemeNameThatIsNoPaletteNamesAThemeItem() {
        Report r = ReportParser.parse(page("      - { id: t, type: text, text: Hi, at: { x: 0, y: 0, w: 4, h: 1 } }\n").replace("dataset: trades-basic", "dataset: trades-basic\ntheme: corporate")).report();
        assertThat(r.theme()).containsEntry("use", "corporate");
        Report r2 = ReportParser.parse(page("      - { id: t, type: text, text: Hi, at: { x: 0, y: 0, w: 4, h: 1 } }\n").replace("dataset: trades-basic", "dataset: trades-basic\ntheme: mono")).report();
        assertThat(r2.theme()).containsEntry("palette", "mono");
    }
}
