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

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ash.drishti.deltalake.write.LakeManifest;
import com.ash.drishti.deltalake.write.LakeWriter;
import com.ash.drishti.memlake.store.MemoryStorage;
import com.ash.drishti.memlake.store.MemoryStores;
import com.ash.drishti.testkit.FeedFiles;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The memory a table costs (docs/architecture/DATA_ARCHITECTURE.md sections 2 and 5; the measured figures in RESOURCES_AND_SCALE.md): demo tables (at most 10,000
 * rows each, from data/testdata) written as the disk lake writes them (Snappy) and as the in-memory lake does (sorted by key, zstd), against
 * their decoded JSON. Prints one line a table; asserts only that memory is smaller than the decoded rows and not larger than the disk.
 */
class MemoryRatiosTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path TESTDATA = Path.of("../data/testdata").toAbsolutePath().normalize();

    @TempDir
    Path tmp;

    @AfterEach
    void drop() {
        MemoryStores.remove("ratios");
    }

    private static List<ObjectNode> read(Path folder, int max) throws Exception {
        List<ObjectNode> out = new ArrayList<>();
        for (FeedFiles.Part f : FeedFiles.parts(folder)) {           // its dated files and tars (DATA_LAKE_GUIDE.md section 5.2)
            for (String line : f.lines()) {
                if (out.size() >= max) {
                    return out;
                }
                out.add((ObjectNode) JSON.readTree(line));
            }
        }
        return out;
    }

    private static String staged(List<ObjectNode> docs, String key) {
        List<ObjectNode> sorted = new ArrayList<>(docs);
        sorted.sort(Comparator.comparing(d -> d.path(key).asText()));
        StringBuilder b = new StringBuilder();
        int i = 0;
        for (ObjectNode d : sorted) {
            JsonNode k = d.get(key);
            ObjectNode line = JSON.createObjectNode().put("id", k == null ? "row-" + i : k.asText()).put("doc", d.toString());
            line.putObject("columns");
            b.append(line).append('\n');
            i++;
        }
        return b.toString();
    }

    private static LakeManifest manifest(String table, String rows, int n) {
        return new LakeManifest(table, List.of("business_date"),
                List.of(new LakeManifest.Column("id", "string"), new LakeManifest.Column("doc", "string"), new LakeManifest.Column("business_date", "date")),
                List.of(), "replace", 5_000, List.of("id", "business_date"),
                List.of(LakeManifest.Part.inMemory(() -> new StringReader(rows), n, Map.of("business_date", "2026-10-02"))));
    }

    private static long parquetBytes(Path dir) throws Exception {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> p.toString().endsWith(".parquet") && !p.toString().contains("_delta_log")).mapToLong(p -> p.toFile().length()).sum();
        }
    }

    @Test
    void memoryPerTableAgainstDiskAndDecoded() throws Exception {
        MemoryStorage store = MemoryStores.define("ratios", 256L << 20, 64 * 1024, "");
        String[][] tables = {{"news-and-markets/news", "NEWS_ID"}, {"news-and-markets/quote", "QUOTE_ID"}, {"trades/calypso", "TRADE_ID"},
            {"core/counterparty", "COUNTERPARTY_ID"}, {"market-risk/desk-pnl", "DESK_PNL_ID"}};
        System.out.println("MEMORY RATIOS  table | rows | decoded JSON | disk Parquet (snappy) | memory Parquet (zstd, sorted) | held in 64 KB chunks | decoded/memory");
        for (String[] t : tables) {
            Path folder = TESTDATA.resolve(t[0]);
            if (!Files.isDirectory(folder)) {
                continue;
            }
            List<ObjectNode> docs = read(folder, 10_000);
            if (docs.isEmpty()) {
                continue;
            }
            String rows = staged(docs, t[1]);
            long decoded = docs.stream().mapToLong(d -> d.toString().getBytes(StandardCharsets.UTF_8).length).sum();
            Path disk = tmp.resolve(t[0].replace('/', '_'));
            LakeWriter.forTable(disk.toString(), Map.of()).write(manifest(disk.toString(), rows, docs.size()), n -> { });
            String mem = "mem://ratios/" + t[0].replace('/', '_');
            LakeWriter.forTable(mem, Map.of()).write(manifest(mem, rows, docs.size()), n -> { });
            long diskBytes = parquetBytes(disk);
            long memFile = store.filesIn(mem).stream().filter(f -> f.path().endsWith(".parquet") && !f.path().contains("_delta_log"))
                    .mapToLong(com.ash.drishti.lakeio.FileEntry::size).sum();
            long held = store.bytesUnder(mem);
            System.out.printf(java.util.Locale.ROOT, "MEMORY RATIOS  %s | %,d | %,d | %,d | %,d | %,d | %.1fx%n", t[0], docs.size(), decoded, diskBytes, memFile,
                    held, (double) decoded / memFile);
            assertTrue(memFile < decoded, t[0]);
            assertTrue(memFile <= diskBytes * 1.05, t[0] + " zstd no larger than snappy");
        }
    }
}
