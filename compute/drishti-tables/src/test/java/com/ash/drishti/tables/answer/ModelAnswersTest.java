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
package com.ash.drishti.tables.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.tables.catalog.Catalogue;
import com.ash.drishti.tables.def.ModelDef;
import com.ash.drishti.tables.def.TableDef;
import com.ash.drishti.tables.lang.LateBound;
import com.ash.drishti.tables.yaml.ModelYaml;
import com.ash.drishti.tables.yaml.TableYaml;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A model query answered by the layer through part A's one compiler (HOW_IT_FITS.md §A28 3.5): measures of the engine's aggregates with
 * {@code where =}, a measure of measures, {@code count(alias)}, the fields of a dimension through the join, a date part, filters, the
 * table's rules and masks per reader, a reader no rule names, {@code fx} from the installation's source and {@code related} through the
 * join; figures worked by hand.
 */
class ModelAnswersTest {

    static final LocalDate DAY = LocalDate.of(2026, 10, 9);

    static final TableDef TRADES = TableYaml.read("""
            rachana: 3
            table: trading.trades
            from: { kind: trade }
            keys: [TRADE_ID]
            columns:
              TRADE_ID:   { path: $.TRADE_ID, type: string }
              DESK:       { path: $.DESK, type: string }
              CCY:        { path: $.CCY, type: string }
              CPTY:       { path: $.CPTY, type: string, masked: true }
              STATUS:     { path: $.STATUS, type: string }
              MTM:        { path: $.MTM, type: decimal }
              NOTIONAL:   { path: $.NOTIONAL, type: decimal }
              TRADE_DATE: { path: $.TRADE_DATE, type: date }
            rules:
              - { role: desk-head, where: "$.DESK in user.desks" }
              - { role: [risk, analyst], where: "true" }
            """).table();
    static final TableDef DESKS = TableYaml.read("""
            rachana: 3
            table: core.desks
            from: { kind: desk }
            columns:
              NAME:   { path: $.NAME, type: string }
              REGION: { path: $.REGION, type: string }
            """).table();
    static final ModelDef MODEL = ModelYaml.read("""
            rachana: 3
            model: trading-risk
            tables:
              trades: { table: trading.trades, role: fact }
              desks:  { table: core.desks, role: dimension }
            relationships:
              - { from: trades.DESK, to: desks.id }
            measures:
              MTM:         { expr: "sum(trades.MTM)" }
              Open MTM:    { expr: "sum(trades.MTM, where = trades.STATUS != 'MATURED' && trades.STATUS != null)" }
              Trades:      { expr: "count(trades)" }
              Avg MTM:     { expr: "measure('MTM') / measure('Trades')" }
              Per 1m:      { expr: "ratio(sum(trades.MTM), sum(trades.NOTIONAL)) * 1000000" }
              Notional USD: { expr: "sum(trades.NOTIONAL * fx(trades.CCY, 'USD'))" }
              Regions:     { expr: "distinct(related(desks.REGION))" }
              Rank:        { expr: "rank(sum(trades.MTM))" }
            """).model();

    static DataNode row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return DataNode.of(m);
    }

    static final Map<String, List<DataNode>> DATA = Map.of(
            "trading.trades", List.of(
                    row("id", "T1", "TRADE_ID", "T1", "DESK", "D1", "CCY", "USD", "CPTY", "ACME", "STATUS", "LIVE", "MTM", 100.0, "NOTIONAL", 1000.0, "TRADE_DATE", "2026-01-15"),
                    row("id", "T2", "TRADE_ID", "T2", "DESK", "D1", "CCY", "EUR", "CPTY", "BETA", "STATUS", "MATURED", "MTM", -40.0, "NOTIONAL", 2000.0, "TRADE_DATE", "2026-02-15"),
                    row("id", "T3", "TRADE_ID", "T3", "DESK", "D2", "CCY", "EUR", "CPTY", "ACME", "STATUS", null, "MTM", 10.0, "NOTIONAL", 500.0, "TRADE_DATE", "2026-02-20")),
            "core.desks", List.of(row("id", "D1", "NAME", "Rates", "REGION", "EMEA"), row("id", "D2", "NAME", "FX", "REGION", "APAC")));

    final ModelAnswers answers = new ModelAnswers(Catalogue.of(List.of(TRADES, DESKS), List.of(MODEL)), (t, d) -> DATA.get(t.name()),
            (from, to, at) -> from.equals(to) ? Double.valueOf(1.0) : "EUR".equals(from) && "USD".equals(to) ? Double.valueOf(1.5) : null);

    static ModelAnswers.Reader risk() {
        return new ModelAnswers.Reader(Set.of("risk"), Map.of(), true);
    }

    ModelAnswers.Answer ask(List<String> fields, List<String> measures, ModelAnswers.Reader who, ModelAnswers.Where... where) {
        return answers.ask(MODEL, new ModelAnswers.Question(fields, measures, List.of(where), DAY), who);
    }

    static double d(Object o) {
        return ((Number) o).doubleValue();
    }

    @Test
    void theGrandTotalsOfTheEnginesAggregatesAndMeasuresOfMeasures() {
        ModelAnswers.Answer a = ask(List.of(), List.of("MTM", "Open MTM", "Trades", "Avg MTM", "Per 1m", "Notional USD", "Regions"), risk());
        assertThat(a.rows()).hasSize(1);
        List<Object> r = a.rows().get(0);
        assertThat(d(r.get(0))).isEqualTo(70.0);
        assertThat(d(r.get(1))).isEqualTo(100.0);                         // the matured and the missing status left out
        assertThat(d(r.get(2))).isEqualTo(3.0);
        assertThat(d(r.get(3))).isCloseTo(70.0 / 3, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(d(r.get(4))).isCloseTo(70.0 / 3500 * 1_000_000, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(d(r.get(5))).isEqualTo(1000.0 + 3000.0 + 750.0);       // fx: EUR at 1.5
        assertThat(d(r.get(6))).isEqualTo(2.0);                           // related through the join
        assertThat(a.grand()).isEqualTo(r);
    }

    @Test
    void groupedByADimensionThroughTheJoinAndByADatePart() {
        ModelAnswers.Answer a = ask(List.of("desks.REGION"), List.of("MTM", "Trades"), risk());
        assertThat(a.columns()).containsExactly("desks.REGION", "MTM", "Trades");
        assertThat(a.rows()).hasSize(2);
        assertThat(a.rows().get(0).get(0)).isEqualTo("APAC");
        assertThat(d(a.rows().get(0).get(1))).isEqualTo(10.0);
        assertThat(d(a.rows().get(1).get(1))).isEqualTo(60.0);
        ModelAnswers.Answer byMonth = ask(List.of("trades.TRADE_DATE.month"), List.of("Trades"), risk());
        assertThat(byMonth.rows()).extracting(x -> ((Number) x.get(0)).intValue()).containsExactly(1, 2);
        assertThat(byMonth.rows()).extracting(x -> d(x.get(1))).containsExactly(1.0, 2.0);
    }

    @Test
    void filtersBeforeAggregation() {
        ModelAnswers.Answer a = ask(List.of(), List.of("MTM"), risk(), new ModelAnswers.Where("trades.CCY", "eq", List.of("EUR")),
                new ModelAnswers.Where("trades.MTM", "lt", List.of(0)));
        assertThat(d(a.rows().get(0).get(0))).isEqualTo(-40.0);
    }

    @Test
    void eachReaderSeesWhatTheTablesRulesAndMasksAllow() {
        ModelAnswers.Reader oneDesk = new ModelAnswers.Reader(Set.of("desk-head"), Map.of("desks", List.<Object>of("D2")), true);
        assertThat(d(ask(List.of(), List.of("MTM"), oneDesk).rows().get(0).get(0))).isEqualTo(10.0);
        ModelAnswers.Reader noDesks = new ModelAnswers.Reader(Set.of("desk-head"), Map.of("desks", List.<Object>of()), true);
        assertThat(ask(List.of(), List.of("Trades"), noDesks).rows().get(0).get(0)).satisfies(v -> assertThat(d(v)).isEqualTo(0.0));
        ModelAnswers.Reader intern = new ModelAnswers.Reader(Set.of("intern"), Map.of(), true);
        assertThat(ask(List.of(), List.of("Trades"), intern).noAccess()).containsExactly("trades");
        ModelAnswers.Reader analyst = new ModelAnswers.Reader(Set.of("analyst"), Map.of(), false);
        ModelAnswers.Answer masked = ask(List.of("trades.CPTY"), List.of("Trades"), analyst);
        assertThat(masked.masked()).containsExactly("trades.CPTY");
        assertThat(masked.rows()).singleElement().satisfies(r -> assertThat(r.get(0)).isEqualTo("•••"));
    }

    @Test
    void whatTheInMemoryRouteDoesNotAnswerIsRefusedByName() {
        assertThatThrownBy(() -> ask(List.of(), List.of("Rank"), risk())).isInstanceOf(ModelAnswers.Refused.class).hasMessageContaining("DRS-9129");
        assertThatThrownBy(() -> ask(List.of("trades.NOPE"), List.of("MTM"), risk())).hasMessageContaining("DRS-9152");
    }

    @Test
    void lateBoundFunctionsBecomeColumnsOfTheRow() {
        LateBound.Bound b = LateBound.bind(com.ash.drishti.tables.lang.Trees.parse("$.NOTIONAL * fx($.CCY, 'USD') + len(related(core.desks.NAME, on = $.DESK))"), false);
        assertThat(b.slots()).extracting(LateBound.Slot::fn).containsExactly("fx", "related");
        assertThat(com.ash.drishti.rachana.el.Printer.print(b.expr())).isEqualTo("$.NOTIONAL * __l0 + len(__l1)");
    }
}
