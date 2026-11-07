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
package com.ash.drishti.lakehouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.iceberg.Table;
import com.ash.drishti.iceberg.catalog.Catalog;
import com.ash.drishti.iceberg.expr.Expr;
import com.ash.drishti.iceberg.meta.Snapshot;
import com.ash.drishti.iceberg.read.TaskReader;
import com.ash.drishti.iceberg.scan.FileTask;
import com.ash.drishti.iceberg.types.NestedField;
import com.ash.drishti.iceberg.types.Schema;
import com.ash.drishti.lakehouse.delta.DeltaSchemas;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A Spark-shaped Delta table (plain columns, INT96 timestamps, a struct, an array, column mapping, a deletion vector) read as Iceberg. */
public class DeltaAsIcebergTest {

    @TempDir
    Path dir;

    private static final ExecutorService POOL = Executors.newVirtualThreadPerTaskExecutor();

    /** Every live row of the snapshot, by TRADE_ID and business date, as {@code [date, id, desk, notional, qty, ts, cpty, tags]}. */
    public static Map<String, Object[]> rows(Table t, Snapshot s) {
        Schema schema = t.metadata().schemaFor(s);
        List<Integer> ids = schema.columns().stream().map(NestedField::id).toList();
        Map<String, Object[]> out = new TreeMap<>();
        for (FileTask task : t.plan(s, Expr.TRUE, POOL)) {
            try (TaskReader r = t.rows(task, schema, ids, Expr.TRUE, false)) {
                while (r.hasNext()) {
                    Object[] row = r.next();
                    int date = (Integer) row[ids.indexOf(schema.field("business_date").id())];
                    out.put(LocalDate.ofEpochDay(date) + "/" + row[ids.indexOf(schema.field("TRADE_ID").id())], row);
                }
            }
        }
        return out;
    }

    private void readsTheTable(boolean columnMapping) throws Exception {
        SparkDeltaTable.write(dir.resolve("mart").resolve("trades"), columnMapping);
        try (Catalog c = LakehouseCatalogs.delta(Map.of("root", dir.toString()), List.of("mart"))) {
            assertThat(c.tables()).containsExactly("trades");
            Table t = c.load("trades").orElseThrow();
            Schema schema = t.metadata().schema();
            assertThat(schema.columns()).extracting(NestedField::name)
                    .containsExactly("TRADE_ID", "DESK", "NOTIONAL", "QTY", "TRADE_TS", "CPTY", "TAGS", "business_date");
            assertThat(t.metadata().spec().fields()).hasSize(1);
            assertThat(t.metadata().snapshots()).hasSize(2);                       // one per Delta version
            Map<String, Object[]> rows = rows(t, t.currentSnapshot().orElseThrow());
            assertThat(rows.keySet()).containsExactly("2026-09-29/T-1", "2026-09-29/T-2", "2026-09-29/T-3", "2026-09-30/T-1", "2026-09-30/T-2",
                    "2026-09-30/T-4");
            Object[] t2 = rows.get("2026-09-30/T-2");
            assertThat(t2[1]).isEqualTo("RATES");
            assertThat(t2[2]).isEqualTo(3_100_000.0);
            assertThat(t2[3]).isEqualTo(31L);
            long micros = SparkDeltaTable.D30.atTime(10, 1).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() * 1000;
            assertThat(t2[4]).isEqualTo(micros);                                   // INT96, Spark's timestamps, to microseconds
            assertThat(java.util.Arrays.deepToString((Object[]) t2[5])).contains("CP-2").contains("Bravo Capital");
            assertThat(String.valueOf(t2[6])).contains("amended");
            Snapshot first = t.metadata().snapshots().get(0);
            assertThat(rows(t, first).keySet()).containsExactly("2026-09-29/T-1", "2026-09-29/T-2", "2026-09-29/T-3");
            FileTask day30 = t.plan(t.currentSnapshot().orElseThrow(), Expr.TRUE, POOL).stream()
                    .filter(f -> f.file().partition()[0].equals((int) SparkDeltaTable.D30.toEpochDay())).findFirst().orElseThrow();
            assertThat(day30.file().recordCount()).isEqualTo(3);
            assertThat(day30.file().lowerBounds()).containsKey(schema.field("TRADE_ID").id());          // the log's statistics prune
        }
    }

    @Test
    void aSparkShapedTableIsReadByNameWithItsPartitionsTypesAndStatistics() throws Exception {
        readsTheTable(false);
    }

    @Test
    void aTableWithColumnMappingIsReadByItsPhysicalNames() throws Exception {
        readsTheTable(true);
    }

    @Test
    void rowsDeletedInPlaceByADeletionVectorAreLeftOutAndEarlierVersionsKeepThem() throws Exception {
        SparkDeltaTable.Written w = SparkDeltaTable.write(dir.resolve("trades"), false);
        SparkDeltaTable.deleteInPlace(w, SparkDeltaTable.D30, "T-2");
        try (Catalog c = LakehouseCatalogs.delta(Map.of("root", dir.toString()), List.of())) {
            Table t = c.load("trades").orElseThrow();
            assertThat(rows(t, t.currentSnapshot().orElseThrow()).keySet()).contains("2026-09-30/T-1", "2026-09-30/T-4")
                    .doesNotContain("2026-09-30/T-2").contains("2026-09-29/T-2");
            assertThat(rows(t, t.metadata().snapshots().get(1)).keySet()).contains("2026-09-30/T-2");
        }
    }

    @Test
    void aNewCommitIsSeenAtRefresh() throws Exception {
        SparkDeltaTable.Written w = SparkDeltaTable.write(dir.resolve("trades"), false);
        try (Catalog c = LakehouseCatalogs.delta(Map.of("root", dir.toString()), List.of())) {
            Table t = c.load("trades").orElseThrow();
            assertThat(t.refresh()).isFalse();
            SparkDeltaTable.deleteInPlace(w, SparkDeltaTable.D30, "T-4");
            assertThat(t.refresh()).isTrue();
            assertThat(t.metadata().snapshots()).hasSize(3);
        }
    }

    @Test
    void aMissingTableAndAnUnreadTypeAreSaidInWords() throws Exception {
        try (Catalog c = LakehouseCatalogs.delta(Map.of("root", dir.toString()), List.of("mart"))) {
            assertThat(c.load("nothing")).isEmpty();
            assertThat(c.missing("nothing")).contains("nothing").contains("_delta_log is not there");
            assertThatThrownBy(() -> c.load("../escape")).hasMessageContaining("not a path");
        }
        String variant = "{\"type\":\"struct\",\"fields\":[{\"name\":\"PAYLOAD\",\"type\":\"variant\",\"nullable\":true,\"metadata\":{}}]}";
        assertThatThrownBy(() -> DeltaSchemas.convert(variant, List.of())).hasMessageContaining("PAYLOAD").hasMessageContaining("variant");
        assertThatThrownBy(() -> LakehouseCatalogs.delta(Map.of(), List.of())).hasMessageContaining("settings.root");
    }
}
