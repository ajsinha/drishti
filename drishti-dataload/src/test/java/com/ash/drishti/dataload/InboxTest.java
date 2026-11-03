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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The hand-shake (DATALOAD_INBOX.md section 3): markers, ready.all, stability, names, links, the cycle's caps. */
class InboxTest {

    @TempDir Path tmp;

    private Inbox.Found look(Fixture f) throws IOException {
        return new Inbox(f.inbox()).look(f.clock.instant(), Duration.ofSeconds(30), 500, Long.MAX_VALUE);
    }

    @Test
    void aFileIsReadyOnlyWithItsMarker() throws IOException {
        Fixture f = new Fixture(tmp);
        f.drop("trades-a.jsonl", Fixture.rows("2026-11-02", "1"), "");
        f.drop("trades-b.jsonl", Fixture.rows("2026-11-02", "2"), null);
        Inbox.Found found = look(f);
        assertThat(found.ready()).extracting(Inbox.Ready::name).containsExactly("trades-a.jsonl");
        assertThat(found.ready().get(0).marker()).isEqualTo(f.inbox().resolve("trades-a.jsonl.ready"));
        assertThat(found.waiting()).extracting(Inbox.Waiting::name, Inbox.Waiting::why).containsExactly(
                org.assertj.core.groups.Tuple.tuple("trades-b.jsonl", "no trades-b.jsonl.ready yet"));
        assertThat(found.readyAll()).isNull();
    }

    @Test
    void readyAllMakesEveryDataFileReadyWithOrWithoutItsMarker() throws IOException {
        Fixture f = new Fixture(tmp);
        f.drop("trades-a.jsonl", Fixture.rows("2026-11-02", "1"), "");
        f.drop("trades-b.jsonl", Fixture.rows("2026-11-02", "2"), null);
        f.drop("notes.txt", "hello", null);
        Files.writeString(f.inbox().resolve(Inbox.READY_ALL), "");
        Inbox.Found found = look(f);
        assertThat(found.ready()).extracting(Inbox.Ready::name).containsExactlyInAnyOrder("trades-a.jsonl", "trades-b.jsonl");
        assertThat(found.ready()).filteredOn(r -> r.name().equals("trades-b.jsonl")).singleElement().extracting(Inbox.Ready::marker).isNull();
        assertThat(found.refused()).singleElement().satisfies(r -> {
            assertThat(r.file().getFileName().toString()).isEqualTo("notes.txt");
            assertThat(r.reason()).contains("the loader does not read notes.txt");
        });
        assertThat(found.readyAll()).isEqualTo(f.inbox().resolve(Inbox.READY_ALL));
    }

    @Test
    void aFileOrMarkerChangedWithinTheStableTimeWaitsEvenUnderReadyAll() throws IOException {
        Fixture f = new Fixture(tmp);
        Path a = f.drop("trades-a.jsonl", Fixture.rows("2026-11-02", "1"), "");
        Files.setLastModifiedTime(a, FileTime.from(f.clock.instant().minusSeconds(5)));
        Path b = f.drop("trades-b.jsonl", Fixture.rows("2026-11-02", "2"), "");
        Files.setLastModifiedTime(f.inbox().resolve("trades-b.jsonl.ready"), FileTime.from(f.clock.instant().minusSeconds(10)));
        Files.writeString(f.inbox().resolve(Inbox.READY_ALL), "");
        Inbox.Found found = look(f);
        assertThat(found.ready()).isEmpty();
        assertThat(found.waiting()).extracting(Inbox.Waiting::why).allMatch(w -> w.startsWith("not stable yet"));
        assertThat(b).exists();
    }

    @Test
    void hiddenNamesAreNeverLookedAtAndMarkersWithoutAFileAreListed() throws IOException {
        Fixture f = new Fixture(tmp);
        f.drop(".trades-hidden.jsonl", "x", "");
        Files.writeString(f.inbox().resolve("gone.jsonl.ready"), "");
        Inbox.Found found = look(f);
        assertThat(found.ready()).isEmpty();
        assertThat(found.waiting()).isEmpty();
        assertThat(found.orphanMarkers()).containsExactly("gone.jsonl.ready");
    }

    @Test
    void linksFoldersAndUnreadNamesAreRefusedOnceMarked() throws IOException {
        Fixture f = new Fixture(tmp);
        Path real = tmp.resolve("elsewhere.jsonl");
        Files.writeString(real, Fixture.rows("2026-11-02", "1"));
        Path link = f.inbox().resolve("trades-link.jsonl");
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "this file system makes no symbolic links");
        }
        Files.writeString(f.inbox().resolve("trades-link.jsonl.ready"), "");
        Files.createDirectories(f.inbox().resolve("trades-dir.jsonl"));
        Files.writeString(f.inbox().resolve("trades-dir.jsonl.ready"), "");
        f.drop("trades.csv", "a,b", "");
        f.drop("data.gz", "x", "");
        f.age(f.inbox().resolve("trades-link.jsonl.ready"), f.inbox().resolve("trades-dir.jsonl.ready"));
        Inbox.Found found = look(f);
        assertThat(found.ready()).isEmpty();
        assertThat(found.refused()).extracting(r -> r.file().getFileName().toString())
                .containsExactly("data.gz", "trades-dir.jsonl", "trades-link.jsonl", "trades.csv");
        assertThat(found.refused().get(2).reason()).contains("a link");
        assertThat(found.refused().get(1).reason()).contains("a folder");
    }

    @Test
    void everyFormatTheLoaderReadsIsTakenOldestFirstWithinTheCaps() throws IOException {
        Fixture f = new Fixture(tmp);
        String[] names = {"a.jsonl", "b.ndjson", "c.json", "d.jsonl.gz", "e.jsonl.zst", "f.zip", "g.tar", "h.tar.gz", "i.tgz"};
        for (int i = 0; i < names.length; i++) {
            Path p = f.drop(names[i], "0123456789", "");
            Files.setLastModifiedTime(p, FileTime.from(Instant.parse("2026-11-03T10:00:00Z").plusSeconds(names.length - i)));
        }
        Inbox.Found all = look(f);
        assertThat(all.ready()).extracting(Inbox.Ready::name).containsExactly("i.tgz", "h.tar.gz", "g.tar", "f.zip", "e.jsonl.zst", "d.jsonl.gz", "c.json",
                "b.ndjson", "a.jsonl");
        Inbox.Found capped = new Inbox(f.inbox()).look(f.clock.instant(), Duration.ofSeconds(30), 3, Long.MAX_VALUE);
        assertThat(capped.ready()).hasSize(3);
        assertThat(capped.waiting()).filteredOn(w -> w.why().startsWith("the next cycle")).hasSize(6);
        Inbox.Found bytes = new Inbox(f.inbox()).look(f.clock.instant(), Duration.ofSeconds(30), 500, 25);
        assertThat(bytes.ready()).extracting(Inbox.Ready::name).containsExactly("i.tgz", "h.tar.gz");
        Inbox.Found one = new Inbox(f.inbox()).look(f.clock.instant(), Duration.ofSeconds(30), 500, 1);
        assertThat(one.ready()).as("the first file is always taken, whatever its size").hasSize(1);
    }
}
