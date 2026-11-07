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

import com.ash.drishti.lakeconfig.Lake;
import com.ash.drishti.lakeconfig.Lakes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The lake a file goes to (DATA_LAKE_GUIDE.md section 4): {@code lake:} in the {@code .ready} file over the rule's and the inbox's default; a
 * lake with no file yet created ({@code config/lakes/<lake>.yaml}, its own folder {@code <data>/delta/<lake>}, the pack's entry), the tables
 * landing at {@code <data>/delta/<lake>/<domain>/<table>}; and the warning when no connector reads the lake. Synthetic rows in a temporary
 * folder only.
 */
class LakeRoutingTest {

    @TempDir Path tmp;
    Path lakes;
    Path data;

    @BeforeEach
    void folders() {
        lakes = tmp.resolve("config/lakes");
        data = tmp.resolve("data");
        System.setProperty(Lakes.PROPERTY, lakes.toString());
        Lakes.useProperties(k -> k.equals("drishti.data.dir") ? data.toString() : null);
    }

    @AfterEach
    void reset() {
        System.clearProperty(Lakes.PROPERTY);
        Lakes.useProperties(null);
    }

    /** The fixture's packs, with the lakes the connectors read ({@code null}: not known). */
    static Routes.Packs reading(Fixture f, Set<String> read) {
        Routes.Packs p = f.packs();
        return new Routes.Packs() {
            @Override
            public Routes.PackInfo find(String name) {
                return p.find(name);
            }

            @Override
            public List<String> names() {
                return p.names();
            }

            @Override
            public Set<String> lakesRead() {
                return read;
            }
        };
    }

    @Test
    void theReadyFileNamesTheLakeOverTheRuleAndTheDefault() throws Exception {
        Fixture f = new Fixture(tmp);
        Path marker = tmp.resolve("m.ready");
        Files.writeString(marker, "lake: risklake\nbusiness-date: 2026-11-02\n");
        ReadyNote note = ReadyNote.read(marker);
        assertThat(note.lake()).isEqualTo("risklake");
        assertThat(ReadyNote.KEYS).contains("lake");
        Files.writeString(marker, "lake: Risk Lake\n");
        assertThatThrownBy(() -> ReadyNote.read(marker)).hasMessageContaining("lake Risk Lake").hasMessageContaining("[a-z0-9][a-z0-9_-]*");
        Files.writeString(f.rules, "dataload:\n  rules:\n    - match: \"trades-*\"\n      pack: demo-pack\n      kind: trade\n      lake: rulelake\n");
        Routes routes = Routes.read(f.rules, "demo", f.packs());
        assertThat(routes.route("trades-a.2026-11-02.jsonl", note).lake()).isEqualTo("risklake");
        assertThat(routes.route("trades-a.2026-11-02.jsonl", null).lake()).isEqualTo("rulelake");
        assertThat(routes.route("anything.jsonl", ReadyNote.of("demo-pack", "trade", null, null, null)).lake()).isEqualTo("demo");
        assertThat(routes.preview("trades-a.2026-11-02.jsonl", note)).containsEntry("lake", "risklake");
    }

    @Test
    void aNewLakeIsCreatedAndItsTablesLandInItsOwnFolder() throws Exception {
        Fixture f = new Fixture(tmp);
        f.settings = f.settings.at(f.root.toString(), "demo", f.rules.toString());
        f.drop("trades-a.2026-11-02.jsonl", Fixture.rows("2026-11-02", "A1", "A2"), "lake: risklake\n");
        DataInbox inbox = new DataInbox(f.settings, "node-a", f.clock, Fixture.ZONE, reading(f, Set.of("demo")), new LoaderProcess(f.settings),
                f.signal(), null);
        Cycle.Report r = inbox.cycle("test");
        assertThat(r.files()).as(r.toString()).singleElement().satisfies(o -> assertThat(o.outcome()).isEqualTo("loaded"));
        Path file = lakes.resolve("risklake.yaml");
        assertThat(file).isRegularFile();
        assertThat(Files.readString(file)).contains("lake: risklake", "format: delta", "root: ${drishti.data.dir:./data}/delta/risklake");
        Lake lake = Lakes.readFile(file, k -> k.equals("drishti.data.dir") ? data.toString() : null);
        assertThat(lake.root()).isEqualTo(data + "/delta/risklake");
        assertThat(lake.tables()).containsOnlyKeys("demo.trade");
        assertThat(lake.tables().get("demo.trade").promote()).contains("TRADE_ID", "DESK");
        assertThat(data.resolve("delta/risklake/demo/trade/_delta_log")).isDirectory();
        try (Stream<Path> s = Files.list(data.resolve("delta"))) {
            assertThat(s.map(p -> p.getFileName().toString()).toList()).containsExactly("risklake");
        }
        String warning = Cycle.unreadText("risklake", "demo-pack");
        assertThat(warning).isEqualTo("loaded into risklake, but no connector reads it: point demo-pack's connectors at it (lake: risklake) and restart");
        assertThat(r.notices()).containsExactly(warning);
        JsonNode report = report(f, "trades-a.2026-11-02.jsonl.loaded.json");
        assertThat(report.path("warning").asText()).isEqualTo(warning);
        assertThat(report.path("route").path("lake").asText()).isEqualTo("risklake");

        // a second file for the same lake: the file is kept as it is, the rows appended where the first went
        f.drop("trades-b.2026-11-03.jsonl", Fixture.rows("2026-11-03", "B1"), "lake: risklake\n");
        String text = Files.readString(file);
        Cycle.Report again = inbox.cycle("test");
        assertThat(again.files()).as(again.toString()).singleElement().satisfies(o -> assertThat(o.outcome()).isEqualTo("loaded"));
        assertThat(Files.readString(file)).isEqualTo(text);
    }

    @Test
    void noWarningWhenAConnectorReadsTheLakeOrWhenTheServerCannotSay() throws Exception {
        for (Set<String> read : java.util.Arrays.asList(Set.of("demo", "risklake"), null)) {
            Path base = Files.createDirectories(tmp.resolve("case-" + (read == null ? "unknown" : "read")));
            Fixture f = new Fixture(base);
            f.drop("trades-a.2026-11-02.jsonl", Fixture.rows("2026-11-02", "A1"), "lake: risklake\n");
            Cycle.Report r = new Cycle(f.settings, "node-a", f.clock, Fixture.ZONE, reading(f, read), f.fakeLoader(), f.signal(), () -> null, null)
                    .run("test");
            assertThat(r.files()).as(r.toString()).singleElement().satisfies(o -> assertThat(o.outcome()).isEqualTo("loaded"));
            assertThat(r.notices()).isEmpty();
            assertThat(report(f, "trades-a.2026-11-02.jsonl.loaded.json").has("warning")).isFalse();
        }
    }

    @Test
    void theWarningWithTheStandInLoader() throws Exception {
        Fixture f = new Fixture(tmp);
        f.drop("trades-a.2026-11-02.jsonl", Fixture.rows("2026-11-02", "A1"), "lake: risklake\n");
        f.drop("trades-b.2026-11-02.jsonl", Fixture.rows("2026-11-02", "B1"), "lake: risklake\n");
        Cycle.Report r = new Cycle(f.settings, "node-a", f.clock, Fixture.ZONE, reading(f, Set.of("demo")), f.fakeLoader(), f.signal(), () -> null, null)
                .run("test");
        assertThat(r.files()).hasSize(2);
        assertThat(r.notices()).as("said once a cycle").containsExactly(Cycle.unreadText("risklake", "demo-pack"));
    }

    private static JsonNode report(Fixture f, String name) throws IOException {
        try (Stream<Path> s = Files.walk(f.root.resolve("outbox"))) {
            Path p = s.filter(x -> x.getFileName().toString().equals(name)).findFirst().orElseThrow();
            return new ObjectMapper().readTree(p.toFile());
        }
    }
}
