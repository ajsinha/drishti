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
package com.ash.drishti.api.resolution;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The one module layout's list (module-layout.yaml): it reads, and what an older checkout leaves at a module's top (an old build folder)
 * is named in words, never taken for the module's content.
 */
class ModuleLayoutTest {

    @TempDir
    Path tmp;

    @Test
    void theOneListReads() {
        assertThat(ModuleLayout.CONTENT).contains("module.yaml", "packs", "console", "docs", "java");
        assertThat(ModuleLayout.BUILD).contains("pom.xml", "target", "tools", "content.yaml");
        assertThat(ModuleLayout.CONTENT).doesNotContainAnyElementsOf(ModuleLayout.BUILD);
    }

    @Test
    void anOldBuildFolderIsNamedInWords() throws Exception {
        Path banking = tmp.resolve("modules/banking");
        Files.createDirectories(banking.resolve("banking-compute/target"));
        Files.createDirectories(banking.resolve("packs"));
        Files.createDirectories(banking.resolve("target"));
        Files.writeString(banking.resolve("module.yaml"), "id: banking\n");
        assertThat(ModuleLayout.strays(banking, "modules/banking"))
                .containsExactly("modules/banking/banking-compute: not part of the module layout, left out (an old build folder? delete it)");
    }
}
