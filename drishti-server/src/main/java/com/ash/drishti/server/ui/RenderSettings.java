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
package com.ash.drishti.server.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * The UI Board's render settings (docs/architecture/UI_BOARD.md section 7.3): the {@code render:} section of {@code config/ui/ui.yaml}, the same file as
 * {@link UiSettings}, whose {@code limits:} give the board's bytes, chips and traces. Read once at start; a missing key keeps the default.
 *
 * @param bytes            a board document's size limit (DRS-2075; {@code limits.board-bytes})
 * @param chips            chip instances per board (DRS-2075; {@code limits.chips})
 * @param traces           traces per board (DRS-2075; {@code limits.traces})
 * @param moduleDepth      module nesting depth (DRS-2075)
 * @param rowsPerChip      rows painted per chip; the rest are said to be truncated
 * @param chipBudget       a chip's first-paint budget (DRS-2077 past it for a non-BI supplier; a BI question becomes a run card)
 * @param boardBudget      the whole board's budget to {@code done}; chips not painted by then report DRS-2077
 * @param dynamicPerMinute dynamic (unsaved) renders per person a minute (DRS-2076)
 * @param dynamicAtOnce    dynamic renders per person painting at once (DRS-2076)
 * @param sessions         open render sessions per person; past it the oldest is closed (DRS-2079 on it)
 * @param idle             a render session ends after this long without a pin change
 * @param reuse            how long a model answer is shared by access profile (0: never)
 * @param keepDynamic      how long a dynamic document is kept by its hash for an auditor (0: not kept)
 */
public record RenderSettings(int bytes, int chips, int traces, int moduleDepth, int rowsPerChip, Duration chipBudget, Duration boardBudget,
        int dynamicPerMinute, int dynamicAtOnce, int sessions, Duration idle, Duration reuse, Duration keepDynamic) {

    /** The shipped defaults (UI_BOARD.md 7.3, 14.1 decision 9). */
    public static RenderSettings defaults() {
        return new RenderSettings(64 * 1024, 40, 120, 3, 10_000, Duration.ofSeconds(10), Duration.ofSeconds(30), 30, 4, 8, Duration.ofMinutes(10),
                Duration.ofSeconds(30), Duration.ofDays(7));
    }

    /** The limits the static board check enforces. */
    public com.ash.drishti.rachana.ui.BoardCheckContext.Limits checkLimits() {
        return new com.ash.drishti.rachana.ui.BoardCheckContext.Limits(bytes, chips, traces, moduleDepth);
    }

    /** Reads the {@code render:} section of {@code <dir>/ui.yaml} with the board limits of {@code ui}; the defaults where absent. */
    public static RenderSettings load(Path dir, UiSettings ui) {
        Path file = dir == null ? null : dir.resolve("ui.yaml");
        RenderSettings r = defaults();
        if (file != null && Files.isRegularFile(file)) {
            try {
                r = from(new ObjectMapper(new YAMLFactory()).readTree(file.toFile()).path("render"));
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read the UI settings " + file + ": " + e.getMessage(), e);
            }
        }
        return ui == null ? r : new RenderSettings(ui.boardBytes(), ui.chips(), ui.traces(), r.moduleDepth, r.rowsPerChip, r.chipBudget, r.boardBudget,
                r.dynamicPerMinute, r.dynamicAtOnce, r.sessions, r.idle, r.reuse, r.keepDynamic);
    }

    static RenderSettings from(JsonNode l) {
        RenderSettings d = defaults();
        return new RenderSettings(d.bytes, d.chips, d.traces, l.path("module-depth").asInt(d.moduleDepth), l.path("rows-per-chip").asInt(d.rowsPerChip),
                duration(l.path("chip-budget"), d.chipBudget), duration(l.path("board-budget"), d.boardBudget),
                l.path("dynamic-per-minute").asInt(d.dynamicPerMinute), l.path("dynamic-at-once").asInt(d.dynamicAtOnce),
                l.path("sessions-per-person").asInt(d.sessions), duration(l.path("session-idle"), d.idle),
                duration(l.path("reuse"), d.reuse), duration(l.path("keep-dynamic"), d.keepDynamic));
    }

    /** With other per-person limits (tests). */
    public RenderSettings with(int dynamicPerMinute, int dynamicAtOnce, int sessions, int chips) {
        return new RenderSettings(bytes, chips, traces, moduleDepth, rowsPerChip, chipBudget, boardBudget, dynamicPerMinute, dynamicAtOnce, sessions,
                idle, reuse, keepDynamic);
    }

    private static Duration duration(JsonNode n, Duration dflt) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return dflt;
        }
        if (n.isNumber()) {
            return Duration.ofSeconds(n.asLong());
        }
        String t = n.asText().trim().toLowerCase(java.util.Locale.ROOT);
        long v = Long.parseLong(t.replaceAll("[a-z]+$", ""));
        return t.endsWith("ms") ? Duration.ofMillis(v) : t.endsWith("m") ? Duration.ofMinutes(v) : t.endsWith("h") ? Duration.ofHours(v)
                : t.endsWith("d") ? Duration.ofDays(v) : Duration.ofSeconds(v);
    }
}
