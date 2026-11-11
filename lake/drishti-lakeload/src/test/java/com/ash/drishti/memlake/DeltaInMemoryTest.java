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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ash.drishti.deltalake.NativeEngine;
import com.ash.drishti.deltalake.TableReader;
import com.ash.drishti.deltalake.write.LakeManifest;
import com.ash.drishti.deltalake.write.LakeWriter;
import com.ash.drishti.deltalake.write.TableUpkeep;
import com.ash.drishti.memlake.store.MemoryStorage;
import com.ash.drishti.memlake.store.MemoryStoreFullException;
import com.ash.drishti.memlake.store.MemoryStores;
import io.delta.kernel.utils.FileStatus;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Delta tables in memory (docs/architecture/DATA_ARCHITECTURE.md): written by the lake writer from rows held in memory (no staged file),
 * read by the native engine unchanged, equal to the same table on disk, never a byte on the disk.
 */
class DeltaInMemoryTest {

    @TempDir
    Path tmp;

    @AfterEach
    void drop() {
        MemoryStores.remove("unit");
    }

    private static String rows(int from, int count, String word) {
        StringBuilder b = new StringBuilder();
        for (int i = from; i < from + count; i++) {
            String doc = "{\\\"NEWS_ID\\\":\\\"N" + i + "\\\",\\\"TITLE\\\":\\\"" + word + " " + i + "\\\"}";
            b.append("{\"id\":\"N").append(i).append("\",\"doc\":\"").append(doc).append("\",\"columns\":{}}\n");
        }
        return b.toString();
    }

    private static LakeManifest manifest(String table, String mode, String date, String rows, int count) {
        return new LakeManifest(table, List.of("business_date"),
                List.of(new LakeManifest.Column("id", "string"), new LakeManifest.Column("doc", "string"), new LakeManifest.Column("business_date", "date")),
                List.of(), mode, 500, List.of("id", "business_date"),
                List.of(LakeManifest.Part.inMemory(() -> new StringReader(rows), count, Map.of("business_date", date))));
    }

    private static Map<String, String> read(String table) throws Exception {
        try (NativeEngine engine = NativeEngine.create(Map.of())) {
            return TableReader.rows(engine, table, null);
        }
    }

    @Test
    void aTableInMemoryIsWrittenAndReadLikeOneOnDisk() throws Exception {
        MemoryStorage store = MemoryStores.define("unit", 64L << 20, 64 * 1024, "TEST");
        String table = "mem://unit/news/news";
        LakeWriter.Result r = LakeWriter.forTable(table, Map.of()).write(manifest(table, "overwrite-dates", "2026-11-02", rows(0, 300, "alpha"), 300),
                n -> { });
        assertEquals(0, r.version());
        assertEquals(300, r.rows());
        Map<String, String> got = read(table);
        assertEquals(300, got.size());
        assertTrue(got.get("2026-11-02|N7").contains("alpha 7"), got.get("2026-11-02|N7"));

        // replace: one commit, the new rows only; append adds
        LakeWriter.forTable(table, Map.of()).write(manifest(table, "overwrite-dates", "2026-11-02", rows(1000, 50, "beta"), 50), n -> { });
        got = read(table);
        assertEquals(50, got.size());
        assertTrue(got.get("2026-11-02|N1000").contains("beta 1000"));
        LakeWriter.forTable(table, Map.of()).write(manifest(table, "append", "2026-11-03", rows(2000, 5, "gamma"), 5), n -> { });
        assertEquals(55, read(table).size());
        assertTrue(store.isDirectory("mem://unit/news/news/_delta_log"));
        assertTrue(store.pool().inUseBytes() > 0 && store.pool().inUseBytes() <= 64L << 20);
    }

    @Test
    void theSameRowsGiveByteEqualParquetOnDiskAndInMemory() throws Exception {
        MemoryStores.define("unit", 64L << 20, 4096, "");
        String rows = rows(0, 200, "same");
        Path disk = tmp.resolve("lake/news/news");
        LakeWriter.forTable(disk.toString(), Map.of()).write(manifest(disk.toString(), "overwrite-dates", "2026-11-02", rows, 200), n -> { });
        String mem = "mem://unit/news/news";
        LakeWriter.forTable(mem, Map.of("codec", "snappy")).write(manifest(mem, "overwrite-dates", "2026-11-02", rows, 200), n -> { });
        assertEquals(read(disk.toString()).values().stream().sorted().toList(), read(mem).values().stream().sorted().toList());
        try (NativeEngine engine = NativeEngine.create(Map.of())) {
            List<FileStatus> d = TableReader.files(engine, disk.toString());
            List<FileStatus> m = TableReader.files(engine, mem);
            assertEquals(d.size(), m.size());
            for (int i = 0; i < d.size(); i++) {
                byte[] a = new com.ash.drishti.lakeio.LocalStorage().readAll(d.get(i).getPath());
                byte[] b = MemoryStores.store("unit").readAll(m.get(i).getPath());
                assertArrayEquals(a, b, "the Parquet bytes of file " + i);
            }
        }
    }

    @Test
    void aFullStoreRefusesTheCommitAndTheTableStaysWholeAtItsVersion() throws Exception {
        MemoryStorage store = MemoryStores.define("unit", 64L << 20, 4096, "VX-1");
        String table = "mem://unit/t";
        LakeWriter.forTable(table, Map.of()).write(manifest(table, "overwrite-dates", "2026-11-02", rows(0, 100, "kept"), 100), n -> { });
        store.pool().cap(store.pool().inUseBytes() + 4096);       // room for one more chunk only
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            big.append(rows(i * 1000, 100, "x".repeat(200) + i));
        }
        assertThrows(Exception.class, () -> LakeWriter.forTable(table, Map.of()).write(manifest(table, "overwrite-dates", "2026-11-02",
                big.toString(), 4000), n -> { }));
        assertTrue(store.refusals() > 0);
        Map<String, String> got = read(table);
        assertEquals(100, got.size());
        assertTrue(got.get("2026-11-02|N1").contains("kept 1"));
        assertTrue(new MemoryStoreFullException("x").getMessage().equals("x"));
    }

    @Test
    void oldVersionsAreExpiredAndRowsReadBackForARewrite() throws Exception {
        MemoryStorage store = MemoryStores.define("unit", 64L << 20, 4096, "");
        String table = "mem://unit/up";
        for (int i = 0; i < 12; i++) {                           // replaces: each version's files only live until it is past keep-versions
            LakeWriter.forTable(table, Map.of()).write(manifest(table, "replace", "2026-11-0" + (1 + i % 3), rows(i * 100, 100, "v" + i), 100), n -> { });
        }
        try (NativeEngine engine = NativeEngine.create(Map.of())) {
            TableUpkeep.Shape before = TableUpkeep.shape(engine, table, 1 << 20);
            assertEquals(11, before.version());
            assertTrue(before.rows() == 100 || before.rows() == -1, "rows " + before.rows());
            long used = store.pool().inUseBytes();
            long freed = MemoryUpkeep.expire(engine, store, table, 2);
            assertTrue(freed > 0, "freed " + freed);
            assertTrue(store.pool().inUseBytes() < used);
            assertEquals(100, read(table).size());
            assertEquals(2, store.filesIn(table).stream().filter(f -> f.path().endsWith(".parquet") && !f.path().contains("_delta_log")).count());
            List<TableUpkeep.Staged> staged = TableUpkeep.rows(engine, table, pv -> true);
            assertEquals(100, staged.size());
            assertTrue(staged.get(0).line().contains("\"doc\":") && staged.get(0).partitionValues().containsKey("business_date"));
            assertEquals(3, TableUpkeep.columns(engine, table).size());
        }
    }

    @Test
    void zstdIsTheDefaultCodecInMemory() throws Exception {
        MemoryStores.define("unit", 64L << 20, 4096, "");
        String table = "mem://unit/z";
        LakeWriter.forTable(table, Map.of()).write(manifest(table, "overwrite-dates", "2026-11-02", rows(0, 50, "z"), 50), n -> { });
        try (NativeEngine engine = NativeEngine.create(Map.of())) {
            List<String> codecs = new ArrayList<>();
            for (FileStatus f : TableReader.files(engine, table)) {
                try (org.apache.parquet.hadoop.ParquetFileReader r = org.apache.parquet.hadoop.ParquetFileReader.open(
                        com.ash.drishti.lakeio.ParquetInput.of(MemoryStores.store("unit"), f.getPath(), f.getSize()))) {
                    r.getFooter().getBlocks().forEach(b -> b.getColumns().forEach(c -> codecs.add(c.getCodec().name())));
                }
            }
            assertTrue(!codecs.isEmpty() && codecs.stream().allMatch("ZSTD"::equals), codecs.toString());
        }
    }
}
