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

import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.tables.Problem;
import com.ash.drishti.tables.TableCodes;
import com.ash.drishti.tables.catalog.Catalogue;
import com.ash.drishti.tables.def.ModelDef;
import com.ash.drishti.tables.def.TableDef;
import com.ash.drishti.tables.preview.Preview;
import com.ash.drishti.tables.preview.RowEvaluator;
import com.ash.drishti.tables.yaml.ModelYaml;
import com.ash.drishti.tables.yaml.TableYaml;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * The offer for a table a data load created (V8): columns and types from the sample, the id key, the lists as grains, the offer read at a
 * grain; the offered definition passes the layer's checks and previews. Names unique across virtual and stored tables (V12). The scan
 * estimate's warning on a join across connectors (V10).
 */
class TableOfferTest {

    static final JsonCodec JSON = new JsonCodec();

    static List<Preview.Doc> sample() {
        return List.of(
                new Preview.Doc("A1", "2026-09-30", JSON.read("{\"ACCOUNT_ID\":\"A1\",\"OPENED\":\"2026-01-02\",\"LIMIT\":5,\"ACTIVE\":true,"
                        + "\"OWNER\":{\"NAME\":\"Ann\"},\"MOVES\":[{\"AMOUNT\":1.5,\"CCY\":\"USD\"}]}")),
                new Preview.Doc("A2", "2026-09-30", JSON.read("{\"ACCOUNT_ID\":\"A2\",\"OPENED\":\"2026-02-03\",\"LIMIT\":7.5,\"ACTIVE\":false,"
                        + "\"OWNER\":{\"NAME\":\"Bo\"},\"MOVES\":[]}")));
    }

    @Test
    void theOfferHasTypedColumnsTheKeyAndTheGrains() {
        TableOffer.Offer o = TableOffer.suggest("bank.accounts", TableDef.Source.ofKind("account"), sample());
        TableDef t = o.table();
        assertThat(t.columns().keySet()).containsExactly("ACCOUNT_ID", "OPENED", "LIMIT", "ACTIVE", "OWNER_NAME");
        assertThat(t.columns().get("OPENED").type()).isEqualTo("date");
        assertThat(t.columns().get("LIMIT").type()).isEqualTo("decimal");
        assertThat(t.columns().get("ACTIVE").type()).isEqualTo("boolean");
        assertThat(t.columns().get("OWNER_NAME").path()).isEqualTo("$.OWNER.NAME");
        assertThat(t.keys()).containsExactly("ACCOUNT_ID");
        assertThat(o.grains()).containsEntry("$.MOVES[*]", 1);
        TableYaml.Result back = TableYaml.read(TableYaml.write(t, List.of()));
        assertThat(back.problems()).noneMatch(Problem::isError);
        assertThat(Catalogue.of(List.of(back.table()), List.of()).check()).noneMatch(Problem::isError);
        assertThat(Preview.rows(back.table(), sample(), 10, RowEvaluator.rachana()).rows()).hasSize(2);
    }

    @Test
    void theOfferAtAGrainIsOneRowPerElement() {
        TableOffer.Offer o = TableOffer.atGrain(TableOffer.suggest("bank.moves", TableDef.Source.ofKind("account"), sample()), "$.MOVES[*]", sample());
        assertThat(o.table().grain()).isEqualTo("$.MOVES[*]");
        assertThat(o.table().columns().keySet()).containsExactly("ACCOUNT_ID", "AMOUNT", "CCY");
        assertThat(o.table().columns().get("CCY").path()).isEqualTo("@.CCY");
        assertThat(Catalogue.of(List.of(o.table()), List.of()).check()).noneMatch(Problem::isError);
        assertThat(Preview.rows(o.table(), sample(), 10, RowEvaluator.rachana()).rows()).hasSize(1);
    }

    @Test
    void aVirtualTableNamedLikeAStoredTableIsRefused() {
        TableDef t = TableOffer.suggest("trading.trade_endur", TableDef.Source.ofKind("trade"), sample()).table();
        Catalogue c = Catalogue.of(List.of(t), List.of());
        assertThat(c.clashes(List.of("TRADING.TRADE_ENDUR", "trade"))).singleElement().extracting(Problem::code).isEqualTo(TableCodes.NAME_CLASH);
        assertThat(c.clashes(List.of("trading.trade"))).isEmpty();
        assertThat(c.free("trading.trade_endur", List.of())).isFalse();
        assertThat(c.free("trading.other", List.of("trading.other"))).isFalse();
        assertThat(c.free("trading.other", List.of())).isTrue();
    }

    @Test
    void aJoinAcrossConnectorsWarnsOfALargeUnfilteredSide() {
        TableDef trades = TableYaml.read("""
                rachana: 3
                table: trading.trades
                from: { kind: trade }
                columns:
                  CPTY: $.CPTY
                """).table();
        TableDef cpty = TableYaml.read("""
                rachana: 3
                table: core.cpty
                from: { kind: counterparty, connector: crm }
                columns:
                  NAME: $.NAME
                """).table();
        ModelDef m = ModelYaml.read("""
                rachana: 3
                model: trading-cpty
                tables:
                  trades: { table: trading.trades, role: fact }
                  cpty:   { table: core.cpty, role: dimension }
                relationships:
                  - { from: trades.CPTY, to: cpty.id }
                measures:
                  Trades: { expr: "count(trades)" }
                """).model();
        SourceRows rows = new SourceRows() {
            @Override
            public List<LocalDate> dates(TableDef.Source s) {
                return List.of();
            }

            @Override
            public void read(TableDef.Source s, LocalDate date, Instant knownAt, Predicate<Preview.Doc> sink) {}

            @Override
            public long estimate(TableDef.Source s, LocalDate date) {
                return "trade".equals(s.kind()) ? 5_000_000 : 100;
            }

            @Override
            public String connector(TableDef.Source s) {
                return s.connector() == null ? "lake" : s.connector();
            }
        };
        Catalogue c = Catalogue.of(List.of(trades, cpty), List.of(m));
        List<Problem> w = ScanEstimate.warnings(m, c, rows, LocalDate.of(2026, 9, 30), 0);
        assertThat(w).singleElement().satisfies(p -> {
            assertThat(p.level()).isEqualTo(Problem.WARNING);
            assertThat(p.message()).contains("trades").contains("5000000 rows").contains("across connectors");
        });
        // the model's own scan-warn-rows over the installation's: higher, no warning; lower, the small side too
        ModelDef high = ModelYaml.read(ModelYaml.write(m, List.of()).replace("rachana: 3", "rachana: 3\nscan-warn-rows: 10000000")).model();
        assertThat(high.scanWarnRows()).isEqualTo(10_000_000L);
        assertThat(ScanEstimate.warnings(high, c, rows, LocalDate.of(2026, 9, 30), 0)).isEmpty();
        ModelDef low = ModelYaml.read(ModelYaml.write(m, List.of()).replace("rachana: 3", "rachana: 3\nscan-warn-rows: 50")).model();
        assertThat(ScanEstimate.warnings(low, c, rows, LocalDate.of(2026, 9, 30), 0)).hasSize(2);
        ModelDef bad = ModelYaml.read(ModelYaml.write(m, List.of()).replace("rachana: 3", "rachana: 3\nscan-warn-rows: lots")).model();
        assertThat(Catalogue.of(List.of(trades, cpty), List.of(bad)).check()).anyMatch(p -> p.isError() && p.path().contains("scan-warn-rows"));
    }
}
