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
package com.ash.drishti.dataload;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The README.md each folder of the data inbox keeps in git is never a data file: not claimed, waiting, orphaned, moved or listed. */
class ReadmeFoldersTest {

    private static final List<String> FOLDERS = List.of("inbox", "inprocess", "outbox", "failed", "ledger");

    @TempDir Path tmp;

    private static void readmes(Fixture f) throws IOException {
        Files.writeString(f.root.resolve("README.md"), "the inbox");
        for (String d : FOLDERS) {
            Files.createDirectories(f.root.resolve(d));
            Files.writeString(f.root.resolve(d).resolve("README.md"), d);
        }
    }

    @Test
    void theRuleIsOneNameInOnePlace() {
        assertThat(Inbox.ignored("README.md")).isTrue();
        assertThat(Inbox.ignored(".hidden")).isTrue();
        assertThat(Inbox.ignored("readme.jsonl")).isFalse();
        assertThat(Inbox.ignored("trades-a.jsonl")).isFalse();
    }

    @Test
    void aReadmeInTheInboxIsNotWaitingNorAnOrphanNorReady() throws IOException {
        Fixture f = new Fixture(tmp);
        readmes(f);
        Inbox.Found found = new Inbox(f.inbox()).look(f.clock.instant(), Duration.ofSeconds(30), 500, Long.MAX_VALUE);
        assertThat(found.ready()).isEmpty();
        assertThat(found.refused()).isEmpty();
        assertThat(found.waiting()).isEmpty();
        assertThat(found.orphanMarkers()).isEmpty();
        f.drop("README.md.ready", "", null);
        assertThat(new Inbox(f.inbox()).look(f.clock.instant(), Duration.ofSeconds(30), 500, Long.MAX_VALUE).ready()).isEmpty();
    }

    @Test
    void aRealFileWithItsMarkerStillLoadsAndEveryReadmeStaysWhereItIs() throws IOException {
        Fixture f = new Fixture(tmp);
        readmes(f);
        f.drop("trades-a.jsonl", Fixture.rows("2026-11-02", "1", "2"), "");
        Cycle.Report r = f.run();
        assertThat(r.state()).isEqualTo("done");
        assertThat(r.files()).extracting(Cycle.FileOutcome::file, Cycle.FileOutcome::outcome)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("trades-a.jsonl", "loaded"));
        for (String d : FOLDERS) {
            assertThat(f.root.resolve(d).resolve("README.md")).as(d).exists().hasContent(d);
        }
        assertThat(f.tree("inbox")).containsExactly("inbox/README.md");
        assertThat(f.tree("outbox")).noneMatch(p -> p.contains("/RUN/") && p.contains("README"));
        assertThat(f.ledgerLines()).extracting(n -> n.path("file").asText()).doesNotContain("README.md");
    }

    @Test
    void recoveryAndTheFailedListIgnoreReadmeFiles() throws IOException {
        Fixture f = new Fixture(tmp);
        readmes(f);
        Cycle.Report r = f.run();
        assertThat(r.files()).isEmpty();
        assertThat(new Outbox(f.root, f.clock, Fixture.ZONE).failedCopies()).isEmpty();
        assertThat(Recovery.dataFiles(f.root.resolve("inprocess"))).isEmpty();
        assertThat(f.root.resolve("inprocess/README.md")).exists();
    }
}
