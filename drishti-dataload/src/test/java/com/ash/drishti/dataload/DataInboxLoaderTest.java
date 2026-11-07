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

import com.ash.drishti.common.FileDates;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The inbox with the real loader (DATA_LAKE_GUIDE.md, integration): files of each format dropped in a temporary data folder,
 * cycles run, the loader started as a child Java process (four runs in all, each with a 256 MB heap), the Delta versions, the outbox and
 * the signals checked. The archives are the loaders' shared fixtures (tools/lake/testdata/archives).
 */
class DataInboxLoaderTest {

    static final Path REPO = repo();
    static final Path ARCHIVES = REPO.resolve("tools/lake/testdata/archives");

    @TempDir Path tmp;

    static Path repo() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("tools/lake/testdata/archives"))) {
            p = p.getParent();
        }
        return p;
    }

    private static List<String> log(Path table) throws IOException {
        Path log = table.resolve("_delta_log");
        try (var s = Files.list(log)) {
            return s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json")).sorted().toList();
        }
    }

    @Test
    void filesOfEveryFormatLoadIntoDeltaAndTheServerIsToldOfEachDate() throws IOException {
        Fixture f = new Fixture(tmp);
        Files.writeString(f.rules, """
                dataload:
                  rules:
                    - match: "trades-*"
                      pack: demo-pack
                      kind: trade
                    - match: "trade.*"
                      pack: demo-pack
                      kind: trade
                      table: trade_archive
                      key: id
                      date-field: date
                """);
        f.drop("trades-a.2026-11-02.jsonl", Fixture.rows("2026-11-02", "A1", "A2"), "");
        Path gz = f.inbox().resolve("trades-b.2026-11-02.jsonl.gz");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(gz))) {
            out.write(Fixture.rows("2026-11-02", "B1").getBytes(StandardCharsets.UTF_8));
        }
        Path zst = f.inbox().resolve("trades-c.2026-11-03.jsonl.zst");
        try (OutputStream out = new com.github.luben.zstd.ZstdOutputStream(Files.newOutputStream(zst))) {
            out.write(Fixture.rows("2026-11-03", "C1", "C2", "C3").getBytes(StandardCharsets.UTF_8));
        }
        Files.writeString(f.inbox().resolve("trades-b.2026-11-02.jsonl.gz.ready"), "");
        Files.writeString(f.inbox().resolve("trades-c.2026-11-03.ready"), "");
        f.age(gz, zst, f.inbox().resolve("trades-b.2026-11-02.jsonl.gz.ready"), f.inbox().resolve("trades-c.2026-11-03.ready"));
        DataInbox inbox = new DataInbox(f.settings, "node-a", f.clock, Fixture.ZONE, f.packs(), new LoaderProcess(f.settings), f.signal(), null);

        Cycle.Report first = inbox.cycle("test");
        assertThat(first.files()).as(first.toString()).hasSize(3).allMatch(o -> o.outcome().equals("loaded"));
        Path table = f.lake.resolve("demo/trade");
        assertThat(log(table)).containsExactly("00000000000000000000.json", "00000000000000000001.json");
        assertThat(first.files().get(0).versions()).containsEntry("2026-11-02", 0L).containsEntry("2026-11-03", 1L);
        assertThat(first.files().get(0).rows()).isEqualTo(6);
        assertThat(f.signals).extracting(Signal.Landed::date, Signal.Landed::rows).containsExactly(
                org.assertj.core.groups.Tuple.tuple(LocalDate.parse("2026-11-02"), 3L), org.assertj.core.groups.Tuple.tuple(LocalDate.parse("2026-11-03"), 3L));
        assertThat(f.tree("outbox")).contains("outbox/2026-11-03/RUN/trades-c.2026-11-03.jsonl.zst", "outbox/2026-11-03/RUN/trades-c.2026-11-03.jsonl.zst.ready",   // a short marker takes its file's full name
                "outbox/2026-11-03/RUN/trades-c.2026-11-03.jsonl.zst.loaded.json", "outbox/2026-11-03/RUN/loads/load-1.log",
                "outbox/2026-11-03/RUN/loads/load-1.summary.json");

        String archive = "trade.2026-10-01.zip";                        // a zip: one table and one date, from its name
        Files.copy(ARCHIVES.resolve("trade.zip"), f.inbox().resolve(archive), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(f.inbox().resolve(archive + Inbox.READY), "");
        f.age(f.inbox().resolve(archive), f.inbox().resolve(archive + Inbox.READY));
        Cycle.Report z = inbox.cycle("test");
        assertThat(z.files()).as(z.toString()).singleElement().satisfies(o -> {
            assertThat(o.outcome()).isEqualTo("loaded");
            assertThat(o.rows()).isEqualTo(6);
            assertThat(o.versions()).containsOnlyKeys("2026-10-01");
        });
        assertThat(log(f.lake.resolve("demo/trade_archive"))).as("one commit for its one date").hasSize(1);

        f.drop("trades-a.2026-11-02.jsonl", Fixture.rows("2026-11-02", "A1", "A2"), "");
        f.drop("trades-bad.2026-11-02.jsonl", "this is not JSON\n" + Fixture.rows("2026-11-02", "X1"), "");
        Cycle.Report last = inbox.cycle("test");
        assertThat(last.files()).extracting(Cycle.FileOutcome::file, Cycle.FileOutcome::outcome).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("trades-a.2026-11-02.jsonl", "duplicate"),
                org.assertj.core.groups.Tuple.tuple("trades-bad.2026-11-02.jsonl", "failed"));
        assertThat(log(table)).as("nothing committed by the failed load").hasSize(2);
        JsonNode failed = Fixture.JSON.readTree(f.root.resolve("failed/2026-11-03").toFile().listFiles()[0].toPath()
                .resolve("trades-bad.2026-11-02.jsonl.failed.json").toFile());
        assertThat(failed.path("reason").asText()).as(failed.toPrettyString()).isNotBlank();
        assertThat(failed.path("load").path("rejected").asLong()).isEqualTo(1);
        assertThat(f.tree("inbox")).isEmpty();
        assertThat(f.tree("inprocess")).isEmpty();
        assertThat(inbox.status()).containsEntry("on", false).containsKey("cycles");
    }

    /**
     * The business date from outside (docs/guides/DATA_LAKE_GUIDE.md): the name's date for every row of the file, the rows whose own
     * date differs only counted (the outcome, the report, the board); a .ready business-date over the name; a name without a date under the
     * business today; the table from the name when no rule matches. Two loader runs (one per route).
     */
    @Test
    void theNamesDateTheReadyFilesDateOrTodayDatesEveryRowOfAFile() throws IOException {
        Fixture f = new Fixture(tmp);
        f.drop("trades-d.2026-11-02.jsonl", Fixture.rows("2026-11-01", "D1", "D2") + Fixture.rows("2026-11-02", "D3"), "");
        f.drop("trades-e.2026-11-02.jsonl", Fixture.rows("2026-11-02", "E1"), "business-date: 2026-10-30\n");
        f.drop("trade.jsonl", Fixture.rows("2026-11-01", "F1"), "");
        DataInbox inbox = new DataInbox(f.settings, "node-a", f.clock, Fixture.ZONE, f.packs(), new LoaderProcess(f.settings), f.signal(), null);

        Cycle.Report r = inbox.cycle("test");
        assertThat(r.files()).as(r.toString()).hasSize(3).allMatch(o -> o.outcome().equals("loaded"));
        Cycle.FileOutcome d = r.files().stream().filter(o -> o.file().startsWith("trades-d")).findFirst().orElseThrow();
        assertThat(d.versions()).containsKey("2026-11-02");
        assertThat(d.dateNote()).isEqualTo("2 rows of trades-d.2026-11-02.jsonl carried another date in BUSINESS_DATE; loaded under 2026-11-02");
        Cycle.FileOutcome e = r.files().stream().filter(o -> o.file().startsWith("trades-e")).findFirst().orElseThrow();
        assertThat(e.versions()).containsOnlyKeys("2026-10-30");
        assertThat(e.dateNote()).isNotNull();
        String today = FileDates.businessToday().toString();
        assertThat(f.signals).extracting(Signal.Landed::date).contains(LocalDate.parse("2026-11-02"), LocalDate.parse("2026-10-30"), LocalDate.parse(today));

        JsonNode report = Fixture.JSON.readTree(f.root.resolve("outbox/2026-11-03").toFile().listFiles()[0].toPath()
                .resolve("trades-d.2026-11-02.jsonl.loaded.json").toFile());
        assertThat(report.path("load").path("dateNotes").toString()).contains("2 rows of trades-d.2026-11-02.jsonl");
        assertThat(report.path("dateBy").asText()).isEqualTo("2026-11-02 from the name");
        assertThat(inbox.status().toString()).contains("2 rows of trades-d.2026-11-02.jsonl carried another date in BUSINESS_DATE");
    }

    /** A ustar archive of {@code members} (name → text). */
    static byte[] tar(Map<String, String> members) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (Map.Entry<String, String> m : members.entrySet()) {
            byte[] data = m.getValue().getBytes(StandardCharsets.UTF_8);
            byte[] h = new byte[512];
            put(h, 0, m.getKey());
            put(h, 100, "0000644\0");
            put(h, 108, "0000000\0");
            put(h, 116, "0000000\0");
            put(h, 124, String.format("%011o\0", data.length));
            put(h, 136, String.format("%011o\0", 1_700_000_000L));
            put(h, 148, "        ");
            h[156] = '0';
            put(h, 257, "ustar\0");
            put(h, 263, "00");
            int sum = 0;
            for (byte b : h) {
                sum += b & 0xff;
            }
            put(h, 148, String.format("%06o\0 ", sum));
            out.write(h);
            out.write(data);
            out.write(new byte[(512 - data.length % 512) % 512]);
        }
        out.write(new byte[1024]);
        return out.toByteArray();
    }

    private static void put(byte[] h, int at, String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, h, at, b.length);
    }

    private static String positions(String... ids) {
        StringBuilder b = new StringBuilder();
        for (String id : ids) {
            b.append("{\"POSITION_ID\":\"").append(id).append("\",\"QTY\":1}\n");
        }
        return b.toString();
    }

    /**
     * A tar archive in the inbox (DATA_LAKE_GUIDE.md sections 4.4 and 4): each table folder loaded into its own table with each folder's date,
     * one load id for the archive; a table that fails rolls back the tables already committed and the archive goes to failed/ saying so.
     */
    @Test
    void aTarLoadsEachTableFolderAndAFailureRollsTheArchiveBack() throws IOException {
        Fixture f = new Fixture(tmp);
        Map<String, String> good = new java.util.LinkedHashMap<>();
        good.put("position/2026-11-02/p.jsonl", positions("P1", "P2"));
        good.put("trade/2026-11-02/t.jsonl", Fixture.rows("2026-11-02", "T1", "T2", "T3"));
        Files.write(f.inbox().resolve("eod.tar"), tar(good));
        Files.writeString(f.inbox().resolve("eod.ready"), "");
        f.age(f.inbox().resolve("eod.tar"), f.inbox().resolve("eod.ready"));
        DataInbox inbox = new DataInbox(f.settings, "node-a", f.clock, Fixture.ZONE, f.packs(), new LoaderProcess(f.settings), f.signal(), null);
        Cycle.Report r = inbox.cycle("test");
        assertThat(r.files()).as(r.toString()).singleElement().satisfies(o -> {
            assertThat(o.outcome()).isEqualTo("loaded");
            assertThat(o.rows()).isEqualTo(5);
        });
        assertThat(log(f.lake.resolve("demo/position"))).hasSize(1);
        assertThat(log(f.lake.resolve("demo/trade"))).hasSize(1);
        assertThat(f.signals).extracting(Signal.Landed::kind).containsExactlyInAnyOrder("position", "trade");
        JsonNode rep = Fixture.JSON.readTree(f.root.resolve("outbox/2026-11-03").toFile().listFiles()[0].toPath().resolve("eod.tar.loaded.json").toFile());
        assertThat(rep.path("tables")).hasSize(2);
        String loadId = rep.path("loadId").asText();
        assertThat(rep.path("tables").get(0).path("load").path("loads").has(loadId)).isTrue();
        assertThat(rep.path("tables").get(1).path("load").path("loads").has(loadId)).as("one load id for the archive").isTrue();

        Map<String, String> bad = new java.util.LinkedHashMap<>();
        bad.put("position/2026-11-02/p.jsonl", positions("P3"));
        bad.put("trade/2026-11-02/t.jsonl", "this is not JSON\n" + Fixture.rows("2026-11-02", "T9"));
        Files.write(f.inbox().resolve("bad.tar"), tar(bad));
        Files.writeString(f.inbox().resolve("bad.tar.ready"), "");
        f.age(f.inbox().resolve("bad.tar"), f.inbox().resolve("bad.tar.ready"));
        Cycle.Report r2 = inbox.cycle("test");
        assertThat(r2.files()).singleElement().satisfies(o -> {
            assertThat(o.outcome()).isEqualTo("failed");
            assertThat(o.reason()).contains("table trade").contains("rolled back: demo/position");
        });
        assertThat(log(f.lake.resolve("demo/trade"))).as("nothing more committed to trade").hasSize(1);
        assertThat(log(f.lake.resolve("demo/position"))).as("position: its commit, then the rollback's").hasSize(3);
    }

    @Test
    void theLaunchIsTheExecutableJarsOwnLauncherOrThePlainClassPath(@TempDir Path dir) throws IOException {
        assertThat(LoaderProcess.launch("/a/x.jar" + java.io.File.pathSeparator + "/a/y.jar"))
                .containsExactly("-cp", "/a/x.jar" + java.io.File.pathSeparator + "/a/y.jar", "com.ash.drishti.lakeload.LakeLoad");
        Path boot = dir.resolve("drishti-server-exec.jar");
        try (var z = new java.util.zip.ZipOutputStream(Files.newOutputStream(boot))) {
            z.putNextEntry(new java.util.zip.ZipEntry("BOOT-INF/"));
            z.closeEntry();
        }
        assertThat(LoaderProcess.launch(boot.toString())).containsExactly("-Dloader.main=com.ash.drishti.lakeload.LakeLoad", "-jar", boot.toString());
    }
}
