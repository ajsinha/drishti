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
package com.ash.drishti.packs.module;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A retired module id stops the start with the modules to switch instead; no alias, no migration (docs/plans/MODULE_RATIONALISATION.md). */
class RetiredModulesTest {

    @Test
    void aSwitchFileOfARetiredModuleIsRefusedNamingTheNewModules(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("economy.yaml"), "enabled: true\n");
        Files.writeString(dir.resolve("external-data.yaml"), "enabled: true\n");
        var lines = RetiredModules.lines(dir, k -> null);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).contains("economy.yaml").contains("is retired").contains("external-data").contains("news-and-events");
        assertThat(RetiredNames.message(lines)).startsWith("the server cannot start");
    }

    @Test
    void aSettingOfARetiredModuleIsRefusedAndAnUnrelatedOneIsNot(@TempDir Path dir) {
        var lines = RetiredModules.lines(dir, k -> "drishti.modules.capitalmarkets.enabled".equals(k) ? "environment DRISHTI_MODULES_CAPITALMARKETS_ENABLED" : null);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).contains("drishti.modules.capitalmarkets.enabled").contains("external-data");
        assertThat(RetiredModules.lines(dir, k -> null)).isEmpty();
        assertThat(RetiredModules.lines(null, k -> null)).isEmpty();
    }
}
