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
package com.ash.drishti.server.bi.layer;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.server.bi.dataset.DatasetParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The {@code rachana: 2} dataset: the spec's own examples read cleanly, and one case per row of the problem table (section 1.11). */
class DatasetDefParserTest {

    static final String LIMITS = """
            rachana: 2
            dataset: desk-limits
            version: 1.0.0
            title: Desk limits
            owner: risk-control
            source:
              connector: risk-warehouse
              query: "SELECT desk_id, limit_type, limit_amount, currency FROM limits WHERE as_of = :asOf"
              params: { asOf: as-of }
            grain: { says: One row per desk and limit type per date, keys: [desk_id, limit_type] }
            time: { mode: snapshot }
            refresh: "business-days 06:30"
            columns:
              desk_id:      { type: string, role: dimension, references: desk }
              limit_type:   { type: string, role: dimension }
              limit_amount: { type: decimal, role: amount, unit: { currency: currency }, additive: { over-time: last }, aggregate: sum, format: amount0 }
              currency:     { type: string, role: dimension, meaning: currency }
            tests: [{ name: One limit per desk and type, unique: [desk_id, limit_type] }]
            """;

    static final String PYTHON = """
            rachana: 2
            dataset: pnl-vs-exposure-py
            version: 1.0.0
            source:
              python: pnl-vs-exposure
              inputs:
                pnl:      { model: trading-risk@1, select: { fields: [counterparties.id], measures: ["P&L 1d", Trades] } }
                exposure: { model: counterparty-exposure@1, select: { fields: [counterparties.id], measures: [Exposure (EE peak)] } }
              params: { floor: 1.0 }
            grain: { says: One row per counterparty, keys: [counterparty] }
            time: { mode: snapshot }
            columns:
              counterparty:     { type: string,  role: key, references: counterparty }
              desk_pnl:         { type: decimal, role: amount, unit: { currency: USD }, aggregate: sum }
              pnl_per_exposure: { type: decimal, role: ratio, additive: false, aggregate: avg }
            """;

    static final String TRADES = """
            rachana: 2
            dataset: trades
            version: 1.0.0
            source: { connector: trading-lake, kind: trade }
            grain: { keys: [id] }
            columns:
              desk:     { type: string, role: dimension, references: desk }
              currency: { type: string, path: ccy }
              notional: { type: decimal, unit: { currency: currency }, aggregate: sum }
              mtm:      { type: decimal, unit: { currency: USD }, aggregate: sum }
            security:
              rows:
                - { role: desk-head, where: "trades.desk in user.desks" }
                - { role: [risk, analyst], where: "True" }
              masked: [mtm]
            """;

    static List<String> codes(String yaml) {
        return DatasetDefParser.parse(yaml).problems().stream().filter(DatasetParser.Problem::error).map(DatasetParser.Problem::code).toList();
    }

    @Test
    void theSpecsExamplesReadCleanly() {
        for (String y : List.of(LIMITS, PYTHON, TRADES)) {
            DatasetDefParser.Result r = DatasetDefParser.parse(y);
            assertThat(r.problems()).as(y).noneMatch(DatasetParser.Problem::error);
        }
        DatasetDef limits = DatasetDefParser.parse(LIMITS).dataset();
        assertThat(limits.source().type()).isEqualTo("connector");
        assertThat(limits.columns().get("limit_amount").semiAdditive()).isTrue();
        assertThat(limits.columns().get("limit_amount").perRowCurrency(limits.columns())).isTrue();
        assertThat(limits.grain().keys()).containsExactly("desk_id", "limit_type");
        DatasetDef trades = DatasetDefParser.parse(TRADES).dataset();
        assertThat(trades.columns().get("notional").additive()).isEqualTo("true");          // amounts add by default
        assertThat(trades.columns().get("desk").role()).isEqualTo("dimension");
        assertThat(trades.security().rows()).hasSize(2);
        assertThat(trades.defaultAlias()).isEqualTo("trades");
        assertThat(DatasetDefParser.isVersion2(TRADES)).isTrue();
    }

    @Test
    void structureIs9100() {
        assertThat(codes(TRADES.replace("version: 1.0.0", "version: 1"))).contains("DRS-9100");
        assertThat(codes(TRADES.replace("dataset: trades", "dataset: pack.trading.trade"))).contains("DRS-9100");     // the pack. names are the packs'
        assertThat(codes(TRADES.replace("source: { connector: trading-lake, kind: trade }\n", ""))).contains("DRS-9100");
        assertThat(codes(TRADES.replace("kind: trade }", "kind: trade, query: \"SELECT 1\" }"))).contains("DRS-9100");     // two sources
        assertThat(DatasetDefParser.parse(TRADES + "colour: red\n").problems()).anyMatch(p -> "DRS-9100".equals(p.code()) && "warning".equals(p.level()));
    }

    @Test
    void columnsAre9101() {
        assertThat(codes(TRADES.replace("{ type: string, path: ccy }", "{ type: text }"))).contains("DRS-9101");
        assertThat(codes(TRADES.replace("aggregate: sum }\n  mtm", "aggregate: median }\n  mtm"))).contains("DRS-9101");
        assertThat(codes(TRADES.replace("references: desk }", "role: attribute, of: nothing }"))).contains("DRS-9101");
        assertThat(codes(TRADES.replace("unit: { currency: USD }, aggregate: sum", "additive: { over-time: sometimes }"))).contains("DRS-9101");
        assertThat(codes(LIMITS.replace("columns:\n", "columns: {}\nold:\n"))).contains("DRS-9101");                        // a query dataset declares its columns
    }

    @Test
    void sourcesAre9102() {
        assertThat(codes(LIMITS.replace("params: { asOf: as-of }", "params: { asOf: user-text }"))).contains("DRS-9102");
        assertThat(codes(LIMITS.replace(":asOf\"", ":asOf AND desk = :desk\""))).contains("DRS-9102");                   // a parameter not bound
    }

    @Test
    void timeIs9103() {
        assertThat(codes(TRADES.replace("grain: { keys: [id] }", "grain: { keys: [id] }\ntime: { mode: sometimes }"))).contains("DRS-9103");
        assertThat(codes(LIMITS.replace("time: { mode: snapshot }", "time: { mode: live }"))).contains("DRS-9103");
        assertThat(codes(LIMITS.replace("time: { mode: snapshot }", "time: { mode: history }"))).contains("DRS-9103");
    }

    @Test
    void grainIs9104() {
        assertThat(codes(LIMITS.replace("keys: [desk_id, limit_type]", "keys: [desk, limit_type]"))).contains("DRS-9104");
        assertThat(codes(LIMITS.replace("grain: { says: One row per desk and limit type per date, keys: [desk_id, limit_type] }\n", ""))).contains("DRS-9104");
    }

    @Test
    void extendsIs9105() {
        String narrow = """
                rachana: 2
                dataset: trades-eu
                version: 1.0.0
                extends: pack.trading.trade@1
                where: "currency in ['EUR', 'GBP']"
                columns:
                  notionalM: { type: decimal, expr: "notional / 1000000", role: amount }
                security: { masked: [trader] }
                """;
        assertThat(codes(narrow)).isEmpty();
        assertThat(codes(narrow.replace("extends: pack.trading.trade@1\n", "extends: pack.trading.trade@1\nsource: { kind: trade }\n"))).contains("DRS-9105");
        assertThat(codes(TRADES + "where: \"desk == 'A'\"\n")).contains("DRS-9105");                                   // where only narrows a base
    }

    @Test
    void securityIs9106() {
        assertThat(codes(TRADES.replace("trades.desk in user.desks", "desks.region == 'EMEA'"))).contains("DRS-9106");    // another table's column
        assertThat(codes(TRADES.replace("masked: [mtm]", "masked: [trader]"))).contains("DRS-9106");
        assertThat(codes(TRADES.replace("trades.desk in user.desks", "desk in user.desks"))).isEmpty();                  // a bare column is its own
    }

    @Test
    void anInputThatIsNotAModelQueryIs9108() {
        assertThat(codes(PYTHON.replace("select: { fields: [counterparties.id], measures: [Exposure (EE peak)] }", "select: 7"))).contains("DRS-9108");
        assertThat(codes(PYTHON.replace("measures: [\"P&L 1d\", Trades] }", "measures: [\"P&L 1d\", Trades] }, colour: red"))).contains("DRS-9108");
    }

    @Test
    void laterWaveKeysAreReadAndKept() {
        String withAi = TRADES + "ai:\n  synonyms: { mtm: [mark to market] }\n";
        DatasetDefParser.Result r = DatasetDefParser.parse(withAi.replace("masked: [mtm]", "masked: [mtm]\n  hidden-for: { roles: [external], columns: [desk] }"));
        assertThat(r.ok()).isTrue();
        assertThat(r.dataset().ai().path("synonyms").has("mtm")).isTrue();
        assertThat(r.dataset().security().hiddenColumns()).containsExactly("desk");
    }

    @Test
    void aVersion2DatasetIsReadByTheVersion1ParserAsItsImplicitModel() {
        DatasetParser.Result r = DatasetParser.parse(TRADES);
        assertThat(r.ok()).as(r.problems().toString()).isTrue();
        assertThat(r.dataset().tables()).containsOnlyKeys("trades");
        assertThat(r.dataset().measures()).containsKeys("Count of trades", "Sum of notional", "Sum of mtm");
        assertThat(r.dataset().security().perTable()).isTrue();
        assertThat(r.dataset().security().masked()).containsExactly("trades.mtm");
        assertThat(DatasetParser.parse(LIMITS).ok()).isFalse();                                                           // a query dataset needs the model layer
    }
}
