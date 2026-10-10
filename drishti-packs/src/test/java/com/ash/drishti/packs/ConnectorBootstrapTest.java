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
package com.ash.drishti.packs;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Start-up: files from pack templates (once, never over an existing file) and the one-time migration of old data-source overrides. */
class ConnectorBootstrapTest {

    private static List<Pack> packs(Path dir, String yaml) throws Exception {
        Path p = Files.createDirectories(dir.resolve("packs/risk"));
        Files.writeString(p.resolve("pack.yaml"), yaml);
        return new PackLoader().load(dir.resolve("packs"), List.of("risk"));
    }

    private static final String RISK = "pack: risk\nkinds: [var]\nroutes:\n  var: risk-lake\nconnectors:\n  risk-lake:\n    plugin: delta\n    kinds: [var]\n    settings:\n"
            + "      root: ${DRISHTI_DELTA_ROOT:./data/delta}\n      domain: risk\n";

    @Test
    void aTemplateWithNoFileBecomesAFileOnceAndIsNeverOverwritten(@TempDir Path dir) throws Exception {
        List<Pack> packs = packs(dir, RISK);
        ConnectorFiles files = new ConnectorFiles(dir.resolve("config/connectors"));
        ConnectorBootstrap.Result r = ConnectorBootstrap.run(packs, files, null, true);
        assertThat(r.generated()).containsExactly("risk-lake");
        ConnectorFiles.Definition d = files.read("risk-lake");
        assertThat(d.plugin()).isEqualTo("delta");
        assertThat(d.kinds()).containsExactly("var");
        assertThat(d.settings()).containsEntry("root", "${DRISHTI_DELTA_ROOT:./data/delta}").containsEntry("domain", "risk");
        assertThat(d.text()).contains("Generated at first start").contains("never overwritten");

        files.write("risk-lake", "plugin: delta\nsettings:\n  root: /my/lake\n");           // the site edits it
        ConnectorBootstrap.Result again = ConnectorBootstrap.run(packs, files, null, true);
        assertThat(again.generated()).isEmpty();
        assertThat(files.read("risk-lake").settings()).containsEntry("root", "/my/lake").doesNotContainKey("domain");
    }

    @Test
    void generationCanBeSwitchedOff(@TempDir Path dir) throws Exception {
        ConnectorFiles files = new ConnectorFiles(dir.resolve("c"));
        assertThat(ConnectorBootstrap.run(packs(dir, RISK), files, null, false).generated()).isEmpty();
        assertThat(files.names()).isEmpty();
    }

    @Test
    void anOldOverrideBecomesAnEditOfTheConnectorFileAndIsBackedUp(@TempDir Path dir) throws Exception {
        List<Pack> packs = packs(dir, RISK);
        PackSettings overrides = new PackSettings(dir.resolve("settings"));
        overrides.write("risk", Map.of("connectors", Map.of("risk-lake", Map.of("enabled", false, "settings", Map.of("root", "/mnt/lake", "engine", "native")))));
        ConnectorFiles files = new ConnectorFiles(dir.resolve("connectors"));
        ConnectorBootstrap.Result r = ConnectorBootstrap.run(packs, files, overrides, true);
        assertThat(r.migrated()).containsExactly("risk-lake");
        ConnectorFiles.Definition d = files.read("risk-lake");
        assertThat(d.isEnabled()).isFalse();
        assertThat(d.settings()).containsEntry("root", "/mnt/lake").containsEntry("engine", "native").containsEntry("domain", "risk");   // template + the admin's edit
        assertThat(overrides.text("risk")).isNull();                                                                                  // the override file is gone ...
        try (var s = Files.list(overrides.dir().resolve(".migrated"))) {
            List<Path> kept = s.toList();
            assertThat(kept).hasSize(1);                                                                                              // ... and kept as a backup
            assertThat(Files.readString(kept.get(0))).contains("/mnt/lake");
        }
        assertThat(r.messages()).anyMatch(m -> m.contains("moved into risk-lake.yaml")).anyMatch(m -> m.contains("original override file"));
        // a second start has nothing left to migrate and does not touch the file
        String text = files.text("risk-lake");
        assertThat(ConnectorBootstrap.run(packs, files, overrides, true).migrated()).isEmpty();
        assertThat(files.text("risk-lake")).isEqualTo(text);
    }

    @Test
    void anOverrideOverAnExistingFileIsAppliedOnItAndTheEarlierTextKept(@TempDir Path dir) throws Exception {
        List<Pack> packs = packs(dir, RISK);
        ConnectorFiles files = new ConnectorFiles(dir.resolve("connectors"));
        files.write("risk-lake", "plugin: delta\nsettings:\n  root: /site/lake\n  cache-mb: '64'\n");
        PackSettings overrides = new PackSettings(dir.resolve("settings"));
        overrides.write("risk", Map.of("connectors", Map.of("risk-lake", Map.of("settings", Map.of("root", "/admin/lake")))));
        ConnectorBootstrap.run(packs, files, overrides, true);
        assertThat(files.read("risk-lake").settings()).containsEntry("root", "/admin/lake").containsEntry("cache-mb", "64");
        assertThat(files.history("risk-lake")).hasSize(1);
        assertThat(files.revisionText("risk-lake", files.history("risk-lake").get(0).id())).contains("/site/lake");
    }

    @Test
    void anOverrideWithNoTemplateAndNoFileIsLeftInPlaceNotLost(@TempDir Path dir) throws Exception {
        List<Pack> packs = packs(dir, "pack: risk\nkinds: [var]\n");
        PackSettings overrides = new PackSettings(dir.resolve("settings"));
        overrides.write("risk", Map.of("connectors", Map.of("mystery", Map.of("settings", Map.of("root", "/x")))));
        ConnectorFiles files = new ConnectorFiles(dir.resolve("connectors"));
        ConnectorBootstrap.Result r = ConnectorBootstrap.run(packs, files, overrides, true);
        assertThat(r.migrated()).isEmpty();
        assertThat(overrides.text("risk")).contains("mystery").contains("/x");
        assertThat(r.messages()).anyMatch(m -> m.contains("mystery") && m.contains("kept in"));
    }

    @Test
    void aPackThatOnlyListsConnectorNamesReferencesThemAndAddsNoTemplate(@TempDir Path dir) throws Exception {
        List<Pack> packs = packs(dir, "pack: risk\nkinds: [var]\nconnectors: [risk-lake, risk-stream]\nroutes:\n  var: risk-lake\n");
        assertThat(packs.get(0).connectorRefs()).containsExactly("risk-lake", "risk-stream");
        assertThat(packs.get(0).connectorTemplates()).isEmpty();
        ConnectorFiles files = new ConnectorFiles(dir.resolve("c"));
        assertThat(ConnectorBootstrap.run(packs, files, null, true).generated()).isEmpty();
        assertThat(new PackLoader(files).properties(packs)).doesNotContainKey("drishti.sources.connectors.risk-lake.plugin");
    }
}
