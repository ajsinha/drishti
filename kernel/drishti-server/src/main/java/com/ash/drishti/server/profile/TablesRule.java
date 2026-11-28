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
package com.ash.drishti.server.profile;

import com.ash.lake.config.Lake;
import com.ash.lake.config.LakeTable;
import com.ash.lake.config.Lakes;
import java.util.ArrayList;
import java.util.List;

/**
 * Production rule T1 (docs/admin/TABLE_CATALOGUE.md, "Binding a bank's external lake"): every table the server reads is resolved explicitly.
 * A table with no {@code uri:} of its own sits where the lake's layout puts it ({@code <root>/<domain>/<table>}), a fallback for development
 * and the demo; a bank's tables are bound by their lake file's {@code uri:}. One line per lake that still has fallbacks, naming them and the
 * fix, in the form {@code ProductionProfile.refusals} lists (a catalogue-bound lake, whose catalog says where its tables are, has none).
 */
public final class TablesRule {

    /** The longest list of table names in one line. */
    static final int SHOWN = 6;

    private TablesRule() {
    }

    /** The refusals of {@code lakes}: empty when every table of every lake has its own uri. */
    public static List<String> refusals(Lakes lakes) {
        List<String> out = new ArrayList<>();
        for (Lake lake : lakes.all().values()) {
            if (lake.root().isEmpty()) {
                continue;                       // a catalog (rest, glue, s3tables) locates its tables
            }
            List<String> fallback = lake.tables().values().stream().filter(t -> t.uri() == null).map(LakeTable::name).sorted().toList();
            if (!fallback.isEmpty()) {
                String shown = String.join(", ", fallback.subList(0, Math.min(SHOWN, fallback.size()))) + (fallback.size() > SHOWN ? ", ..." : "");
                out.add("lake " + lake.name() + " has " + fallback.size() + " table" + (fallback.size() == 1 ? "" : "s") + " resolved by the layout, not by a uri (" + shown
                        + "). Fix: give each its own uri: in " + (lake.file() == null ? "its lake file" : lake.file()) + " (python tools/drishti.py lake entries --write)");
            }
        }
        return out;
    }
}
