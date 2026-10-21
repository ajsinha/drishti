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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The technical manual (datasheet) inside every chip (UI_BOARD.md section 2): fixed keys, read by the renderer and the
 * board check, by people and by agents. A missing key is null (the publish check says which).
 *
 * @param purpose what the chip shows and when to use it
 * @param pins per pin: what it means, what happens when it is empty
 * @param behaviour what the person can do and which out pin each action drives
 * @param defaults what the chip does with no control input
 * @param limits rows, columns, refresh, sizes
 * @param examples wiring examples (board fragments), checked at publish
 * @param notFor what to use instead in common wrong cases
 * @param accessibility keyboard and screen-reader behaviour
 */
public record ChipManual(String purpose, Map<String, String> pins, String behaviour, String defaults, String limits, List<Example> examples,
        String notFor, String accessibility) {

    /** The manual's keys, as written. */
    public static final List<String> KEYS = List.of("purpose", "pins", "behaviour", "defaults", "limits", "examples", "not-for", "accessibility");

    public ChipManual {
        pins = pins == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(pins));
        examples = examples == null ? List.of() : List.copyOf(examples);
    }

    /**
     * One wiring example.
     *
     * @param title what it shows
     * @param board the board fragment (YAML or JSON text: {@code chips:} and {@code traces:}, optionally the rest)
     */
    public record Example(String title, String board) {}
}
