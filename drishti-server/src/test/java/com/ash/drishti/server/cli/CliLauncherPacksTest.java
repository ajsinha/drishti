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
package com.ash.drishti.server.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The CLI loads the packs its command line names, so a pack is checked with its own About text (pack check on market-risk). */
class CliLauncherPacksTest {

    @Test
    void aPackFolderOrASutraInsideItIsEnabledAndAPackElsewhereIsReadFromItsParent(@TempDir Path dir) throws Exception {
        Path pack = Files.createDirectories(dir.resolve("my-bank"));
        Files.writeString(pack.resolve("pack.yaml"), "name: my-bank\nversion: 1.0.0\n");
        Path sutra = Files.createDirectories(pack.resolve("sutras/trade")).resolve("x.v1.sutra.yaml");
        Files.writeString(sutra, "rachana: 1\n");

        var byFolder = CliLauncher.packProperties(List.of("lint", pack.toString(), "--strict"));
        assertThat(byFolder.get("drishti.packs.enabled")).contains("my-bank");
        assertThat(byFolder).containsEntry("drishti.packs.installed-dir", dir.toAbsolutePath().normalize().toString());

        assertThat(CliLauncher.packProperties(List.of("test", sutra.toString())).get("drishti.packs.enabled")).contains("my-bank");
        assertThat(CliLauncher.packProperties(List.of("design", dir.resolve("samples").toString()))).as("no pack named: nothing changes").isEmpty();
    }
}
