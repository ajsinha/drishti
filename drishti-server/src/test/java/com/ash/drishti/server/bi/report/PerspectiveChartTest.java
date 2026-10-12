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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The {@code perspective-chart} visual and the settings panel's saved view ({@code format.view}): what validates, what is refused, what the writer keeps. */
class PerspectiveChartTest {

    static final String HEAD = """
            report: r
            dataset: trading-risk
            pages:
              - id: p
                visuals:
            """;
    static final String AT = ", at: { x: 0, y: 0, w: 12, h: 8 }";
    static final String WELLS = ", wells: { rows: [trades.desk], columns: [trades.currency], values: [trades.mtm] }";

    List<ReportParser.Problem> problems(String visual) throws IOException {
        ReportParser.Result parsed = ReportParser.parse(HEAD + "      - " + visual + "\n");
        List<ReportParser.Problem> out = new ArrayList<>(parsed.problems());
        if (parsed.report() != null) {
            out.addAll(ReportValidator.validate(parsed.report(), ReportParserTest.dataset("trading-risk")));
        }
        return out;
    }

    List<String> errors(String visual) throws IOException {
        return problems(visual).stream().filter(ReportParser.Problem::error).map(p -> p.path() + ": " + p.message()).toList();
    }

    @Test
    void aChartIsAPivotWithAPluginAndTheCatalogueSaysItIsPerspective() throws IOException {
        assertThat(VisualCatalogue.type("perspective-chart").group()).isEqualTo("perspective");
        assertThat(errors("{ id: c, type: perspective-chart" + AT + WELLS + ", format: { plugin: Y Line, aggregates: { trades.mtm: sum } } }")).isEmpty();
        assertThat(errors("{ id: c, type: perspective-chart" + AT + WELLS + " }")).as("the plugin is optional (Y Bar)").isEmpty();
        assertThat(errors("{ id: c, type: perspective-chart" + AT + ", wells: { rows: [trades.desk] } }")).anyMatch(e -> e.contains("values"));
        assertThat(errors("{ id: c, type: perspective-chart" + AT + WELLS + ", format: { plugin: Pie } }")).anyMatch(e -> e.contains("format.plugin") && e.contains("is not a chart of Perspective"));
        assertThat(errors("{ id: g, type: perspective-pivot" + AT + WELLS + ", format: { plugin: Y Bar } }")).anyMatch(e -> e.contains("plugin is for a perspective-chart"));
    }

    @Test
    void aSavedViewIsCheckedAndAnUnknownKeyIsAWarning() throws IOException {
        String view = "view: { plugin: Y Bar, group_by: [desk], split_by: [currency], columns: [mtm], aggregates: { mtm: avg }, sort: [[mtm, desc]], filter: [[currency, '==', EUR]] }";
        assertThat(errors("{ id: c, type: perspective-chart" + AT + WELLS + ", format: { " + view + " } }")).isEmpty();
        assertThat(errors("{ id: c, type: perspective-pivot" + AT + WELLS + ", format: { view: { plugin: Datagrid, columns: [mtm] } } }")).isEmpty();
        assertThat(errors("{ id: c, type: perspective-chart" + AT + WELLS + ", format: { view: [a] } }")).anyMatch(e -> e.contains("format.view"));
        assertThat(errors("{ id: c, type: perspective-chart" + AT + WELLS + ", format: { view: { plugin: Pie } } }")).anyMatch(e -> e.contains("format.view.plugin"));
        assertThat(problems("{ id: c, type: perspective-chart" + AT + WELLS + ", format: { view: { colour: red } } }")).filteredOn(p -> !p.error()).extracting(ReportParser.Problem::path)
                .anyMatch(p -> p.endsWith("format.view.colour"));
    }

    @Test
    void theWriterKeepsASavedViewAndTheQueryIsThePivotsRows() throws IOException {
        String yaml = HEAD + "      - { id: c, type: perspective-chart" + AT + WELLS + ", format: { plugin: Treemap, view: { plugin: Treemap, group_by: [desk], plugin_config: { x: 1 } } } }\n";
        Report r = ReportParser.parse(yaml).report();
        Report back = ReportParser.parse(ReportYaml.write(r.toMap())).report();
        assertThat(back.page("p").visual("c").format()).isEqualTo(r.page("p").visual("c").format());
        assertThat(back.page("p").visual("c").format()).containsKey("view");
        assertThat(ReportValidator.validate(back, ReportParserTest.dataset("trading-risk")).stream().filter(ReportParser.Problem::error)).isEmpty();
    }
}
