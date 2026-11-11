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
package com.ash.drishti.server.bi;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.identity.bi.BiStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The BI folder is live: a file added appears, a file changed is the next version, a bad edit keeps the last good version and shows the problem, a file deleted is gone. */
class BiFolderWatchTest {

    @TempDir Path dir;

    static void until(BooleanSupplier ok) throws Exception {
        long end = System.currentTimeMillis() + 15_000;
        while (!ok.getAsBoolean()) {
            assertThat(System.currentTimeMillis()).as("timed out").isLessThan(end);
            Thread.sleep(50);
        }
    }

    private void check(String mode) throws Exception {
        Path reports = Files.createDirectories(dir.resolve("reports"));
        try (BiFolder folder = new BiFolder(dir.toString(), Duration.ofMillis(100), mode)) {
            folder.start();
            assertThat(folder.items()).isEmpty();
            assertThat(folder.watchState()).isIn("WATCHING", "POLLING");

            Path f = reports.resolve("pnl.report.yaml");
            Files.writeString(f, "title: P&L\npages: []\n");                                   // added
            until(() -> folder.item("file:report:pnl").isPresent());
            assertThat(folder.item("file:report:pnl").orElseThrow().publishedVersion()).isEqualTo(1);

            Files.writeString(f, "title: P&L v2\npages: [a]\n");                               // changed: the next version
            until(() -> folder.item("file:report:pnl").orElseThrow().publishedVersion() == 2);
            assertThat(folder.item("file:report:pnl").orElseThrow().title()).isEqualTo("P&L v2");

            Files.writeString(f, "title: [unclosed\n");                                        // broken: the last good version stays, the problem shows
            until(() -> !folder.problems().isEmpty());
            BiFolder.Problem p = folder.problems().get(0);
            assertThat(p.file()).isEqualTo("reports/pnl.report.yaml");
            assertThat(p.keptLastGood()).isTrue();
            BiStore.Item kept = folder.item("file:report:pnl").orElseThrow();
            assertThat(kept.title()).isEqualTo("P&L v2");
            assertThat(kept.publishedVersion()).isEqualTo(2);

            Files.writeString(f, "title: P&L fixed\n");                                       // fixed: the problem goes, the version moves on
            until(() -> folder.problems().isEmpty() && folder.item("file:report:pnl").orElseThrow().publishedVersion() == 3);

            Files.delete(f);                                                                   // deleted: gone
            until(() -> folder.item("file:report:pnl").isEmpty());
        }
    }

    @Test
    void auto() throws Exception {
        check("auto");
    }

    @Test
    void poll() throws Exception {
        check("poll");
    }

    @Test
    void offReadsOnTheNextRequestAfterTheRefreshInterval() throws Exception {
        Path reports = Files.createDirectories(dir.resolve("reports"));
        BiFolder folder = new BiFolder(dir.toString(), Duration.ofMillis(100), "off");
        folder.start();
        assertThat(folder.watchState()).isEqualTo("OFF");
        Files.writeString(reports.resolve("a.report.yaml"), "title: A\n");
        until(() -> !folder.items().isEmpty());
    }

    @Test
    void aFileThatNeverParsedHasNoItemButIsListed() throws Exception {
        Path reports = Files.createDirectories(dir.resolve("reports"));
        Files.writeString(reports.resolve("bad.report.yaml"), "- just\n- a list\n");
        try (BiFolder folder = new BiFolder(dir.toString(), Duration.ofMillis(100), "poll")) {
            folder.start();
            assertThat(folder.items()).isEmpty();
            assertThat(folder.problems()).singleElement().satisfies(p -> {
                assertThat(p.keptLastGood()).isFalse();
                assertThat(p.message()).contains("mapping");
            });
        }
    }
}
