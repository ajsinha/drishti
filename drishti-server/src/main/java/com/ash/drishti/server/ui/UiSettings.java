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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The UI Board settings, {@code config/ui/ui.yaml} (its folder named by {@code drishti.ui.dir}). A missing file or key means the default.
 *
 * @param boardBytes      the largest board document (bytes)
 * @param chips           chip instances per board
 * @param traces          traces per board
 * @param boardsPerPerson saved personal boards per person
 * @param revisions       earlier saves kept per board
 * @param certifiedOnly   workspaces whose shared boards may use certified chips only
 * @param chipsDir        the seed chips' folder ({@code <dir>/chips})
 */
public record UiSettings(int boardBytes, int chips, int traces, int boardsPerPerson, int revisions, List<String> certifiedOnly, Path chipsDir) {

    public static UiSettings defaults(Path chipsDir) {
        return new UiSettings(65536, 40, 120, 500, 50, List.of(), chipsDir);
    }

    /** Reads {@code <dir>/ui.yaml}; the seed chips are {@code <dir>/chips}. */
    public static UiSettings load(Path dir) {
        UiSettings d = defaults(dir.resolve("chips"));
        Path f = dir.resolve("ui.yaml");
        if (!Files.isRegularFile(f)) {
            return d;
        }
        try {
            Map<String, Object> m = UiDocs.read(Files.readString(f));
            Map<String, Object> lim = UiDocs.map(m.get("limits"));
            List<String> certified = new ArrayList<>();
            UiDocs.map(m.get("workspaces")).forEach((ws, v) -> {
                if (Boolean.TRUE.equals(UiDocs.map(v).get("certified-only"))) {
                    certified.add(ws);
                }
            });
            return new UiSettings(num(lim, "board-bytes", d.boardBytes), num(lim, "chips", d.chips), num(lim, "traces", d.traces),
                    num(lim, "boards-per-person", d.boardsPerPerson), num(lim, "revisions", d.revisions), List.copyOf(certified), d.chipsDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int num(Map<String, Object> m, String k, int dflt) {
        Object v = m.get(k);
        return v instanceof Number n && n.intValue() > 0 ? n.intValue() : dflt;
    }
}
