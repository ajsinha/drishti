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

import com.ash.drishti.api.DataNode;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.el.EvalContext;
import com.ash.drishti.rachana.el.Values;
import com.ash.drishti.rachana.format.Formats;
import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.rachana.model.Sutra;
import com.ash.drishti.rachana.model.Wiring;
import com.ash.drishti.rachana.parse.SutraBlocks;
import com.ash.drishti.rachana.parse.SutraParser;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Sutra Next phases 2 and 3, the grammar: {@code source:} as a mapping, {@code emits:}, {@code waits:}, {@code children:} as groups,
 * {@code @panel.event.field}, {@code @parent} and {@code $src} in Rachana-EL, and the lint (DRS-2057 events and sources, DRS-2056
 * children): unknown panels, events and fields, a cycle, a depth over three, a type mismatch, a use that wires its panel.
 */
class SutraWiringTest {

    static final ElCompiler EL = new ElCompiler();

    @AfterEach
    void empty() {
        SutraBlocks.load(List.of());
    }

    static String sutra(String panels) {
        return "rachana: 1\nsutra: probe\nversion: 1\nmatch: { kind: portfolio-trades }\ntitle: { id: $.PORTFOLIO }\npanels:\n" + panels;
    }

    static final String DESK = """
              - id: trades
                kind: table
                rows: $.TRADES
                columns: [ { label: Trade, bind: "@.TRADE_ID" } ]
                emits:
                  - { name: open, on: row-click, payload: { trade: "@.TRADE_ID", cpty: { bind: "@.COUNTERPARTY", type: id }, n: { bind: "@.N", type: number } } }
                children:
                  - place: expand-row
                    source: { kind: trade, id: "@parent.open.trade" }
                    waits: Pick a trade
                    panels:
                      - id: flows
                        kind: ladder
                        rows: $src.EVENTS
                        columns: [ { label: Date, bind: "@.EVENT_DATE" } ]
              - id: limits
                kind: kv
                title: "Limits of ${@trades.open.cpty}"
                area: right
                waits: Pick a trade to see its counterparty's limits
                source: { pack: counterparty-risk, kind: credit-limit, where: "@.COUNTERPARTY == @trades.open.cpty", limit: 5 }
                columns: [ { label: Used, bind: "$src[0].USED" } ]
            """;

    static Sutra parse(String panels) {
        return new SutraParser().parse(sutra(panels), "probe.v1.sutra.yaml", "x");
    }

    static List<SutraProblem> lint(String panels) {
        return new SutraExpressions(EL).check(parse(panels));
    }

    static List<SutraProblem> problems(String panels) {
        try {
            return lint(panels);
        } catch (SutraException e) {
            return e.problems();
        }
    }

    @Test
    void theGrammarReadsSourcesEventsWaitsAndChildren() {
        Sutra s = parse(DESK);
        Panel trades = s.panels().get(0);
        Wiring.Emit open = trades.wiring().emit("open").orElseThrow();
        assertThat(open.on()).isEqualTo("row-click");
        assertThat(open.payload()).containsOnlyKeys("trade", "cpty", "n");
        assertThat(open.payload().get("cpty").type()).isEqualTo("id");
        assertThat(open.payload().get("trade").type()).isEqualTo("any");
        Wiring.Children g = trades.wiring().children().get(0);
        assertThat(g.place()).isEqualTo("expand-row");
        assertThat(g.show()).isEqualTo("on-select");
        assertThat(g.source().kind()).isEqualTo("trade");
        assertThat(g.source().id()).isEqualTo("@parent.open.trade");
        assertThat(g.panels()).extracting(Panel::id).containsExactly("flows");
        Panel limits = s.panels().get(1);
        assertThat(limits.wiring().source().pack()).isEqualTo("counterparty-risk");
        assertThat(limits.wiring().source().single()).isFalse();
        assertThat(limits.wiring().source().limit()).isEqualTo(5);
        assertThat(limits.wiring().waits()).startsWith("Pick a trade");
        assertThat(limits.options()).doesNotContainKeys("source", "emits", "waits");
        assertThat(lint(DESK)).isEmpty();
        assertThat(SutraWiring.nodes(s)).containsOnlyKeys("trades", "flows", "limits");
        assertThat(SutraWiring.nodes(s).get("flows").depth()).isEqualTo(2);
        assertThat(SutraWiring.reads(limits, null)).containsExactly("trades");
    }

    @Test
    void aComputedSourceTakesTypedParametersAsExpressions() {
        Sutra s = parse("""
                  - { id: var, kind: metric, value: $src.SUM, source: { kind: var-result, id: "$.DESK", params: { confidence: ctl.conf, horizon: 1, live: true } } }
                """);
        Wiring.Source src = s.panels().get(0).wiring().source();
        assertThat(src.params()).containsExactly(Map.entry("confidence", "ctl.conf"), Map.entry("horizon", "1"), Map.entry("live", "true"));
        assertThat(src.expressions()).contains("ctl.conf", "1");
        assertThat(problems("  - { id: v, kind: kv, source: { kind: var-result, where: \"@.A > 1\", params: { x: 1 } } }\n"))
                .anySatisfy(p -> assertThat(p.code() + " " + p.message()).contains("DRS-2057").contains("'params' goes with a source read by id"));
        assertThat(problems("  - { id: v, kind: kv, source: { kind: var-result, id: $.A, params: [1, 2] } }\n"))
                .anySatisfy(p -> assertThat(p.code() + " " + p.message()).contains("DRS-2012").contains("mapping of parameter names"));
        assertThat(problems("  - { id: v, kind: kv, source: { kind: var-result, id: $.A, params: { 9x: 1, y: { a: 1 } } } }\n"))
                .anySatisfy(p -> assertThat(p.message()).contains("'9x' is not a parameter name"))
                .anySatisfy(p -> assertThat(p.message()).contains("parameter y is an expression"));
    }

    @Test
    void theOlderFormsStayOptions() {
        Sutra s = parse("""
                  - { id: fx, kind: line, rows: $.POINTS, source: "link($.CURVE, 'ir-curve')" }
                  - { id: tree, kind: table, rows: $.NODES, children: "@.KIDS", columns: [ { label: N, bind: "@.N" } ] }
                """);
        assertThat(s.panels().get(0).options()).containsEntry("source", "link($.CURVE, 'ir-curve')");
        assertThat(s.panels().get(0).wiring().isNone()).isTrue();
        assertThat(s.panels().get(1).options()).containsEntry("children", "@.KIDS");
    }

    @Test
    void eventsAndSourceAreReadInExpressions() {
        DataNode doc = new JsonCodec().read("{\"A\":1}");
        DataNode open = new JsonCodec().read("{\"open\":{\"trade\":\"T-1\"}}");
        DataNode src = new JsonCodec().read("{\"POINTS\":[1,2,3]}");
        EvalContext c = EvalContext.of(doc, Formats.defaults()).withScope(new EvalContext.Scope(Map.of("by-book", open), open, src));
        assertThat(Values.simplify(EL.compile("@by-book.open.trade").eval(c))).isEqualTo("T-1");
        assertThat(Values.simplify(EL.compile("@parent.open.trade").eval(c))).isEqualTo("T-1");
        assertThat(Values.simplify(EL.compile("size($src.POINTS)").eval(c))).isEqualTo(3L);
        assertThat(Values.truthy(EL.compile("@other.open == null").eval(c))).isTrue();     // not fired: nothing
        assertThat(Values.simplify(EL.compile("@.X").eval(c.withRow(new JsonCodec().read("{\"X\":7}"), 0)))).isEqualTo(7L);
        assertThat(Values.simplify(EL.compile("$.A - 1").eval(c))).isEqualTo(0L);
        assertThat(Wiring.refs("'@nope' + @trades.open.trade + x@mail.com")).containsExactly(new Wiring.Ref("trades", "open", "trade"));
    }

    @Test
    void lintFindsUnknownPanelsEventsAndFields() {
        assertThat(problems(DESK.replace("@trades.open.cpty}", "@trade.open.cpty}")))
                .anySatisfy(p -> assertThat(p.code() + " " + p.message()).contains("DRS-2057").contains("@trade, which is no panel"));
        assertThat(problems(DESK.replace("${@trades.open.cpty}", "${@trades.pick.cpty}")))
                .anySatisfy(p -> assertThat(p.message()).contains("emits open"));
        assertThat(problems(DESK.replace("${@trades.open.cpty}", "${@trades.open.desk}")))
                .anySatisfy(p -> assertThat(p.message()).contains("payload of open has trade, cpty, n"));
        assertThat(problems(DESK.replace("rows: $src.EVENTS", "rows: $src.EVENTS\n            title: \"${@parent.drill.x}\"")))
                .anySatisfy(p -> assertThat(p.code()).isEqualTo("DRS-2056"));
        assertThat(problems(DESK.replace("id: \"@parent.open.trade\"", "id: \"@parent.open.n\"")))
                .anySatisfy(p -> assertThat(p.message()).contains("a number; an id is text"));
        assertThat(problems("  - { id: a, kind: kv, title: \"${@parent.x.y}\", columns: [ { label: A, bind: $.A } ] }\n"))
                .anySatisfy(p -> assertThat(p.message()).contains("is not a child panel"));
        assertThat(problems("  - { id: a, kind: kv, columns: [ { label: A, bind: $src.A } ] }\n"))
                .anySatisfy(p -> assertThat(p.message()).contains("reads $src but names no source"));
    }

    @Test
    void lintFindsACycleAndADepthOverThree() {
        String cycle = """
                  - id: a
                    kind: table
                    rows: $.A
                    where: "@.K == @b.pick.k"
                    columns: [ { label: K, bind: "@.K" } ]
                    emits: [ { name: pick, on: row-click, payload: { k: "@.K" } } ]
                  - id: b
                    kind: table
                    rows: $.B
                    where: "@.K == @a.pick.k"
                    columns: [ { label: K, bind: "@.K" } ]
                    emits: [ { name: pick, on: row-click, payload: { k: "@.K" } } ]
                """;
        assertThat(problems(cycle)).anySatisfy(p -> assertThat(p.message()).contains("cycle: a > b > a"));
        String deep = """
                  - id: l1
                    kind: table
                    rows: $.A
                    columns: [ { label: K, bind: "@.K" } ]
                    emits: [ { name: pick, on: row-click, payload: { k: "@.K" } } ]
                    children:
                      - panels:
                          - id: l2
                            kind: table
                            rows: $.A
                            columns: [ { label: K, bind: "@.K" } ]
                            emits: [ { name: pick, on: row-click, payload: { k: "@.K" } } ]
                            children:
                              - panels:
                                  - id: l3
                                    kind: table
                                    rows: $.A
                                    columns: [ { label: K, bind: "@.K" } ]
                                    emits: [ { name: pick, on: row-click, payload: { k: "@.K" } } ]
                                    children:
                                      - panels: [ { id: l4, kind: kv, title: "${@parent.pick.k}", columns: [ { label: K, bind: $.K } ] } ]
                """;
        assertThat(problems(deep)).anySatisfy(p -> {
            assertThat(p.code()).isEqualTo("DRS-2056");
            assertThat(p.message()).contains("'l4' is nested 4 deep");
        });
        assertThat(problems(deep.substring(0, deep.indexOf("\n", deep.lastIndexOf("emits")) + 1)))
                .isEmpty();
    }

    @Test
    void theParserRefusesWhatIsNotBuilt() {
        assertThatThrownBy(() -> parse("  - { id: a, kind: kv, columns: [ { label: A, bind: $.A } ], emits: [ { name: pick, on: row-click } ] }\n"))
                .isInstanceOf(SutraException.class).hasMessageContaining("cannot fire on row-click");
        assertThatThrownBy(() -> parse("  - { id: a, kind: table, rows: $.A, columns: [ { label: A, bind: $.A } ], emits: [ { name: pick, on: tick } ] }\n"))
                .hasMessageContaining("come in later phases");
        assertThatThrownBy(() -> parse("  - { id: a, kind: kv, columns: [ { label: A, bind: $.A } ], source: { kind: trade } }\n"))
                .hasMessageContaining("it names none");
        assertThatThrownBy(() -> parse("  - { id: a, kind: kv, columns: [ { label: A, bind: $.A } ], source: { model: risk } }\n"))
                .hasMessageContaining("BI model is not built yet");
        assertThatThrownBy(() -> parse("  - { id: a, kind: kv, columns: [ { label: A, bind: $.A } ], children: [ { place: expand-row, panels: [ { id: b, kind: kv, columns: [ { label: A, bind: $.A } ] } ] } ] }\n"))
                .hasMessageContaining("only a table or ladder has rows");
        assertThatThrownBy(() -> parse(DESK.replace("id: flows", "id: limits")))
                .hasMessageContaining("duplicate panel id 'limits'");
    }

    @Test
    void aUseCarriesItsPanelsWiring() {
        assertThat(SutraBlocks.load(List.of(Path.of("../config/ui-kit/fragments")))).isEmpty();
        Sutra s = parse("""
                  - use: field-list@1
                    with: { id: limit, title: Limit, rows: $src }
                    source: { kind: credit-limit, id: "'LIM-X'" }
                    area: right
                """);
        assertThat(s.panels().get(0).wiring().source().kind()).isEqualTo("credit-limit");
        assertThat(s.panels().get(0).area().name()).isEqualTo("RIGHT");
        assertThatThrownBy(() -> parse("  - { use: curve-overview@1, emits: [ { name: pick, on: row-click } ] }\n"))
                .hasMessageContaining("on a use that brings 2 panels");
        assertThatThrownBy(() -> parse("  - { use: field-list@1, colour: red }\n")).hasMessageContaining("not 'colour'");
    }
}
