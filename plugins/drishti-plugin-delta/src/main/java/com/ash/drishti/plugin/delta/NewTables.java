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
package com.ash.drishti.plugin.delta;

import java.io.IOException;
import java.util.Set;
import java.util.function.Consumer;

/**
 * New tables in a connector's domain (a kind loaded after the connector started), found without a restart: at every reindex, and at a
 * data-load signal's refresh ({@code purgeCaches}, {@code refreshVersions}), so the first load into an empty lake is readable as soon as it
 * is announced, not at the next reindex. Only for a connector that discovers its kinds (no {@code kinds} setting).
 */
final class NewTables {

    private NewTables() {}

    /**
     * Adds every table of {@code lake} not in {@code known} through {@code add}; true when one was found. A lake that cannot be read now
     * finds nothing: its health says so, and the next look tries again.
     */
    static boolean find(LakeStore lake, Set<String> known, Consumer<String> add) {
        boolean found = false;
        try {
            for (String kind : lake.tables()) {
                if (!known.contains(kind)) {
                    add.accept(kind);                          // shows in /api/v1/sources and Admin → Health
                    found = true;
                }
            }
        } catch (IOException | RuntimeException e) {
            // the lake is unreachable for now
        }
        return found;
    }
}
