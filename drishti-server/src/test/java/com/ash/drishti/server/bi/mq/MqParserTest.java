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
package com.ash.drishti.server.bi.mq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Step 1 of the validation (shape, DRS-9150), YAML and JSON reading the same, and the canonical form the cache keys on. */
class MqParserTest {

    static final String FULL = """
            mq: 1
            model: trading-risk@1
            define:
              Loss share: "ratio([Open MTM], [MTM])"
            select:
              fields:   [counterparties.sector, trades.tradeDate.month]
              measures: [MTM, Exposure (EE peak), Loss share]
            where:
              - { field: trades.currency, op: in, values: [USD, EUR] }
              - { field: trades.tradeDate, op: relative, window: last-5-business-days }
              - { field: counterparties.id, op: top, n: 10, by: Exposure (EE peak), dir: desc }
            having:
              - { measure: MTM, op: lt, value: 0 }
            time:
              as-of: 2026-10-09
            sort:  [{ by: MTM, desc: true }]
            limit: 100
            totals: { levels: [[], [counterparties.sector]] }
            drill:  { hierarchy: Counterparty, path: [Banking] }
            shape: grouped
            params: {}
            options: { layer: auto, explain: false, format: json }
            """;

    static String code(String text) {
        try {
            MqParser.parse(text);
            return "ok";
        } catch (MqException e) {
            return e.problems().get(0).code() + " " + e.problems().get(0).path();
        }
    }

    @Test
    void theSpecsFullExampleReads() {
        ModelQuery q = MqParser.parse(FULL);
        assertThat(q.model()).isEqualTo("trading-risk@1");
        assertThat(q.define()).containsKey("Loss share");
        assertThat(q.where()).extracting(ModelQuery.Where::op).containsExactly("in", "relative", "top");
        assertThat(q.where().get(2).n()).isEqualTo(10);
        assertThat(q.time().asOf()).hasToString("2026-10-09");
        assertThat(q.totals().levels()).containsExactly(List.of(), List.of("counterparties.sector"));
        assertThat(q.drill().path()).containsExactly("Banking");
    }

    @Test
    void yamlAndJsonInAnyKeyOrderAreTheSameQuery() throws Exception {
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(new com.fasterxml.jackson.databind.ObjectMapper(
                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory()).readValue(FULL, Map.class));
        assertThat(MqParser.hash(MqParser.parse(json))).isEqualTo(MqParser.hash(MqParser.parse(FULL)));
        String a = "{ \"mq\": 1, \"model\": \"m\", \"select\": { \"measures\": [\"MTM\"], \"fields\": [\"t.d\"] }, \"limit\": 5 }";
        String b = "{ \"limit\": 5, \"select\": { \"fields\": [\"t.d\"], \"measures\": [\"MTM\"] }, \"model\": \"m\", \"mq\": 1 }";
        assertThat(MqParser.json(MqParser.parse(a))).isEqualTo(MqParser.json(MqParser.parse(b)));
        assertThat(MqParser.hash(MqParser.parse(a))).isNotEqualTo(MqParser.hash(MqParser.parse(a.replace("5", "6"))));
    }

    @Test
    void theShapeIs9150() {
        assertThat(code("{ model: m, select: { measures: [MTM] } }")).isEqualTo("DRS-9150 mq");
        assertThat(code("{ mq: 2, model: m, select: { measures: [MTM] } }")).isEqualTo("DRS-9150 mq");
        assertThat(code("{ mq: 1, model: m, select: { measures: [MTM] }, colour: red }")).isEqualTo("DRS-9150 colour");
        assertThat(code("{ mq: 1, model: m, select: { measures: MTM } }")).isEqualTo("DRS-9150 select.measures");
        assertThat(code("{ mq: 1, model: m, select: { } }")).isEqualTo("DRS-9150 select");
        assertThat(code("{ mq: 1, model: m, select: { measures: [MTM] }, time: { as-of: 2026-10-09, live: true } }")).isEqualTo("DRS-9150 time");
        assertThat(code("{ mq: 1, model: m, select: { measures: [MTM] }, time: { as-of: yesterday } }")).isEqualTo("DRS-9150 time.as-of");
        assertThat(code("{ mq: 1, model: m, select: { measures: [MTM] }, where: [{ field: t.d, op: like, value: x }] }")).isEqualTo("DRS-9150 where[0].op");
        assertThat(code("{ mq: 1, model: m, select: { measures: [MTM] }, limit: 0 }")).isEqualTo("DRS-9150 limit");
        assertThat(code("{ mq: 1, model: m, select: { measures: [MTM] }, params: { shock: 0.1 } }")).isEqualTo("DRS-9150 params");
        assertThat(code("{ mq: 1, model: m, select: { measures: [MTM] }, shape: pivot }")).isEqualTo("DRS-9150 shape");
        assertThat(code("{ mq: 1, model: m, select: { measures: [MTM] }, options: { format: xml } }")).isEqualTo("DRS-9150 options.format");
        assertThat(code("mq: [")).startsWith("DRS-9150");
        MqException all = (MqException) org.assertj.core.api.Assertions.catchThrowable(() -> MqParser.parse("{ mq: 1, colour: red, size: 2, select: { measures: [M] } }"));
        assertThat(all.problems()).hasSize(2);                                                          // every problem of the step
        assertThatThrownBy(() -> MqParser.parse("{ select: { measures: [M] } }")).isInstanceOf(MqException.class)
                .satisfies(e -> assertThat(((MqException) e).problems().get(0).fix()).containsEntry("to", 1));
    }
}
