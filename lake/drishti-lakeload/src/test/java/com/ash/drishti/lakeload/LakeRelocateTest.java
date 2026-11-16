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
package com.ash.drishti.lakeload;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.lakeconfig.Lakes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The one-time move of a lake built in the older layout ({@code <data>/delta/<domain>/<table>}) into its own folder
 * ({@code <data>/delta/<lake>/}): the known folders moved by a rename and every table measured the same after; another lake's folders left
 * where they are; refused, with nothing moved, when the target exists, when anything is unknown, for an Iceberg table, for a missing data
 * file or while the lake's lock is held; moved back on an error during the move or the check; recorded once. Synthetic tables in a
 * temporary folder only.
 */
class LakeRelocateTest {

    @TempDir
    Path tmp;
    Path delta;

    @BeforeEach
    void olderLayout() throws Exception {
        delta = tmp.resolve("data/delta");
        Path in = PvData.byDate(tmp.resolve("in"), PvData.lines(120, 8, 2, 7));
        JsonNode s = LakeLoadTest.load(tmp, PvData.config(in.toString(), delta.toString(), "delta"));
        assertThat(s.path("exit").asInt()).as(s.path("said").asText() + s.path("error").asText()).isZero();
        JsonNode t = LakeLoadTest.load(tmp, PvData.config(in.toString(), delta.toString(), "delta").replace("domain: risk", "domain: trading")
                .replace("kind: scenario-pv", "kind: trade"));
        assertThat(t.path("exit").asInt()).as(t.path("said").asText() + t.path("error").asText()).isZero();
        Files.createDirectories(delta.resolve("risk-results/var-official/_delta_log"));          // another lake's table at the same place
        Files.createDirectories(delta.resolve(LakeState.STATE));
        Files.writeString(delta.resolve(LakeState.STATE).resolve("runs.log"), "a run\n");
    }

    private Lakes lakes(String demoRoot) {
        return Lakes.of(Lakes.parse("lake: demo\nformat: delta\nroot: '" + demoRoot + "'\ntables:\n  risk.scenario-pv: {}\n  trading.trade: {}\n",
                "demo", k -> null), Lakes.parse("lake: var-official\nroot: '" + delta + "'\ntables:\n  risk-results.var-official: {}\n",
                "var-official", k -> null));
    }

    private Lakes lakes() {
        return lakes(delta.resolve("demo").toString());
    }

    private static List<String> names(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void theKnownFoldersMoveByRenameAndEveryTableMeasuresTheSame() throws Exception {
        LakeRelocate.Measure risk = LakeRelocate.measure(delta.resolve("risk/scenario-pv"));
        assertThat(risk.rows()).isEqualTo(120);
        LakeRelocate.Outcome o = LakeRelocate.relocate(lakes(), "demo", false);
        assertThat(o.state()).as(o.message()).isEqualTo("moved");
        assertThat(o.folders()).containsExactly("risk", "trading", LakeState.STATE);
        assertThat(names(delta)).as("another lake's folder stays; the demo lake in its own").containsExactly("demo", "risk-results");
        assertThat(names(delta.resolve("demo"))).containsExactly(LakeState.STATE, "risk", "trading");
        assertThat(LakeRelocate.measure(delta.resolve("demo/risk/scenario-pv"))).isEqualTo(risk);
        assertThat(o.tables()).containsOnlyKeys("risk/scenario-pv", "trading/trade");
        assertThat(Files.readString(delta.resolve("demo/_drishti-lake/runs.log"))).isEqualTo("a run\n");
        JsonNode rec = new ObjectMapper().readTree(delta.resolve("demo/_drishti-lake/" + LakeRelocate.RECORD).toFile());
        assertThat(rec.path("from").asText()).isEqualTo(delta.toAbsolutePath().normalize().toString());
        assertThat(rec.path("tables").path("risk/scenario-pv").path("rows").asLong()).isEqualTo(120);
        // a load into the moved lake works as before: the next run reads it where it is now
        Path more = PvData.byDate(tmp.resolve("in2"), PvData.lines(30, 8, 1, 9));
        JsonNode s = LakeLoadTest.load(tmp, PvData.config(more.toString(), delta.resolve("demo").toString(), "delta"));
        assertThat(s.path("exit").asInt()).as(s.path("said").asText() + s.path("error").asText()).isZero();
        assertThat(LakeRelocate.relocate(lakes(), "demo", false).state()).as("it runs once").isEqualTo("done-before");
    }

    @Test
    void aDryRunMeasuresAndMovesNothing() throws Exception {
        List<String> before = names(delta);
        LakeRelocate.Outcome o = LakeRelocate.relocate(lakes(), "demo", true);
        assertThat(o.state()).as(o.message()).isEqualTo("would-move");
        assertThat(o.tables()).hasSize(2);
        assertThat(names(delta)).isEqualTo(before);
    }

    @Test
    void anythingUnknownIsRefusedAndNothingMoves() throws Exception {
        Files.createDirectories(delta.resolve("my-own-tables/x"));
        List<String> before = names(delta);
        LakeRelocate.Outcome o = LakeRelocate.relocate(lakes(), "demo", false);
        assertThat(o.state()).isEqualTo("refused");
        assertThat(o.message()).contains("my-own-tables").contains("belong to no lake").contains("nothing was moved");
        assertThat(names(delta)).isEqualTo(before);
    }

    @Test
    void anExistingTargetIsRefused() throws Exception {
        Files.createDirectories(delta.resolve("demo/trading"));                                   // a target that holds something
        LakeRelocate.Outcome o = LakeRelocate.relocate(lakes(), "demo", false);
        assertThat(o.state()).isEqualTo("refused");
        assertThat(o.message()).contains("exists already");
        assertThat(names(delta)).contains("risk", "trading");
        assertThat(names(delta.resolve("demo"))).containsExactly("trading");
    }

    @Test
    void lakeFoldersKeptInGitWithOnlyTheirReadmeAreAsGoodAsAbsent() throws Exception {
        Files.createDirectories(delta.resolve("demo"));
        Files.writeString(delta.resolve("demo/README.md"), "the demo lake\n");                   // as committed
        Files.createDirectories(delta.resolve("risklake"));
        Files.writeString(delta.resolve("risklake/README.md"), "the risklake lake\n");           // another lake's empty folder
        LakeRelocate.Outcome o = LakeRelocate.relocate(lakes(), "demo", false);
        assertThat(o.state()).as(o.message()).isEqualTo("moved");
        assertThat(names(delta)).containsExactly("demo", "risk-results", "risklake");
        assertThat(names(delta.resolve("demo"))).containsExactly("README.md", LakeState.STATE, "risk", "trading");
        assertThat(names(delta.resolve("risklake"))).containsExactly("README.md");
    }

    @Test
    void anIcebergTableOrAMissingDataFileIsRefused() throws Exception {
        Path ice = Files.createDirectories(delta.resolve("trading/trade_calypso/metadata"));
        Files.writeString(ice.resolve("v1.metadata.json"), "{}");
        LakeRelocate.Outcome o = LakeRelocate.relocate(lakes(), "demo", false);
        assertThat(o.state()).isEqualTo("refused");
        assertThat(o.message()).contains("Iceberg");
        Files.delete(ice.resolve("v1.metadata.json"));
        Files.delete(ice);
        Files.delete(ice.getParent());
        Path parquet;
        try (Stream<Path> s = Files.walk(delta.resolve("trading/trade"))) {
            parquet = s.filter(p -> p.toString().endsWith(".parquet")).findFirst().orElseThrow();
        }
        Files.move(parquet, tmp.resolve("aside.parquet"));
        o = LakeRelocate.relocate(lakes(), "demo", false);
        assertThat(o.state()).isEqualTo("refused");
        assertThat(o.message()).contains("trading/trade").contains("not there");
        assertThat(Files.exists(delta.resolve("demo"))).isFalse();
    }

    @Test
    void aHeldLockIsRefused() throws Exception {
        Path lock = delta.resolve(LakeState.STATE).resolve("lock");
        Files.writeString(lock, "a build\n");
        try (FileChannel ch = FileChannel.open(lock, StandardOpenOption.WRITE); FileLock held = ch.lock()) {
            LakeRelocate.Outcome o = LakeRelocate.relocate(lakes(), "demo", false);
            assertThat(o.state()).isEqualTo("refused");
            assertThat(o.message()).contains("holds the lake's lock");
            assertThat(held.isValid()).isTrue();
        }
        assertThat(names(delta)).contains("risk", "trading").doesNotContain("demo");
    }

    @Test
    void anErrorDuringTheMoveOrTheCheckMovesEverythingBack() throws Exception {
        LakeRelocate.Measure risk = LakeRelocate.measure(delta.resolve("risk/scenario-pv"));
        List<String> before = names(delta);
        for (String step : List.of("moved 1", "moved 2", "verify", "record")) {
            LakeRelocate.Outcome o = LakeRelocate.relocate(lakes(), "demo", false, s -> {
                if (s.equals(step)) {
                    throw new IOException("a fault at " + step);
                }
            });
            assertThat(o.state()).as(step + ": " + o.message()).isEqualTo("rolled-back");
            assertThat(o.message()).contains("a fault at " + step).contains("nothing was deleted");
            assertThat(names(delta)).as(step).isEqualTo(before);
            assertThat(LakeRelocate.measure(delta.resolve("risk/scenario-pv"))).isEqualTo(risk);
            assertThat(Files.readString(delta.resolve(LakeState.STATE).resolve("runs.log"))).isEqualTo("a run\n");
        }
        assertThat(LakeRelocate.relocate(lakes(), "demo", false).state()).as("and it moves after").isEqualTo("moved");
    }

    @Test
    void aLakeWithItsOwnRootOrNothingAtTheOldPlaceIsLeftAlone() throws Exception {
        assertThat(LakeRelocate.relocate(lakes(delta.toString()), "demo", false).state()).as("DRISHTI_DELTA_ROOT names the root").isEqualTo("nothing");
        Path fresh = tmp.resolve("fresh/delta/demo");
        LakeRelocate.Outcome o = LakeRelocate.relocate(Lakes.of(Lakes.parse("lake: demo\nroot: '" + fresh + "'\ntables:\n  risk.scenario-pv: {}\n",
                "demo", k -> null)), "demo", false);
        assertThat(o.state()).isEqualTo("nothing");
        assertThat(Files.exists(fresh)).isFalse();
    }

    @Test
    void theCommandLineSaysWhatItDidAsJson() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("lakes"));
        Files.writeString(dir.resolve("demo.yaml"), "lake: demo\nroot: '" + delta.resolve("demo") + "'\ntables:\n  risk.scenario-pv: {}\n  trading.trade: {}\n");
        Files.writeString(dir.resolve("var-official.yaml"), "lake: var-official\nroot: '" + delta + "'\ntables:\n  risk-results.var-official: {}\n");
        System.setProperty(Lakes.PROPERTY, dir.toString());
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int code = LakeRelocate.run(new String[] {"--lake", "demo", "--dry-run"}, new PrintStream(out, true, StandardCharsets.UTF_8));
            JsonNode j = new ObjectMapper().readTree(out.toString(StandardCharsets.UTF_8));
            assertThat(code).isZero();
            assertThat(j.path("state").asText()).isEqualTo("would-move");
            assertThat(j.path("tables").size()).isEqualTo(2);
            Files.createDirectories(delta.resolve("stray"));
            out.reset();
            assertThat(LakeRelocate.run(new String[] {"--lake", "demo"}, new PrintStream(out, true, StandardCharsets.UTF_8))).isEqualTo(1);
            assertThat(new ObjectMapper().readTree(out.toString(StandardCharsets.UTF_8)).path("state").asText()).isEqualTo("refused");
        } finally {
            System.clearProperty(Lakes.PROPERTY);
        }
    }
}
