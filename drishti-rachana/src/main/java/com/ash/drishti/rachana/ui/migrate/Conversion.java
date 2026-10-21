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
package com.ash.drishti.rachana.ui.migrate;

import com.ash.drishti.rachana.ui.UiProblem;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/**
 * One screen converted into a board (docs/architecture/UI_BOARD.md 11.1, phase P5).
 *
 * @param screen     what was converted: {@code sutra:<name>@<v>} (an entity page) or {@code report:<name>/<page>} (a BI report page)
 * @param pack       the pack it belongs to ({@code (bi)} for a report no pack's kinds supply)
 * @param kind       the entity kind of an entity page, else null
 * @param board      the board's name
 * @param document   the board document (null when the screen could not be converted at all)
 * @param status     how it converted
 * @param seeds      the Chip Store chips used ({@code name@N}), in order
 * @param locals     the board-local chips made, each with the reason the panel or visual fits no seed chip
 * @param links      drill-through links kept as navigation (a link to another page is not a trace)
 * @param traces     traces drawn (follow-the-selection: a page control into the panels that read it)
 * @param reason     why it failed (null unless {@link Status#FAILS})
 * @param problems   the board check's findings on the converted board
 */
public record Conversion(String screen, String pack, String kind, String board, ObjectNode document, Status status, List<String> seeds,
        List<Local> locals, int links, int traces, String reason, List<UiProblem> problems) {

    /** How a screen converts: only Chip Store chips (the page header aside), with board-local chips, or not at all. */
    public enum Status {
        CLEAN, LOCAL, FAILS;

        public String text() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * A board-local chip: a Chip Store candidate (14.1 decision 5) unless it is the page header.
     *
     * @param chip   its name in the board's {@code local-chips}
     * @param from   the panel or visual it wraps
     * @param reason why no seed chip fits
     */
    public record Local(String chip, String from, String reason) {}

    public Conversion {
        seeds = List.copyOf(seeds);
        locals = List.copyOf(locals);
        problems = List.copyOf(problems);
    }
}
