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
package com.ash.drishti.memlake;

import com.ash.drishti.common.RowRetention;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.deltalake.write.LakeWriter;
import com.ash.drishti.deltalake.write.TableUpkeep;
import com.ash.drishti.memlake.store.MemoryStores;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A memory table's row retention: rows past their TTL (by a field, or by arrival) and beyond the row cap are dropped by the compaction. */
class RowRetentionTest {

    @TempDir
    Path dir;
    private MemoryLake lake;

    @AfterEach
    void stop() {
        if (lake != null) {
            lake.close();
        }
        MemoryStores.remove("rx");
    }

    private void feed(String jsonl) throws java.io.IOException {
        Files.writeString(dir.resolve("trades.jsonl"), jsonl);
    }

    private MemoryLake lake(RowRetention r) {
        MemoryLakeSettings.TableSpec t = new MemoryLakeSettings.TableSpec("trades", "rx", null,
                new MemoryLakeSettings.Source(null, null, dir.resolve("trades.jsonl").toString(), null, null, null, null), "merge",
                List.of("TRADE_ID"), null, "2026-11-14", List.of("BOOK"), null, null, false, null, false, 2, null, null, r);
        MemoryLakeSettings p = new MemoryLakeSettings(Map.of("rx", new MemoryLakeSettings.Store(64, 64, "RX", true, null, null)), List.of(t),
                new MemoryLakeSettings.Compaction(false, false, null, 0, 1, null, 3, null), null, 1);
        return new MemoryLake(p, new ReplenishSource(null), null, () -> true, null);
    }

    private static int rows() throws Exception {
        List<TableUpkeep.Staged> s = TableUpkeep.rows(LakeWriter.engineFor("mem://rx/trades", Map.of()), "mem://rx/trades", pv -> true);
        return s == null ? -1 : s.size();
    }

    @Test
    void rowsPastTheirTtlByAFieldAndBeyondTheCapAreDroppedByTheCompaction() throws Exception {
        Instant now = Instant.now();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            Instant at = now.minusSeconds(3_600L * i);                      // 0 h, 1 h, ... 9 h old
            b.append("{\"TRADE_ID\":\"T").append(i).append("\",\"BOOK\":\"B\",\"UPDATED_AT\":\"").append(at).append("\"}\n");
        }
        feed(b.toString());
        lake = lake(new RowRetention("8h", "UPDATED_AT", 5));
        assertThat(lake.runNow("trades", "test").outcome()).isEqualTo("ok");
        assertThat(rows()).isEqualTo(10);
        assertThat(MemoryCompactor.expired(lake.table("trades"))).isEqualTo(5);         // two past 8 h, then the cap keeps the newest 5
        assertThat(lake.compactor().candidates("rx")).first().satisfies(c -> assertThat(c.why()).contains("rows past the retention"));
        assertThat(lake.compactor().rewrite(lake.table("trades"), "retention")).isTrue();
        assertThat(rows()).isEqualTo(5);
        assertThat(lake.table("trades").expiredRows.get()).isEqualTo(5);
        List<TableUpkeep.Staged> kept = TableUpkeep.rows(LakeWriter.engineFor("mem://rx/trades", Map.of()), "mem://rx/trades", pv -> true);
        assertThat(kept).extracting(TableUpkeep.Staged::id).containsExactlyInAnyOrder("T0", "T1", "T2", "T3", "T4");
        @SuppressWarnings("unchecked")
        Map<String, Object> t = ((List<Map<String, Object>>) lake.describe().get("tables")).get(0);
        assertThat(t).containsEntry("expiredRows", 5L);
    }

    @Test
    void byArrivalTheTableCarriesWhenEachRowCameAndNoRetentionChangesNothing() throws Exception {
        feed("{\"TRADE_ID\":\"T1\",\"BOOK\":\"B\"}\n");
        lake = lake(new RowRetention("1d", null, null));
        lake.runNow("trades", "test");
        String line = TableUpkeep.rows(LakeWriter.engineFor("mem://rx/trades", Map.of()), "mem://rx/trades", pv -> true).get(0).line();
        assertThat(line).contains(RowRetention.ARRIVED);
        assertThat(MemoryCompactor.expired(lake.table("trades"))).isZero();
        assertThat(new MemoryLakeSettings.TableSpec("x", "rx", null, null, null, null, null, null, List.of("A"), null, null, null, null, null, null, null, null,
                null).written()).containsExactly("A");
        assertThatThrownBy(() -> new RowRetention("soon", null, null)).hasMessageContaining("retention.ttl");
    }
}
