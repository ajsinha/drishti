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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Routing (DATALOAD_INBOX.md section 5): the .ready file's keys, then the first matching rule; never guessed. And the marker's contents. */
class RoutesTest {

    @TempDir Path tmp;

    private Routes routes(Fixture f) throws IOException {
        return Routes.read(f.rules, f.lake.toString(), f.packs());
    }

    @Test
    void theFirstMatchingRuleRoutesAFileToItsPackAndKind() throws Exception {
        Fixture f = new Fixture(tmp);
        Routes.Route r = routes(f).route("trades-endur.2026-11-03.jsonl.gz", ReadyNote.EMPTY);
        assertThat(r.pack()).isEqualTo("demo-pack");
        assertThat(r.kind()).isEqualTo("trade");
        assertThat(r.asOf()).as("the loader dates each file the same way (FileDates.decide)").isNull();
        assertThat(r.dateFrom()).isEqualTo("auto");
        assertThat(r.nameDate()).isEqualTo(LocalDate.parse("2026-11-03"));
        assertThat(r.dateBy()).isEqualTo("2026-11-03 from the name");
        assertThat(r.lake()).isEqualTo(f.lake.toString());
        assertThat(r.format()).isEqualTo("delta");
        assertThat(r.packDir()).isEqualTo(f.pack);
        assertThat(r.by()).isEqualTo("the rule trades-*");
        assertThat(r.mode()).as("the inbox's default-mode: append").isEqualTo("append");
        Routes.Route today = routes(f).route("trades-endur.jsonl.gz", ReadyNote.EMPTY);
        assertThat(today.dateBy()).isEqualTo(FileDates.businessToday() + " (today: no date in the name)");
        assertThat(today.group()).as("dated and undated names go to one load: the loader dates each file").isEqualTo(r.group());
    }

    @Test
    void dateFromNameRefusesANameWithoutADateAndTheReadyFilesDateWins() throws Exception {
        Fixture f = new Fixture(tmp);
        assertThat(routes(f).route("named-trades.2026-10-30.jsonl", ReadyNote.EMPTY).dateBy()).isEqualTo("2026-10-30 from the name");
        assertThatThrownBy(() -> routes(f).route("named-latest.jsonl", ReadyNote.EMPTY)).isInstanceOf(Routes.Unrouted.class)
                .hasMessageContaining("date-from: name").hasMessageContaining("carries none");
        ReadyNote dated = ReadyNote.of(null, null, null, null, LocalDate.parse("2026-10-29"));
        Routes.Route r = routes(f).route("named-latest.jsonl", dated);
        assertThat(List.of(String.valueOf(r.asOf()), r.dateBy())).containsExactly("2026-10-29", "2026-10-29 from the .ready file");
        Path load = tmp.resolve("load.yaml");
        LoaderProcess.writeConfig(load, r, List.of(Files.writeString(tmp.resolve("x.jsonl"), "{}\n")), tmp.resolve("s.json"), tmp.resolve("r.jsonl"), 0, tmp.resolve("spill"));
        assertThat(Files.readString(load)).containsPattern("as-of: \"?2026-10-29\"?").containsPattern("mode: \"?append\"?");
        assertThatThrownBy(() -> Routes.read(writeRule(f, "date-from: field"), "lake", f.packs())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("auto (the .ready file's business-date");
    }

    private static Path writeRule(Fixture f, String extra) throws IOException {
        Files.writeString(f.rules, "dataload:\n  rules:\n    - match: \"x-*\"\n      pack: demo-pack\n      kind: trade\n      " + extra + "\n");
        return f.rules;
    }

    @Test
    void withoutARuleTheNameOrTheReadyFileNamesTheTable() throws Exception {
        Fixture f = new Fixture(tmp);
        Routes.Route r = routes(f).route("position.2026-11-02.jsonl", ReadyNote.EMPTY);
        assertThat(List.of(r.pack(), r.kind(), r.by())).containsExactly("demo-pack", "position", "the name's table position");
        Routes.Route t = routes(f).route("feed.jsonl", ReadyNote.of(null, null, null, "TRADE", null));
        assertThat(List.of(t.kind(), t.by())).containsExactly("trade", "the .ready file's table trade");
        assertThatThrownBy(() -> routes(f).route("mystery.jsonl", ReadyNote.EMPTY)).isInstanceOf(Routes.Unrouted.class)
                .hasMessageContaining("mystery: no pack, kind or table of that name (known: demo-pack/position, demo-pack/trade)");
        assertThatThrownBy(() -> routes(f).route("mystery.final.jsonl", ReadyNote.EMPTY)).hasMessageContaining("'final' is neither the date");
        ReadyNote note = new ReadyNote("demo-pack", "position", "pos-lake", null, null, "append", null, null, false, "POS_ID", java.util.Map.of(), false);
        Routes.Route n = routes(f).route("mystery.jsonl", note);
        assertThat(List.of(n.kind(), n.connector(), n.key(), n.mode(), n.by())).containsExactly("position", "pos-lake", "POS_ID", "append", "the .ready file");
        assertThat(routes(f).route("trades-1.jsonl", note).by()).isEqualTo("the .ready file over the rule trades-*");
        assertThatThrownBy(() -> routes(f).route("feed.tar.gz", ReadyNote.of(null, null, null, null, LocalDate.parse("2026-11-01"))))
                .hasMessageContaining("a .tar archive names its own tables and dates");
    }

    @Test
    void aNewTableIsMadeOnlyWhenAskedForAndAllowed() throws Exception {
        Fixture f = new Fixture(tmp);
        ReadyNote asked = new ReadyNote("demo-pack", null, null, null, null, null, null, null, true, "DEAL_ID", java.util.Map.of(), false);
        assertThatThrownBy(() -> routes(f).route("deals.2026-11-02.jsonl", asked)).hasMessageContaining("deals: no pack, kind or table")
                .hasMessageContaining("does not allow new tables");
        Routes.Route r = routes(f).with("append", true).route("deals.2026-11-02.jsonl", asked);
        assertThat(List.of(r.kind(), r.table(), r.newDomain(), r.key(), r.dateBy())).containsExactly("deals", "deals", "demo", "DEAL_ID",
                "2026-11-02 from the name");
        assertThatThrownBy(() -> routes(f).with("append", true).route("deals.jsonl",
                new ReadyNote(null, null, null, null, null, null, null, null, true, null, java.util.Map.of(), false)))
                .hasMessageContaining("a new table needs its pack");
        Path load = tmp.resolve("new.yaml");
        LoaderProcess.writeConfig(load, r, List.of(Files.writeString(tmp.resolve("x.jsonl"), "{}\n")), tmp.resolve("s.json"), tmp.resolve("r.jsonl"), 0, tmp.resolve("spill"));
        assertThat(Files.readString(load)).doesNotContain("pack:").containsPattern("domain: \"?demo\"?").containsPattern("table: \"?deals\"?");
    }

    @Test
    void anUnknownPackOrKindIsRefusedWithTheReason() throws Exception {
        Fixture f = new Fixture(tmp);
        assertThatThrownBy(() -> routes(f).route("x.jsonl", ReadyNote.of("nope", "trade", null, null, null)))
                .hasMessageContaining("no loaded pack is named nope");
        assertThatThrownBy(() -> routes(f).route("x.jsonl", ReadyNote.of("demo-pack", "curve", null, null, null)))
                .hasMessageContaining("has no kind curve").hasMessageContaining("[trade, position]");
    }

    @Test
    void aBadRulesFileIsRefusedAndNoFileIsNoRules() throws Exception {
        Fixture f = new Fixture(tmp);
        Files.writeString(f.rules, "dataload:\n  rules:\n    - {match: \"a*\", pack: p, kind: k, colour: red}\n");
        assertThatThrownBy(() -> routes(f)).hasMessageContaining("colour is not a key of a rule");
        Files.writeString(f.rules, "dataload:\n  rules: []\n  unmatched: guess\n");
        assertThatThrownBy(() -> routes(f)).hasMessageContaining("fail is the only choice");
        Files.writeString(f.rules, "dataload:\n  rules:\n    - {match: \"a*\", pack: p}\n");
        assertThatThrownBy(() -> routes(f)).hasMessageContaining("needs match");
        Files.delete(f.rules);
        assertThat(routes(f).size()).isZero();
    }

    @Test
    void aMarkerMayBeEmptyOrSayItsKeysAndIsCheckedStrictly() throws IOException {
        Path m = tmp.resolve("a.jsonl.ready");
        Files.writeString(m, "");
        assertThat(ReadyNote.read(m)).isEqualTo(ReadyNote.EMPTY);
        Files.writeString(m, "pack: demo-pack\nkind: trade\nbusiness-date: 2026-11-02\nrows: 12\nsha256: " + "AB".repeat(32) + "\nmode: replace\n"
                + "table: Trades\nconnector: c\nnew-table: true\nkey: TRADE_ID\nfolders: {Trades: core}\nhold: true\n");
        ReadyNote n = ReadyNote.read(m);
        assertThat(n.pack()).isEqualTo("demo-pack");
        assertThat(n.businessDate()).isEqualTo(LocalDate.parse("2026-11-02"));
        assertThat(n.rows()).isEqualTo(12);
        assertThat(n.sha256()).isEqualTo("ab".repeat(32));
        assertThat(List.of(n.table(), n.connector(), n.key(), n.mode())).containsExactly("trades", "c", "TRADE_ID", "replace");
        assertThat(n.newTable()).isTrue();
        assertThat(n.hold()).isTrue();
        assertThat(n.folders()).containsEntry("trades", "core");
        Files.writeString(m, "sha256: " + "1".repeat(64) + "\n");
        assertThat(ReadyNote.read(m).sha256()).as("64 digits are a checksum, not a number").isEqualTo("1".repeat(64));
        Files.writeString(m, "{\"kind\": \"trade\"}");
        assertThat(ReadyNote.read(m).kind()).as("JSON too").isEqualTo("trade");
        Files.writeString(m, "mode: append\n");
        assertThat(ReadyNote.read(m).mode()).isEqualTo("append");
        Files.writeString(m, "mode: merge\n");
        assertThatThrownBy(() -> ReadyNote.read(m)).hasMessageContaining("append (the default");
        Files.writeString(m, "date-field: COB\n");
        assertThatThrownBy(() -> ReadyNote.read(m)).hasMessageContaining("key date-field");
        Files.writeString(m, "sha256: abc\n");
        assertThatThrownBy(() -> ReadyNote.read(m)).hasMessageContaining("64 hexadecimal");
        Files.writeString(m, "business-date: yesterday\n");
        assertThatThrownBy(() -> ReadyNote.read(m)).hasMessageContaining("YYYY-MM-DD");
        Files.writeString(m, "hold: maybe\n");
        assertThatThrownBy(() -> ReadyNote.read(m)).hasMessageContaining("true or false");
    }
}
