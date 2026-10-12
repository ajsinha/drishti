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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The {@code vega-lite} visual: its specification lives in {@code format.spec}, loads nothing from a URL, names fields the wells supply, and survives the YAML writer. */
class VegaLiteVisualTest {

    static final String HEAD = """
            report: r
            dataset: trading-risk
            pages:
              - id: p
                visuals:
            """;
    static final String AT = ", at: { x: 0, y: 0, w: 6, h: 4 }";

    List<ReportParser.Problem> problems(String visual) throws IOException {
        ReportParser.Result parsed = ReportParser.parse(HEAD + "      - " + visual + "\n");
        List<ReportParser.Problem> out = new ArrayList<>(parsed.problems());
        if (parsed.report() != null) {
            out.addAll(ReportValidator.validate(parsed.report(), ReportParserTest.dataset("trading-risk")));
        }
        return out;
    }

    @Test
    void aSpecificationOverTheWellsIsValid() throws IOException {
        String ok = "{ id: v, type: vega-lite" + AT + ", wells: { axis: [trades.desk], values: [MTM] }, format: { spec: { mark: bar, encoding: { x: { field: desk, type: nominal }, y: { field: MTM, type: quantitative } } } } }";
        assertThat(problems(ok)).isEmpty();
    }

    @Test
    void aSpecificationThatLoadsAUrlOrNeedsNoWellsOrNoSpecIsRefused() throws IOException {
        String url = "{ id: v, type: vega-lite" + AT + ", wells: { axis: [trades.desk], values: [MTM] }, format: { spec: { data: { url: 'http://x/y.json' }, mark: bar } } }";
        assertThat(problems(url)).filteredOn(ReportParser.Problem::error).extracting(ReportParser.Problem::message).anyMatch(m -> m.contains("loads nothing from a URL"));
        String none = "{ id: v, type: vega-lite" + AT + ", format: { spec: { mark: bar } } }";
        assertThat(problems(none)).filteredOn(ReportParser.Problem::error).extracting(ReportParser.Problem::path).anyMatch(p -> p.endsWith(".wells"));
        String noSpec = "{ id: v, type: vega-lite" + AT + ", wells: { values: [MTM] } }";
        assertThat(problems(noSpec)).filteredOn(ReportParser.Problem::error).extracting(ReportParser.Problem::path).anyMatch(p -> p.endsWith("format.spec"));
    }

    @Test
    void aFieldNoWellSuppliesIsAWarningAndATransformSilencesIt() throws IOException {
        String off = "{ id: v, type: vega-lite" + AT + ", wells: { axis: [trades.desk], values: [MTM] }, format: { spec: { mark: bar, encoding: { x: { field: trades_desk }, y: { field: MTM } } } } }";
        assertThat(problems(off)).filteredOn(p -> !p.error()).extracting(ReportParser.Problem::message).anyMatch(m -> m.contains("'trades_desk'"));
        String made = "{ id: v, type: vega-lite" + AT + ", wells: { axis: [trades.desk], values: [MTM] }, format: { spec: { transform: [{ calculate: 'datum.MTM * 2', as: double }], mark: bar, encoding: { x: { field: desk }, y: { field: double } } } } }";
        assertThat(problems(made)).filteredOn(p -> !p.error()).isEmpty();
    }

    @Test
    void twoFieldsOfTheSameNameKeepTheirTableInTheKeys() {
        Report.Visual v = new Report.Visual("v", "vega-lite", null, null, Map.of("axis", List.of("trades.desk", "desks.desk"), "values", List.of("MTM")), List.of(), null, null, null, null, Map.of(), null, null, null, false, Map.of(), null);
        assertThat(VegaSpecs.keys(v, null)).containsExactly("trades_desk", "desks_desk", "MTM");
    }

    @Test
    void theWriterKeepsTheSpecificationAndTheParserReadsItBack() throws IOException {
        String ok = "{ id: v, type: vega-lite" + AT + ", wells: { axis: [trades.desk], values: [MTM] }, format: { spec: { mark: { type: bar, tooltip: true }, encoding: { x: { field: desk, type: nominal }, y: { field: MTM, type: quantitative, aggregate: sum } } } } }";
        Report r = ReportParser.parse(HEAD + "      - " + ok + "\n").report();
        String yaml = ReportYaml.write(r.toMap());
        Report back = ReportParser.parse(yaml).report();
        assertThat(back.page("p").visual("v").format()).isEqualTo(r.page("p").visual("v").format());
        assertThat(new ObjectMapper().valueToTree(back.page("p").visual("v").format().get("spec")).path("encoding").path("y").path("aggregate").asText()).isEqualTo("sum");
    }
}
