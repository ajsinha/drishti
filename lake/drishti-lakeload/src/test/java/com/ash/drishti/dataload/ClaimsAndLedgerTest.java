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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The in-process folder, the folder lock (two runners, a stale lock, a holder whose process ended) and the ledger (duplicates, a restart). */
class ClaimsAndLedgerTest {

    @TempDir Path tmp;

    @Test
    void oneRunnerHoldsTheLockAndASecondIsRefusedUntilItIsReleased() throws IOException {
        Fixture f = new Fixture(tmp);
        Claims a = new Claims(f.root, f.clock);
        Claims b = new Claims(f.root, f.clock);
        try (Claims.Lock la = a.lock("node-a", Duration.ofMinutes(30), Duration.ofMinutes(1))) {
            assertThat(la).isNotNull();
            assertThat(la.held()).isTrue();
            assertThat(b.lock("node-b", Duration.ofMinutes(30), Duration.ofMinutes(1))).as("the second runner").isNull();
            assertThat(a.holder().path("node").asText()).isEqualTo("node-a");
            assertThat(a.holder().path("pid").asLong()).isEqualTo(ProcessHandle.current().pid());
        }
        assertThat(f.root.resolve(Claims.LOCK)).doesNotExist();
        try (Claims.Lock lb = b.lock("node-b", Duration.ofMinutes(30), Duration.ofMinutes(1))) {
            assertThat(lb).as("free again").isNotNull();
        }
    }

    @Test
    void aStaleLockIsTakenOverAndItsFormerHolderKnowsItLostIt() throws IOException {
        Fixture f = new Fixture(tmp);
        Files.createDirectories(f.root);
        Files.writeString(f.root.resolve(Claims.LOCK), "{\"node\":\"node-old\",\"pid\":1,\"since\":\"2026-11-03T10:00:00Z\",\"heartbeat\":\"2026-11-03T14:00:00Z\"}");
        Claims c = new Claims(f.root, f.clock);
        assertThat(c.lock("node-a", Duration.ofHours(2), Duration.ofMinutes(1))).as("a heartbeat one hour old within two").isNull();
        try (Claims.Lock l = c.lock("node-a", Duration.ofMinutes(30), Duration.ofMinutes(1))) {
            assertThat(l).as("an hour-old heartbeat over 30 minutes is a holder gone").isNotNull();
            assertThat(c.holder().path("node").asText()).isEqualTo("node-a");
            // another runner takes it over in turn: this one sees it is no longer its own
            Files.writeString(f.root.resolve(Claims.LOCK), "{\"node\":\"node-b\",\"pid\":2,\"since\":\"x\",\"heartbeat\":\"2026-11-03T15:00:00Z\"}");
            l.beat();
            assertThat(l.held()).isFalse();
        }
        assertThat(c.holder().path("node").asText()).as("a lost lock is not removed by its former holder").isEqualTo("node-b");
    }

    @Test
    void aLockOfThisNodeWhoseProcessEndedIsTakenOverAtOnce() throws IOException {
        Fixture f = new Fixture(tmp);
        Process gone = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-version")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            gone.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Files.writeString(f.root.resolve(Claims.LOCK), "{\"node\":\"node-a\",\"pid\":" + gone.pid() + ",\"since\":\"x\",\"heartbeat\":\""
                + f.clock.instant() + "\"}");
        Claims c = new Claims(f.root, f.clock);
        try (Claims.Lock l = c.lock("node-a", Duration.ofMinutes(30), Duration.ofMinutes(1))) {
            assertThat(l).as("the same node's process ended: the server restarted").isNotNull();
        }
        Files.writeString(f.root.resolve(Claims.LOCK), "{\"node\":\"node-b\",\"pid\":" + gone.pid() + ",\"since\":\"x\",\"heartbeat\":\""
                + f.clock.instant() + "\"}");
        assertThat(c.lock("node-a", Duration.ofMinutes(30), Duration.ofMinutes(1))).as("another node's process is not ours to judge").isNull();
    }

    @Test
    void aClaimMovesTheFileAndItsMarkerIntoTheRunsWorkFolder() throws IOException {
        Fixture f = new Fixture(tmp);
        Path d = f.drop("trades-a.jsonl", Fixture.rows("2026-11-02", "1"), "pack: demo-pack");
        Inbox.Ready r = new Inbox.Ready(d, f.inbox().resolve("trades-a.jsonl.ready"), Files.size(d), f.clock.instant());
        Path[] claimed = new Claims(f.root, f.clock).claim(r, "20261103T150000Z-node-a");
        assertThat(claimed[0]).isEqualTo(f.root.resolve("inprocess/20261103T150000Z-node-a/trades-a.jsonl")).exists();
        assertThat(claimed[1]).isEqualTo(f.root.resolve("inprocess/20261103T150000Z-node-a/trades-a.jsonl.ready")).exists();
        assertThat(f.inbox().resolve("trades-a.jsonl")).doesNotExist();
    }

    @Test
    void theLedgerFindsALoadedFileBySha256AfterARestartAndCountsLockedCycles() throws IOException {
        Fixture f = new Fixture(tmp);
        Path d = f.drop("trades-a.jsonl", Fixture.rows("2026-11-02", "1"), null);
        String sha = Ledger.sha256(d);
        assertThat(sha).hasSize(64);
        Ledger l = new Ledger(f.root.resolve("ledger"), f.clock);
        l.write("r1", "trades-a.jsonl", Ledger.CLAIMED, null);
        l.write("r1", "trades-a.jsonl", Ledger.LOCKED, Fixture.JSON.createObjectNode().put("sha256", sha));
        l.write("r2", "trades-a.jsonl", Ledger.LOCKED, Fixture.JSON.createObjectNode().put("sha256", sha));
        assertThat(l.lockedTimes(sha)).isEqualTo(2);
        assertThat(l.loaded(sha)).isNull();
        l.write("r3", "trades-a.jsonl", Ledger.LOADED, Fixture.JSON.createObjectNode().put("sha256", sha));
        Files.writeString(f.root.resolve("ledger/ledger-2026-11.jsonl"), "{\"cut sho", java.nio.file.StandardOpenOption.APPEND);
        Ledger again = new Ledger(f.root.resolve("ledger"), f.clock);
        assertThat(again.loaded(sha).path("run").asText()).isEqualTo("r3");
        assertThat(again.lockedTimes(sha)).as("reset by the load").isZero();
        assertThat(again.last("r1", "trades-a.jsonl").path("outcome").asText()).isEqualTo(Ledger.LOCKED);
        assertThat(again.recent(2)).extracting(n -> n.path("run").asText()).containsExactly("r3", "r2");
        again.write("r4", "trades-b.jsonl", Ledger.CLAIMED, null);
        assertThat(new Ledger(f.root.resolve("ledger"), f.clock).last("r4", "trades-b.jsonl")).as("a line after one cut short is its own").isNotNull();
    }
}
