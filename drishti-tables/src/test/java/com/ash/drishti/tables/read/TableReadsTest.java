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
package com.ash.drishti.tables.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.tables.answer.ModelAnswers;
import com.ash.drishti.tables.catalog.Catalogue;
import com.ash.drishti.tables.def.TableDef;
import com.ash.drishti.tables.preview.Preview;
import com.ash.drishti.tables.yaml.TableYaml;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * Reading virtual tables (docs/architecture/ARCHITECTURE.md section 10): many tables over one kind from one shared read (V7), the reader's rules, masks and
 * hidden columns, a caller's condition (a masked column refused in it), a table over a table, dates (latest, a date held, one not held,
 * an undated source as today, known-at refused where the source keeps no history), the row limit never cut short.
 */
class TableReadsTest {

    static final LocalDate D1 = LocalDate.of(2026, 9, 29);
    static final LocalDate D2 = LocalDate.of(2026, 9, 30);

    static TableDef table(String yaml) {
        TableYaml.Result r = TableYaml.read(yaml);
        assertThat(r.problems()).noneMatch(p -> p.isError());
        return r.table();
    }

    static final TableDef SUMMARY = table("""
            rachana: 3
            table: trading.trade_summary
            from: { kind: trade }
            keys: [TRADE_ID]
            filter: "$.STATUS != 'CANCELLED'"
            columns:
              TRADE_ID: { path: $.TRADE_ID, type: string }
              DESK:     { path: $.DESK, type: string }
              CPTY:     { path: $.CPTY, type: string, masked: true }
              LEGS:     { expr: "count($.LEGS)", type: integer }
            rules:
              - { role: desk-head, where: "$.DESK in user.desks" }
              - { role: risk, where: "true" }
            """);
    static final TableDef LEGS = table("""
            rachana: 3
            table: trading.trade_legs
            from: { kind: trade }
            grain: $.LEGS[*]
            keys: [TRADE_ID, LEG_ID]
            columns:
              TRADE_ID: { path: $.TRADE_ID, type: string }
              LEG_ID:   { path: "@.LEG_ID", type: string }
              CURRENCY: { path: "@.CURRENCY", type: string }
              NOTIONAL: { path: "@.NOTIONAL", type: decimal }
            """);
    static final TableDef BIG_LEGS = table("""
            rachana: 3
            table: trading.big_legs
            from: { table: trading.trade_legs }
            filter: "@.NOTIONAL >= 1000"
            columns:
              HALF: { expr: "@.NOTIONAL / 2", type: decimal }
            """);
    static final TableDef RATES = table("""
            rachana: 3
            table: market.rates
            from: { kind: rate }
            columns:
              CCY:  { path: $.CCY, type: string }
              RATE: { path: $.RATE, type: decimal, hidden-for: [intern] }
            """);

    /** A source of trades on two dates and an undated kind of rates, counting its reads. */
    static final class Source implements SourceRows {
        int reads;
        final JsonCodec json = new JsonCodec();

        @Override
        public List<LocalDate> dates(TableDef.Source s) {
            return "trade".equals(s.kind()) ? List.of(D1, D2) : List.of();
        }

        @Override
        public void read(TableDef.Source s, LocalDate date, Instant knownAt, Predicate<Preview.Doc> sink) {
            reads++;
            List<String> docs = new ArrayList<>();
            if ("trade".equals(s.kind())) {
                docs.add("{\"TRADE_ID\":\"T1\",\"DESK\":\"D1\",\"CPTY\":\"ACME\",\"STATUS\":\"LIVE\",\"LEGS\":[{\"LEG_ID\":\"L1\",\"CURRENCY\":\"USD\",\"NOTIONAL\":1000},{\"LEG_ID\":\"L2\",\"CURRENCY\":\"EUR\",\"NOTIONAL\":500}]}");
                docs.add("{\"TRADE_ID\":\"T2\",\"DESK\":\"D2\",\"CPTY\":\"BETA\",\"STATUS\":\"CANCELLED\",\"LEGS\":[{\"LEG_ID\":\"L1\",\"CURRENCY\":\"JPY\",\"NOTIONAL\":3000}]}");
                if (D2.equals(date)) {
                    docs.add("{\"TRADE_ID\":\"T3\",\"DESK\":\"D2\",\"CPTY\":\"ACME\",\"STATUS\":\"LIVE\",\"LEGS\":[]}");
                }
            } else {
                docs.add("{\"CCY\":\"EUR\",\"RATE\":1.1}");
            }
            for (String d : docs) {
                DataNode n = json.read(d);
                if (!sink.test(new Preview.Doc(n.get("TRADE_ID") == null ? "r" : n.get("TRADE_ID").asText(), date == null ? null : date.toString(), n))) {
                    return;
                }
            }
        }
    }

    final Source source = new Source();
    final TableReads reads = new TableReads(Catalogue.of(List.of(SUMMARY, LEGS, BIG_LEGS, RATES), List.of()), source, null, 0,
            Clock.fixed(Instant.parse("2026-11-04T12:00:00Z"), ZoneOffset.UTC));

    static final ModelAnswers.Reader RISK = new ModelAnswers.Reader(Set.of("risk"), Map.of(), true);

    @Test
    void manyTablesOverOneKindShareOneReadPerDate() {
        TableReads.Read s = reads.read("trading.trade_summary", null, null, RISK, null);
        TableReads.Read l = reads.read("trading.trade_legs", null, null, RISK, null);
        assertThat(s.date()).isEqualTo(D2);
        assertThat(s.rows()).extracting(r -> r.get(2)).containsExactly("T1", "T3");      // T2 cancelled
        assertThat(l.columns()).containsExactly("id", "business_date", "index", "TRADE_ID", "LEG_ID", "CURRENCY", "NOTIONAL");
        assertThat(l.rows()).hasSize(3);
        assertThat(source.reads).isEqualTo(1);
        reads.read("trading.trade_legs", "2026-09-29", null, RISK, null);
        assertThat(source.reads).isEqualTo(2);
        assertThat(reads.sourceReads()).isEqualTo(2);
    }

    @Test
    void rulesMasksAndHiddenColumnsAreTheReaders() {
        ModelAnswers.Reader head = new ModelAnswers.Reader(Set.of("desk-head"), Map.of("desks", List.of("D2")), false);
        TableReads.Read r = reads.read("trading.trade_summary", null, null, head, null);
        assertThat(r.rows()).hasSize(1);
        assertThat(r.rows().get(0).get(r.columns().indexOf("CPTY"))).isEqualTo(DataNode.MASK);
        assertThat(r.masked()).containsExactly("CPTY");
        ModelAnswers.Reader nobody = new ModelAnswers.Reader(Set.of("intern"), Map.of(), false);
        assertThatThrownBy(() -> reads.read("trading.trade_summary", null, null, nobody, null)).hasMessageContaining("none of its rules");
        assertThat(reads.read("market.rates", null, null, nobody, null).columns()).doesNotContain("RATE");
    }

    @Test
    void aConditionFiltersAndAMaskedColumnCannotFilter() {
        TableReads.Read r = reads.read("trading.trade_legs", null, null, RISK, "CURRENCY in ['JPY', 'EUR']");
        assertThat(r.rows()).extracting(x -> x.get(r.columns().indexOf("CURRENCY"))).containsExactly("EUR", "JPY");
        TableReads.Read at = reads.read("trading.trade_legs", null, null, RISK, "@.NOTIONAL > 600");
        assertThat(at.rows()).hasSize(2);
        ModelAnswers.Reader masked = new ModelAnswers.Reader(Set.of("risk"), Map.of(), false);
        assertThatThrownBy(() -> reads.read("trading.trade_summary", null, null, masked, "CPTY == 'ACME'")).hasMessageContaining("masked");
    }

    @Test
    void aTableOverATableReadsItsBasesSourceGrainAndFilter() {
        TableReads.Read r = reads.read("trading.big_legs", null, null, RISK, null);
        assertThat(r.columns()).contains("LEG_ID", "HALF");
        assertThat(r.rows()).extracting(x -> x.get(r.columns().indexOf("HALF"))).containsExactly(500L, 1500L);
        assertThat(source.reads).isEqualTo(1);
    }

    @Test
    void datesAreTheSources() {
        assertThatThrownBy(() -> reads.read("trading.trade_legs", "2026-09-01", null, RISK, null)).hasMessageContaining("holds no data on 2026-09-01");
        TableReads.Read rates = reads.read("market.rates", null, null, RISK, null);
        assertThat(rates.date()).isEqualTo(LocalDate.of(2026, 11, 4));
        assertThat(rates.rows().get(0).get(1)).isEqualTo("2026-11-04");
        assertThatThrownBy(() -> reads.read("trading.trade_legs", null, Instant.parse("2026-09-30T10:00:00Z"), RISK, null))
                .hasMessageContaining("keeps no history");
        assertThat(reads.dates("trading.big_legs")).containsExactly(D1, D2);
    }

    @Test
    void aReadOverTheLimitIsRefusedNeverCutShort() {
        TableReads small = new TableReads(Catalogue.of(List.of(LEGS), List.of()), source, null, 2, null);
        assertThatThrownBy(() -> small.read("trading.trade_legs", null, null, RISK, null)).hasMessageContaining("more than 2 rows");
    }

    @Test
    void theModelAnswersReadTheSameRows() {
        List<DataNode> rows = reads.rows(LEGS, D1);
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).get("LEG_ID").asText()).isEqualTo("L1");
    }
}
