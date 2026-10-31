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
package com.ash.drishti.plugin.delta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.IdMapFolder;
import com.ash.drishti.deltalake.write.LakeCli;
import com.ash.drishti.testkit.DatedSourceContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The document disk cache never serves stale data (2.9.7, OPERATIONS.md section 12, "Sizing caches and disk per connector"): a record
 * of the same date committed again is read new right after a data-load signal; without a signal it may be the old one within
 * {@code refresh-seconds} and is always the new one after that; an as-of read at the old version still reads the old one. The documents
 * are keyed by the table version they were read from, so the cache only ever answers for the version asked. Also: the id-map TTL's
 * minimum is enforced at start, and a map replaced by a new version says so on Admin -> Caches.
 */
class DeltaDocStalenessTest {

    static final LocalDate D1 = LocalDate.of(2026, 10, 21);
    static final int REFRESH_SECONDS = 2;
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tmp;

    /** Commits {@code T-1} (with {@code value}) and {@code T-2} as the whole of {@link #D1} in {@code <root>/desk/trade}. */
    private void commit(Path root, int value) throws Exception {
        List<String> rows = List.of(
                JSON.createObjectNode().put("id", "T-1").put("doc", "{\"id\":\"T-1\",\"value\":" + value + "}").toString(),
                JSON.createObjectNode().put("id", "T-2").put("doc", "{\"id\":\"T-2\",\"value\":0}").toString());
        Path rowsFile = Files.createTempFile(tmp, "rows", ".jsonl");
        Files.write(rowsFile, rows, StandardCharsets.UTF_8);
        ObjectNode m = JSON.createObjectNode();
        m.put("table", root.resolve("desk").resolve("trade").toString());
        m.putArray("partitionColumns").add("business_date");
        ArrayNode c = m.putArray("columns");
        for (String[] nt : new String[][] {{"id", "string"}, {"doc", "string"}, {"business_date", "date"}}) {
            c.addObject().put("name", nt[0]).put("type", nt[1]);
        }
        m.put("mode", "overwrite-dates");
        m.putArray("statsColumns").add("id").add("business_date");
        m.putArray("parts").addObject().put("file", rowsFile.toString()).putObject("values").put("business_date", D1.toString());
        Path mf = Files.createTempFile(tmp, "manifest", ".json");
        Files.writeString(mf, m.toString());
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = LakeCli.run(new String[] {"lake", "write", "--manifest", mf.toString()}, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        assertThat(code).as(err.toString(StandardCharsets.UTF_8)).isZero();
    }

    private DeltaSourcePlugin plugin(Path root, Map<String, String> extra) throws Exception {
        Map<String, String> s = new HashMap<>(Map.of("root", root.toString(), "domain", "desk", "source-name", "stale-lake", "engine", "native",
                "doc-disk-cache.dir", tmp.resolve("cache/docs").toString(), "id-map-disk.dir", tmp.resolve("cache/idmaps").toString(),
                "doc-cache-mb", "0", "refresh-seconds", String.valueOf(REFRESH_SECONDS), "warm-dates", "0"));
        s.putAll(extra);
        DeltaSourcePlugin p = new DeltaSourcePlugin();
        p.start(DatedSourceContract.context(s));
        return p;
    }

    private static int value(DeltaSourcePlugin p, AsOf asOf) throws Exception {
        return (int) p.fetch(EntityRef.of("trade", "T-1"), asOf).orElseThrow().data().get("value").asDouble();
    }

    private static long stat(DeltaSourcePlugin p, String name) {
        return ((Number) p.cacheStats().get(name)).longValue();
    }

    @Test
    void aRecordCommittedAgainIsReadNewAfterTheSignalOrTheRefreshAndOldOnlyAsOfTheOldVersion() throws Exception {
        Path root = tmp.resolve("lake");
        commit(root, 1);
        DeltaSourcePlugin p = plugin(root, Map.of());
        try {
            assertThat(value(p, AsOf.of(D1))).isEqualTo(1);
            assertThat(value(p, AsOf.of(D1))).isEqualTo(1);
            assertThat(p.cacheStats()).as("the disk cache is open").containsKey("docsOnDiskMB");
            assertThat(stat(p, "docsOnDiskHits") + stat(p, "docsOnDiskMisses")).as("documents go through the disk cache").isGreaterThan(0);
            Thread.sleep(1100);                                              // commit times a second apart: as-of reads tell them apart
            Instant atFirst = Instant.now();
            Thread.sleep(1100);

            // (i) a data-load signal: the next read is the new document
            commit(root, 2);
            p.refreshVersions();
            assertThat(value(p, AsOf.of(D1))).isEqualTo(2);
            Thread.sleep(1100);
            Instant atSecond = Instant.now();
            Thread.sleep(1100);

            // (ii) no signal: within refresh-seconds the old one may still answer; after it, always the new one
            commit(root, 3);
            long committed = System.nanoTime();
            assertThat(value(p, AsOf.of(D1))).isIn(2, 3);
            Thread.sleep(REFRESH_SECONDS * 1000L + 500);
            assertThat(System.nanoTime() - committed).isGreaterThan(REFRESH_SECONDS * 1_000_000_000L);
            for (int i = 0; i < 5; i++) {
                assertThat(value(p, AsOf.of(D1))).as("after refresh-seconds, never the cached old document").isEqualTo(3);
            }

            // (iii) as of the older versions: their own documents, from the cache or the lake, never the newest
            assertThat(value(p, new AsOf(D1, atFirst))).isEqualTo(1);
            assertThat(value(p, new AsOf(D1, atSecond))).isEqualTo(2);
            assertThat(value(p, AsOf.of(D1))).isEqualTo(3);

            // the earlier versions' id maps were replaced, and Admin -> Caches says so
            assertThat(stat(p, "idMapsReplaced")).isGreaterThan(0);
        } finally {
            p.close();
        }
        // a restart keeps the disk cache: the latest read is still the newest, the old version still the old one
        DeltaSourcePlugin again = plugin(root, Map.of());
        try {
            assertThat(value(again, AsOf.of(D1))).isEqualTo(3);
            assertThat(value(again, new AsOf(D1, atFirstOf(root)))).isEqualTo(1);
        } finally {
            again.close();
        }
    }

    /** An instant between the first and second commits, from the commit files' times (Delta resolves as-of reads against them). */
    private static Instant atFirstOf(Path root) throws Exception {
        Path log = root.resolve("desk/trade/_delta_log");
        long first = Files.getLastModifiedTime(log.resolve(String.format("%020d.json", 0))).toMillis();
        return Instant.ofEpochMilli(first + 1000);
    }

    /** An id map released for being idle (id-map-disk.idle-release) keeps its file; the next read maps it again and answers the same. */
    @Test
    void aMapReleasedForBeingIdleIsMappedAgainAtTheNextRead() throws Exception {
        Path root = tmp.resolve("lake");
        commit(root, 4);
        DeltaSourcePlugin p = plugin(root, Map.of("doc-disk-cache.enabled", "false"));
        try {
            assertThat(value(p, AsOf.of(D1))).isEqualTo(4);
            Path maps = tmp.resolve("cache/idmaps");
            assertThat(com.ash.drishti.api.MappedIdFile.mapped(maps)[0]).isEqualTo(1L);
            try (var files = Files.walk(maps)) {
                for (Path f : files.filter(x -> x.toString().endsWith(".idmap")).toList()) {
                    com.ash.drishti.api.MappedIdFile.releaseThen(f, () -> { });   // what the sweep does to an idle map
                }
            }
            assertThat(com.ash.drishti.api.MappedIdFile.mapped(maps)[0]).isZero();
            assertThat(value(p, AsOf.of(D1))).isEqualTo(4);
            assertThat(p.fetch(EntityRef.of("trade", "T-2"), AsOf.of(D1))).isPresent();
            assertThat(com.ash.drishti.api.MappedIdFile.mapped(maps)[0]).as("mapped again").isEqualTo(1L);
            assertThatThrownBy(() -> plugin(root, Map.of("id-map-disk.idle-release", "1m"))).hasMessageContaining("id-map-disk.idle-release")
                    .hasMessageContaining("minimum is 5m");
        } finally {
            p.close();
        }
    }

    @Test
    void anIdMapTtlUnderADayIsRefusedAtStartNamingTheSettingAndTheMinimum() throws Exception {
        Path root = tmp.resolve("lake");
        commit(root, 1);
        assertThatThrownBy(() -> plugin(root, Map.of("id-map-disk.ttl", "2h"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stale-lake").hasMessageContaining("id-map-disk.ttl").hasMessageContaining("minimum is 24h");
        DeltaSourcePlugin p = plugin(root, Map.of("id-map-disk.ttl", "36h"));
        try {
            assertThat(p.cacheStats()).containsEntry("idMapTtl", "PT36H");
            assertThat(IdMapFolder.all()).anyMatch(f -> f.connector().equals("stale-lake"));
        } finally {
            p.close();
        }
        assertThat(IdMapFolder.all()).noneMatch(f -> f.connector().equals("stale-lake") && f.dir().startsWith(tmp));
    }
}
