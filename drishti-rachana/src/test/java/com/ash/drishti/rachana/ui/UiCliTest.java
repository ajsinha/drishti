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
package com.ash.drishti.rachana.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code ui check}: the board check on the command line, answering by rule, path and fix. */
class UiCliTest {

    @TempDir
    Path dir;

    private Path chips;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void files() throws Exception {
        chips = Files.createDirectories(dir.resolve("chips"));
        for (String c : List.of("filterable-table.v2.chip.yaml", "entity-card.v1.chip.yaml", "cashflow-ladder.v1.chip.yaml")) {
            Files.writeString(chips.resolve(c), UiFixtures.text(c));
        }
        for (String f : List.of("book-trades.board.yaml", "book-trades.board.json", "suppliers.yaml")) {
            Files.writeString(dir.resolve(f), UiFixtures.text(f));
        }
    }

    private int run(String... args) {
        List<String> a = new ArrayList<>(List.of("ui", "check"));
        a.addAll(List.of(args));
        return UiCli.run(a.toArray(String[]::new), new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void aGoodBoardExitsZeroAndListsItsWarnings() {
        assertThat(run(dir.resolve("book-trades.board.yaml").toString(), dir.resolve("book-trades.board.json").toString(), "--chips", chips.toString(),
                "--suppliers", dir.resolve("suppliers.yaml").toString())).isZero();
        assertThat(out()).contains("board book-trades: 0 error(s), 1 warning(s)").contains("BC-W5 warning chips.trades.map.rows.measures: MTM_USD is masked");
    }

    @Test
    void aBrokenBoardExitsOneWithRulePathAndFix() throws Exception {
        Path bad = dir.resolve("bad.yaml");
        Files.writeString(bad, UiFixtures.text("book-trades.board.yaml").replace("to:   ticket.id", "to:   ticket.ids"));
        assertThat(run(bad.toString(), "--chips", chips.toString())).isEqualTo(UiCli.PROBLEMS);
        assertThat(out()).contains("BC-06 error traces[0].to: entity-card@1 has no in pin 'ids'").contains("fix: in pins: [id, doc]");
    }

    @Test
    void json() throws Exception {
        Path bad = dir.resolve("bad.yaml");
        Files.writeString(bad, UiFixtures.text("book-trades.board.yaml").replace("filterable-table@2", "filterable-table"));
        assertThat(run(bad.toString(), "--chips", chips.toString(), "--json")).isEqualTo(UiCli.PROBLEMS);
        JsonNode j = new ObjectMapper().readTree(out());
        assertThat(j.path("ok").asBoolean()).isFalse();
        JsonNode p = j.path("files").get(0).path("problems").get(0);
        assertThat(p.path("rule").asText()).isEqualTo("BC-02");
        assertThat(p.path("path").asText()).isEqualTo("chips.trades.use");
        assertThat(p.path("fix").asText()).isEqualTo("write filterable-table@<version>");
    }

    @Test
    void aChipDocumentGetsThePublishChecks() {
        assertThat(run(chips.resolve("filterable-table.v2.chip.yaml").toString(), "--chips", chips.toString())).isZero();
        assertThat(out()).contains("chip filterable-table: 0 error(s)");
    }

    @Test
    void usageErrorsExitTwo() {
        assertThat(UiCli.run(new String[] {"ui"}, new PrintStream(out), new PrintStream(err))).isEqualTo(UiCli.USAGE);
        assertThat(run()).isEqualTo(UiCli.USAGE);
        assertThat(run("x.yaml", "--bogus")).isEqualTo(UiCli.USAGE);
        assertThat(run(dir.resolve("missing.yaml").toString())).isEqualTo(UiCli.USAGE);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("usage: ui check");
    }
}
