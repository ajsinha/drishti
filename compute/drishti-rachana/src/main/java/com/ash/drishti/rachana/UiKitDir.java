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
package com.ash.drishti.rachana;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where the Drishti UI kit is (RACHANA_REFERENCE.md, "The Drishti UI kit"): {@code drishti.ui-kit.dir} (default {@code ./config/ui-kit}) when it
 * holds the kit, else the {@code ui-kit} folder beside the packs folder ({@code drishti.packs.dir}: {@code config/packs} finds
 * {@code config/ui-kit}). A server started from another working folder with {@code DRISHTI_PACKS_DIR} pointing at the repository's packs
 * (the browser tests, a service whose working folder is its data) otherwise found no kit, and every screen that uses a kit fragment fell
 * back to an inferred layout (DRS-2048, "no block series-line@1").
 */
public final class UiKitDir {

    private UiKitDir() {
    }

    /** The kit folder: the configured one when it has fragments, else the packs' sibling when that has them, else the configured one. */
    public static Path resolve(String configured, String packsDir) {
        Path set = Path.of(configured == null || configured.isBlank() ? "./config/ui-kit" : configured).toAbsolutePath().normalize();
        if (Files.isDirectory(set.resolve("fragments")) || packsDir == null || packsDir.isBlank()) {
            return set;
        }
        Path parent = Path.of(packsDir).toAbsolutePath().normalize().getParent();
        Path beside = parent == null ? null : parent.resolve("ui-kit");
        return beside != null && Files.isDirectory(beside.resolve("fragments")) ? beside : set;
    }
}
