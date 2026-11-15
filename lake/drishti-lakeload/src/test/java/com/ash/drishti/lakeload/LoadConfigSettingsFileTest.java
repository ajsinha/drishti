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
package com.ash.drishti.lakeload;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.settingsfile.SettingsFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A data-load file's placeholders resolve keys of the settings files (a namespaced section and a label section alike). */
class LoadConfigSettingsFileTest {

    @TempDir
    Path dir;
    private final List<String> set = new ArrayList<>();

    @AfterEach
    void clear() {
        set.forEach(System::clearProperty);
        System.clearProperty(SettingsFile.PROPERTY);
    }

    @Test
    void aLoadFilesPlaceholderResolvesASettingsFileKey() throws Exception {
        Path ini = dir.resolve("site.ini");
        Files.writeString(ini, "[load.inbox]\nfiles=/srv/inbox/pv.jsonl\n[site]\nsite.cold-lake=/lakes/cold\n");
        System.setProperty(SettingsFile.PROPERTY, ini.toString());
        set.addAll(SettingsFile.applyFromSystemProperty());
        Path file = dir.resolve("load.yaml");
        Files.writeString(file, """
                source:
                  files: ${load.inbox.files}
                  date: BUSINESS_DATE
                target:
                  path: ${site.cold-lake}/risk/pv
                """);
        LoadConfig c = LoadConfig.read(file, List.of());
        assertThat(c.tablePath()).isEqualTo("/lakes/cold/risk/pv");
        assertThat(c.source().files()).containsExactly("/srv/inbox/pv.jsonl");
    }
}
