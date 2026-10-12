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
package com.ash.drishti.server.alerts.measure;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.server.bi.board.Board;
import com.ash.drishti.server.bi.board.BoardParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A board tile with a hysteresis keeps its level while the value hovers at a threshold: it drops only when the value is back inside by the hysteresis. The same
 * rule as the viewer's (test_tile_hysteresis.py) and as a measure alert re-arming (AlertLogic).
 */
class TileLevelHysteresisTest {

    static final Board.Alert ALERT = new Board.Alert("MTM", new Board.Rule("lt", 100), new Board.Rule("lt", 50), null, 10);

    @Test
    void theLevelDropsOnlyWhenTheValueIsBackInsideByTheHysteresis() {
        assertThat(TileLevel.rank(ALERT, List.of(49.0), 0)).isEqualTo(2);
        assertThat(TileLevel.rank(ALERT, List.of(55.0), 2)).as("above 50 but not by 10: still red").isEqualTo(2);
        assertThat(TileLevel.rank(ALERT, List.of(60.0), 2)).as("10 back inside: red clears to amber").isEqualTo(1);
        assertThat(TileLevel.rank(ALERT, List.of(105.0), 1)).as("above 100 but within 10: still amber").isEqualTo(1);
        assertThat(TileLevel.rank(ALERT, List.of(110.0), 1)).isEqualTo(0);
        assertThat(TileLevel.rank(ALERT, List.of(105.0), 0)).as("a normal tile does not turn amber by hovering").isEqualTo(0);
        assertThat(TileLevel.rank(ALERT, List.of(30.0), 0)).as("never lower than the value says").isEqualTo(2);
        assertThat(TileLevel.rank(ALERT, List.of(120.0), 2)).isEqualTo(0);
    }

    @Test
    void withNoHysteresisThePreviousLevelChangesNothing() {
        Board.Alert plain = new Board.Alert("MTM", new Board.Rule("lt", 100), new Board.Rule("lt", 50));
        for (double previous : new double[] {0, 1, 2}) {
            assertThat(TileLevel.rank(plain, List.of(55.0), previous)).isEqualTo(1);
        }
    }

    @Test
    void theNotificationStateMachineClearsOnlyOnTheRelaxedLevel() {
        AlertSpec spec = AlertLogicTest.tile("crit");
        AlertLogic.State active = AlertLogic.State.FRESH.with(true, java.time.Instant.parse("2026-10-12T10:00:00Z"), 2.0, null, "");
        java.time.Instant at = java.time.Instant.parse("2026-10-12T10:05:00Z");
        // the value is 55: strictly amber (1), but red is held within the hysteresis (relaxed 2): the alert stays active
        assertThat(AlertLogic.decide(spec, AlertLogic.Reading.tile(1, 2, null), null, active, at, false).action()).isEqualTo(AlertLogic.Action.NONE);
        // the value is 60: amber, and red no longer holds: it clears
        assertThat(AlertLogic.decide(spec, AlertLogic.Reading.tile(1, 1, null), null, active, at, false).action()).isEqualTo(AlertLogic.Action.CLEAR);
    }

    @Test
    void theBoardParserReadsAndWritesTheHysteresisAndRefusesANegativeOne() {
        String yaml = "board: b\npages:\n  - id: p\n    tiles:\n      - { id: t, report: r, visual: v, at: { x: 0, y: 0, w: 3, h: 3 },\n"
                + "          alert: { on: MTM, warn: { op: lt, value: 100 }, crit: { op: lt, value: 50 }, hysteresis: 10 } }\n";
        BoardParser.Result ok = BoardParser.parse(yaml);
        assertThat(ok.ok()).as(ok.firstError()).isTrue();
        Board.Alert a = ok.board().pages().get(0).tiles().get(0).alert();
        assertThat(a.hysteresis()).isEqualTo(10.0);
        assertThat(a.toMap()).containsEntry("hysteresis", 10.0);
        assertThat(BoardParser.parse(yaml.replace("hysteresis: 10", "hysteresis: -1")).ok()).isFalse();
        assertThat(BoardParser.parse(yaml.replace(", hysteresis: 10", "")).board().pages().get(0).tiles().get(0).alert().toMap()).doesNotContainKey("hysteresis");
    }
}
