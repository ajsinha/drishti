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
package com.ash.drishti.server.connectors;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.packs.ConnectorFiles;
import com.ash.drishti.packs.ConnectorOverlay;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The owner's site: a pack reload had written funds-store into the shared overlay with the old {@code root:}, and that copy hid the
 * file for good. What the server generates now goes to the file, and what an override changes is shown exactly.
 */
class ConnectorOverlayDiffTest {

    private static final String FILE = "plugin: delta\nkinds:\n- fund\nsettings:\n  lake: demo\n  lake-tables: funds.fund\n";
    private static final String OVERRIDE = "plugin: delta\nkinds:\n- fund\nsettings:\n  root: /old/delta\n  domain: funds\n  password: hunter2\n";

    private static final class MemoryOverlay implements ConnectorOverlay {
        final Map<String, Entry> map = new LinkedHashMap<>();

        @Override
        public Entry get(String name) {
            return map.get(name);
        }

        @Override
        public Map<String, Entry> all() {
            return new LinkedHashMap<>(map);
        }

        @Override
        public void put(String name, String text, String expectedEtag, String fileText) {
            map.put(name, new Entry(text, false, Instant.now()));
        }

        @Override
        public void hide(String name, String effectiveText) {
            map.put(name, new Entry(null, true, Instant.now()));
        }

        @Override
        public boolean remove(String name, String effectiveText) {
            return map.remove(name) != null;
        }

        @Override
        public List<ConnectorFiles.Revision> history(String name) {
            return List.of();
        }

        @Override
        public String revisionText(String name, String id) {
            return null;
        }
    }

    @Test
    void theDiffNamesEachSettingTheOverrideChangesWithSecretsMasked(@TempDir Path dir) throws Exception {
        MemoryOverlay overlay = new MemoryOverlay();
        ConnectorFiles files = new ConnectorFiles(Files.createDirectories(dir.resolve("c")), dir.resolve("s")).overlay(overlay);
        Files.writeString(files.file("funds-store"), FILE);
        overlay.map.put("funds-store", new ConnectorOverlay.Entry(OVERRIDE, false, Instant.now()));

        List<Map<String, String>> diff = ConnectorManager.overlayDiff(files, "funds-store");
        assertThat(diff).extracting(d -> d.get("what")).containsExactlyInAnyOrder("settings.domain", "settings.lake", "settings.lake-tables", "settings.password", "settings.root");
        assertThat(diff).filteredOn(d -> d.get("what").equals("settings.root")).singleElement()
                .satisfies(d -> assertThat(d).containsEntry("file", "(not set)").containsEntry("override", "/old/delta"));
        assertThat(diff).filteredOn(d -> d.get("what").equals("settings.password")).singleElement()
                .satisfies(d -> assertThat(d.get("override")).isEqualTo("***"));

        overlay.map.put("only", new ConnectorOverlay.Entry(OVERRIDE, false, Instant.now()));
        assertThat(ConnectorManager.overlayDiff(files, "only")).singleElement().satisfies(d -> assertThat(d.get("what")).isEqualTo("(file)"));
    }

    @Test
    void whatTheServerGeneratesGoesToTheFileAndLeavesAnOverrideAlone(@TempDir Path dir) throws Exception {
        MemoryOverlay overlay = new MemoryOverlay();
        ConnectorFiles files = new ConnectorFiles(Files.createDirectories(dir.resolve("c")), dir.resolve("s")).overlay(overlay);
        overlay.map.put("funds-store", new ConnectorOverlay.Entry(OVERRIDE, false, Instant.now()));
        files.writeGenerated("funds-store", FILE);
        assertThat(Files.readString(files.file("funds-store"))).isEqualTo(FILE);
        assertThat(overlay.map.get("funds-store").text()).isEqualTo(OVERRIDE);
    }
}
