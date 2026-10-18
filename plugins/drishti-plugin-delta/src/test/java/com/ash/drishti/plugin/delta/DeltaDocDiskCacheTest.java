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

import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.testkit.DatedSourceContract;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The documents tier on disk (doc-disk-cache): a document read once is answered from disk, also after a restart; a purge empties it. */
class DeltaDocDiskCacheTest {

    @TempDir
    Path tmp;

    private DeltaSourcePlugin plugin(Path root, Path cache) throws Exception {
        DeltaSourcePlugin p = new DeltaSourcePlugin();
        p.start(DatedSourceContract.context(Map.of("root", root.toString(), "domain", "desk", "source-name", "lake", "engine", "native",
                "layout.trade.columns", "mtm,book,nettingSet,counterparty.id", "doc-disk-cache.enabled", "true", "doc-disk-cache.dir", cache.toString(),
                "doc-cache-mb", "0")));
        return p;
    }

    private Path lake() throws Exception {
        Path root = tmp.resolve("lake");
        Path src = Path.of("src/test/resources/lake-layout");
        try (Stream<Path> files = Files.walk(src)) {
            for (Path f : files.toList()) {
                Path to = root.resolve(src.relativize(f).toString());
                if (Files.isDirectory(f)) {
                    Files.createDirectories(to);
                } else {
                    Files.copy(f, to);
                }
            }
        }
        return root;
    }

    private static long stat(DeltaSourcePlugin p, String name) {
        return ((Number) p.cacheStats().get(name)).longValue();
    }

    @Test
    void aDocumentReadOnceIsAnsweredFromDiskAlsoAfterARestartAndAPurgeEmptiesIt() throws Exception {
        Path root = lake();
        Path cache = tmp.resolve("data").resolve("cache").resolve("lake").resolve("docs");
        EntityRef t1 = EntityRef.of("trade", "T-001");
        DeltaSourcePlugin first = plugin(root, cache);
        EntityDocument read = first.fetch(t1).orElseThrow();                 // read from Parquet, kept on disk
        first.close();
        assertThat(cache).isDirectory();                                    // under the folder it was given, nowhere else

        // the start-up warm-up reads a document of each recent date in the background: counts are compared, not fixed
        DeltaSourcePlugin again = plugin(root, cache);                      // a restart: the heap tier is empty, the disk kept
        assertThat(again.fetch(t1).orElseThrow().data().unwrap()).isEqualTo(read.data().unwrap());
        assertThat(stat(again, "docsOnDiskHits")).isGreaterThan(0);        // answered from disk
        again.purgeCaches();
        long missesBefore = stat(again, "docsOnDiskMisses");
        assertThat(again.fetch(t1)).isPresent();
        assertThat(stat(again, "docsOnDiskMisses")).isGreaterThan(missesBefore);   // purged: read from Parquet again
        again.close();
    }

    @Test
    void aCacheStillLockedByAStoppingServerIsOpenedOnceItIsFree() throws Exception {
        Path root = lake();
        Path cache = tmp.resolve("data").resolve("cache").resolve("lake").resolve("docs");
        long was = DeltaSourcePlugin.diskRetrySeconds;
        DeltaSourcePlugin.diskRetrySeconds = 1;
        try {
            DeltaSourcePlugin stopping = plugin(root, cache);                  // the previous process, not yet gone
            DeltaSourcePlugin started = plugin(root, cache);                   // finds the cache locked: reads go on without it
            assertThat(started.cacheStats()).doesNotContainKey("docsOnDiskMB");
            assertThat(started.fetch(EntityRef.of("trade", "T-001"))).isPresent();
            stopping.close();                                                  // the lock is released
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
            while (!started.cacheStats().containsKey("docsOnDiskMB") && System.nanoTime() < deadline) {
                Thread.sleep(200);
            }
            assertThat(started.cacheStats()).containsKey("docsOnDiskMB");    // opened by a retry, not at the next start
            started.close();
        } finally {
            DeltaSourcePlugin.diskRetrySeconds = was;
        }
    }

    @Test
    void durationsReadAsTheSettingsWriteThem() {
        assertThat(DeltaSourcePlugin.duration("12h")).hasHours(12);
        assertThat(DeltaSourcePlugin.duration("30m")).hasMinutes(30);
        assertThat(DeltaSourcePlugin.duration("PT2H")).hasHours(2);
    }
}
