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

import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.report.ReportParser.Problem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The report parser and validator: the shipped samples are clean, and every kind of mistake comes back with its DRS code and the path of the key. */
class ReportParserTest {

    static final Path REPORTS = Path.of("src/test/resources/bi/samples-2.1/reports");            // the 2.1 reports on datasets (config/bi has them on models)
    static final Path DATASETS = Path.of("src/test/resources/bi/samples-2.1/datasets");

    static String report(String name) throws IOException {
        return Files.readString(REPORTS.resolve(name + ".report.yaml"));
    }

    static Dataset dataset(String name) throws IOException {
        return DatasetParser.parse(Files.readString(DATASETS.resolve(name + ".dataset.yaml"))).dataset();
    }

    static final String MINI = """
            report: mini
            dataset: trades-basic
            pages:
              - id: p
                visuals:
                  - { id: v, type: bar, at: { x: 0, y: 0, w: 6, h: 4 }, wells: { axis: [trade.DESK], values: [MTM] } }
            """;

    static List<Problem> errors(String yaml, String dataset) throws IOException {
        ReportParser.Result r = ReportParser.parse(yaml);
        List<Problem> all = new java.util.ArrayList<>(r.problems());
        if (r.report() != null) {
            all.addAll(ReportValidator.validate(r.report(), dataset(dataset)));
        }
        return all.stream().filter(Problem::error).toList();
    }

    @Test
    void theThreeShippedSamplesParseAndCheckAgainstTheirDatasets() throws IOException {
        for (String[] s : new String[][] {{"trades-overview", "trades-basic"}, {"counterparty-risk", "trading-risk"}, {"cashflow-ladder", "trading-cashflows"}}) {
            ReportParser.Result r = ReportParser.parse(report(s[0]));
            assertThat(r.problems().stream().filter(Problem::error).toList()).as(s[0]).isEmpty();
            assertThat(r.report().sample()).isTrue();
            assertThat(r.report().dataset()).isEqualTo(s[1]);
            assertThat(ReportValidator.validate(r.report(), dataset(s[1]))).as(s[0] + " against " + s[1]).isEmpty();
            assertThat(Files.readString(REPORTS.resolve(s[0] + ".report.yaml")).lines().filter(l -> l.startsWith("#")).count()).isGreaterThan(3);
        }
    }

    @Test
    void theOverviewHasCardsABarAndALineATableAndASlicer() throws IOException {
        Report r = ReportParser.parse(report("trades-overview")).report();
        assertThat(r.pages()).extracting(Report.Page::id).containsExactly("overview", "products");
        assertThat(r.page("overview").visuals()).extracting(Report.Visual::type).containsExactly("card", "card", "card", "card", "bar", "line", "table", "slicer");
        assertThat(r.page("overview").visual("by-desk").hierarchy()).isEqualTo("Desk and book");
        assertThat(r.bookmarks()).hasSize(1);
    }

    @Test
    void theCounterpartyReportDrillsThroughToAPageThatTakesTheCounterparty() throws IOException {
        Report r = ReportParser.parse(report("counterparty-risk")).report();
        assertThat(r.page("exposure").visual("by-counterparty").drillThrough()).isEqualTo(new Report.DrillThrough("counterparty", "counterparties.name"));
        assertThat(r.page("counterparty").drillThrough().fields()).containsExactly("counterparties.name");
        Report.Filter top = r.page("exposure").visual("by-counterparty").filters().get(0);
        assertThat(top.op()).isEqualTo("top");
        assertThat(top.n()).isEqualTo(10);
    }

    @Test
    void theModelRoundTripsThroughItsMapAndThroughTheWriter() throws IOException {
        for (String name : List.of("trades-overview", "counterparty-risk", "cashflow-ladder")) {
            Report r = ReportParser.parse(report(name)).report();
            String yaml = ReportYaml.write(r.toMap());
            var again = ReportParser.parse(yaml);
            assertThat(again.problems().stream().filter(Problem::error)).as(name + "\n" + yaml).isEmpty();
            assertThat(again.report().toMap()).as(name).isEqualTo(r.toMap());
        }
    }

    @Test
    void theWriterLooksLikeYamlAPersonWrote() throws IOException {
        Report r = ReportParser.parse(report("trades-overview")).report();
        String yaml = ReportYaml.write(r.toMap());
        assertThat(yaml).contains("report: trades-overview\n").contains("      - id: mtm\n        type: card\n        title: Mark to market\n        at: { x: 6, y: 0, w: 3, h: 2 }\n        wells: { values: [MTM] }\n");
        assertThat(yaml).contains("pages:\n  - id: overview\n    title: Overview\n    visuals:\n").doesNotContain("\"y\"");
        assertThat(yaml.lines().filter(l -> l.startsWith("        ") && l.length() > 170)).as("a visual's lines stay readable").isEmpty();
    }

    @Test
    void theWriterQuotesWhatWouldOtherwiseBeReadAsSomethingElse() {
        String yaml = ReportYaml.write(new java.util.LinkedHashMap<>(java.util.Map.of("report", "x", "dataset", "file:dataset:a", "title", "true", "description", "12, a: b # c\nnext")));
        var again = ReportParser.parse(yaml + "pages:\n  - { id: p, visuals: [] }\n");
        assertThat(again.report().dataset()).isEqualTo("file:dataset:a");
        assertThat(again.report().title()).isEqualTo("true");
        assertThat(again.report().description()).isEqualTo("12, a: b # c\nnext");
    }

    @Test
    void structuralMistakesAreDrs9030WithThePathOfTheKey() throws IOException {
        assertThat(ReportParser.parse("a: [1").problems().get(0).code()).isEqualTo("DRS-9030");
        assertThat(ReportParser.parse("just text").problems().get(0).code()).isEqualTo("DRS-9030");
        List<Problem> p = ReportParser.parse("pages: []\n").problems();
        assertThat(p).extracting(Problem::path).contains("report", "dataset", "pages");
        assertThat(errors(MINI.replace("w: 6", "w: 13"), "trades-basic")).extracting(Problem::path).contains("pages[0].visuals[0].at");
        assertThat(errors(MINI.replace("x: 0", "x: 8"), "trades-basic")).extracting(Problem::code).contains("DRS-9030");        // x + w over 12
        assertThat(errors(MINI.replace("type: bar", "type: pyramid"), "trades-basic")).extracting(Problem::message).anyMatch(m -> m.contains("not a visual type"));
        assertThat(errors(MINI + "  - id: p\n    visuals: []\n", "trades-basic")).extracting(Problem::message).anyMatch(m -> m.contains("used twice"));
        assertThat(errors(MINI.replace("w: 6, h: 4 }", "w: 6, h: 4 }\n                    , filters: [{ field: trade.DESK, op: like, values: [x] }]"), "trades-basic")).isNotEmpty();
        assertThat(ReportParser.parse(MINI.replace("type: bar", "type: image, src: 'https://example.com/x.png'")).problems()).extracting(Problem::path).contains("pages[0].visuals[0].src");
    }

    @Test
    void anEntityViewNeedsItsKeyFieldAndAsksNoQuery() throws IOException {
        String ok = MINI.replace("type: bar, at: { x: 0, y: 0, w: 6, h: 4 }, wells: { axis: [trade.DESK], values: [MTM] }",
                "type: entity-view, at: { x: 0, y: 0, w: 6, h: 4 }, wells: { key: [trade.DESK] }");
        assertThat(errors(ok, "trades-basic")).isEmpty();
        assertThat(errors(ok.replace("key: [trade.DESK]", "key: []"), "trades-basic")).extracting(Problem::code).contains("DRS-9032");
        assertThat(errors(ok.replace("key: [trade.DESK]", "key: [MTM]"), "trades-basic")).isNotEmpty();                   // a measure is not a key
        Report r = ReportParser.parse(ok).report();
        Report.Page p = r.page("p");
        assertThatThrownBy(() -> VisualQueries.build(r, p, p.visual("v"), ReportState.NONE, dataset("trades-basic"), BiReportProperties.defaults(), (f, n, by, d, c, a, b) -> List.of()))
                .isInstanceOf(com.ash.drishti.common.DrishtiException.class).hasMessageContaining("draws no data");
    }

    @Test
    void reservedVisualsAreDrs9033() {
        for (String type : List.of("shape")) {
            List<Problem> p = ReportParser.parse(MINI.replace("type: bar", "type: " + type)).problems();
            assertThat(p).as(type).extracting(Problem::code).contains("DRS-9033");
        }
    }

    @Test
    void wellsThatDoNotExistOrAreMisusedAreDrs9032() throws IOException {
        assertThat(errors(MINI.replace("axis: [trade.DESK]", "rows: [trade.DESK]"), "trades-basic")).extracting(Problem::code).contains("DRS-9032");     // a bar has no rows well
        assertThat(errors(MINI.replace("values: [MTM]", "values: [trade.MTM]"), "trades-basic")).extracting(Problem::message).anyMatch(m -> m.contains("is a field; the values well takes measures"));
        assertThat(errors(MINI.replace("axis: [trade.DESK]", "axis: [MTM]"), "trades-basic")).extracting(Problem::message).anyMatch(m -> m.contains("is a measure; the axis well takes fields"));
        assertThat(errors(MINI.replace(", values: [MTM]", ""), "trades-basic")).extracting(Problem::message).anyMatch(m -> m.contains("needs at least one item in its values well"));
        assertThat(errors(MINI.replace("axis: [trade.DESK]", "axis: [trade.DESK, trade.PORTFOLIO]"), "trades-basic")).extracting(Problem::message).anyMatch(m -> m.contains("holds 1 item"));
    }

    @Test
    void namesTheDatasetLacksAreDrs9031WithWhatItHas() throws IOException {
        List<Problem> p = errors(MINI.replace("trade.DESK", "trade.nope").replace("MTM", "Profit"), "trades-basic");
        assertThat(p).extracting(Problem::code).contains("DRS-9031");
        assertThat(p).extracting(Problem::message).anyMatch(m -> m.contains("has no column 'nope'")).anyMatch(m -> m.contains("no measure 'Profit'") && m.contains("Notional"));
        assertThat(errors(MINI.replace("w: 6, h: 4 }", "w: 6, h: 4 }, hierarchy: Nope"), "trades-basic")).extracting(Problem::path).contains("pages[0].visuals[0].hierarchy");
        String drill = MINI.replace("w: 6, h: 4 }", "w: 6, h: 4 }, drillthrough: { page: elsewhere, field: trade.DESK }");
        assertThat(errors(drill, "trades-basic")).extracting(Problem::message).anyMatch(m -> m.contains("no page 'elsewhere'"));
        assertThat(errors(MINI.replace("w: 6, h: 4 }", "w: 6, h: 4 }, filters: [{ field: trade.DESK, op: top, n: 3, by: Nope }]"), "trades-basic")).extracting(Problem::path).contains("pages[0].visuals[0].filters[0].by");
        assertThat(errors(MINI + "bookmarks:\n  - { name: b, page: gone }\n", "trades-basic")).extracting(Problem::path).contains("bookmarks[0].page");
    }

    @Test
    void anyFieldOfADatePartIsAccepted() throws IOException {
        assertThat(errors(MINI.replace("trade.DESK", "trade.TRADE_DATE.month"), "trades-basic")).isEmpty();
        assertThat(errors(MINI.replace("trade.DESK", "trade.DESK.month"), "trades-basic")).isNotEmpty();
    }

    @Test
    void accessibilityChecksFlagMissingTextOverlapAndLowContrast() throws IOException {
        String yaml = """
                report: a
                dataset: trades-basic
                theme: { background: "#ffffff" }
                pages:
                  - id: p
                    visuals:
                      - { id: a, type: bar, at: { x: 0, y: 0, w: 6, h: 4 }, wells: { axis: [trade.DESK], values: [MTM] }, format: { colour: "#ffff99" } }
                      - { id: b, type: card, at: { x: 4, y: 2, w: 4, h: 2 }, wells: { values: [MTM] } }
                """;
        Report r = ReportParser.parse(yaml).report();
        List<Problem> w = ReportValidator.accessibility(r);
        assertThat(w).extracting(Problem::level).containsOnly("warning");
        assertThat(w).extracting(Problem::message).anyMatch(m -> m.contains("no title and no alt text")).anyMatch(m -> m.contains("overlaps 'b'")).anyMatch(m -> m.contains("contrast ratio"));
        assertThat(ReportValidator.accessibility(ReportParser.parse(report("trades-overview")).report())).as("the sample is accessible").isEmpty();
    }

    static final String PHONE = MINI.replace("pages:", """
            layouts:
              phone:
                p:
                  order: [v]
                  hide: [w]
                  span: { v: 5 }
            pages:""").replace("      - { id: v,",
            "      - { id: w, type: card, at: { x: 6, y: 0, w: 3, h: 2 }, wells: { values: [MTM] } }\n      - { id: v,");

    @Test
    void aPhoneLayoutParsesChecksItsReferencesAndSurvivesTheYamlWriter() throws IOException {
        ReportParser.Result r = ReportParser.parse(PHONE);
        assertThat(r.problems().stream().filter(Problem::error).toList()).isEmpty();
        Report.PhonePage p = r.report().layouts().get("phone").get("p");
        assertThat(p.order()).containsExactly("v");
        assertThat(p.hide()).containsExactly("w");
        assertThat(p.span()).containsEntry("v", 5);
        assertThat(ReportValidator.validate(r.report(), dataset("trades-basic")).stream().filter(Problem::error).toList()).isEmpty();
        // written and read back it is the same model
        String back = ReportYaml.write(r.report().toMap());
        assertThat(back).contains("layouts:").contains("  phone:");
        assertThat(ReportParser.parse(back).report().toMap()).isEqualTo(r.report().toMap());
    }

    @Test
    void aTabletLayoutIsAPhoneLayoutPlusTheVisualsThatTakeTheWholeWidth() throws IOException {
        String yaml = PHONE.replace("layouts:\n", "layouts:\n  tablet:\n    p:\n      order: [w, v]\n      wide: [v]\n      span: { w: 3 }\n");
        ReportParser.Result r = ReportParser.parse(yaml);
        assertThat(r.problems().stream().filter(Problem::error).toList()).isEmpty();
        Report.PhonePage t = r.report().layouts().get("tablet").get("p");
        assertThat(t.order()).containsExactly("w", "v");
        assertThat(t.wide()).containsExactly("v");
        assertThat(r.report().layouts().get("phone").get("p").wide()).isEmpty();
        assertThat(ReportValidator.validate(r.report(), dataset("trades-basic")).stream().filter(Problem::error).toList()).isEmpty();
        String back = ReportYaml.write(r.report().toMap());
        assertThat(back).contains("  tablet:").contains("wide:");
        assertThat(ReportParser.parse(back).report().toMap()).isEqualTo(r.report().toMap());
        // a bad one is refused with its path; "wide" means nothing on a phone and is ignored with a warning; an unknown device is ignored
        String bad = yaml.replace("wide: [v]", "wide: [nope]").replace("hide: [w]", "hide: [w]\n      wide: [v]").replace("layouts:\n", "layouts:\n  watch:\n    p: { order: [v] }\n");
        List<Problem> all = ReportParser.parse(bad).problems();
        assertThat(all).extracting(Problem::path).contains("layouts.watch", "layouts.phone.p.wide");
        assertThat(all.stream().filter(Problem::error)).isEmpty();
        assertThat(errors(yaml.replace("wide: [v]", "wide: [nope]"), "trades-basic")).extracting(Problem::path).contains("layouts.tablet.p.wide");
    }

    @Test
    void aPhoneLayoutNamingAMissingPageVisualOrABadSpanIsRefused() throws IOException {
        String bad = PHONE.replace("order: [v]", "order: [v, v, nope]").replace("hide: [w]", "hide: [v]").replace("span: { v: 5 }", "span: { v: 99, zz: 2 }")
                .replace("  phone:\n    p:", "  phone:\n    ghost: { order: [v] }\n    p:");
        List<Problem> errs = errors(bad, "trades-basic");
        assertThat(errs).extracting(Problem::path).contains("layouts.phone.ghost", "layouts.phone.p.order", "layouts.phone.p.hide", "layouts.phone.p.span.v", "layouts.phone.p.span.zz");
    }

    @Test
    void aReportWithoutLayoutsHasNone() {
        assertThat(ReportParser.parse(MINI).report().layouts()).isEmpty();
        assertThat(ReportParser.parse(MINI).report().toMap()).doesNotContainKey("layouts");
    }
}
