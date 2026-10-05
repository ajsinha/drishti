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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The data-source override file: merged over the pack at load (the pack's files untouched) and validated before it is kept. */
class PackSettingsTest {

    private static Map<String, Object> declared() {
        Map<String, Object> lake = new LinkedHashMap<>();
        lake.put("plugin", "delta");
        lake.put("settings", Map.of("root", "./data/delta", "domain", "risk"));
        Map<String, Object> db = new LinkedHashMap<>();
        db.put("plugin", "jdbc");
        return Map.of("lake", lake, "db", db);
    }

    private static Map<String, Object> doc(String connector, Map<String, Object> body) {
        return Map.of("connectors", Map.of(connector, body));
    }

    @Test
    void theOverrideWinsOverThePackAndLeavesThePackFilesAlone(@TempDir Path dir) throws Exception {
        Path packs = Files.createDirectories(dir.resolve("packs/risk"));
        String yaml = "pack: risk\nkinds: [var]\nconnectors:\n  lake:\n    plugin: delta\n    enabled: true\n    kinds: [var]\n    settings:\n"
                + "      root: ${DRISHTI_DELTA_ROOT:./data/delta}\n      domain: risk\n";
        Files.writeString(packs.resolve("pack.yaml"), yaml);
        PackSettings st = new PackSettings(dir.resolve("settings"));
        PackLoader plain = new PackLoader();
        Map<String, Object> before = plain.properties(plain.load(dir.resolve("packs"), List.of("risk")));
        assertThat(before).containsEntry("drishti.sources.connectors.lake.settings.root", "${DRISHTI_DELTA_ROOT:./data/delta}");

        st.write("risk", doc("lake", Map.of("enabled", false, "settings", Map.of("root", "/mnt/lake", "engine", "native"))));
        PackLoader withOverride = new PackLoader(st);
        Map<String, Object> after = withOverride.properties(withOverride.load(dir.resolve("packs"), List.of("risk")));
        assertThat(after).containsEntry("drishti.sources.connectors.lake.settings.root", "/mnt/lake")
                .containsEntry("drishti.sources.connectors.lake.settings.engine", "native")        // a setting the pack did not have
                .containsEntry("drishti.sources.connectors.lake.settings.domain", "risk")          // the pack's own, untouched
                .containsEntry("drishti.sources.connectors.lake.enabled", false);
        assertThat(Files.readString(packs.resolve("pack.yaml"))).isEqualTo(yaml);                  // the pack's artifact is never rewritten

        st.write("risk", Map.of());                                                                // reset: the file goes, the pack applies again
        assertThat(st.text("risk")).isNull();
        assertThat(new PackLoader(st).properties(new PackLoader(st).load(dir.resolve("packs"), List.of("risk"))))
                .containsEntry("drishti.sources.connectors.lake.settings.root", "${DRISHTI_DELTA_ROOT:./data/delta}");
    }

    @Test
    void aWrittenOverrideReadsBackAndIsAtomic(@TempDir Path dir) {
        PackSettings st = new PackSettings(dir);
        st.write("p", doc("lake", Map.of("settings", Map.of("password", "${LAKE_PW}", "root", "/x"))));
        assertThat(st.connector("p", "lake")).containsKey("settings");
        assertThat(PackSettings.flat((Map<String, Object>) st.connector("p", "lake").get("settings"))).containsEntry("password", "${LAKE_PW}");
        assertThat(st.text("p")).startsWith("# Data source override");
        assertThat(dir.toFile().list()).containsExactly("p.yaml");                                  // no temp file left behind
    }

    @Test
    void validationRefusesWhatShouldNeverBeStored() {
        Map<String, Object> d = declared();
        assertThat(PackSettings.validate(doc("lake", Map.of("settings", Map.of("root", "/mnt/lake"))), d)).isEmpty();
        assertThat(PackSettings.validate(doc("lake", Map.of("settings", Map.of("password", "${LAKE_PW}"))), d)).isEmpty();   // an environment reference is fine
        assertThat(PackSettings.validate(doc("lake", Map.of("settings", Map.of("password", "hunter2"))), d)).singleElement().asString().contains("never stored here", "${PASSWORD}");
        assertThat(PackSettings.validate(doc("lake", Map.of("settings", Map.of("api-key", "${K:fallback}"))), d)).singleElement().asString().contains("never stored here");   // no default
        assertThat(PackSettings.validate(doc("db", Map.of("settings", Map.of("url", "jdbc:postgresql://u:secret@h/db"))), d)).singleElement().asString().contains("password inside a URL");
        assertThat(PackSettings.validate(doc("db", Map.of("settings", Map.of("url", "jdbc:postgresql://h/db?user=u&password=x"))), d)).singleElement().asString().contains("password inside a URL");
        assertThat(PackSettings.validate(doc("db", Map.of("settings", Map.of("url", "jdbc:postgresql://h/db?password=${DB_PW}"))), d)).isEmpty();
        assertThat(PackSettings.validate(doc("db", Map.of("settings", Map.of("url", "postgres://h/db"))), d)).singleElement().asString().contains("starts with jdbc:");
        assertThat(PackSettings.validate(doc("nope", Map.of("enabled", true)), d)).singleElement().asString().contains("not one the pack declares");
        assertThat(PackSettings.validate(doc("lake", Map.of("plugin", "file")), d)).singleElement().asString().contains("unknown key 'plugin'");
        assertThat(PackSettings.validate(doc("lake", Map.of("settings", Map.of("root", ""))), d)).singleElement().asString().contains("cannot be empty");
        assertThat(PackSettings.validate(doc("lake", Map.of("settings", Map.of("bad key", "x"))), d)).singleElement().asString().contains("not a valid setting name");
        assertThat(PackSettings.validate(doc("lake", Map.of("settings", Map.of("root", "a\nb"))), d)).singleElement().asString().contains("one line");
        assertThat(PackSettings.validate(Map.of("packs", "x"), d)).singleElement().asString().contains("unknown section");
    }

    @Test
    void anOverrideFileNamedAfterAnotherPathIsRefused(@TempDir Path dir) {
        PackSettings st = new PackSettings(dir);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> st.file("../escape"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> st.file("a/b"));
    }
}
