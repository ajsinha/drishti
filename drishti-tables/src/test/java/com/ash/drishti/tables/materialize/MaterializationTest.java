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
package com.ash.drishti.tables.materialize;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.tables.def.TableDef;
import com.ash.drishti.tables.preview.Preview;
import com.ash.drishti.tables.preview.RowEvaluator;
import com.ash.drishti.tables.yaml.TableYaml;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The layer's materialisations (BI_FOUNDATION_2911E.md 3.4): live by key from pushed entities, and on-load Parquet per business date. */
class MaterializationTest {

    @TempDir Path store;

    static final class MovingClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-09T10:00:00Z"));

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    static DataNode trade(String id, String desk, double mtm) {
        return DataNode.of(Map.of("VALUATION_ID", id, "DESK", desk, "MTM", mtm));
    }

    static final TableDef LIVE = TableYaml.read("""
            rachana: 3
            table: live.trades
            from: { kind: trade-valuation }
            keys: [VALUATION_ID]
            filter: "$.DESK != 'CLOSED'"
            columns:
              VALUATION_ID: { path: $.VALUATION_ID, type: string }
              DESK:         { path: $.DESK, type: string }
              MTM:          { path: $.MTM, type: decimal }
            materialize: { live: { rows: 2, age: 1h } }
            """).table();

    @Test
    void aLiveTableIsKeptByKeyWithItsSequenceAndBounds() {
        MovingClock clock = new MovingClock();
        LiveStore live = new LiveStore(clock, 1000, RowEvaluator.rachana());
        assertThat(live.apply(LIVE, List.of(new LiveStore.Change("V1", "2026-10-09", trade("V1", "A", 1)),
                new LiveStore.Change("V2", "2026-10-09", trade("V2", "B", 2))))).isEqualTo(2);
        assertThat(live.sequence(LIVE)).isEqualTo(2);
        assertThat(live.apply(LIVE, List.of(new LiveStore.Change("V1", "2026-10-09", trade("V1", "A", 1))))).as("the same values").isZero();
        assertThat(live.apply(LIVE, List.of(new LiveStore.Change("V1", "2026-10-09", trade("V1", "A", 5))))).isEqualTo(1);
        assertThat(live.current(LIVE)).extracting(r -> r.get("VALUATION_ID").asText()).containsExactly("V2", "V1");
        assertThat(live.current(LIVE).get(1).get("update_seq").asDouble()).isEqualTo(3.0);
        live.apply(LIVE, List.of(new LiveStore.Change("V3", "2026-10-09", trade("V3", "C", 3))));
        assertThat(live.rows(LIVE)).as("at most two rows: the oldest updated goes").isEqualTo(2);
        assertThat(live.current(LIVE)).extracting(r -> r.get("VALUATION_ID").asText()).containsExactly("V1", "V3");
        live.apply(LIVE, List.of(new LiveStore.Change("V3", "2026-10-09", null), new LiveStore.Change("V1", "2026-10-09", trade("V1", "CLOSED", 5))));
        assertThat(live.rows(LIVE)).as("deleted, and filtered out").isZero();
        live.apply(LIVE, List.of(new LiveStore.Change("V4", "2026-10-09", trade("V4", "A", 4))));
        clock.now.set(clock.now.get().plusSeconds(7200));
        live.apply(LIVE, List.of(new LiveStore.Change("V5", "2026-10-09", trade("V5", "A", 5))));
        assertThat(live.current(LIVE)).as("older than an hour goes").extracting(r -> r.get("VALUATION_ID").asText()).containsExactly("V5");
    }

    @Test
    void onLoadWritesParquetPerDateAndSaysWhatItHolds() throws Exception {
        TableDef t = TableYaml.read("""
                rachana: 3
                table: trading.trade_legs
                from: { kind: trade }
                grain: $.LEGS[*]
                columns:
                  TRADE_ID: $.TRADE_ID
                  NOTIONAL: { path: "@.NOTIONAL", type: decimal }
                materialize: on-load
                """).table();
        LocalDate day = LocalDate.of(2026, 10, 9);
        DataNode doc = DataNode.of(Map.of("TRADE_ID", "T1", "LEGS", List.of(Map.of("NOTIONAL", 100.0), Map.of("NOTIONAL", 250.0))));
        ParquetOnLoad onLoad = new ParquetOnLoad(new StoreLayout(store), (tb, d) -> List.of(new Preview.Doc("T1", d.toString(), doc)), RowEvaluator.rachana(),
                Clock.fixed(Instant.parse("2026-10-09T18:00:00Z"), ZoneOffset.UTC));
        Materialization.Partition p = onLoad.build(t, day);
        assertThat(p.rows()).isEqualTo(2);
        assertThat(p.file()).startsWith(store.resolve("trading/trade_legs/v1/2026-10-09"));
        try (Connection db = DriverManager.getConnection("jdbc:duckdb:"); Statement s = db.createStatement();
                ResultSet r = s.executeQuery("SELECT sum(NOTIONAL), max(TRADE_ID), count(*) FROM read_parquet('" + p.file() + "')")) {
            r.next();
            assertThat(r.getDouble(1)).isEqualTo(350.0);
            assertThat(r.getString(2)).isEqualTo("T1");
        }
        onLoad.loaded(t, day);
        assertThat(onLoad.held(t)).containsExactly(day);
        assertThat(onLoad.current(t, day)).hasValueSatisfying(x -> assertThat(x.rows()).isEqualTo(2));
        assertThat(Files.list(p.file().getParent()).filter(f -> f.toString().endsWith(".tmp") || f.toString().endsWith(".jsonl"))).isEmpty();
    }
}
