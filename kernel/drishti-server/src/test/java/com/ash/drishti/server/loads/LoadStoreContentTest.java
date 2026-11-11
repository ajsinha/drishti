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
package com.ash.drishti.server.loads;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.identity.content.FileContentStore;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Load history in the content store (DATA_ARCHITECTURE.md › The content store: user content every node reaches, section 9.6): two nodes on one root, the old file imported once. */
class LoadStoreContentTest {

    @Test
    void twoNodesSeeOneHistoryAndALateFlagGoesOutOnce() throws Exception {
        Path dir = com.ash.drishti.server.TestRun.root().resolve(Path.of("content-tests", "loads-" + UUID.randomUUID()));
        Path folder = dir.resolve("loads");
        LocalDate d = LocalDate.of(2026, 10, 20);
        LoadStore old = new LoadStore(folder, 100);
        old.begin("finance", "trades", d, LoadRecord.READY, 10L, 0L, "file", "b0", "", Instant.now(), "ann");
        Path root = dir.resolve("fstore");
        LoadStore a = new LoadStore(folder, 100, new FileContentStore(root));
        LoadStore b = new LoadStore(folder, 100, new FileContentStore(root));
        assertThat(b.list("finance", null, null, null, 10)).as("imported from the file").hasSize(1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<?>> fs = new ArrayList<>();
        for (LoadStore s : List.of(a, b)) {
            fs.add(pool.submit(() -> {
                for (int i = 0; i < 10; i++) {
                    s.begin("finance", "trades", d, LoadRecord.READY, (long) i, 0L, "file", (s == a ? "a" : "b") + i, "", Instant.now(), "ann");
                }
                return null;
            }));
        }
        for (Future<?> f : fs) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(a.list("finance", null, null, null, 100)).as("no announcement lost").hasSize(21);
        assertThat(a.begin("finance", "trades", d, LoadRecord.READY, 3L, 0L, "file", "z3", "", Instant.now(), "ann").duplicate()).isFalse();
        assertThat(b.begin("finance", "trades", d, LoadRecord.READY, 3L, 0L, "file", "z3", "", Instant.now(), "ann").duplicate())
                .as("the other node sees the batch").isTrue();
        assertThat(a.flagLate("finance", "trades", d, Instant.now())).isTrue();
        assertThat(b.flagLate("finance", "trades", d, Instant.now())).isFalse();
        assertThat(b.clearLate("finance", "trades", d)).isTrue();
        assertThat(a.lateFlags("finance")).isEmpty();
    }
}
