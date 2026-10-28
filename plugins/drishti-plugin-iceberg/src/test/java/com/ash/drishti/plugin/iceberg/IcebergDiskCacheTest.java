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
package com.ash.drishti.plugin.iceberg;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.testkit.DatedSourceContract;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Iceberg connector's disk caches (as the Delta connector's): after a restart, a date's id map is mapped from its file and a
 * document read before comes from the document cache, so neither reads a data file (the test removes the data files of those dates
 * before the restart, and both still answer); a commit of another date keeps them valid (each is keyed by its date's files); a
 * vector document is cached with its vector put back. With both caches off, nothing is written under the cache folder.
 */
class IcebergDiskCacheTest {

    static final LocalDate D1 = LocalDate.of(2026, 10, 21);
    static final LocalDate D2 = LocalDate.of(2026, 10, 22);
    static final LocalDate D3 = LocalDate.of(2026, 10, 23);

    @TempDir
    Path tmp;

    private IcebergSourcePlugin plugin(Path root, Path cache, Map<String, String> extra) throws Exception {
        Map<String, String> s = new java.util.HashMap<>(VectorLake.settings(root, "native", cache));
        s.putAll(extra);
        IcebergSourcePlugin p = new IcebergSourcePlugin();
        p.start(DatedSourceContract.context(s));
        return p;
    }

    @Test
    void afterARestartIdMapsAndDocumentsComeFromDiskWithoutReadingADataFile() throws Exception {
        Path root = tmp.resolve("lake");
        Path cache = tmp.resolve("cache");
        Map<LocalDate, List<VectorLake.Pos>> days = new TreeMap<>(Map.of(D1, VectorLake.positions(10, 1), D2, VectorLake.positions(10, 2)));
        VectorLake.loadIceberg(tmp, root, "native", days);
        Map<LocalDate, Object> before = new TreeMap<>();
        IcebergSourcePlugin first = plugin(root, cache, Map.of());
        try {
            for (LocalDate d : days.keySet()) {
                before.put(d, first.fetch(EntityRef.of(VectorLake.KIND, "POS-00003"), AsOf.of(d)).orElseThrow().data().unwrap());
            }
            assertThat(first.cacheStats()).containsEntry("idMapsMappedFromDisk", 0L);
        } finally {
            first.close();
        }
        assertThat(Files.list(cache.resolve("idmaps").resolve(VectorLake.KIND)).toList()).hasSize(2);

        // a commit of another date, then the data files of the first two dates gone: only their caches can answer for them
        VectorLake.loadIceberg(tmp, root, "native", Map.of(D3, VectorLake.positions(10, 3)));
        int removed = 0;
        for (LocalDate d : days.keySet()) {
            try (var files = Files.list(root.resolve("risk").resolve(VectorLake.KIND).resolve("data").resolve("business_date=" + d))) {
                for (Path f : files.toList()) {
                    Files.delete(f);
                    removed++;
                }
            }
        }
        assertThat(removed).isGreaterThan(2);
        com.ash.drishti.lakeio.ParquetFooters.purge();          // nothing of the files is left in this process either
        com.ash.drishti.iceberg.read.Deletes.purge();

        IcebergSourcePlugin second = plugin(root, cache, Map.of());
        try {
            for (LocalDate d : days.keySet()) {
                var doc = second.fetch(EntityRef.of(VectorLake.KIND, "POS-00003"), AsOf.of(d)).orElseThrow().data();
                assertThat(doc.unwrap()).as("the document of " + d).isEqualTo(before.get(d));
                assertThat(doc.get("PNL").size()).as("cached with its vector").isEqualTo(VectorLake.N);
                assertThat(second.days(EntityRef.of(VectorLake.KIND, "POS-00009"), d, d).orElseThrow()).containsExactly(d);
            }
            assertThat((Long) second.cacheStats().get("idMapsMappedFromDisk")).isGreaterThanOrEqualTo(2L);
            assertThat((Long) second.cacheStats().get("docsOnDiskHits")).isGreaterThanOrEqualTo(2L);
            // the new date is read from the lake as usual
            assertThat(second.fetch(EntityRef.of(VectorLake.KIND, "POS-00003"), AsOf.of(D3))).isPresent();
        } finally {
            second.close();
        }
    }

    @Test
    void bothCachesOffWriteNothingUnderTheCacheFolder() throws Exception {
        Path root = tmp.resolve("lake");
        Path cache = tmp.resolve("cache");
        VectorLake.loadIceberg(tmp, root, "native", Map.of(D1, VectorLake.positions(5, 1)));
        IcebergSourcePlugin p = plugin(root, cache, Map.of("id-map-disk.enabled", "false", "doc-disk-cache.enabled", "false"));
        try {
            assertThat(p.fetch(EntityRef.of(VectorLake.KIND, "POS-00001"), AsOf.of(D1))).isPresent();
            assertThat(p.cacheStats()).containsEntry("idMapsOnDisk", false).doesNotContainKey("docsOnDiskMB");
        } finally {
            p.close();
        }
        assertThat(cache).doesNotExist();
    }

    @Test
    void purgingTheCachesEmptiesTheDiskToo() throws Exception {
        Path root = tmp.resolve("lake");
        Path cache = tmp.resolve("cache");
        VectorLake.loadIceberg(tmp, root, "native", Map.of(D1, VectorLake.positions(5, 1)));
        IcebergSourcePlugin p = plugin(root, cache, Map.of());
        try {
            assertThat(p.fetch(EntityRef.of(VectorLake.KIND, "POS-00001"), AsOf.of(D1))).isPresent();
            assertThat(Files.list(cache.resolve("idmaps").resolve(VectorLake.KIND)).toList()).isNotEmpty();
            p.purgeCaches();
            assertThat(Files.exists(cache.resolve("idmaps").resolve(VectorLake.KIND))).isFalse();
            assertThat(p.fetch(EntityRef.of(VectorLake.KIND, "POS-00001"), AsOf.of(D1))).isPresent();   // read from the lake again
        } finally {
            p.close();
        }
    }
}
