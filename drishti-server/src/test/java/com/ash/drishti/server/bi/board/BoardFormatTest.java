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

import com.ash.drishti.server.bi.report.ReportYaml;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** The board designer's YAML tab: a board model written by the server's writer is read back to the same board, for every shipped sample. */
class BoardFormatTest {

    @Test
    void everyShippedBoardSurvivesAWriteAndARead() throws IOException {
        Path folder = Path.of("src/test/resources/bi/boards-2.3");                         // the 2.3 boards (config/bi has them as reports with board pages)
        try (var files = Files.list(folder)) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".board.yaml")).toList()) {
                BoardParser.Result first = BoardParser.parse(Files.readString(f));
                assertThat(first.ok()).as(f.getFileName() + ": " + first.firstError()).isTrue();
                String written = ReportYaml.write(first.board().toMap());
                BoardParser.Result again = BoardParser.parse(written);
                assertThat(again.ok()).as(f.getFileName() + " written back: " + again.firstError() + "\n" + written).isTrue();
                assertThat(again.board().toMap()).as(f.getFileName().toString()).isEqualTo(first.board().toMap());
            }
        }
    }

    @Test
    void aTileWithAnAlertAndAHysteresisIsOneReadableLine() {
        String yaml = "board: b\ntitle: B\nrotate: 20s\npages:\n  - id: p\n    title: P\n    tiles:\n      - { id: t, report: r, visual: v, at: { x: 0, y: 0, w: 3, h: 3 },\n"
                + "          alert: { on: MTM, warn: { op: lt, value: 100 }, crit: { op: lt, value: 50 }, hysteresis: 10 } }\n";
        BoardParser.Result r = BoardParser.parse(yaml);
        String written = ReportYaml.write(r.board().toMap());
        assertThat(written).contains("rotate: \"20s\"").contains("hysteresis: 10").contains("value: 100");
        assertThat(written.lines().filter(l -> l.contains("report: r")).count()).isEqualTo(1);
        assertThat(BoardParser.parse(written).board().toMap()).isEqualTo(r.board().toMap());
    }
}
