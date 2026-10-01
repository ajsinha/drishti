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
package com.ash.drishti.rachana;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.rachana.model.Area;
import com.ash.drishti.rachana.model.PanelKind;
import com.ash.drishti.rachana.model.Sutra;
import com.ash.drishti.rachana.parse.SutraParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class SutraParserTest {

    static final Path SUTRAS = Path.of("..", "packs", "finance", "sutras");
    private final SutraParser parser = new SutraParser();

    private Sutra load(String rel) throws Exception {
        Path p = SUTRAS.resolve(rel);
        return parser.parse(Files.readString(p), p.getFileName().toString(), p.getParent().getFileName().toString());
    }

    @Test
    void referenceSutrasParse() throws Exception {
        Sutra irs = load("rates/irs-vanilla.v3.sutra.yaml");
        assertThat(irs.id()).isEqualTo("irs-vanilla@3");
        assertThat(irs.domain()).isEqualTo("rates");
        assertThat(irs.strip()).hasSize(8);
        assertThat(irs.panels()).extracting(p -> p.id()).containsExactly("legs", "cashflows", "leg2", "built", "curve", "refs", "dv01");
        assertThat(irs.panels().get(0).body().kind()).isEqualTo(PanelKind.KV);
        assertThat(irs.panels().get(4).area()).isEqualTo(Area.RIGHT);
        assertThat(irs.keys()).containsKeys("F7", "F8", "F9");
        for (String f : List.of("fx/fx-swap.v2.sutra.yaml", "commodities/listed-future.v1.sutra.yaml", "credit/netting-set.v1.sutra.yaml")) {
            assertThat(load(f).panels()).isNotEmpty();
        }
        assertThat(load("credit/netting-set.v1.sutra.yaml").panels().get(0).options().get("series")).isInstanceOf(List.class);
    }

    @Test
    void everySutraDeclaresTheLanguageVersionItIsWrittenIn() {
        String body = "sutra: v-check\nversion: 1\nmatch: { kind: trade }\n";
        assertThat(parser.parse("rachana: 1\n" + body, "v.sutra.yaml", "rates").id()).isEqualTo("v-check@1");
        assertThatThrownBy(() -> parser.parse(body, "v.sutra.yaml", "rates"))
                .satisfies(e -> assertThat(((SutraException) e).problems()).extracting(SutraProblem::code).contains("DRS-2009"));
        assertThatThrownBy(() -> parser.parse("rachana: 2\n" + body, "v.sutra.yaml", "rates"))
                .satisfies(e -> assertThat(((SutraException) e).problems().get(0).message()).contains("not a language version this server reads"));
        assertThatThrownBy(() -> parser.parse("rachana: 1\nnotes: [a, b]\n" + body, "v.sutra.yaml", "rates"))
                .satisfies(e -> assertThat(((SutraException) e).problems()).extracting(SutraProblem::message).anySatisfy(m -> assertThat(m).contains("'notes' is plain text")));
    }

    @Test
    void reportsEveryProblemWithLineAndColumn() {
        String yaml = """
                rachana: 1
                sutra: Bad_Name
                version: 0
                match: { kind: trade }
                colour: red
                strip:
                  - { label: X }
                panels:
                  - { id: a, kind: table, key: F2 }
                  - { id: a, kind: pie }
                  - { id: b, kind: kv, key: F2, wobble: 1 }
                """;
        assertThatThrownBy(() -> parser.parse(yaml, "bad.yaml", "x"))
                .isInstanceOfSatisfying(SutraException.class, e -> {
                    assertThat(e.problems()).extracting(SutraProblem::code).contains(
                            "DRS-2020", "DRS-2011", "DRS-2010", "DRS-2022", "DRS-2021", "DRS-2023", "DRS-2025");
                    assertThat(e.problems()).filteredOn(p -> p.code().equals("DRS-2011"))
                            .first().satisfies(p -> assertThat(p.location().line()).isEqualTo(5));
                });
    }

    @Test
    void yamlSyntaxErrorsHaveAPosition() {
        assertThatThrownBy(() -> parser.parse("rachana: 1\nsutra: x\n  version: [1,\n", "broken.yaml", "x"))
                .isInstanceOfSatisfying(SutraException.class,
                        e -> assertThat(e.problems().get(0).code()).isEqualTo("DRS-2001"));
    }

    @Test
    void aTablePanelMayTurnItsSearchOffAndOnlyTablesHaveOne() {
        String ok = """
                rachana: 1
                sutra: quiet-table
                version: 1
                match: { kind: trade }
                panels:
                  - id: legs
                    kind: table
                    rows: $.legs
                    search: false
                    columns:
                      - { label: Leg, bind: "@.leg" }
                """;
        var s = parser.parse(ok, "quiet.yaml", "x");
        assertThat(s.panels().get(0).option("search")).contains("false");
        String bad = """
                rachana: 1
                sutra: noisy-kv
                version: 1
                match: { kind: trade }
                panels:
                  - { id: terms, kind: kv, search: false }
                """;
        assertThatThrownBy(() -> parser.parse(bad, "noisy.yaml", "x"))
                .isInstanceOfSatisfying(SutraException.class, e -> assertThat(e.problems()).extracting(SutraProblem::message)
                        .anySatisfy(m -> assertThat(m).contains("'search' applies only to panels that show a table")));
    }

    @Test
    void theChartAndAggregateKindsParseAndRejectValuesTheyDoNotAllow() {
        String ok = """
                rachana: 1
                sutra: chart-kinds
                version: 1
                match: { kind: trade }
                panels:
                  - { id: w, kind: waterfall, rows: $.explain, label: step, value: pnl, sum: Closing }
                  - id: h
                    kind: histogram
                    rows: $.scenarios
                    bins: 30
                    markers:
                      - { label: VaR 99%, value: "-$.var99", tone: neg }
                  - { id: s, kind: scatter, rows: $.books, x: var, y: pnl, group: desk }
                  - { id: c, kind: candlestick, rows: $.ohlc, volume: volume }
                  - { id: g, kind: graph, nodes: $.tree.nodes, edges: $.tree.edges, layout: force }
                  - { id: t, kind: timeline, rows: $.events }
                  - { id: p, kind: pivot, rows: $.positions, by: book, across: currency, value: mtm, agg: avg, heat: true }
                """;
        var s = parser.parse(ok, "charts.yaml", "x");
        assertThat(s.panels()).extracting(x -> x.kind().id())
                .containsExactly("waterfall", "histogram", "scatter", "candlestick", "graph", "timeline", "pivot");
        assertThat(s.panels().get(1).options().get("markers")).isInstanceOf(List.class);
        String bad = """
                rachana: 1
                sutra: bad-charts
                version: 1
                match: { kind: trade }
                panels:
                  - { id: p, kind: pivot, rows: $.positions, by: book, across: currency, agg: median, heat: yes please }
                  - { id: h, kind: histogram, rows: $.scenarios, bins: 0, markers: [ { label: VaR } ] }
                  - { id: g, kind: graph, nodes: $.n, layout: circle }
                  - { id: s, kind: scatter, rows: $.books, x: var }
                  - { id: w, kind: waterfall, rows: $.explain, colors: rainbow }
                """;
        assertThatThrownBy(() -> parser.parse(bad, "bad-charts.yaml", "x"))
                .isInstanceOfSatisfying(SutraException.class, e -> {
                    assertThat(e.problems()).filteredOn(p -> p.code().equals("DRS-2029")).extracting(SutraProblem::message).hasSize(6)
                            .anySatisfy(m -> assertThat(m).contains("must be one of sum, count, avg, min, max, not 'median'"))
                            .anySatisfy(m -> assertThat(m).contains("'heat' of 'pivot' panels must be true or false"))
                            .anySatisfy(m -> assertThat(m).contains("'bins' of 'histogram' panels must be a whole number from 1 to 200"))
                            .anySatisfy(m -> assertThat(m).contains("each histogram marker must be a mapping with a 'value'"))
                            .anySatisfy(m -> assertThat(m).contains("must be one of tree, force, not 'circle'"))
                            .anySatisfy(m -> assertThat(m).contains("option 'colors' of 'waterfall' panels must be one of gain-loss, theme, not 'rainbow'"));
                    assertThat(e.problems()).extracting(SutraProblem::message).contains("'scatter' panel 's' needs option 'y'");
                });
    }
}
