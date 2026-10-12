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
import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.engine.QueryRequest;
import com.ash.drishti.server.bi.python.PythonManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Python in BI (RUPAKA.md section 20.8): the manifest, a Python measure in a dataset, and the python visual: how each is read, checked and asked for. */
class PythonBiTest {

    static final Path PYTHON = Path.of("..", "config", "bi", "python");
    static final List<String> PACKAGES = BiReportProperties.defaults().pythonPackages();

    static PythonManifest.Result manifest(String body) {
        return PythonManifest.parse(body, PACKAGES, 60, 1024);
    }

    static String sample(String name) throws IOException {
        return Files.readString(PYTHON.resolve(name + ".python.yaml"));
    }

    @Test
    void theShippedSamplesAreValidManifests() throws IOException {
        for (String name : List.of("desk-pnl-volatility", "pnl-histogram")) {
            PythonManifest.Result r = manifest(sample(name));
            assertThat(r.ok()).as(name + ": " + r.problems()).isTrue();
            assertThat(r.manifest().source()).contains("def ");
            assertThat(r.manifest().target()).isEqualTo("both");
        }
        PythonManifest.Manifest m = manifest(sample("desk-pnl-volatility")).manifest();
        assertThat(m.interfaceKind()).isEqualTo("measure");
        assertThat(m.entry()).isEqualTo("compute");
        assertThat(m.inputs()).containsExactly("pnl1d");
        assertThat(m.output()).isEqualTo("number");
        assertThat(manifest(sample("pnl-histogram")).manifest().output()).isEqualTo("echarts");
    }

    @Test
    void everyKindOfMistakeIsDrs9070WithThePathOfTheKey() {
        String base = "python: x\ninterface: measure\ninputs: [a]\nsource: |\n  def compute(table, params):\n      return 1\n";
        assertThat(manifest(base).ok()).isTrue();
        assertThat(manifest(base.replace("interface: measure", "interface: widget")).problems()).anyMatch(p -> p.path().equals("interface"));
        assertThat(manifest(base.replace("inputs: [a]", "inputs: []")).problems()).anyMatch(p -> p.path().equals("inputs"));
        assertThat(manifest(base + "packages: [requests]\n").problems()).anyMatch(p -> p.path().equals("packages") && p.message().contains("not an allowed package"));
        assertThat(manifest(base + "target: cloud\n").problems()).anyMatch(p -> p.path().equals("target"));
        assertThat(manifest(base + "timeout: 600\n").problems()).anyMatch(p -> p.path().equals("timeout"));
        assertThat(manifest(base + "memory: 8\n").problems()).anyMatch(p -> p.path().equals("memory"));
        assertThat(manifest(base + "colour: red\n").problems()).anyMatch(p -> p.path().equals("colour"));
        assertThat(manifest(base.replace("def compute", "def other")).problems()).anyMatch(p -> p.path().equals("source") && p.message().contains("compute"));
        assertThat(manifest("python: x\ninterface: visual\nsource: pass\n").problems()).anyMatch(p -> p.path().equals("source"));
        assertThat(manifest("not: [valid").ok()).isFalse();
        assertThat(manifest(base).problems()).allMatch(p -> p.toMap().get("code").equals("DRS-9070"));
    }

    @Test
    void aBrowserTargetCannotNeedAPackagePyodideLacks() {
        String body = "python: x\ninterface: measure\ninputs: [a]\npackages: [pyarrow]\ntarget: both\nsource: |\n  def compute(table, params):\n      return 1\n";
        assertThat(manifest(body).problems()).anyMatch(p -> p.message().contains("browser"));
        assertThat(manifest(body.replace("both", "server")).ok()).isTrue();
    }

    @Test
    void aVisualsOutputIsEchartsSvgOrPng() {
        String v = "python: v\ninterface: visual\nsource: |\n  def render(table, params):\n      return {}\n";
        assertThat(manifest(v).manifest().output()).isEqualTo("echarts");
        assertThat(manifest(v + "output: svg\n").ok()).isTrue();
        assertThat(manifest(v + "output: html\n").problems()).anyMatch(p -> p.path().equals("output"));
    }

    static final String DATASET = """
            dataset: d
            tables:
              trade:
                source: { kind: trade }
                columns: { desk: string, pnl1d: decimal, other: string }
              leg:
                source: { kind: leg }
                columns: { x: decimal }
            measures:
              N: { expr: "count(trade)" }
              Vol: { python: desk-pnl-volatility, inputs: [trade.pnl1d], by: [trade.desk] }
            """;

    @Test
    void aPythonMeasureNamesItsItemItsInputsAndItsGroups() {
        Dataset ds = DatasetParser.parse(DATASET).dataset();
        Dataset.Measure vol = ds.measures().get("Vol");
        assertThat(vol.expr()).isNull();
        assertThat(vol.python()).isEqualTo("desk-pnl-volatility");
        assertThat(vol.inputs()).containsExactly("trade.pnl1d");
        assertThat(vol.by()).containsExactly("trade.desk");
        assertThat(DatasetParser.parse(DATASET.replace("Vol: {", "Vol: { expr: \"sum(trade.pnl1d)\",")).ok()).as("expr and python together").isFalse();
    }

    @Test
    void aPythonMeasureIsCheckedAgainstTheModelAndWarnsThatAQueryRefusesIt() throws IOException {
        DatasetParser.Result good = DatasetParser.parse(DATASET);
        assertThat(good.problems()).filteredOn(p -> p.path().equals("measures.Vol")).extracting(p -> p.level()).containsExactly("warning");
        assertThat(DatasetParser.parse(DATASET.replace("inputs: [trade.pnl1d]", "inputs: [trade.nope]")).problems()).anyMatch(p -> p.path().equals("measures.Vol.inputs") && "error".equals(p.level()));
        assertThat(DatasetParser.parse(DATASET.replace("inputs: [trade.pnl1d]", "inputs: []")).problems()).anyMatch(p -> p.path().equals("measures.Vol.inputs"));
        assertThat(DatasetParser.parse(DATASET.replace("by: [trade.desk]", "by: [leg.x]")).problems()).anyMatch(p -> p.path().equals("measures.Vol") && p.message().contains("one table"));
        assertThat(ReportParserTest.dataset("trades-basic").measures().get("Desk P&L volatility").python()).isEqualTo("desk-pnl-volatility");
    }

    @Test
    void thePythonSampleReportIsValidAndAPythonVisualAsksForTheRowsOfOneTable() throws IOException {
        ReportParser.Result parsed = ReportParser.parse(ReportParserTest.report("python-desk-risk"));
        assertThat(parsed.ok()).as(parsed.problems().toString()).isTrue();
        Dataset ds = ReportParserTest.dataset("trades-basic");
        assertThat(ReportValidator.validate(parsed.report(), ds)).noneMatch(ReportParser.Problem::error);
        Report r = parsed.report();
        Report.Page page = r.page("risk");
        QueryRequest hist = VisualQueries.build(r, page, page.visual("histogram"), ReportState.of(null), ds, BiReportProperties.defaults(), (f, n, by, d, c, from, to) -> List.of()).query();
        assertThat(hist.fields()).containsExactly("trade.desk", "trade.pnl1d", "trade.id");
        assertThat(hist.measures()).isEmpty();
        assertThat(hist.limit()).isEqualTo(10_000);
        QueryRequest vol = VisualQueries.build(r, page, page.visual("volatility"), ReportState.of(null), ds, BiReportProperties.defaults(), (f, n, by, d, c, from, to) -> List.of()).query();
        assertThat(vol.fields()).as("the measure's groups, its inputs, then the row id").containsExactly("trade.desk", "trade.pnl1d", "trade.id");
        assertThat(vol.measures()).isEmpty();
    }

    @Test
    void aPythonVisualNeedsAnItemOrAPythonMeasureAndItsWellsMustFit() throws IOException {
        String report = """
                report: r
                dataset: trades-basic
                pages:
                  - id: p
                    title: P
                    visuals:
                      - { id: a, type: python, wells: { columns: [trade.desk] } }
                      - { id: b, type: python, wells: { values: [MTM] }, format: { python: x } }
                      - { id: c, type: python, format: { python: x } }
                      - { id: d, type: python, wells: { columns: [trade.desk] }, format: { python: x } }
                """;
        List<ReportParser.Problem> problems = ReportValidator.validate(ReportParser.parse(report).report(), ReportParserTest.dataset("trades-basic"));
        assertThat(problems).anyMatch(p -> p.path().contains("visuals[0].format.python"));
        assertThat(problems).anyMatch(p -> p.path().contains("visuals[1].wells.values") && p.message().contains("Python measure"));
        assertThat(problems).anyMatch(p -> p.path().contains("visuals[2].wells.columns"));
        assertThat(problems).noneMatch(p -> p.path().contains("visuals[3]"));
        assertThat(VisualCatalogue.RESERVED).doesNotContain("python");
        assertThat(VisualCatalogue.type("python").group()).isEqualTo("python");
    }

    @Test
    void aTransformListsDatasetsNotColumnsAndDefinesRunAsAFunctionOrAClassMethod() throws IOException {
        PythonManifest.Manifest shipped = manifest(sample("pnl-vs-exposure")).manifest();
        assertThat(manifest(sample("pnl-vs-exposure")).ok()).isTrue();
        assertThat(shipped.interfaceKind()).isEqualTo("transform");
        assertThat(shipped.entry()).isEqualTo("run");
        assertThat(shipped.inputs()).containsExactly("trading-risk", "counterparty-exposure");
        assertThat(shipped.output()).isEqualTo("frame");
        String base = "python: t\ninterface: transform\ninputs: [trading-risk, file:dataset:counterparty-exposure]\nsource: |\n  def run(a, b):\n      return a\n";
        assertThat(manifest(base).ok()).as(manifest(base).problems().toString()).isTrue();
        assertThat(manifest("python: t\ninterface: transform\ninputs: [x]\nsource: |\n  class Transform:\n      def run(self, *frames):\n          return frames[0]\n").ok()).isTrue();
        assertThat(manifest(base.replace("inputs: [trading-risk, file:dataset:counterparty-exposure]", "inputs: []")).problems()).anyMatch(p -> p.path().equals("inputs") && p.message().contains("datasets it reads"));
        assertThat(manifest(base.replace("trading-risk", "bad name")).problems()).anyMatch(p -> p.path().equals("inputs") && p.message().contains("not a dataset"));
        assertThat(manifest(base.replace("def run(a, b)", "def other(a, b)")).problems()).anyMatch(p -> p.path().equals("source") && p.message().contains("'run'"));
        assertThat(manifest(base + "target: browser\n").problems()).anyMatch(p -> p.path().equals("target") && p.message().contains("runs on the server"));
    }
}
