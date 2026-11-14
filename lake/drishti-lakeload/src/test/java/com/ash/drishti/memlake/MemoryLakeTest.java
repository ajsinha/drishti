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

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.deltalake.write.LakeWriter;
import com.ash.drishti.deltalake.write.TableUpkeep;
import com.ash.drishti.memlake.store.MemoryStorage;
import com.ash.drishti.memlake.store.MemoryStores;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The in-memory lake's jobs (docs/architecture/DATA_ARCHITECTURE.md sections 4, 6 and 13a), without Spring: a vendor feed served over HTTP fills tables in memory
 * (replace, append, merge), the eviction ladder gives up whole unpinned tables least recently read first and queues their refill, a full
 * store refuses and the table stays whole, compaction merges small files and saves memory.
 */
class MemoryLakeTest {

    private HttpServer http;
    private final AtomicReference<String> feed = new AtomicReference<>("");
    private MemoryLake lake;

    @BeforeEach
    void serve() throws Exception {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/feed", ex -> {
            byte[] b = feed.get().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        http.start();
    }

    @AfterEach
    void stop() {
        if (lake != null) {
            lake.close();
        }
        http.stop(0);
        MemoryStores.remove("vx");
    }

    private String url() {
        return "http://127.0.0.1:" + http.getAddress().getPort() + "/feed";
    }

    private static String docs(int from, int count, String word) {
        StringBuilder b = new StringBuilder();
        for (int i = from; i < from + count; i++) {
            b.append("{\"INSTRUMENT_ID\":\"I").append(String.format("%05d", i)).append("\",\"DESK\":\"D").append(i % 4)
                    .append("\",\"PRICE\":").append(i * 1.5).append(",\"NOTE\":\"").append(word).append("\",\"NOISE\":\"").append(java.util.UUID.nameUUIDFromBytes((word + i).getBytes(StandardCharsets.UTF_8))).append(java.util.UUID.nameUUIDFromBytes((i + word).getBytes(StandardCharsets.UTF_8))).append("\"}\n");
        }
        return b.toString();
    }

    private MemoryLakeSettings.TableSpec table(String name, String mode, boolean pin) {
        return new MemoryLakeSettings.TableSpec(name, "vx", null, new MemoryLakeSettings.Source(null, null, null, url(), null, null, null), mode,
                List.of("INSTRUMENT_ID"), null, "2026-11-02", List.of("DESK"), null, null, false, null, pin, 2, null, null, null);
    }

    private MemoryLake lake(int maxMb, List<MemoryLakeSettings.TableSpec> tables) {
        MemoryLakeSettings p = new MemoryLakeSettings(Map.of("vx", new MemoryLakeSettings.Store(maxMb, 64, "VX-2026-07", true, null, null)),
                tables, new MemoryLakeSettings.Compaction(false, false, null, 0, 1, null, 3, null), null, 1);
        return new MemoryLake(p, new ReplenishSource(null), null, () -> true, null);
    }

    private static int rows(String path) throws Exception {
        List<TableUpkeep.Staged> s = TableUpkeep.rows(LakeWriter.engineFor(path, Map.of()), path, pv -> true);
        return s == null ? -1 : s.size();
    }

    @Test
    void replaceAppendAndMergeFillTablesInMemory() throws Exception {
        lake = lake(64, List.of(table("prices", "replace", true), table("ticks", "append", false), table("book", "merge", false)));
        feed.set(docs(0, 500, "first"));
        assertThat(lake.runNow("prices", "test").outcome()).isEqualTo("ok");
        assertThat(rows("mem://vx/prices")).isEqualTo(500);
        feed.set(docs(1000, 20, "second"));
        lake.runNow("prices", "test");
        assertThat(rows("mem://vx/prices")).as("replace: the run is the whole table").isEqualTo(20);

        lake.runNow("ticks", "test");
        lake.runNow("ticks", "test");
        assertThat(rows("mem://vx/ticks")).as("append adds").isEqualTo(40);

        feed.set(docs(0, 10, "old"));
        lake.runNow("book", "test");
        feed.set(docs(5, 10, "new"));
        lake.runNow("book", "test");
        List<TableUpkeep.Staged> book = TableUpkeep.rows(LakeWriter.engineFor("mem://vx/book", Map.of()), "mem://vx/book", pv -> true);
        assertThat(book).hasSize(15);
        assertThat(book.stream().filter(s -> s.id().equals("I00007")).findFirst().orElseThrow().line()).contains("new");
        assertThat(book.stream().filter(s -> s.id().equals("I00002")).findFirst().orElseThrow().line()).contains("old");
        assertThat(lake.table("book").rowsReplaced.get()).isEqualTo(5);

        Map<String, Object> d = lake.describe();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tables = (List<Map<String, Object>>) d.get("tables");
        Map<String, Object> prices = tables.get(0);
        assertThat(prices.get("state")).isEqualTo("ready");
        assertThat((Long) prices.get("bytes")).isGreaterThan(0);
        assertThat(prices.get("contract")).isEqualTo("VX-2026-07");
        assertThat(lake.jobs(null, 100)).hasSizeGreaterThanOrEqualTo(6);
        assertThat(lake.words("mem://vx", "prices")).isNull();
    }

    @Test
    void theLadderEvictsWholeUnpinnedTablesLeastRecentlyReadFirstThenRefuses() throws Exception {
        lake = lake(3, List.of(table("a", "replace", false), table("b", "replace", false), table("pinned", "replace", true)));
        MemoryStorage store = MemoryStores.store("vx");
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            big.append(docs(i * 4000, 4000, "x".repeat(40) + i));
        }
        feed.set(docs(0, 2000, "y".repeat(60)));
        assertThat(lake.runNow("pinned", "test").outcome()).isEqualTo("ok");
        feed.set(big.toString());
        assertThat(lake.runNow("a", "test").outcome()).isEqualTo("ok");
        assertThat(lake.runNow("b", "test").outcome()).isEqualTo("ok");
        rows("mem://vx/b");                                           // b read last: a goes first
        long used = store.pool().inUseBytes();
        assertThat(used).isLessThanOrEqualTo(3L << 20);
        feed.set(big.toString());
        MemoryTable.Run r = lake.runNow("pinned", "test");
        List<String> evicted = new ArrayList<>();
        for (String t : List.of("a", "b")) {
            if (lake.table(t).state() == MemoryTable.State.EVICTED) {
                evicted.add(t);
            }
        }
        assertThat(evicted).as("whole tables were evicted: " + r + " used before " + used + " now " + store.pool().inUseBytes() + " " + lake.jobs(null, 20)).isNotEmpty();
        assertThat(evicted.get(0)).isEqualTo("a");
        assertThat(store.isDirectory("mem://vx/a")).isFalse();
        assertThat(lake.words("mem://vx", "a")).contains("evicted").contains("a refill is queued").contains("VX-2026-07");
        if (r.outcome().equals("refused")) {
            assertThat(rows("mem://vx/pinned")).as("refused: whole at its previous version").isEqualTo(2000);
            assertThat(lake.table("pinned").state()).isEqualTo(MemoryTable.State.REFUSED);
        } else {
            assertThat(rows("mem://vx/pinned")).isEqualTo(24000);
        }
        assertThat(store.pool().allocatedBytes()).isLessThanOrEqualTo(3L << 20);
        assertThat(r.outcome()).isEqualTo("ok");
    }

    @Test
    void aStoreTooSmallRefusesAndTheTableStaysAtItsVersion() throws Exception {
        lake = lake(1, List.of(table("only", "replace", true)));
        feed.set(docs(0, 50, "small"));
        lake.runNow("only", "test");
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            big.append(docs(i * 1000, 1000, "z".repeat(80) + i));
        }
        feed.set(big.toString());
        MemoryTable.Run r = lake.runNow("only", "test");
        assertThat(r.outcome()).as(r.message()).isEqualTo("refused");
        assertThat(r.message()).contains("the store is full");
        assertThat(rows("mem://vx/only")).isEqualTo(50);
        assertThat(lake.health().get("state")).isNotEqualTo("green");
        assertThat(String.valueOf(lake.health().get("problems"))).contains("refused");
    }

    @Test
    void compactionMergesSmallFilesAndSavesMemory() throws Exception {
        lake = lake(64, List.of(table("ticks", "append", false)));
        for (int i = 0; i < 6; i++) {
            feed.set(docs(i * 50, 50, "batch" + i));
            lake.runNow("ticks", "test");
        }
        MemoryStorage store = MemoryStores.store("vx");
        long before = store.bytesUnder("mem://vx/ticks");
        assertThat(lake.compactor().candidates("vx")).isNotEmpty();
        assertThat(lake.compactor().rewrite(lake.table("ticks"), "test")).isTrue();
        long after = store.bytesUnder("mem://vx/ticks");
        assertThat(after).isLessThan(before);
        assertThat(rows("mem://vx/ticks")).isEqualTo(300);
        assertThat(lake.table("ticks").savedTotal.get()).isEqualTo(before - after);
        assertThat(lake.table("ticks").history().get(0).trigger()).isEqualTo("compaction");
    }

    @Test
    void errorsAreRecordedWithoutValues() {
        String why = MemoryLake.valuesFree(new IllegalArgumentException("Unrecognized token 'SECRET-123': was expecting \"SECRET-456\""));
        assertThat(why).doesNotContain("SECRET");
        assertThat(MemoryLake.duration("15m").toMinutes()).isEqualTo(15);
        assertThat(MemoryLake.duration("30s").toSeconds()).isEqualTo(30);
        assertThat(MemoryLake.date("field:AS_OF", new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("AS_OF", "2026-10-16 16:00")))
                .isEqualTo("2026-10-16");
    }
}
