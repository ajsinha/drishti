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
package com.ash.drishti.plugin.deltaext;

import static com.ash.drishti.lakehouse.SparkDeltaTable.D29;
import static com.ash.drishti.lakehouse.SparkDeltaTable.D30;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.ColumnSet;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.lakehouse.MockUnityCatalog;
import com.ash.drishti.lakehouse.SparkDeltaTable;
import com.ash.drishti.testkit.DatedSourceContract;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Plain Delta tables (Spark-shaped: any columns, no id/doc shape) served as kinds, the document built on read from the mapped columns:
 * by folder under a root, and by name in Unity Catalog; a row deleted in place left out; a table that cannot serve said in words.
 */
class DeltaExternalPluginTest {

    private static final JsonCodec CODEC = new JsonCodec();

    @TempDir
    Path dir;

    private DeltaExternalPlugin start(Map<String, String> settings) throws Exception {
        DeltaExternalPlugin p = new DeltaExternalPlugin();
        p.start(DatedSourceContract.context(settings));
        return p;
    }

    private Map<String, String> trades(Map<String, String> more) {
        Map<String, String> s = new HashMap<>(Map.of("root", dir.toString(), "tables.trade.table", "mart.trades", "tables.trade.id", "TRADE_ID",
                "tables.trade.links.CPTY.ID", "counterparty"));
        s.putAll(more);
        return s;
    }

    private static JsonNode json(EntityDocument d) throws Exception {
        return SparkDeltaTable.JSON.readTree(CODEC.toJson(d.data()));
    }

    @Test
    void aSparkShapedTableServesDocumentsBuiltFromItsColumns() throws Exception {
        SparkDeltaTable.write(dir.resolve("mart").resolve("trades"), false);
        DeltaExternalPlugin p = start(trades(Map.of()));
        try {
            assertThat(p.manifest().name()).isEqualTo("delta-external");
            assertThat(p.health()).isEqualTo("UP");
            EntityDocument d29 = p.fetch(EntityRef.of("trade", "T-2"), AsOf.of(D29)).orElseThrow();
            assertThat(d29.provenance().businessDate()).isEqualTo(D29);          // the partition column is the business date
            JsonNode j = json(d29);
            assertThat(j.get("NOTIONAL").asDouble()).isEqualTo(2_500_000.0);
            assertThat(j.get("CPTY").get("NAME").asText()).isEqualTo("Bravo Capital");
            assertThat(j.get("TRADE_TS").asText()).isEqualTo("2026-09-29T10:01:00Z");
            JsonNode d30 = json(p.fetch(EntityRef.of("trade", "T-2"), AsOf.of(D30)).orElseThrow());
            assertThat(d30.get("NOTIONAL").asDouble()).isEqualTo(3_100_000.0);
            assertThat(d30.get("TAGS").get(0).asText()).isEqualTo("amended");
            assertThat(p.fetch(EntityRef.of("trade", "T-3"), AsOf.of(D30))).isEmpty();          // not on the 30th
            assertThat(p.fetch(EntityRef.of("trade", "T-4"))).isPresent();
            assertThat(p.search("trade", "T-", 10)).extracting(h -> h.ref().id()).contains("T-1", "T-2", "T-4");
            ColumnSet c = p.columns("trade", List.of("DESK", "NOTIONAL", "CPTY.NAME"), AsOf.of(D30)).orElseThrow();
            assertThat(c.size()).isEqualTo(3);
            assertThat(c.texts().get("DESK")).contains("CREDIT");
            assertThat(p.reverse(EntityRef.of("counterparty", "CP-1"), "trade", AsOf.of(D29))).extracting(EntityRef::id)
                    .containsExactlyInAnyOrder("T-1", "T-3");
        } finally {
            p.close();
        }
    }

    @Test
    void aRowDeletedInPlaceIsGoneWhileWhatWasKnownBeforeStays() throws Exception {
        SparkDeltaTable.Written w = SparkDeltaTable.write(dir.resolve("mart").resolve("trades"), true);   // column mapping too
        SparkDeltaTable.deleteInPlace(w, D30, "T-4");
        DeltaExternalPlugin p = start(trades(Map.of()));
        try {
            assertThat(p.fetch(EntityRef.of("trade", "T-4"), AsOf.of(D30))).isEmpty();
            assertThat(p.fetch(EntityRef.of("trade", "T-1"), AsOf.of(D30))).isPresent();
        } finally {
            p.close();
        }
    }

    @Test
    void tablesNamedInUnityCatalogAreReadWhereUnitySaysTheyAre() throws Exception {
        SparkDeltaTable.write(dir.resolve("warehouse").resolve("trades"), false);
        try (MockUnityCatalog uc = new MockUnityCatalog()) {
            uc.table("main.trading.trades", dir.resolve("warehouse").resolve("trades").toUri().toString());
            uc.table("main.trading.secret", dir.resolve("warehouse").resolve("trades").toUri().toString());
            uc.denied.add("main.trading.secret");
            DeltaExternalPlugin p = start(Map.of("unity.host", uc.host(), "unity.token", MockUnityCatalog.TOKEN, "tables.trade.table",
                    "main.trading.trades", "tables.trade.id", "TRADE_ID", "tables.secret.table", "main.trading.secret", "tables.secret.id", "TRADE_ID"));
            try {
                assertThat(json(p.fetch(EntityRef.of("trade", "T-1"), AsOf.of(D29)).orElseThrow()).get("DESK").asText()).isEqualTo("FX");
                assertThat(p.health()).startsWith("DEGRADED").contains("secret").contains("no grant");
            } finally {
                p.close();
            }
        }
    }

    @Test
    void settingsThatCannotWorkAreRefusedInWords() throws Exception {
        assertThatThrownBy(() -> start(Map.of("tables.trade.id", "TRADE_ID"))).hasMessageContaining("settings.root")
                .hasMessageContaining("unity.host");
        assertThatThrownBy(() -> start(Map.of("root", dir.toString(), "catalog", "rest"))).hasMessageContaining("iceberg-external");
        SparkDeltaTable.write(dir.resolve("mart").resolve("trades"), false);
        DeltaExternalPlugin p = start(Map.of("root", dir.toString(), "tables.trade.table", "mart.trades", "tables.position.table", "mart.positions",
                "tables.position.id", "ID"));
        try {
            String health = p.health();
            assertThat(health).startsWith("DOWN").contains("no table mart.positions").contains("_delta_log is not there");
            assertThat(health).contains("trade").contains("set tables.trade.id");                // no id named, none in the table
        } finally {
            p.close();
        }
    }
}
