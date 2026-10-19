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

import com.ash.drishti.server.bi.report.Report;
import com.ash.drishti.server.bi.report.ReportParser;
import com.ash.drishti.server.bi.report.ReportParser.Problem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Boards as a report layout (R7, 2.4.0): a 2.3 board converts into a report with board pages that reads back to the same board (tiles, places, alerts,
 * rotation and notifications), the shipped samples are those conversions, and the board checks run on a report's board pages with the report's paths.
 * Run with {@code -Dboards.write=true} to rewrite the shipped samples from the 2.3 boards kept in the test resources.
 */
class BoardsAsReportsTest {

    static final Path OLD = Path.of("src/test/resources/bi/boards-2.3");
    static final Path SHIPPED = Path.of("../config/bi/reports");

    @Test
    void eachTwoThreeBoardConvertsToAReportThatReadsBackToTheSameBoard() throws IOException {
        for (String name : List.of("desk-wall", "python-wall")) {
            String board = Files.readString(OLD.resolve(name + ".board.yaml"));
            String report = BoardConverter.toReport(board, "boards/" + name + ".board.yaml");
            assertThat(report).as(name).isNotNull();
            ReportParser.Result r = ReportParser.parse(report);
            assertThat(r.problems().stream().filter(Problem::error).toList()).as(name).isEmpty();
            Report rep = r.report();
            assertThat(rep.name()).isEqualTo(name);
            assertThat(rep.pages()).allMatch(Report.Page::board);
            assertThat(rep.tilesOnly()).isTrue();
            Board before = BoardParser.parse(board).board();
            Board after = ReportBoards.of(rep).board();
            assertThat(after).as(name).isEqualTo(before);
            assertThat(report).contains("# CONVERTED from the board boards/" + name + ".board.yaml");
            assertThat(report.lines().filter(l -> l.startsWith("#")).count()).as("the comments at the top are kept").isGreaterThan(8);
            Path shipped = SHIPPED.resolve(name + ".report.yaml");
            if (Boolean.getBoolean("boards.write")) {
                Files.writeString(shipped, report);
            }
            assertThat(Files.readString(shipped)).as("config/bi/reports/" + name + ".report.yaml is the conversion (rewrite with -Dboards.write=true)").isEqualTo(report);
        }
    }

    @Test
    void aReportIsNoBoardAndConvertingIsIdempotent() throws IOException {
        String report = Files.readString(SHIPPED.resolve("desk-wall.report.yaml"));
        assertThat(BoardConverter.isBoard(report)).isFalse();
        assertThat(BoardConverter.toReport(report, "x")).isNull();
        assertThat(BoardConverter.isBoard(Files.readString(OLD.resolve("desk-wall.board.yaml")))).isTrue();
        assertThat(ReportBoards.isBoard("report", report)).isTrue();
        assertThat(ReportBoards.boardOfText(report).pages()).hasSize(2);
    }

    @Test
    void theBoardChecksRunOnAReportsBoardPagesAtTheReportsPaths() {
        String yaml = """
                report: wall
                board: { rotate: 1s }
                pages:
                  - id: p
                    layout: board
                    visuals:
                      - { id: a, type: text, text: Hello, at: { x: 0, y: 0, w: 4, h: 1 } }
                      - { id: t, type: tile, from: { report: r, visual: v }, at: { x: 0, y: 1, w: 4, h: 3 }, alert: { on: MTM, warn: { op: lt, value: x } } }
                  - id: q
                    visuals:
                      - { id: u, type: tile, from: { report: r, visual: v }, at: { x: 0, y: 0, w: 4, h: 3 } }
                """;
        List<Problem> errors = ReportParser.parse(yaml).problems().stream().filter(Problem::error).toList();
        assertThat(errors).extracting(Problem::path).contains("board.rotate", "pages[0].visuals[1].alert.warn", "pages[1].visuals[0].type");
        assertThat(errors).noneMatch(p -> p.path().equals("dataset"));                // board pages of tiles read no data of their own
    }

    @Test
    void aTileNeedsFromAndOnlyATileTakesAnAlert() {
        String yaml = """
                report: wall
                dataset: trades-basic
                pages:
                  - id: p
                    layout: board
                    visuals:
                      - { id: t, type: tile, at: { x: 0, y: 0, w: 4, h: 3 } }
                      - { id: c, type: card, at: { x: 4, y: 0, w: 4, h: 3 }, wells: { values: [MTM] }, alert: { warn: { op: lt, value: 0 } } }
                """;
        List<Problem> all = ReportParser.parse(yaml).problems();
        assertThat(all).anyMatch(p -> p.error() && p.path().equals("pages[0].visuals[0].from"));
        assertThat(all).anyMatch(p -> !p.error() && p.path().equals("pages[0].visuals[1].alert"));
    }
}
