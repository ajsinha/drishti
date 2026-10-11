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
package com.ash.drishti.server.bi.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.server.bi.dataset.DatasetParser.Problem;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The dataset YAML: a good model parses; each class of mistake is found with its DRS code and the path of the key. */
class DatasetParserTest {

    static final String GOOD = """
            rachana: 1
            dataset: desk-risk
            version: 3
            title: Desk risk
            tables:
              trades:
                source: { connector: trading-lake, kind: trade }
                refresh: { on-load: true }
                columns:
                  tradeId: { type: string, key: true }
                  desk: string
                  cpty: { path: $.counterparty.id, type: string }
                  notional: { type: decimal }
                  mtm: { type: decimal }
                  tradeDate: { type: date }
                  big: { type: decimal, expr: "notional / 1000" }
              desks:
                source: { connector: desks, kind: desk }
                columns:
                  desk: { type: string, key: true }
                  region: string
            relationships:
              - { from: trades.desk, to: desks.desk, cardinality: many-to-one }
            measures:
              Notional: { expr: "sum(trades.notional)", format: amount0 }
              MTM: "sum(trades.mtm)"
              Trades: { expr: "count(trades)" }
              MTM per 1m: { expr: "ratio(sum(trades.mtm), sum(trades.notional)) * 1000000" }
              BuyMtm: { expr: "sum(trades.mtm, trades.desk == 'A')" }
              P95: { expr: "percentile(trades.mtm, 0.95)" }
              Share: { expr: "[MTM] / [Notional]" }
            hierarchies:
              Date: [trades.tradeDate.year, trades.tradeDate.month, trades.tradeDate]
              Desk: [desks.region, desks.desk]
            security:
              rows:
                - { role: desk-head, where: "trades.desk in user.desks" }
                - { role: risk, where: "true" }
              masked: [cpty]
            rollups:
              - { name: byDesk, table: trades, by: [desk], measures: [Notional, MTM, Trades] }
            """;

    private static List<Problem> errors(String yaml) {
        return DatasetParser.parse(yaml).problems().stream().filter(Problem::error).toList();
    }

    @Test
    void aGoodModelParsesWithNoErrors() {
        DatasetParser.Result r = DatasetParser.parse(GOOD);
        assertThat(r.problems().stream().filter(Problem::error)).isEmpty();
        assertThat(r.ok()).isTrue();
        Dataset d = r.dataset();
        assertThat(d.name()).isEqualTo("desk-risk");
        assertThat(d.version()).isEqualTo(3);
        assertThat(d.tables().keySet()).containsExactly("trades", "desks");
        assertThat(d.tables().get("trades").columns().get("cpty").path()).isEqualTo("counterparty.id");
        assertThat(d.tables().get("trades").columns().get("desk").path()).isEqualTo("desk");
        assertThat(d.measures()).containsKeys("Notional", "MTM per 1m", "Share");
        assertThat(d.security().rows()).hasSize(2);
        assertThat(d.security().masked()).containsExactly("cpty");
    }

    @Test
    void structureMistakesAreDrs9010WithThePath() {
        assertThat(errors("not: [valid")).extracting(Problem::code).containsExactly("DRS-9010");
        assertThat(errors("- a\n- b")).extracting(Problem::code).containsExactly("DRS-9010");
        assertThat(errors("dataset: x\ntables:\n  t:\n    source: { kind: k }\n    mode: sometimes\n    columns: { a: strng }"))
                .extracting(Problem::path).contains("tables.t.mode", "tables.t.columns.a.type");
        assertThat(errors("dataset: x\ntables:\n  'bad name':\n    source: { kind: k }\n    columns: { a: string }")).extracting(Problem::path).contains("tables.bad name");
        assertThat(errors(GOOD.replace("cardinality: many-to-one", "cardinality: lots"))).extracting(Problem::path).contains("relationships[0].cardinality");
        assertThat(errors(GOOD.replace("engine", "engine")).stream().map(Problem::code)).doesNotContain("DRS-9012");
        assertThat(errors(GOOD + "engine: spark\n")).extracting(Problem::path).contains("engine");
        assertThat(errors(GOOD.replace("on-load: true", "schedule: \"* *\""))).extracting(Problem::path).contains("tables.trades.refresh.schedule");
    }

    @Test
    void expressionMistakesAreDrs9011() {
        assertThat(errors(GOOD.replace("sum(trades.notional)", "sum(trades.nope)"))).extracting(Problem::code).contains("DRS-9011");
        assertThat(errors(GOOD.replace("sum(trades.notional)\"", "trades.notional\""))).extracting(Problem::message).anyMatch(m -> m.contains("outside an aggregate"));
        assertThat(errors(GOOD.replace("count(trades)", "frobnicate(trades)"))).extracting(Problem::message).anyMatch(m -> m.contains("unknown function 'frobnicate'"));
        assertThat(errors(GOOD.replace("sum(trades.notional)\"", "sum(sum(trades.notional))\""))).extracting(Problem::message).anyMatch(m -> m.contains("do not nest"));
        assertThat(errors(GOOD.replace("percentile(trades.mtm, 0.95)", "percentile(trades.mtm, 7)"))).extracting(Problem::code).contains("DRS-9011");
        assertThat(errors(GOOD.replace("sum(trades.notional)\"", "sum(trades.notional\""))).extracting(Problem::message).anyMatch(m -> m.contains("expected ')'"));
        assertThat(errors(GOOD.replace("[MTM] / [Notional]", "[Nope] / [Notional]"))).extracting(Problem::message).anyMatch(m -> m.contains("[Nope] is not a measure"));
        assertThat(errors(GOOD.replace("MTM: \"sum(trades.mtm)\"", "MTM: \"[Share]\""))).extracting(Problem::message).anyMatch(m -> m.contains("circle"));
        assertThat(errors(GOOD.replace("notional / 1000", "sum(notional)"))).extracting(Problem::message).anyMatch(m -> m.contains("cannot aggregate"));
        assertThat(errors(GOOD.replace("trades.desk in user.desks", "trades.desk in user.planet"))).extracting(Problem::message).anyMatch(m -> m.contains("not an attribute"));
        assertThat(errors(GOOD.replace("count(trades)", "count(trades) + user.name"))).extracting(Problem::message).anyMatch(m -> m.contains("does not depend on the reader"));
    }

    @Test
    void referenceMistakesAreDrs9012() {
        assertThat(errors(GOOD.replace("to: desks.desk", "to: desks.zone"))).extracting(Problem::code).contains("DRS-9012");
        assertThat(errors(GOOD.replace("to: desks.desk", "to: shops.desk"))).extracting(Problem::message).anyMatch(m -> m.contains("a table the dataset does not have"));
        assertThat(errors(GOOD.replace("to: desks.desk", "to: desks.region, cardinality: many-to-one").replace("tradeDate: { type: date }", "tradeDate: { type: date }")))
                .isEmpty();                                                   // same type (string to string) is fine
        assertThat(errors(GOOD.replace("from: trades.desk", "from: trades.notional"))).extracting(Problem::message).anyMatch(m -> m.contains("joins decimal to string"));
        assertThat(errors(GOOD.replace("[trades.tradeDate.year", "[trades.tradeDate.fortnight, trades.tradeDate.year"))).extracting(Problem::code).contains("DRS-9012");
        assertThat(errors(GOOD.replace("masked: [cpty]", "masked: [ghost]"))).extracting(Problem::code).contains("DRS-9012");
        assertThat(errors(GOOD.replace("by: [desk]", "by: [ghost]"))).extracting(Problem::code).contains("DRS-9012");
        assertThat(errors(GOOD.replace("measures: [Notional, MTM, Trades]", "measures: [Notional, P95]"))).extracting(Problem::message)
                .anyMatch(m -> m.contains("cannot hold measure 'P95'"));
    }

    @Test
    void reservedGrammarIsDrs9013AndPythonMeasuresAreStoredNotRun() {
        assertThat(errors(GOOD.replace("sum(trades.notional)\"", "ytd(sum(trades.notional))\""))).extracting(Problem::code).contains("DRS-9013");
        assertThat(errors(GOOD.replace("count(trades)", "rank(trades.mtm)"))).extracting(Problem::code).contains("DRS-9013");
        DatasetParser.Result r = DatasetParser.parse(GOOD.replace("  Trades: { expr: \"count(trades)\" }", "  Trades: { python: risk.measures.attrib, inputs: [trades.mtm] }")
                .replace("measures: [Notional, MTM, Trades]", "measures: [Notional, MTM]"));
        assertThat(r.problems().stream().filter(Problem::error)).isEmpty();
        assertThat(r.problems()).anyMatch(p -> "warning".equals(p.level()) && p.code().equals("DRS-9013"));
    }

    @Test
    void theParserIsBoundedAndTotal() {
        String deep = "(".repeat(200) + "1" + ")".repeat(200);
        assertThat(errors(GOOD.replace("sum(trades.notional)\"", "sum(" + deep + ")\""))).extracting(Problem::message).anyMatch(m -> m.contains("nests deeper"));
        String long1 = "1" + " + 1".repeat(1000);
        assertThat(errors(GOOD.replace("sum(trades.notional)\"", "sum(" + long1 + ")\""))).extracting(Problem::message).anyMatch(m -> m.contains("longer than"));
    }

    @Test
    void anArrayTableHasItsParentIdAndIndex() {
        Dataset d = DatasetParser.parse("""
                dataset: legs
                tables:
                  trades: { source: { connector: x, kind: trade }, columns: { tradeId: string } }
                  legs:
                    source: { connector: x, kind: trade, each: legs }
                    columns: { notional: decimal, currency: string }
                relationships:
                  - { from: legs.id, to: trades.id, cardinality: many-to-one }
                """).dataset();
        assertThat(d.tables().get("legs").each()).isEqualTo("legs");
        assertThat(new Resolver(d).column("legs", "index")).isNotNull();
        assertThat(new Resolver(d).column("trades", "index")).isNull();
        assertThat(DatasetParser.parse("dataset: legs\ntables:\n  t: { source: { kind: k }, columns: { index: string } }").problems()).anyMatch(p -> p.path().endsWith("index"));
    }
}
