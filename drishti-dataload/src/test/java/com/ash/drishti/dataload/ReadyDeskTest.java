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

import com.ash.drishti.common.DrishtiException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Upload page's server side, on synthetic rows in a temporary folder: the text, the checks, the staging folder and the order of a push. */
class ReadyDeskTest {

    static final String ME = "ajay";
    @TempDir Path tmp;
    Fixture f;
    DataInbox inbox;
    ReadyDesk desk;

    @BeforeEach
    void up() throws IOException {
        f = new Fixture(tmp);
        Files.writeString(f.pack.resolve("pack.yaml"), Files.readString(f.pack.resolve("pack.yaml")).replace("[TRADE_ID, DESK]", "[TRADE_ID, DESK, SOURCE_SYSTEM]"));
        Files.createDirectories(f.pack.resolve("contracts"));
        Files.writeString(f.pack.resolve("contracts/trade.contract.yaml"), """
                kind: trade
                version: '1.0'
                key: [TRADE_ID]
                dated-by: BUSINESS_DATE
                fields:
                  - {name: TRADE_ID, presence: required}
                  - {name: DESK, presence: required}
                  - {name: BUSINESS_DATE, presence: required}
                """);
        inbox = new DataInbox(f.settings, "node-a", f.clock, Fixture.ZONE, f.packs(), new LoaderProcess(f.settings), f.signal(), null);
        desk = desk(new ReadyDesk.Settings(1.0, 0.0, 100, 24), List.of());
    }

    ReadyDesk desk(ReadyDesk.Settings s, List<ReadyRule> rules) {
        return new ReadyDesk(f.settings, s, inbox, f.packs(), f.clock, "node-a", rules);
    }

    static String row(String id, String desk, String system) {
        return "{\"TRADE_ID\":\"" + id + "\",\"DESK\":\"" + desk + "\",\"SOURCE_SYSTEM\":\"" + system + "\"}\n";
    }

    static ReadyForm form(String item, String mode) {
        return new ReadyForm(item, null, "trade", "demo-pack", "trade", null, null, "2026-11-02", mode, false, "TRADE_ID", Map.of(), false, false);
    }

    static ReadyForm ticked(ReadyForm x) {
        return new ReadyForm(x.item(), x.file(), x.table(), x.pack(), x.kind(), x.connector(), x.lake(), x.businessDate(), x.mode(), x.newTable(), x.key(), x.folders(), x.hold(), true);
    }

    String upload(String name, String body) throws Exception {
        Map<String, Object> st = desk.receive(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)), name, body.length(), ME);
        String id = String.valueOf(st.get("id"));
        await(id);
        return id;
    }

    Map<String, Object> await(String id) throws Exception {
        for (int i = 0; i < 400; i++) {
            Map<String, Object> st = desk.state(desk.item(id, ME));
            if (!"inspecting".equals(st.get("state"))) {
                return st;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("still inspecting");
    }

    @SuppressWarnings("unchecked")
    static List<ReadyCheck> checks(Map<String, Object> out) {
        return (List<ReadyCheck>) out.get("checks");
    }

    static String sha(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    // ---- the text

    @Test
    void everyKeyWrittenIsReadBackTheSame() {
        ReadyNote n = new ReadyNote("demo-pack", "trade", "trade-lake", "trade_endur", LocalDate.parse("2026-11-02"), "replace", "a".repeat(64), 12L, true, "TRADE ID",
                Map.of("x_1", "demo-pack"), true, "risklake");
        assertThat(ReadyNote.parse(ReadyWriter.text(n))).isEqualTo(n);
        assertThat(ReadyWriter.text(ReadyNote.EMPTY)).isEmpty();
        assertThat(ReadyNote.parse("")).isEqualTo(ReadyNote.EMPTY);
    }

    @Test
    void anUnknownKeyIsSaidAsARefusal() {
        assertThat(desk.read("nonsense: 1\n").get("refused").toString()).contains("nonsense");
        assertThat(desk.read("table: trade\nmode: append\n")).containsEntry("table", "trade").containsEntry("mode", "append");
    }

    @Test
    void thePreviewNamesTheTableAndSaysFieldErrors() throws Exception {
        String id = upload("trade.2026-11-02.jsonl", row("A1", "X", "ENDUR"));
        ReadyChecks.Built b = desk.preview(new ReadyForm(id, null, "trade", "demo-pack", "trade", null, null, "2026-99-01", "sometimes", false, null, Map.of(), false, false), ME);
        assertThat(b.errors()).containsKeys("date", "mode");
        ReadyChecks.Built ok = desk.preview(form(id, "append"), ME);
        assertThat(ok.errors()).isEmpty();
        assertThat(ok.text()).contains("table: trade\n", "business-date: 2026-11-02\n", "mode: append\n", "key: TRADE_ID\n", "sha256: " + sha(row("A1", "X", "ENDUR")), "rows: 1\n");
        assertThat(ok.derived()).containsEntry("pack", "demo-pack").containsEntry("kind", "trade");
    }

    @Test
    void aFileARuleWouldRouteElsewhereIsNamedInFull() throws Exception {
        String id = upload("trades-x.2026-11-02.jsonl", row("A1", "X", "ENDUR"));      // the rule "trades-*" sends it to demo-pack/trade
        ReadyChecks.Built b = desk.preview(new ReadyForm(id, null, "position", "demo-pack", "position", null, null, null, "append", false, null, Map.of(), false, false), ME);
        assertThat(b.text()).contains("pack: demo-pack\n", "kind: position\n", "table: position\n");
        assertThat(b.derived()).containsEntry("kind", "position");
    }

    @Test
    void aNewTableNeedsItsPackAndItsOwnKind() throws Exception {
        String id = upload("fresh.2026-11-02.jsonl", row("A1", "X", "ENDUR"));
        ReadyForm none = new ReadyForm(id, null, "fresh", null, null, null, null, null, "append", true, null, Map.of(), false, false);
        assertThat(desk.preview(none, ME).errors()).containsKey("pack");
        ReadyForm other = new ReadyForm(id, null, "fresh", "demo-pack", "trade", null, null, null, "append", true, null, Map.of(), false, false);
        assertThat(desk.preview(other, ME).errors()).containsKey("kind");
    }

    // ---- the file

    @Test
    void shaAndRowsAreComputedOnTheServer() throws Exception {
        String body = row("A1", "X", "ENDUR") + "\n" + row("A2", "Y", "ENDUR");
        Map<String, Object> st = await(upload("trade.2026-11-02.jsonl", body));
        assertThat(st).containsEntry("sha256", sha(body)).containsEntry("rows", 2L).containsEntry("state", "ready");
        assertThat(st.get("suggest").toString()).contains("table=trade", "date=2026-11-02");
    }

    @Test
    void aNameOutsideTheFolderOrOfAnotherTypeIsRefused() {
        for (String bad : List.of("../evil.jsonl", "a/b.jsonl", "..\\x.gz", ".hidden.jsonl", "x..y.jsonl", "notes.txt", "x.exe", "")) {
            assertThatThrownBy(() -> desk.receive(new ByteArrayInputStream(new byte[1]), bad, 1, ME)).as(bad).isInstanceOf(DrishtiException.class);
        }
        assertThat(ReadyDesk.checkName("a.tar.gz")).isEqualTo("a.tar.gz");
    }

    @Test
    void aFileOverTheLimitIsRefusedAndNothingStays() throws Exception {
        ReadyDesk small = desk(new ReadyDesk.Settings(1.0 / 1024 / 1024 / 1024 * 10, 0.0, 10, 24), List.of());
        assertThatThrownBy(() -> small.receive(new ByteArrayInputStream(new byte[64]), "a.jsonl", -1, ME)).isInstanceOf(DrishtiException.class)
                .hasMessageContaining("max-gb");
        try (var s = Files.list(f.root.resolve("staging"))) {
            assertThat(s).isEmpty();
        }
    }

    @Test
    void anotherPersonCannotSeeOrPushMyFile() throws Exception {
        String id = upload("trade.2026-11-02.jsonl", row("A1", "X", "ENDUR"));
        assertThatThrownBy(() -> desk.check(form(id, "append"), "someone-else")).isInstanceOf(DrishtiException.class);
        assertThatThrownBy(() -> desk.push(form(id, "append"), "someone-else")).isInstanceOf(DrishtiException.class);
        assertThatThrownBy(() -> desk.discard(id, "someone-else")).isInstanceOf(DrishtiException.class);
        assertThat(desk.state(desk.item(id, ME))).containsEntry("state", "ready");
    }

    // ---- the checks

    @Test
    void passWarningAndRefusalInWords() throws Exception {
        String good = upload("trade.2026-11-02.jsonl", row("A1", "X", "ENDUR") + row("A2", "Y", "ENDUR"));
        List<ReadyCheck> ok = checks(desk.check(form(good, "append"), ME));
        assertThat(ok).allMatch(c -> ReadyCheck.PASS.equals(c.status()));
        assertThat(ok).anyMatch(c -> c.text().contains("every required field")).anyMatch(c -> c.text().contains("unique in the sample"));

        String twice = upload("trade.2026-11-03.jsonl", row("A1", "X", "ENDUR") + row("A1", "X", "ENDUR"));
        assertThat(checks(desk.check(form(twice, "append"), ME))).anyMatch(c -> ReadyCheck.WARNING.equals(c.status()) && c.text().contains("repeats"));

        String missing = upload("trade.2026-11-04.jsonl", "{\"TRADE_ID\":\"A1\"}\n");
        Map<String, Object> out = desk.check(form(missing, "append"), ME);
        assertThat(checks(out)).anyMatch(c -> ReadyCheck.REFUSED.equals(c.status()) && c.text().contains("DESK") && c.fix() != null);
        assertThat(out).containsEntry("blocked", true);
        assertThatThrownBy(() -> desk.push(form(missing, "append"), ME)).isInstanceOf(DrishtiException.class).hasMessageContaining("DESK");
        assertThat(f.inbox().resolve("trade.2026-11-04.jsonl")).doesNotExist();
    }

    @Test
    void aModuleRuleRefusalBlocksThePush() throws Exception {
        desk = desk(new ReadyDesk.Settings(1.0, 0.0, 100, 24), List.of(in -> List.of(ReadyCheck.refused("DRS-5210: ENDUR already sent TRADE grain", "Use replace.").offering("mode", "replace"))));
        String id = upload("trade.2026-11-02.jsonl", row("A1", "X", "ENDUR"));
        assertThat(checks(desk.check(form(id, "append"), ME))).anyMatch(c -> c.text().contains("DRS-5210") && "replace".equals(c.value()));
        assertThatThrownBy(() -> desk.push(form(id, "append"), ME)).isInstanceOf(DrishtiException.class).hasMessageContaining("DRS-5210");
    }

    // ---- the push

    @Test
    void theDataFileGoesFirstAndTheMarkerLast() throws Exception {
        String id = upload("trade.2026-11-02.jsonl", row("A1", "X", "ENDUR"));
        boolean[] seen = {false};
        desk.steps(step -> {
            if (step.equals("data")) {
                seen[0] = true;
                assertThat(f.inbox().resolve("trade.2026-11-02.jsonl")).exists();
                assertThat(f.inbox().resolve("trade.2026-11-02.jsonl.ready")).doesNotExist();
            }
        });
        ReadyDesk.Pushed p = desk.push(form(id, "append"), ME);
        assertThat(seen[0]).isTrue();
        assertThat(f.inbox().resolve("trade.2026-11-02.jsonl.ready")).hasContent(p.text().stripTrailing());
        try (var s = Files.list(f.root.resolve("staging"))) {
            assertThat(s).as("nothing is left in staging").isEmpty();
        }
    }

    @Test
    void aCrashBetweenTheStepsLeavesNoMarker() throws Exception {
        String id = upload("trade.2026-11-02.jsonl", row("A1", "X", "ENDUR"));
        desk.steps(step -> {
            throw new IOException("the disk went away");
        });
        assertThatThrownBy(() -> desk.push(form(id, "append"), ME)).isInstanceOf(IOException.class);
        assertThat(f.inbox().resolve("trade.2026-11-02.jsonl")).exists();
        assertThat(f.inbox().resolve("trade.2026-11-02.jsonl.ready")).doesNotExist();
        assertThat(new Inbox(f.inbox()).look(f.clock.instant(), java.time.Duration.ZERO, 0, Long.MAX_VALUE).ready()).isEmpty();
    }

    @Test
    void aFileOfTheSameNameAlreadyWaitingIsRefused() throws Exception {
        f.drop("trade.2026-11-02.jsonl", "old\n", null);
        String id = upload("trade.2026-11-02.jsonl", row("A1", "X", "ENDUR"));
        assertThat(checks(desk.check(form(id, "append"), ME))).anyMatch(c -> ReadyCheck.REFUSED.equals(c.status()) && c.text().contains("already waiting"));
        assertThatThrownBy(() -> desk.push(form(id, "append"), ME)).isInstanceOf(DrishtiException.class).hasMessageContaining("already waiting");
        assertThat(f.inbox().resolve("trade.2026-11-02.jsonl")).hasContent("old");
    }

    @Test
    void aFileAlreadyInTheInboxGetsOnlyItsMarker() throws Exception {
        String body = row("A1", "X", "ENDUR");
        f.drop("trade.2026-11-02.jsonl", body, null);
        String id = String.valueOf(desk.pick("trade.2026-11-02.jsonl", ME).get("id"));
        Map<String, Object> st = await(id);
        assertThat(st).containsEntry("source", "inbox").containsEntry("sha256", sha(body));
        desk.push(form(id, "append"), ME);
        assertThat(f.inbox().resolve("trade.2026-11-02.jsonl")).hasContent(body.stripTrailing());
        assertThat(f.inbox().resolve("trade.2026-11-02.jsonl.ready")).exists();
        assertThatThrownBy(() -> desk.pick("absent.jsonl", ME)).isInstanceOf(DrishtiException.class);
    }

    @Test
    void uploadPushLoadsThenAReplaceOfTheDateNeedsATick() throws Exception {
        String id = upload("trade.2026-11-02.jsonl", row("A1", "X", "ENDUR") + row("A2", "Y", "ENDUR"));
        desk.push(form(id, "append"), ME);
        f.age(f.inbox().resolve("trade.2026-11-02.jsonl"), f.inbox().resolve("trade.2026-11-02.jsonl.ready"));
        Cycle.Report r = inbox.cycle("test");
        assertThat(r.files()).as(r.toString()).singleElement().satisfies(o -> {
            assertThat(o.outcome()).isEqualTo("loaded");
            assertThat(o.rows()).isEqualTo(2);
        });

        String second = upload("trade.2026-11-02.part2.jsonl", row("B1", "X", "CALYPSO"));
        List<ReadyCheck> cs = checks(desk.check(form(second, "replace"), ME));
        assertThat(cs).as(cs.toString()).anyMatch(ReadyCheck::tick);
        ReadyCheck replace = cs.stream().filter(ReadyCheck::tick).findFirst().orElseThrow();
        assertThat(replace.text()).contains("already holds 2 rows", "ENDUR 2", "Replace removes", "not in this file's sample");
        assertThat(replace.fix()).contains("one tar", "append");
        assertThat(replace.field()).isEqualTo("mode");
        assertThat(replace.value()).isEqualTo("append");
        assertThatThrownBy(() -> desk.push(form(second, "replace"), ME)).isInstanceOf(DrishtiException.class).hasMessageContaining("tick");
        assertThat(checks(desk.check(form(second, "append"), ME))).noneMatch(ReadyCheck::tick);
        assertThat(desk.push(ticked(form(second, "replace")), ME).file()).isEqualTo("trade.2026-11-02.part2.jsonl");
    }

    @Test
    void aTarListsWhatItsFoldersSayAndTakesNoTableOrDate() throws Exception {
        Path tar = tmp.resolve("eod.tar");
        try (var o = new java.io.BufferedOutputStream(Files.newOutputStream(tar))) {
            byte[] body = row("A1", "X", "ENDUR").getBytes(StandardCharsets.UTF_8);
            byte[] h = new byte[512];
            byte[] n = "trade/2026-11-02/part-1.jsonl".getBytes(StandardCharsets.UTF_8);
            System.arraycopy(n, 0, h, 0, n.length);
            System.arraycopy("0000644\0".getBytes(StandardCharsets.UTF_8), 0, h, 100, 8);
            System.arraycopy("0000000\0".getBytes(StandardCharsets.UTF_8), 0, h, 108, 8);
            System.arraycopy("0000000\0".getBytes(StandardCharsets.UTF_8), 0, h, 116, 8);
            System.arraycopy(String.format("%011o\0", body.length).getBytes(StandardCharsets.UTF_8), 0, h, 124, 12);
            System.arraycopy("00000000000\0".getBytes(StandardCharsets.UTF_8), 0, h, 136, 12);
            java.util.Arrays.fill(h, 148, 156, (byte) ' ');
            h[156] = '0';
            System.arraycopy("ustar\0".getBytes(StandardCharsets.UTF_8), 0, h, 257, 6);
            h[263] = '0';
            h[264] = '0';
            int sum = 0;
            for (byte b : h) {
                sum += b & 0xff;
            }
            System.arraycopy(String.format("%06o\0 ", sum).getBytes(StandardCharsets.UTF_8), 0, h, 148, 8);
            o.write(h);
            o.write(body);
            o.write(new byte[512 - body.length]);
            o.write(new byte[1024]);
        }
        String id;
        try (var in = Files.newInputStream(tar)) {
            id = String.valueOf(desk.receive(in, "eod.tar", Files.size(tar), ME).get("id"));
        }
        Map<String, Object> st = await(id);
        assertThat(st.get("folders").toString()).as(st.toString()).contains("trade", "2026-11-02", "rows=1");
        ReadyForm plain = new ReadyForm(id, null, null, null, null, null, null, null, "append", false, null, Map.of(), false, false);
        ReadyChecks.Built b = desk.preview(plain, ME);
        assertThat(b.errors()).isEmpty();
        assertThat(b.text()).doesNotContain("table:", "business-date:").contains("mode: append");
        assertThat(checks(desk.check(plain, ME))).anyMatch(c -> c.text().contains("1 folders"));
        ReadyForm withDate = new ReadyForm(id, null, null, null, null, null, null, "2026-11-02", "append", false, null, Map.of(), false, false);
        assertThat(desk.preview(withDate, ME).errors()).containsKey("date");
    }
}
