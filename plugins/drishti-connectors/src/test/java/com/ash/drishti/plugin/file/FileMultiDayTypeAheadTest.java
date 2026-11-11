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
package com.ash.drishti.plugin.file;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityHit;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.common.JsonCodec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The file connector keeps five business days of ids for type-ahead; each lookup answers for its own date. */
class FileMultiDayTypeAheadTest {

    @TempDir
    Path root;

    private static final LocalDate D0 = LocalDate.of(2026, 9, 28);

    private FileSourcePlugin started(Map<String, String> extra) {
        JsonCodec codec = new JsonCodec();
        FileSourcePlugin p = new FileSourcePlugin();
        Map<String, String> settings = new java.util.HashMap<>(Map.of("root", root.toString(), "source-name", "eod"));
        settings.putAll(extra);
        p.start(new SourceContext() {
            public Map<String, String> settings() {
                return settings;
            }

            public DataNode parseJson(InputStream in) throws IOException {
                return codec.read(in);
            }

            public ScheduledExecutorService scheduler() {
                return Executors.newSingleThreadScheduledExecutor();
            }
        });
        return p;
    }

    /** Day d holds trades T-d-0 .. T-d-2 plus the long-lived T-ALWAYS; day 2 also has a JSON-lines kind. */
    private void history() throws IOException {
        for (int d = 0; d < 5; d++) {
            Path dir = root.resolve(D0.plusDays(d).toString()).resolve("trade");
            Files.createDirectories(dir);
            for (int i = 0; i < 3; i++) {
                Files.writeString(dir.resolve("T-" + d + "-" + i + ".json"), "{}");
            }
            Files.writeString(dir.resolve("T-ALWAYS.json"), "{}");
        }
    }

    private static List<String> ids(List<EntityHit> hits) {
        return hits.stream().map(h -> h.ref().id()).toList();
    }

    @Test
    void eachPastDateAnswersWithItsOwnIdsOnly() throws Exception {
        history();
        FileSourcePlugin p = started(Map.of("index.days", "5"));
        p.datedIndexForTest().awaitWarm(10_000);
        for (int d = 0; d < 5; d++) {
            List<String> got = ids(p.search("trade", "T-", 20, AsOf.of(D0.plusDays(d))));
            assertThat(got).containsExactlyInAnyOrder("T-" + d + "-0", "T-" + d + "-1", "T-" + d + "-2", "T-ALWAYS");
        }
        assertThat(ids(p.search("trade", "T-1-", 20, AsOf.of(D0.plusDays(3))))).isEmpty();
        assertThat(p.health()).contains("type-ahead index: 5 days");
        assertThat(p.cacheStats()).containsEntry("indexDays", 5);
    }

    @Test
    void aDayOutsideTheKeptWindowAnswersFromTheNewestDayAsBefore() throws Exception {
        history();
        FileSourcePlugin p = started(Map.of("index.days", "2"));
        p.datedIndexForTest().awaitWarm(10_000);
        assertThat(ids(p.search("trade", "T-", 20, AsOf.of(D0.plusDays(3))))).contains("T-3-0").doesNotContain("T-1-0");
        // day 1 is outside the window: the connector answers as it did before, from the newest day
        assertThat(ids(p.search("trade", "T-", 20, AsOf.of(D0.plusDays(1))))).contains("T-4-0");
    }

    @Test
    void theDefaultKeepsOneDayAndAddsNothingToHealth() throws Exception {
        history();
        FileSourcePlugin p = started(Map.of());
        assertThat(p.health()).isEqualTo("UP");
        assertThat(p.cacheStats()).doesNotContainKey("indexDays");
    }

    @Test
    void aRestatedPastDayIsRebuiltOnTheNextScan() throws Exception {
        history();
        FileSourcePlugin p = started(Map.of("index.days", "5"));
        p.datedIndexForTest().awaitWarm(10_000);
        Path dir = root.resolve(D0.plusDays(1).toString()).resolve("trade");
        Files.writeString(dir.resolve("T-RESTATED.json"), "{}");
        Files.setLastModifiedTime(dir, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));
        p.rescan();
        p.datedIndexForTest().awaitWarm(10_000);
        assertThat(ids(p.search("trade", "T-REST", 20, AsOf.of(D0.plusDays(1))))).containsExactly("T-RESTATED");
        assertThat(ids(p.search("trade", "T-REST", 20, AsOf.of(D0.plusDays(2))))).isEmpty();
    }

    @Test
    void rollingToANewBusinessDayDropsTheOldestDay() throws Exception {
        history();
        FileSourcePlugin p = started(Map.of("index.days", "5"));
        p.datedIndexForTest().awaitWarm(10_000);
        Path dir = root.resolve(D0.plusDays(5).toString()).resolve("trade");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("T-5-0.json"), "{}");
        p.rescan();
        p.datedIndexForTest().awaitWarm(10_000);
        assertThat(ids(p.search("trade", "T-5", 20, AsOf.of(D0.plusDays(5))))).containsExactly("T-5-0");
        assertThat(p.datedIndexForTest().has("trade", D0)).isFalse();
        assertThat(p.datedIndexForTest().stats().days()).isEqualTo(5);
    }
}
