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
        Routes.Route r = routes(f).route("trades-endur-2026-11-03.jsonl.gz", ReadyNote.EMPTY);
        assertThat(r.pack()).isEqualTo("demo-pack");
        assertThat(r.kind()).isEqualTo("trade");
        assertThat(r.asOf()).as("date-from: auto (the default): the loader applies the name's date per file").isNull();
        assertThat(r.dateFrom()).isEqualTo("auto");
        assertThat(r.nameDate()).isEqualTo(LocalDate.parse("2026-11-03"));
        assertThat(r.dateBy()).isEqualTo("2026-11-03 from the name");
        assertThat(r.lake()).isEqualTo(f.lake.toString());
        assertThat(r.format()).isEqualTo("delta");
        assertThat(r.packDir()).isEqualTo(f.pack);
        assertThat(r.by()).isEqualTo("the rule trades-*");
    }

    @Test
    void dateFromNameTakesTheDateInTheFilesNameAndANameWithoutOneIsRefused() throws Exception {
        Fixture f = new Fixture(tmp);
        assertThat(routes(f).route("named-2026-10-30.jsonl", ReadyNote.EMPTY).asOf()).isEqualTo(LocalDate.parse("2026-10-30"));
        assertThatThrownBy(() -> routes(f).route("named-latest.jsonl", ReadyNote.EMPTY)).isInstanceOf(Routes.Unrouted.class)
                .hasMessageContaining("holds no YYYY-MM-DD");
        ReadyNote dated = new ReadyNote(null, null, null, null, LocalDate.parse("2026-10-29"), null, null, null, null);
        assertThat(routes(f).route("named-latest.jsonl", dated).asOf()).as("the .ready file's business-date wins").isEqualTo(LocalDate.parse("2026-10-29"));
    }

    @Test
    void autoTakesTheNamesDateWhenThereIsOneAndFieldIgnoresIt() throws Exception {
        Fixture f = new Fixture(tmp);
        Files.writeString(f.rules, """
                dataload:
                  rules:
                    - match: "auto-*"
                      pack: demo-pack
                      kind: trade
                    - match: "field-*"
                      pack: demo-pack
                      kind: trade
                      date-from: field
                    - match: "named-*"
                      pack: demo-pack
                      kind: trade
                      date-from: name
                """);
        Routes.Route plain = routes(f).route("auto-trades.jsonl", ReadyNote.EMPTY);
        assertThat(List.of(plain.dateFrom(), plain.dateBy())).containsExactly("auto", "each row's date field (the pack's)");
        assertThat(plain.nameDate()).isNull();
        Routes.Route dated = routes(f).route("auto-trades 20261103.jsonl.gz", ReadyNote.EMPTY);
        assertThat(dated.asOf()).as("the loader enforces it per file (an archive member's own date first)").isNull();
        assertThat(dated.nameDate()).isEqualTo(LocalDate.parse("2026-11-03"));
        assertThat(dated.group()).isEqualTo(plain.group());
        Routes.Route field = routes(f).route("field-trades 20261103.jsonl", ReadyNote.EMPTY);
        assertThat(List.of(field.dateFrom(), field.dateBy())).containsExactly("field", "each row's date field (the pack's)");
        assertThat(field.group()).isNotEqualTo(plain.group());
        assertThat(routes(f).route("named-trades_20261103.jsonl", ReadyNote.EMPTY).asOf()).isEqualTo(LocalDate.parse("2026-11-03"));
        ReadyNote dateField = new ReadyNote(null, null, null, null, null, "COB", null, null, null);
        assertThat(routes(f).route("auto-trades 20261103.jsonl", dateField).dateFrom()).as("a .ready date-field: the rows' own").isEqualTo("field");
        ReadyNote given = new ReadyNote(null, null, null, null, LocalDate.parse("2026-11-01"), null, null, null, null);
        Routes.Route r = routes(f).route("auto-trades 20261103.jsonl", given);
        assertThat(List.of(String.valueOf(r.asOf()), r.dateBy())).containsExactly("2026-11-01", "2026-11-01 from the .ready file");
        Path load = tmp.resolve("load.yaml");
        LoaderProcess.writeConfig(load, field, List.of(tmp.resolve("x.jsonl")), tmp.resolve("s.json"), tmp.resolve("r.jsonl"), 0, tmp.resolve("spill"));
        assertThat(Files.readString(load)).containsPattern("date-from: \"?field\"?");
        assertThatThrownBy(() -> Routes.read(writeRule(f, "date-from: rows"), "lake", f.packs())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("auto (the date at the end of the file's name");
    }

    private static Path writeRule(Fixture f, String extra) throws IOException {
        Files.writeString(f.rules, "dataload:\n  rules:\n    - match: \"x-*\"\n      pack: demo-pack\n      kind: trade\n      " + extra + "\n");
        return f.rules;
    }

    @Test
    void aFileNoRuleMatchesIsRefusedUnlessItsMarkerNamesThePackAndKind() throws Exception {
        Fixture f = new Fixture(tmp);
        assertThatThrownBy(() -> routes(f).route("mystery.jsonl", ReadyNote.EMPTY)).isInstanceOf(Routes.Unrouted.class)
                .hasMessageContaining("no routing rule matches mystery.jsonl");
        ReadyNote note = new ReadyNote("demo-pack", "position", "pos-lake", "position_x", null, "COB", null, null, null);
        Routes.Route r = routes(f).route("mystery.jsonl", note);
        assertThat(r.kind()).isEqualTo("position");
        assertThat(r.connector()).isEqualTo("pos-lake");
        assertThat(r.table()).isEqualTo("position_x");
        assertThat(r.dateField()).isEqualTo("COB");
        assertThat(r.by()).isEqualTo("the .ready file");
        assertThat(routes(f).route("trades-1.jsonl", note).by()).isEqualTo("the .ready file over the rule trades-*");
    }

    @Test
    void anUnknownPackOrKindIsRefusedWithTheReason() throws Exception {
        Fixture f = new Fixture(tmp);
        assertThatThrownBy(() -> routes(f).route("x.jsonl", new ReadyNote("nope", "trade", null, null, null, null, null, null, null)))
                .hasMessageContaining("no loaded pack is named nope");
        assertThatThrownBy(() -> routes(f).route("x.jsonl", new ReadyNote("demo-pack", "curve", null, null, null, null, null, null, null)))
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
        Files.writeString(m, "pack: demo-pack\nkind: trade\nbusiness-date: 2026-11-02\nrows: 12\nsha256: " + "AB".repeat(32) + "\nmode: replace\n");
        ReadyNote n = ReadyNote.read(m);
        assertThat(n.pack()).isEqualTo("demo-pack");
        assertThat(n.businessDate()).isEqualTo(LocalDate.parse("2026-11-02"));
        assertThat(n.rows()).isEqualTo(12);
        assertThat(n.sha256()).isEqualTo("ab".repeat(32));
        Files.writeString(m, "sha256: " + "1".repeat(64) + "\n");
        assertThat(ReadyNote.read(m).sha256()).as("64 digits are a checksum, not a number").isEqualTo("1".repeat(64));
        Files.writeString(m, "{\"kind\": \"trade\"}");
        assertThat(ReadyNote.read(m).kind()).as("JSON too").isEqualTo("trade");
        Files.writeString(m, "mode: append\n");
        assertThatThrownBy(() -> ReadyNote.read(m)).hasMessageContaining("appending is not offered");
        Files.writeString(m, "colour: red\n");
        assertThatThrownBy(() -> ReadyNote.read(m)).hasMessageContaining("key colour");
        Files.writeString(m, "sha256: abc\n");
        assertThatThrownBy(() -> ReadyNote.read(m)).hasMessageContaining("64 hexadecimal");
        Files.writeString(m, "business-date: yesterday\n");
        assertThatThrownBy(() -> ReadyNote.read(m)).hasMessageContaining("YYYY-MM-DD");
    }
}
