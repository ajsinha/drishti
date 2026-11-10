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
package com.ash.drishti.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The one-time move of external_lib/java and drishti.sources.plugin-dir into the lib folder, with the .bak listing and Health's notes. */
class SharedLibMigrateTest {

    @TempDir
    Path tmp;

    @Test
    void theOldFoldersJarsMoveIntoLibOnceWithAListingAndANote() throws Exception {
        Path ext = Files.createDirectories(tmp.resolve("external_lib/java"));
        Path plugins = Files.createDirectories(tmp.resolve("plugins"));
        Path lib = Files.createDirectories(tmp.resolve("data/lib"));
        Files.writeString(ext.resolve("ojdbc11.jar"), "driver");
        Files.writeString(ext.resolve("README.md"), "not a jar");
        Files.writeString(plugins.resolve("acme-connector.jar"), "connector");
        Files.writeString(plugins.resolve("dup.jar"), "old");
        Files.writeString(lib.resolve("dup.jar"), "new");
        Map<String, Path> old = new LinkedHashMap<>();
        old.put("external_lib/java", ext);
        old.put("drishti.sources.plugin-dir", plugins);
        old.put("missing", tmp.resolve("nowhere"));

        List<String> notes = SharedLib.migrate(lib, old, Map.of("drishti.sources.plugin-dir (DRISHTI_PLUGIN_DIR)", "remove the setting"));
        assertThat(lib.resolve("ojdbc11.jar")).hasContent("driver");
        assertThat(lib.resolve("acme-connector.jar")).hasContent("connector");
        assertThat(lib.resolve("dup.jar")).hasContent("new");                     // never overwritten: left where it was, and said
        assertThat(plugins.resolve("dup.jar")).exists();
        assertThat(ext.resolve("README.md")).exists();
        assertThat(notes).hasSize(3);
        assertThat(notes.get(0)).contains("external_lib/java", "1 jar(s) moved", "no longer read");
        assertThat(notes.get(1)).contains("plugin-dir", "left there", "dup.jar");
        assertThat(notes.get(2)).contains("deprecated and ignored");
        try (var s = Files.list(lib)) {
            Path bak = s.filter(p -> p.getFileName().toString().startsWith("moved-") && p.toString().endsWith(".bak")).findFirst().orElseThrow();
            assertThat(Files.readString(bak)).contains("ojdbc11.jar -> ", "acme-connector.jar -> ");
        }
        assertThat(SharedLib.migrate(lib, old, Map.of())).as("a second start moves nothing new").hasSize(1);
    }
}
