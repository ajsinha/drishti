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
package com.ash.drishti.tables.check;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.tables.Problem;
import com.ash.drishti.tables.TableCodes;
import com.ash.drishti.tables.def.TableDef;
import com.ash.drishti.tables.yaml.TableYaml;
import java.util.List;
import org.junit.jupiter.api.Test;

/** One virtual table checked on its own (BI_FOUNDATION_2911E.md sections 3.1, 3.2; Q9, Q11 to Q14). */
class TableChecksTest {

    /** The design's sample (section 3.1): cash flows under two lists, the leg's fields through ^. */
    static final String CASHFLOWS = """
            rachana: 3
            table: bi.trading_cashflows_cashflows
            was: trading-cashflows-cashflows
            version: 1.0.0
            title: "Trade legs and cashflows: cashflows"
            from: { kind: trade }
            grain: $.LEGS[*].CASH_FLOWS[*]
            columns:
              LEG_ID:       { path: "^.LEG_ID", type: integer }
              CURRENCY:     { path: "^.CURRENCY", type: string }
              FLOW_TYPE:    { path: "@.FLOW_TYPE", type: string }
              PAYMENT_DATE: { path: "@.PAYMENT_DATE", type: date }
              NOTIONAL:     { path: "@.NOTIONAL", type: decimal, format: amount0 }
              RATE:         { path: "@.RATE", type: decimal }
            materialize: on-load
            """;

    /** The design's second sample: a rule per role and a masked column on the table (Q9). */
    static final String TRADES = """
            rachana: 3
            table: bi.trading_risk_trades
            from: { kind: trade-valuation, connector: risk-store }
            keys: [VALUATION_ID]
            columns:
              VALUATION_ID: { path: $.VALUATION_ID, type: string, role: key }
              DESK:         { path: $.DESK, type: string }
              COUNTERPARTY: { path: $.COUNTERPARTY, type: string, masked: true }
              MTM:          { path: $.MTM, type: decimal, format: signed0 }
              MTM_M:        { expr: "$.MTM / 1000000", type: decimal }
              CPTY_UPPER:   { expr: "upper($.COUNTERPARTY)", type: string }
              TRADE_DATE:   { path: $.TRADE_DATE, type: date }
            rules:
              - { role: desk-head, where: "$.DESK in user.desks" }
              - { role: [risk, analyst, admin], where: "true" }
            materialize: on-load
            """;

    static List<Problem> problems(String yaml) {
        TableYaml.Result r = TableYaml.read(yaml);
        assertThat(r.table()).as(r.problems().toString()).isNotNull();
        List<Problem> out = new java.util.ArrayList<>(r.problems());
        out.addAll(new TableChecks().check(r.table(), "bi"));
        return out;
    }

    static List<String> codes(List<Problem> ps) {
        return ps.stream().filter(p -> !Problem.INFO.equals(p.level())).map(p -> p.code() + " " + p.path()).toList();
    }

    @Test
    void theDesignsSamplesAreClean() {
        assertThat(codes(problems(CASHFLOWS))).isEmpty();
        assertThat(codes(problems(TRADES))).isEmpty();
        TableDef t = TableYaml.read(CASHFLOWS).table();
        assertThat(t.depth()).isEqualTo(2);
        assertThat(t.was()).isEqualTo("trading-cashflows-cashflows");
    }

    @Test
    void aColumnBuiltFromAMaskedPathIsMasked() {
        TableDef t = TableYaml.read(TRADES).table();
        assertThat(TableChecks.masked(t)).containsExactlyInAnyOrder("COUNTERPARTY", "CPTY_UPPER");
        assertThat(problems(TRADES)).anyMatch(p -> p.path().equals("columns.CPTY_UPPER") && p.message().contains("masked"));
    }

    @Test
    void theRuleIsCheckedByRachanasCheckedMode() {
        assertThat(problems(TRADES)).filteredOn(p -> p.path().equals("rules[0].where")).isEmpty();          // strict in, user.desks: a rule
        TableDef t = TableYaml.read(TRADES.replace("$.DESK in user.desks", "$.DESK in user.desks and")).table();
        assertThat(new TableChecks().check(t, "bi")).anyMatch(p -> p.path().equals("rules[0].where") && p.isError());
        TableDef measureInRule = TableYaml.read(TRADES.replace("$.DESK in user.desks", "sum($.MTM) > 0")).table();
        assertThat(new TableChecks().check(measureInRule, "bi")).anyMatch(p -> p.path().equals("rules[0].where") && p.code().equals(TableCodes.SCOPE));
    }

    @Test
    void refusesWhatTheDesignRefuses() {
        String bad = """
                rachana: 3
                table: trading.Trade-Legs
                from: { kind: trade }
                filter: "sum($.MTM) > 0"
                columns:
                  id:        { path: $.TRADE_ID }
                  LEG:       { path: "@.LEG_ID" }
                  ALL_LEGS:  { path: "$.LEGS[*].NOTIONAL" }
                  BOTH:      { path: $.A, expr: "$.A" }
                  NONE:      { type: string }
                  BARE:      { expr: "MTM_X * 2" }
                  MTM_X:     { path: $.MTM, type: money }
                  SHOWN:     { expr: "fmt($.MTM, 'amount0')" }
                  CTL:       { expr: "ctl.desk" }
                  WHO:       { expr: "user.desk" }
                keys: [NOPE]
                materialize: { live: { rows: 0, age: soon } }
                rules:
                  - { where: "true" }
                """;
        List<String> codes = codes(problems(bad));
        assertThat(codes).contains(TableCodes.NAME + " table", TableCodes.SCOPE + " filter", TableCodes.BUILT_IN + " columns.id", TableCodes.PATH + " columns.LEG.path",
                TableCodes.PATH + " columns.ALL_LEGS.path", TableCodes.COLUMN + " columns.BOTH", TableCodes.COLUMN + " columns.NONE",
                TableCodes.SCOPE + " columns.BARE.expr", TableCodes.TYPE + " columns.MTM_X.type", TableCodes.SCOPE + " columns.CTL.expr", TableCodes.SCOPE + " columns.WHO.expr", TableCodes.KEYS + " keys",
                TableCodes.MATERIALIZE + " materialize.live.rows", TableCodes.MATERIALIZE + " materialize.live.age", TableCodes.RULE + " rules[0].role");
    }

    @Test
    void anAggregateInAColumnIsRefusedAndGreatestIsARowFunction() {
        String t = """
                rachana: 3
                table: bi.trades
                from: { kind: trade }
                columns:
                  BIGGER:        { expr: "greatest($.A, $.B)", type: decimal }
                """;
        assertThat(problems(t).stream().filter(p -> !Problem.INFO.equals(p.level())).map(Problem::line).toList()).isEmpty();
    }

    @Test
    void theFolderIsTheDomainAndOldFormsAreSentToTheMigration() {
        TableDef t = TableYaml.read(TRADES).table();
        assertThat(new TableChecks().check(t, "trading")).anyMatch(p -> p.code().equals(TableCodes.NAME) && p.message().contains("folder"));
        assertThat(TableYaml.read("rachana: 2\ndataset: x\n").problems()).anyMatch(p -> p.code().equals(TableCodes.OLD_FORM)
                && p.message().contains("tables migrate"));
        assertThat(TableYaml.read("rachana: 2\ntable: a.b\nfrom: { kind: k }\ncolumns: { X: $.X }\n").problems())
                .anyMatch(p -> p.code().equals(TableCodes.OLD_FORM));
    }

    @Test
    void aLiveTableIsKeptByKeyFromAKindsPushes() {
        String live = """
                rachana: 3
                table: live.trades
                from: { kind: trade-valuation }
                keys: [VALUATION_ID]
                columns:
                  VALUATION_ID: $.VALUATION_ID
                  MTM: { path: $.MTM, type: decimal }
                materialize: { live: { rows: 5000, age: 2h } }
                """;
        assertThat(codes(new TableChecks().check(TableYaml.read(live).table(), "live"))).isEmpty();
        String noKeys = live.replace("keys: [VALUATION_ID]\n", "");
        assertThat(codes(new TableChecks().check(TableYaml.read(noKeys).table(), "live"))).contains(TableCodes.MATERIALIZE + " materialize");
    }

    @Test
    void theWriterWritesWhatTheReaderReadsBack() {
        TableDef t = TableYaml.read(TRADES).table();
        String once = TableYaml.write(t, List.of("a header line"));
        TableDef again = TableYaml.read(once).table();
        assertThat(again).isEqualTo(t);
        assertThat(TableYaml.write(again, List.of("a header line"))).isEqualTo(once);
        assertThat(once).startsWith("# a header line\nrachana: 3\ntable: bi.trading_risk_trades\n");
    }
}
