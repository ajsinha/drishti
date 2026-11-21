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
package com.ash.drishti.lakeconfig;

import com.ash.drishti.api.DrishtiTools;
import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.common.NamedResources;
import com.ash.tools.settings.Placeholders;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lakes by name from the settings files: {@code [lake.<name>]} root= and storage.<key>= win over the lake file's, two lakes in one
 * configuration stay apart (moving one never moves the other), a lake file's placeholder resolves a settings-file key, a connector
 * naming each lake reads its own root, and a process started from this one is handed the sections.
 */
class LakeNamedSectionTest {

    @TempDir
    Path dir;
    private final List<String> set = new ArrayList<>();

    @BeforeEach
    void processView() {
        NamedResources.useView(null, null);
    }

    @AfterEach
    void clear() {
        set.forEach(System::clearProperty);
        System.clearProperty(DrishtiTools.SETTINGS_FILE);
        System.clearProperty(Lakes.PROPERTY);
    }

    private void ini(String text) throws Exception {
        Path f = dir.resolve("site.ini");
        Files.writeString(f, text);
        System.setProperty(DrishtiTools.SETTINGS_FILE, f.toString());
        set.addAll(DrishtiTools.applySettingsFile());
    }

    private Path lakes() throws Exception {
        Path lakes = Files.createDirectories(dir.resolve("lakes"));
        Files.writeString(lakes.resolve("risk.yaml"), "lake: risk\nformat: delta\nroot: /from/the/file/risk\ntables:\n  risk.var-result: {}\n");
        Files.writeString(lakes.resolve("trading.yaml"), "lake: trading\nformat: delta\ntables:\n  trading.trade: {}\n");
        Files.writeString(lakes.resolve("archive.yaml"), "lake: archive\nformat: delta\nroot: ${site.archive-base}/archive\ntables:\n  risk.var-old: {}\n");
        return lakes;
    }

    @Test
    void eachLakeIsMovedByItsOwnSectionAndNoOther() throws Exception {
        Path folder = lakes();
        ini("""
                [lake.risk]
                root=s3://bank-risk/drishti
                storage.s3.region=eu-west-1
                [lake.trading]
                root=/lakes/trading
                [site]
                site.archive-base=/lakes/cold
                """);
        Lakes all = Lakes.read(folder, Placeholders::process);
        assertThat(all.get("risk").root()).isEqualTo("s3://bank-risk/drishti");
        assertThat(all.get("risk").storage()).containsEntry("s3.region", "eu-west-1");
        assertThat(all.get("trading").root()).isEqualTo("/lakes/trading");
        assertThat(all.get("trading").storage()).isEmpty();
        assertThat(all.get("archive").root()).as("a lake file's placeholder resolves a settings-file key").isEqualTo("/lakes/cold/archive");

        System.setProperty("lake.trading.root", "/lakes/trading-2");          // move one lake (an explicit -D)
        set.add("lake.trading.root");
        Lakes moved = Lakes.read(folder, Placeholders::process);
        assertThat(moved.get("trading").root()).isEqualTo("/lakes/trading-2");
        assertThat(moved.get("risk").root()).as("the other lake never moves").isEqualTo("s3://bank-risk/drishti");
        assertThat(moved.get("archive").root()).isEqualTo("/lakes/cold/archive");
    }

    @Test
    void withoutASectionTheLakeFileDecides() throws Exception {
        Lakes all = Lakes.read(lakes(), k -> k.equals("site.archive-base") ? "/x" : null);
        assertThat(all.get("risk").root()).isEqualTo("/from/the/file/risk");
        assertThat(all.get("archive").root()).isEqualTo("/x/archive");
    }

    @Test
    void twoConnectorsNamingTwoLakesReadTwoRoots() throws Exception {
        Path folder = lakes();
        ini("[lake.risk]\nroot=/lakes/risk\n[lake.trading]\nroot=/lakes/trading\n[site]\nsite.archive-base=/lakes/cold\n");
        Lakes all = Lakes.read(folder, Placeholders::process);
        Map<String, String> risk = ConnectorLake.expand("risk-store", "delta", List.of("var-result"), Map.of("lake", "risk"), () -> all, w -> { });
        Map<String, String> trading = ConnectorLake.expand("trade-store", "delta", List.of("trade"), Map.of("lake", "trading"), () -> all, w -> { });
        assertThat(risk.get("root")).startsWith("/lakes/risk");
        assertThat(trading.get("root")).startsWith("/lakes/trading");
        assertThat(risk).containsEntry(ConnectorLake.LAKE_NAME, "risk");
        assertThat(trading).containsEntry(ConnectorLake.LAKE_NAME, "trading");
    }

    @Test
    void aLoaderStartedFromThisProcessIsHandedTheLakeSections() throws Exception {
        System.setProperty(Lakes.PROPERTY, lakes().toString());
        ini("[lake.risk]\nroot=/lakes/risk\nstorage.s3.region=eu-west-1\n[site]\nsite.archive-base=/lakes/cold\n");
        assertThat(Lakes.environment()).containsEntry("lake.risk.root", "/lakes/risk").containsEntry("lake.risk.storage.s3.region", "eu-west-1")
                .containsEntry("site.archive-base", "/lakes/cold");
    }
}
