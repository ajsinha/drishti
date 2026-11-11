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

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The UI kit is found beside the packs folder when the working folder holds none (a server started elsewhere: the drill's browser tests). */
class UiKitDirTest {

    @TempDir Path tmp;

    @Test
    void theConfiguredKitWinsElseTheOneBesideThePacksFolder() throws Exception {
        Path config = tmp.resolve("repo/config");
        Files.createDirectories(config.resolve("ui-kit/fragments"));
        Files.createDirectories(config.resolve("packs"));
        Path elsewhere = tmp.resolve("work/config/ui-kit");
        assertThat(UiKitDir.resolve(elsewhere.toString(), config.resolve("packs").toString())).isEqualTo(config.resolve("ui-kit"));
        Files.createDirectories(elsewhere.resolve("fragments"));
        assertThat(UiKitDir.resolve(elsewhere.toString(), config.resolve("packs").toString())).isEqualTo(elsewhere);
        Path none = tmp.resolve("none/ui-kit");
        assertThat(UiKitDir.resolve(none.toString(), tmp.resolve("x/packs").toString())).isEqualTo(none);
    }
}
