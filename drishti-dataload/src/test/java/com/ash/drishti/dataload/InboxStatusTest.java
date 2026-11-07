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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.FileDates;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Admin screen's board (DATA_ARCHITECTURE.md section 9.10, mock-up A) as {@link DataInbox#status()} gives it: every waiting file's state and
 * rule, the run loading table by table, the last cycles in words, failures with reasons and reports, today's duplicates, the rules and the
 * settings. Every figure the screen shows is here (the browser computes nothing).
 */
class InboxStatusTest {

    @TempDir Path tmp;

    private static DataInbox inbox(Fixture f, LoadRunner runner) {
        return new DataInbox(f.settings, "node-a", f.clock, Fixture.ZONE, f.packs(), runner, f.signal(), null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object o) {
        return (List<Map<String, Object>>) o;
    }

    private static Map<String, Object> byFile(List<Map<String, Object>> rows, String file) {
        return rows.stream().filter(r -> file.equals(r.get("file"))).findFirst().orElseThrow();
    }

    @Test
    void eachWaitingFileHasItsStateItsSizeInWordsAndTheRuleThatWillRouteIt() throws IOException {
        Fixture f = new Fixture(tmp);
        f.drop("trades-a.jsonl", Fixture.rows("2026-11-02", "1"), "");
        f.drop("positions-2026-11-03.jsonl", Fixture.rows("2026-11-03", "2"), null);
        f.drop("named-x.2026-11-03.jsonl", Fixture.rows("2026-11-03", "3"), "");
        Path fresh = f.drop("trades-fresh.jsonl", Fixture.rows("2026-11-03", "4"), "");
        Files.setLastModifiedTime(fresh, FileTime.from(f.clock.instant().minusSeconds(5)));
        f.drop("mystery.jsonl", Fixture.rows("2026-11-03", "5"), "");
        f.drop("notes.txt", "x", "");
        f.drop("trades-b.2026-11-02.jsonl", Fixture.rows("2026-11-02", "6"), "");
        f.drop("trades-c.2026-11-01.zip", "x", "");
        f.drop("trades-d.2026-11-02.jsonl", Fixture.rows("2026-11-02", "7"), "business-date: 2026-11-01\n");

        Map<String, Object> st = inbox(f, f.fakeLoader()).status();
        Map<String, Object> board = map(st.get("board"));
        List<Map<String, Object>> waiting = list(board.get("waiting"));
        assertThat(board.get("waitingCount")).isEqualTo(waiting.size()).isEqualTo(9);
        // the business date each file will load under, and where it comes from (DATA_ARCHITECTURE.md section 9.5.1)
        assertThat(map(byFile(waiting, "trades-b.2026-11-02.jsonl").get("rule"))).containsEntry("date", "2026-11-02 from the name")
                .containsEntry("nameDate", "2026-11-02");
        assertThat((String) map(byFile(waiting, "trades-b.2026-11-02.jsonl").get("rule")).get("text")).endsWith("· 2026-11-02 from the name");
        assertThat(map(byFile(waiting, "trades-c.2026-11-01.zip").get("rule"))).containsEntry("date", "2026-11-01 from the name");
        assertThat(map(byFile(waiting, "trades-d.2026-11-02.jsonl").get("rule"))).containsEntry("date", "2026-11-01 from the .ready file");
        assertThat(map(byFile(waiting, "trades-a.jsonl").get("rule"))).containsEntry("date", FileDates.businessToday() + " (today: no date in the name)");

        Map<String, Object> a = byFile(waiting, "trades-a.jsonl");
        assertThat(a).containsEntry("code", "ready").containsEntry("label", "ready");
        assertThat((String) a.get("detail")).startsWith("stable 1 h").contains("marker empty");
        assertThat((String) a.get("sizeText")).endsWith(" B");
        assertThat(map(a.get("rule"))).containsEntry("n", 1).containsEntry("pack", "demo-pack").containsEntry("kind", "trade");
        assertThat((String) map(a.get("rule")).get("text")).startsWith("rule 1 → demo-pack / trade");

        Map<String, Object> named = byFile(waiting, "named-x.2026-11-03.jsonl");
        assertThat((String) map(named.get("rule")).get("date")).isEqualTo("2026-11-03 from the name");

        assertThat(byFile(waiting, "positions-2026-11-03.jsonl")).containsEntry("code", "no-marker").containsEntry("label", "no marker");
        assertThat((String) byFile(waiting, "positions-2026-11-03.jsonl").get("next")).contains("positions-2026-11-03.jsonl.ready or ready.all");
        assertThat((String) map(byFile(waiting, "positions-2026-11-03.jsonl").get("rule")).get("text"))
                .startsWith("it would go to failed/: positions-2026-11-03: no pack, kind or table of that name");

        Map<String, Object> notStable = byFile(waiting, "trades-fresh.jsonl");
        assertThat(notStable).containsEntry("code", "not-stable").containsEntry("label", "not yet stable");
        assertThat((String) notStable.get("detail")).startsWith("changed 5 s ago").contains("marker present");
        assertThat((String) notStable.get("next")).contains("30 s (stable-seconds)");

        assertThat(map(byFile(waiting, "mystery.jsonl").get("rule"))).containsKey("error");
        assertThat(byFile(waiting, "notes.txt")).containsEntry("code", "refused");
        assertThat((String) byFile(waiting, "notes.txt").get("detail")).contains("does not read notes.txt");
    }

    @Test
    void readyAllMakesEveryFileReadyAndSaysSo() throws IOException {
        Fixture f = new Fixture(tmp);
        f.drop("trades-a.jsonl", Fixture.rows("2026-11-02", "1"), null);
        Files.writeString(f.inbox().resolve(Inbox.READY_ALL), "");
        f.age(f.inbox().resolve(Inbox.READY_ALL));
        Map<String, Object> board = map(inbox(f, f.fakeLoader()).status().get("board"));
        assertThat(map(board.get("readyAll"))).containsEntry("sinceText", "since 14:00");
        assertThat(byFile(list(board.get("waiting")), "trades-a.jsonl")).containsEntry("code", "ready-all").containsEntry("label", "ready via ready.all");
    }

    @Test
    void whileARunLoadsTheBoardShowsItsFilesAndTablesFromThePlanAndTheLastCyclesSayLoading() throws IOException {
        Fixture f = new Fixture(tmp);
        f.drop("trades-a.jsonl", Fixture.rows("2026-11-02", "1", "2"), "");
        f.drop("trades-b.jsonl", Fixture.rows("2026-11-03", "3"), "");
        AtomicReference<DataInbox> ref = new AtomicReference<>();
        AtomicReference<Map<String, Object>> during = new AtomicReference<>();
        LoadRunner fake = f.fakeLoader();
        LoadRunner watching = (config, extra, log) -> {
            during.set(ref.get().status());
            return fake.run(config, extra, log);
        };
        DataInbox inbox = inbox(f, watching);
        ref.set(inbox);
        Cycle.Report r = inbox.cycle("by a.sinha");
        assertThat(r.state()).isEqualTo("done");

        Map<String, Object> running = map(during.get().get("running"));
        assertThat(running).containsEntry("trigger", "by a.sinha").containsEntry("fileCount", 2).containsEntry("tableCount", 1)
                .containsEntry("tableText", "table 1 of 1").containsEntry("heap", "256m");
        assertThat((String) running.get("run")).endsWith("-node-a");
        assertThat((String) running.get("folder")).startsWith("inprocess/").endsWith("-node-a/");
        assertThat((String) running.get("summaryText")).contains("2 files").contains("load-timeout 5m");
        Map<String, Object> table = list(running.get("tables")).get(0);
        assertThat(table).containsEntry("state", "loading").containsEntry("pack", "demo-pack").containsEntry("kind", "trade");
        assertThat((String) table.get("label")).isEqualTo("loading 1 of 1 · demo-pack / trade");
        assertThat(list(table.get("files"))).extracting(m -> m.get("file")).containsExactly("trades-a.jsonl", "trades-b.jsonl");
        assertThat(list(during.get().get("lastCycles")).get(0)).containsEntry("outcome", "loading");
        assertThat((String) during.get().get("lockText")).startsWith("held by node-a (this node), run ");

        Map<String, Object> after = inbox.status();
        assertThat(after.get("running")).isNull();
        assertThat(after.get("lockText")).isEqualTo("free");
        Map<String, Object> last = list(after.get("lastCycles")).get(0);
        assertThat(last).containsEntry("outcome", "loaded").containsEntry("label", "loaded").containsEntry("time", "15:00");
        assertThat((String) last.get("text")).isEqualTo("2 files · demo-pack/trade · 2026-11-02, 2026-11-03 · 3 rows · 0 rejects · 0 s");
        assertThat((String) last.get("versionsText")).isEqualTo("demo-pack/trade v10, v11");
        assertThat(f.tree("outbox")).anyMatch(p -> p.endsWith("/loads/load-plan.json"));
        JsonNode plan = Fixture.JSON.readTree(Files.readString(f.root.resolve(f.tree("outbox").stream()
                .filter(p -> p.endsWith("/loads/load-plan.json")).findFirst().orElseThrow().replace("RUN", r.run()))));
        assertThat(plan.path("tables").get(0).path("state").asText()).isEqualTo("committed");
        assertThat(plan.path("tables").get(0).path("versions").path("2026-11-03").asLong()).isEqualTo(11L);
    }

    @Test
    void aFailedFileHasItsReasonItsReportAndRetryAndTheReportIsServedButNothingElse() throws IOException {
        Fixture f = new Fixture(tmp);
        f.drop("mystery.jsonl", Fixture.rows("2026-11-02", "1"), "pack: demo-pack\nkind: curve\n");
        DataInbox inbox = inbox(f, f.fakeLoader());
        inbox.cycle("schedule");
        Map<String, Object> board = map(inbox.status().get("board"));
        assertThat(board.get("failedTotal")).isEqualTo(1);
        assertThat(board.get("failedText")).isEqualTo("1, needs a person");
        Map<String, Object> fail = list(board.get("failures")).get(0);
        assertThat((String) fail.get("reason")).contains("has no kind curve");
        assertThat((String) fail.get("report")).startsWith("failed/2026-11-03/").endsWith("/mystery.jsonl.failed.json");
        assertThat(fail).containsEntry("atText", "2026-11-03 15:00");
        assertThat((String) fail.get("retryText")).startsWith("Retry puts the file and its marker back");
        Map<String, Object> last = list(inbox.status().get("lastCycles")).get(0);
        assertThat(last).containsEntry("outcome", "failed-files").containsEntry("label", "1 failed");

        JsonNode rep = (JsonNode) inbox.report((String) fail.get("report"));
        assertThat(rep.path("reason").asText()).contains("has no kind curve");
        assertThatThrownBy(() -> inbox.report("../config/dataload.yaml")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inbox.report(((String) fail.get("report")).replace(".failed.json", ""))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inbox.report("inbox/mystery.jsonl.ready")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inbox.report("failed/2026-11-03/r/none.failed.json")).isInstanceOf(java.nio.file.NoSuchFileException.class);

        assertThat(map(inbox(new Fixture(tmp.resolve("quiet")), f.fakeLoader()).status().get("board")).get("failedText"))
                .isEqualTo("No failed files in the last 30 days (keep-outbox-days).");
    }

    @Test
    void aFileDroppedAgainIsTodaysDuplicateWithTheRunThatLoadedItBefore() throws IOException {
        Fixture f = new Fixture(tmp);
        DataInbox inbox = inbox(f, f.fakeLoader());
        f.drop("trades-a.jsonl", Fixture.rows("2026-11-02", "1"), "");
        Cycle.Report first = inbox.cycle("schedule");
        f.drop("trades-a.jsonl", Fixture.rows("2026-11-02", "1"), "");
        inbox.cycle("schedule");
        Map<String, Object> st = inbox.status();
        Map<String, Object> board = map(st.get("board"));
        assertThat(board.get("duplicatesCount")).isEqualTo(1);
        Map<String, Object> dup = list(board.get("duplicates")).get(0);
        assertThat(dup).containsEntry("file", "trades-a.jsonl").containsEntry("alreadyRun", first.run());
        assertThat((String) dup.get("reason")).startsWith("already loaded in run " + first.run());
        Map<String, Object> last = list(st.get("lastCycles")).get(0);
        assertThat(last).containsEntry("outcome", "duplicate");
        assertThat((String) last.get("text")).startsWith("1 file · already loaded");
        assertThat(list(st.get("lastCycles")).get(0)).containsEntry("todayCount", 2).containsEntry("skippedToday", 0);
    }

    @Test
    void theRulesInEffectAndTheSettingsAreReadOnlyWords() throws IOException {
        Fixture f = new Fixture(tmp);
        Map<String, Object> st = inbox(f, f.fakeLoader()).status();
        Map<String, Object> rules = map(st.get("rules"));
        assertThat(list(rules.get("rules"))).extracting(m -> m.get("match"), m -> m.get("date")).containsExactly(
                org.assertj.core.groups.Tuple.tuple("trades-*", "from the .ready file, else the name, else today"),
                org.assertj.core.groups.Tuple.tuple("named-*", "from the name (required)"));
        assertThat(rules).containsEntry("unmatched", "fail");
        assertThat((String) map(st.get("settings")).get("text")).isEqualTo("interval 10m · stable-seconds 30 · loader-heap 256m · load-timeout 5m · "
                + "reject-max-percent 0 · max-files off · max-gb off · reload-duplicates false · keep-outbox-days 30");
        assertThat(st).containsEntry("nextCycleText", "the inbox is off").containsEntry("lockText", "free").containsEntry("zone", "Z");
        assertThat((String) map(st.get("folders")).get("ledger")).isEqualTo("ledger/ledger-2026-11.jsonl");
    }

    @Test
    void figuresAreWrittenAsAPersonReadsThem() {
        assertThat(InboxStatus.duration(Duration.ofSeconds(4))).isEqualTo("4 s");
        assertThat(InboxStatus.duration(Duration.ofSeconds(72))).isEqualTo("1 min 12 s");
        assertThat(InboxStatus.duration(Duration.ofMinutes(23))).isEqualTo("23 min");
        assertThat(InboxStatus.duration(Duration.ofMinutes(125))).isEqualTo("2 h 5 min");
        assertThat(InboxStatus.duration(Duration.ofSeconds(-3))).isEqualTo("0 s");
        assertThat(InboxStatus.bytes(300)).isEqualTo("300 B");
        assertThat(InboxStatus.bytes(412L * 1024 * 1024)).isEqualTo("412 MB");
        assertThat(InboxStatus.bytes((long) (1.8 * 1024 * 1024 * 1024))).isEqualTo("1.8 GB");
        assertThat(InboxStatus.count(1_284_551)).isEqualTo("1,284,551");
    }
}
