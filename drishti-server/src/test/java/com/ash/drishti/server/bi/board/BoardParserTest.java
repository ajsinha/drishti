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
package com.ash.drishti.server.bi.board;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.server.bi.report.ReportParser.Problem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The board parser (docs/guides/BOARDS.md): the shipped sample is clean, and every kind of mistake comes back with its DRS code and the path of the key. */
class BoardParserTest {

    static final String HEAD = "board: wall\npages:\n  - id: p\n    tiles:\n";

    static List<Problem> problems(String yaml) {
        return BoardParser.parse(yaml).problems();
    }

    static List<Problem> errors(String yaml) {
        return problems(yaml).stream().filter(Problem::error).toList();
    }

    @Test
    void theSampleBoardIsCleanAndKeepsItsModel() throws IOException {
        BoardParser.Result r = BoardParser.parse(Files.readString(Path.of("../config/bi/boards/desk-wall.board.yaml")));
        assertThat(r.ok()).as(r.firstError()).isTrue();
        Board b = r.board();
        assertThat(b.name()).isEqualTo("desk-wall");
        assertThat(b.rotateSeconds()).isEqualTo(20);
        assertThat(b.pages()).hasSize(2);
        Board.Tile t = b.pages().get(0).tiles().get(0);
        assertThat(t.report()).isEqualTo("live-trading-desk");
        assertThat(t.alert().warn().op()).isEqualTo("lt");
        assertThat(t.alert().crit().value()).isLessThan(t.alert().warn().value());
        assertThat(b.toMap().get("rotate")).isEqualTo("20s");
    }

    @Test
    void aMinimalBoardTakesItsDefaults() {
        BoardParser.Result r = BoardParser.parse(HEAD + "      - { report: r, visual: v, at: { x: 0, y: 0, w: 4, h: 3 } }\n");
        assertThat(r.ok()).isTrue();
        assertThat(r.board().title()).isEqualTo("wall");
        assertThat(r.board().rotateSeconds()).isNull();
        assertThat(r.board().pages().get(0).tiles().get(0).id()).isEqualTo("t1");
    }

    @Test
    void structureMistakesAreDrs9052WithPaths() {
        assertThat(errors("")).anyMatch(p -> p.code().equals("DRS-9052"));
        assertThat(errors("pages: []")).anyMatch(p -> p.path().equals("board") || p.path().equals("pages"));
        assertThat(errors("board: wall\npages:\n  - id: p\n    tiles: []\n")).anyMatch(p -> p.path().endsWith("tiles"));
        assertThat(errors(HEAD + "      - { visual: v, at: { x: 0, y: 0, w: 4, h: 3 } }\n")).anyMatch(p -> p.path().endsWith("tiles[0].report"));
        assertThat(errors(HEAD + "      - { report: r, at: { x: 0, y: 0, w: 4, h: 3 } }\n")).anyMatch(p -> p.path().endsWith("tiles[0].visual"));
        assertThat(errors(HEAD + "      - { report: r, visual: v, at: { x: 9, y: 0, w: 6, h: 3 } }\n")).anyMatch(p -> p.path().endsWith("at") && p.message().contains("x + w"));
        assertThat(errors(HEAD + "      - { id: a, report: r, visual: v, at: { x: 0, y: 0, w: 4, h: 3 } }\n      - { id: a, report: r, visual: v, at: { x: 4, y: 0, w: 4, h: 3 } }\n"))
                .anyMatch(p -> p.message().contains("used twice"));
        assertThat(errors("board: wall\nrotate: 1s\npages:\n  - id: p\n    tiles:\n      - { report: r, visual: v, at: { x: 0, y: 0, w: 4, h: 3 } }\n")).anyMatch(p -> p.path().equals("rotate"));
    }

    @Test
    void alertsNeedAThresholdAndAKnownComparison() {
        String tile = HEAD + "      - { report: r, visual: v, at: { x: 0, y: 0, w: 4, h: 3 }, alert: %s }\n";
        assertThat(errors(tile.formatted("{ on: MTM }"))).anyMatch(p -> p.message().contains("warn or a crit"));
        assertThat(errors(tile.formatted("{ warn: { op: near, value: 1 } }"))).anyMatch(p -> p.path().endsWith("alert.warn"));
        assertThat(errors(tile.formatted("{ warn: { op: lt, value: soon } }"))).anyMatch(p -> p.path().endsWith("alert.warn"));
        assertThat(errors(tile.formatted("{ on: MTM, warn: { op: lt, value: 0 }, crit: { op: lt, value: -5 } }"))).isEmpty();
    }

    @Test
    void notificationsAreReservedAndNotBuiltDrs9053() {
        assertThat(errors("board: wall\nnotify: { email: a@b.c }\npages:\n  - id: p\n    tiles:\n      - { report: r, visual: v, at: { x: 0, y: 0, w: 4, h: 3 } }\n"))
                .anyMatch(p -> p.code().equals("DRS-9053") && p.path().equals("notify"));
        assertThat(errors(HEAD + "      - { report: r, visual: v, at: { x: 0, y: 0, w: 4, h: 3 }, alert: { warn: { op: lt, value: 0 }, notify: { slack: x } } }\n"))
                .anyMatch(p -> p.code().equals("DRS-9053") && p.path().endsWith("alert.notify"));
    }

    @Test
    void unknownKeysAreWarningsNotErrors() {
        List<Problem> all = problems("board: wall\ncolour: red\npages:\n  - id: p\n    tiles:\n      - { report: r, visual: v, size: big, at: { x: 0, y: 0, w: 4, h: 3 } }\n");
        assertThat(all.stream().filter(Problem::error)).isEmpty();
        assertThat(all).anyMatch(p -> p.level().equals("warning") && p.path().equals("colour")).anyMatch(p -> p.level().equals("warning") && p.path().endsWith("size"));
    }
}
