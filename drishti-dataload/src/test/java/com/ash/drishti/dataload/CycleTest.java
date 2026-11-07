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

import com.ash.drishti.lakeload.LakeLoad;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** One cycle with a stand-in loader: what is loaded, moved, told, and every failure of DATA_LAKE_GUIDE.md section 8. */
class CycleTest {

    @TempDir Path tmp;

    @Test
    void readyFilesOfOneTableLoadInOneRunAndMoveWithTheirMarkersAndReports() throws IOException {
        Fixture f = new Fixture(tmp);
        f.drop("trades-a.jsonl", Fixture.rows("2026-11-02", "1", "2"), "");
        f.drop("trades-b.jsonl", Fixture.rows("2026-11-03", "3"), "");
        f.drop("trades-c.jsonl", Fixture.rows("2026-11-03", "4"), null);
        Cycle.Report r = f.run();
        assertThat(r.state()).isEqualTo("done");
        assertThat(f.loads).as("one table, one loader run").hasSize(1);
        assertThat(r.files()).extracting(Cycle.FileOutcome::file, Cycle.FileOutcome::outcome).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("trades-a.jsonl", "loaded"), org.assertj.core.groups.Tuple.tuple("trades-b.jsonl", "loaded"));
        assertThat(r.files().get(0).versions()).containsEntry("2026-11-02", 10L).containsEntry("2026-11-03", 11L);
        assertThat(f.tree("outbox")).containsExactly("outbox/2026-11-03/RUN/loads/load-1.log", "outbox/2026-11-03/RUN/loads/load-1.route.json",
                "outbox/2026-11-03/RUN/loads/load-1.summary.json", "outbox/2026-11-03/RUN/loads/load-1.yaml", "outbox/2026-11-03/RUN/loads/load-plan.json",
                "outbox/2026-11-03/RUN/trades-a.jsonl", "outbox/2026-11-03/RUN/trades-a.jsonl.loaded.json", "outbox/2026-11-03/RUN/trades-a.jsonl.ready",
                "outbox/2026-11-03/RUN/trades-b.jsonl", "outbox/2026-11-03/RUN/trades-b.jsonl.loaded.json", "outbox/2026-11-03/RUN/trades-b.jsonl.ready");
        assertThat(f.tree("inbox")).as("a file without its marker is never touched").containsExactly("inbox/trades-c.jsonl");
        assertThat(f.tree("inprocess")).isEmpty();
        assertThat(f.signals).extracting(Signal.Landed::pack, Signal.Landed::kind, Signal.Landed::date, Signal.Landed::rows).containsExactly(
                org.assertj.core.groups.Tuple.tuple("demo-pack", "trade", LocalDate.parse("2026-11-02"), 2L),
                org.assertj.core.groups.Tuple.tuple("demo-pack", "trade", LocalDate.parse("2026-11-03"), 1L));
        assertThat(f.signals.get(0).batch()).startsWith("inbox:2026").endsWith("#1");
        assertThat(f.signals.get(0).rejected()).as("several dates: rejects not known per date").isNull();
        JsonNode report = Fixture.JSON.readTree(Files.readString(Files.list(f.root.resolve("outbox/2026-11-03")).findFirst().orElseThrow()
                .resolve("trades-a.jsonl.loaded.json")));
        assertThat(report.path("outcome").asText()).isEqualTo("loaded");
        assertThat(report.path("route").path("pack").asText()).isEqualTo("demo-pack");
        assertThat(report.path("load").path("documents").asLong()).isEqualTo(3);
        assertThat(report.path("filesInLoad")).hasSize(2);
        String cfg = Files.readString(f.root.resolve("outbox/2026-11-03").resolve(f.loads.get(0).getParent().getFileName()).resolve("loads/load-1.yaml"));
        assertThat(cfg).contains("pack: \"" + f.pack.toAbsolutePath()).contains("kind: \"trade\"").contains("lake: \"" + f.lake).contains("max-pct: 0.0");
        assertThat(f.ledgerLines()).extracting(n -> n.path("outcome").asText()).containsExactly("claimed", "claimed", "loaded", "loaded");
    }

    @Test
    void theSameFileDroppedAgainIsSkippedButAChangedFileOfTheSameNameLoads() throws IOException {
        Fixture f = new Fixture(tmp);
        f.drop("trades-a.jsonl", Fixture.rows("2026-11-02", "1"), "");
        f.run();
        f.drop("trades-a.jsonl", Fixture.rows("2026-11-02", "1"), "");
        Cycle.Report again = f.run();
        assertThat(again.files()).singleElement().satisfies(o -> {
            assertThat(o.outcome()).isEqualTo("duplicate");
            assertThat(o.reason()).startsWith("already loaded in run ").contains("not loaded again");
        });
        assertThat(f.loads).hasSize(1);
        f.drop("trades-a.jsonl", Fixture.rows("2026-11-02", "1", "2"), "");
        assertThat(f.run().files()).singleElement().extracting(Cycle.FileOutcome::outcome).isEqualTo("loaded");
        assertThat(f.loads).hasSize(2);
        f.settings = new DataLoadSettings(true, f.root.toString(), null, null, null, null, "256m", null, null, true, null, f.lake.toString(),
                f.rules.toString(), false, null, null);
        f.drop("trades-a.jsonl", Fixture.rows("2026-11-02", "1", "2"), "");
        assertThat(f.run().files()).as("reload-duplicates: true").singleElement().extracting(Cycle.FileOutcome::outcome).isEqualTo("loaded");
    }

    @Test
    void readyAllLoadsEveryFileOnceAndGoesToTheOutbox() throws IOException {
        Fixture f = new Fixture(tmp);
        f.drop("trades-a.jsonl", Fixture.rows("2026-11-02", "1"), null);
        f.drop("trades-b.jsonl", Fixture.rows("2026-11-02", "2"), null);
        Files.writeString(f.inbox().resolve(Inbox.READY_ALL), "");
        Cycle.Report r = f.run();
        assertThat(r.files()).hasSize(2).allMatch(o -> o.outcome().equals("loaded"));
        assertThat(f.tree("inbox")).isEmpty();
        assertThat(f.tree("outbox")).contains("outbox/2026-11-03/RUN/ready.all", "outbox/2026-11-03/RUN/trades-a.jsonl");
        assertThat(f.run().files()).as("ready.all applied once").isEmpty();
    }

    @Test
    void rejectsWithinTheLimitLoadAndAFailedLoadGoesToFailedWithTheLoadersReason() throws IOException {
        Fixture f = new Fixture(tmp);
        f.exit = names -> names.contains("trades-bad.jsonl") ? LakeLoad.FAILED : names.contains("trades-some.jsonl") ? LakeLoad.PARTIAL : LakeLoad.OK;
        f.drop("trades-some.jsonl", Fixture.rows("2026-11-02", "1"), "pack: demo-pack\nkind: trade\nconnector: other");
        f.drop("trades-bad.jsonl", Fixture.rows("2026-11-02", "2"), "");
        f.drop("trades-good.jsonl", Fixture.rows("2026-11-02", "3"), "");
        Cycle.Report r = f.run();
        assertThat(r.files()).extracting(Cycle.FileOutcome::file, Cycle.FileOutcome::outcome).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("trades-some.jsonl", "loaded"), org.assertj.core.groups.Tuple.tuple("trades-bad.jsonl", "failed"),
                org.assertj.core.groups.Tuple.tuple("trades-good.jsonl", "loaded"));
        assertThat(r.files()).filteredOn(o -> o.file().equals("trades-some.jsonl")).singleElement().satisfies(o -> {
            assertThat(o.reason()).isEqualTo("loaded with rejects");
            assertThat(o.rejected()).isEqualTo(1);
        });
        assertThat(r.files()).filteredOn(o -> o.file().equals("trades-bad.jsonl")).singleElement().extracting(Cycle.FileOutcome::reason).asString()
                .startsWith("line 3: not JSON");
        // the bad file and the good one were one load; it failed, so each was loaded alone: only the bad one failed
        assertThat(f.loads).hasSize(4);
        assertThat(f.tree("failed")).containsExactly("failed/2026-11-03/RUN/trades-bad.jsonl", "failed/2026-11-03/RUN/trades-bad.jsonl.failed.json",
                "failed/2026-11-03/RUN/trades-bad.jsonl.ready");
        assertThat(f.signals).hasSize(2);
    }

    @Test
    void aLockedTableSendsItsFilesBackAndAfterThreeCyclesANotice() throws IOException {
        Fixture f = new Fixture(tmp);
        f.exit = names -> LakeLoad.LOCKED;
        f.drop("trades-a.jsonl", Fixture.rows("2026-11-02", "1"), "");
        for (int i = 1; i <= 3; i++) {
            Cycle.Report r = f.run();
            assertThat(r.files()).singleElement().extracting(Cycle.FileOutcome::outcome).isEqualTo("locked");
            assertThat(f.tree("inbox")).containsExactly("inbox/trades-a.jsonl", "inbox/trades-a.jsonl.ready");
            assertThat(r.notices()).hasSize(i == 3 ? 1 : 0);
        }
        f.exit = names -> LakeLoad.OK;
        assertThat(f.run().files()).singleElement().extracting(Cycle.FileOutcome::outcome).isEqualTo("loaded");
    }

    @Test
    void aChecksumOrRowCountInTheMarkerThatDoesNotMatchFailsBeforeLoading() throws IOException {
        Fixture f = new Fixture(tmp);
        Path a = f.drop("trades-a.jsonl", Fixture.rows("2026-11-02", "1", "2"), null);
        String sha = Ledger.sha256(a);
        Files.writeString(f.inbox().resolve("trades-a.jsonl.ready"), "sha256: " + "0".repeat(63) + "1\n");
        f.drop("trades-b.jsonl", Fixture.rows("2026-11-02", "3", "4"), "rows: 3\n");
        f.drop("trades-c.jsonl", Fixture.rows("2026-11-02", "5", "6"), "rows: 2\n");
        Path d = f.drop("trades-d.jsonl", Fixture.rows("2026-11-02", "7"), null);
        Files.writeString(f.inbox().resolve("trades-d.jsonl.ready"), "sha256: " + Ledger.sha256(d) + "\n");
        f.age(f.inbox().resolve("trades-a.jsonl.ready"), f.inbox().resolve("trades-d.jsonl.ready"));
        Cycle.Report r = f.run();
        assertThat(r.files()).extracting(Cycle.FileOutcome::file, Cycle.FileOutcome::outcome).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("trades-a.jsonl", "failed"), org.assertj.core.groups.Tuple.tuple("trades-b.jsonl", "failed"),
                org.assertj.core.groups.Tuple.tuple("trades-c.jsonl", "loaded"), org.assertj.core.groups.Tuple.tuple("trades-d.jsonl", "loaded"));
        assertThat(r.files()).filteredOn(o -> o.file().equals("trades-a.jsonl")).singleElement().extracting(Cycle.FileOutcome::reason).asString()
                .contains("SHA-256 in its .ready file is not the file's (" + sha + ")");
        assertThat(r.files()).filteredOn(o -> o.file().equals("trades-b.jsonl")).singleElement().extracting(Cycle.FileOutcome::reason).asString()
                .isEqualTo("its .ready file says 3 rows, the file holds 2 documents: not loaded");
    }

    @Test
    void unknownNamesLinksNoRuleAndABadMarkerGoToFailedWithTheReason() throws IOException {
        Fixture f = new Fixture(tmp);
        f.drop("mystery.jsonl", Fixture.rows("2026-11-02", "1"), "");
        f.drop("trades.csv", "a,b\n", "");
        f.drop("trades-x.jsonl", Fixture.rows("2026-11-02", "1"), "date-field: COB\n");
        Cycle.Report r = f.run();
        assertThat(r.files()).extracting(Cycle.FileOutcome::outcome).containsOnly("failed");
        assertThat(r.files()).extracting(Cycle.FileOutcome::reason)
                .anyMatch(s -> s.contains("mystery: no pack, kind or table of that name (known: demo-pack/position, demo-pack/trade)"))
                .anyMatch(s -> s.contains("the loader does not read trades.csv")).anyMatch(s -> s.contains("key date-field"));
        assertThat(f.loads).isEmpty();
        assertThat(f.tree("failed")).contains("failed/2026-11-03/RUN/mystery.jsonl.failed.json", "failed/2026-11-03/RUN/trades.csv.ready");
    }

    @Test
    void aBusyServerANodeThatIsNotTheOwnerAndAHeldLockSkipTheCycle() throws IOException {
        Fixture f = new Fixture(tmp);
        f.drop("trades-a.jsonl", Fixture.rows("2026-11-02", "1"), "");
        Cycle busy = new Cycle(f.settings, "node-a", f.clock, Fixture.ZONE, f.packs(), f.fakeLoader(), f.signal(), () -> "memory is short", null);
        assertThat(busy.run("t").why()).isEqualTo("busy protection: memory is short; the next cycle tries again");
        DataLoadSettings notOwner = new DataLoadSettings(false, f.root.toString(), null, null, null, null, null, null, null, null, null, null, null, false, null, null);
        assertThat(new Cycle(notOwner, "node-b", f.clock, Fixture.ZONE, f.packs(), f.fakeLoader(), f.signal(), null, null).run("t").why())
                .contains("not the inbox's owner");
        try (Claims.Lock held = new Claims(f.root, f.clock).lock("node-b", Duration.ofHours(1), Duration.ofMinutes(1))) {
            Cycle.Report r = f.run();
            assertThat(r.state()).isEqualTo("skipped");
            assertThat(r.why()).isEqualTo("run of node-b still loading (0 min)");
        }
        assertThat(f.loads).isEmpty();
        assertThat(f.tree("inbox")).hasSize(2);
    }

    @Test
    void aLoadStoppedAtItsTimeLimitSendsItsFilesBack() throws IOException {
        Fixture f = new Fixture(tmp);
        f.drop("trades-a.jsonl", Fixture.rows("2026-11-02", "1"), "");
        LoadRunner hung = (config, extra, log) -> new LoadRunner.Result(LakeLoad.FAILED, true, "the load took longer than load-timeout (5 min) and was stopped");
        Cycle.Report r = f.cycle(hung, null).run("t");
        assertThat(r.files()).singleElement().satisfies(o -> {
            assertThat(o.outcome()).isEqualTo("returned");
            assertThat(o.reason()).contains("load-timeout").endsWith("back in the inbox");
        });
        assertThat(f.tree("inbox")).containsExactly("inbox/trades-a.jsonl", "inbox/trades-a.jsonl.ready");
    }

    @Test
    void retryPutsAFailedFileAndItsMarkerBackAndTheRetentionCleansOldDays() throws IOException {
        Fixture f = new Fixture(tmp);
        f.drop("mystery.jsonl", Fixture.rows("2026-11-02", "1"), "pack: demo-pack\nkind: curve\n");
        f.run();
        DataInbox inbox = new DataInbox(f.settings, "node-a", f.clock, Fixture.ZONE, f.packs(), f.fakeLoader(), f.signal(), null);
        var st = inbox.status();
        assertThat((List<?>) st.get("failed")).singleElement().asString().contains("has no kind curve");
        var out = inbox.retry("mystery.jsonl", "a.sinha");
        assertThat(out.get("to")).isEqualTo("inbox/mystery.jsonl");
        assertThat(f.tree("inbox")).containsExactly("inbox/mystery.jsonl", "inbox/mystery.jsonl.ready");
        assertThat(f.ledgerLines()).extracting(n -> n.path("outcome").asText()).endsWith("retried");
        Outbox ob = new Outbox(f.root, f.clock, Fixture.ZONE);
        Files.createDirectories(f.root.resolve("outbox/2026-09-01/r1"));
        Files.writeString(f.root.resolve("outbox/2026-09-01/r1/x.jsonl"), "{}");
        Files.createDirectories(f.root.resolve("outbox/2026-11-01/r2"));
        Files.createDirectories(f.root.resolve("outbox/keep-me"));
        assertThat(ob.purge(30)).isEqualTo(1);
        assertThat(f.root.resolve("outbox/2026-09-01")).doesNotExist();
        assertThat(f.root.resolve("outbox/2026-11-01")).exists();
        assertThat(f.root.resolve("outbox/keep-me")).exists();
        assertThat(f.root.resolve("ledger")).isNotEmptyDirectory();
    }
}
