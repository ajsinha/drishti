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
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Crash safety (DATA_ARCHITECTURE.md section 9.7): the server stops after each step of a cycle, then starts again and runs a cycle. Nothing is
 * lost, nothing is loaded twice, every file ends in the outbox with its marker and report, and the server is told of each date once
 * (signals are idempotent per batch; a signal sent again carries the same batch).
 */
class RecoveryTest {

    @TempDir Path tmp;

    /** The "server" stops: the cycle ends here, nothing after it runs. */
    static final class Stop extends RuntimeException {
        Stop(String step) {
            super("stopped after " + step);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"claimed", "loaded", "recorded", "moved"})
    void aServerStoppedAfterEachStepFinishesTheFileOnItsNextCycle(String step) throws IOException {
        Fixture f = new Fixture(tmp);
        f.drop("trades-a.jsonl", Fixture.rows("2026-11-02", "1", "2"), "");
        f.drop("trades-b.jsonl", Fixture.rows("2026-11-02", "3"), "");
        AtomicInteger runs = new AtomicInteger();
        LoadRunner counted = (config, extra, log) -> {
            runs.incrementAndGet();
            return f.fakeLoader().run(config, extra, log);
        };
        Cycle.Report crashed = f.cycle(counted, s -> {
            if (s.equals(step)) {
                throw new Stop(s);
            }
        }).run("test");
        assertThat(crashed.state()).isEqualTo("failed");
        assertThat(crashed.why()).isEqualTo("stopped after " + step);
        assertThat(f.root.resolve(Claims.LOCK)).as("the lock of a stopped cycle in this process is released").doesNotExist();

        Cycle.Report next = f.cycle(counted, null).run("restart");
        assertThat(next.state()).isEqualTo("done");
        assertThat(runs.get()).as("loaded once, whatever the step").isEqualTo(1);
        assertThat(f.tree("inbox")).isEmpty();
        assertThat(f.tree("inprocess")).isEmpty();
        assertThat(f.tree("outbox")).contains("outbox/2026-11-03/RUN/trades-a.jsonl", "outbox/2026-11-03/RUN/trades-a.jsonl.ready",
                "outbox/2026-11-03/RUN/trades-a.jsonl.loaded.json", "outbox/2026-11-03/RUN/trades-b.jsonl", "outbox/2026-11-03/RUN/trades-b.jsonl.ready");
        assertThat(f.signals).as("told of the date").isNotEmpty().allMatch(l -> l.date().equals(LocalDate.parse("2026-11-02")) && l.rows() == 3);
        assertThat(f.signals.stream().map(Signal.Landed::batch).distinct()).as("one batch: the server records it once").hasSize(1);
        assertThat(f.ledgerLines()).filteredOn(n -> n.path("outcome").asText().equals("loaded")).hasSize(2);
        assertThat(f.run().files()).as("a third cycle finds nothing").isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aSignalTheServerCouldNotTakeIsSentAgainAndARefusedOneIsNot(boolean refused) throws IOException {
        Fixture f = new Fixture(tmp);
        f.drop("trades-a.jsonl", Fixture.rows("2026-11-02", "1"), "");
        AtomicInteger tries = new AtomicInteger();
        Signal flaky = l -> {
            if (tries.incrementAndGet() == 1) {
                throw refused ? new IllegalArgumentException("business date in the future") : new IllegalStateException("server starting");
            }
            f.signals.add(l);
        };
        new Cycle(f.settings, "node-a", f.clock, Fixture.ZONE, f.packs(), f.fakeLoader(), flaky, null, null).run("t");
        assertThat(f.signals).isEmpty();
        assertThat(f.tree("inprocess")).as(refused ? "nothing pending" : "the signal pending").hasSize(refused ? 0 : 1);
        new Cycle(f.settings, "node-a", f.clock, Fixture.ZONE, f.packs(), f.fakeLoader(), flaky, null, null).run("t");
        assertThat(f.signals).hasSize(refused ? 0 : 1);
        assertThat(f.tree("inprocess")).isEmpty();
    }
}
