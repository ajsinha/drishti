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
package com.ash.drishti.plugin.delta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.lakehouse.MockUnityCatalog;
import com.ash.drishti.testkit.DatedSourceContract;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * The same contract as the local lake, with each kind's table named in Unity Catalog ({@code table.<kind>: main.desk.<table>}) and found
 * where Unity says it is (a mock Unity Catalog answering as Databricks does); a table Unity refuses is left out, its reason in words.
 */
class DeltaOnUnityCatalogTest extends DatedSourceContract {

    private static MockUnityCatalog uc;
    private static DeltaSourcePlugin plugin;
    private static Path root;

    @Override
    protected synchronized SourcePlugin plugin() throws Exception {
        if (plugin != null) {
            return plugin;
        }
        root = Files.createTempDirectory("drishti-unity-lake");
        Path src = Path.of("src/test/resources/lake");
        try (var files = Files.walk(src)) {
            for (Path f : files.toList()) {
                Path to = root.resolve(src.relativize(f).toString());
                if (Files.isDirectory(f)) {
                    Files.createDirectories(to);
                } else {
                    Files.copy(f, to);
                }
            }
        }
        uc = new MockUnityCatalog();
        uc.table("main.desk.trade", root.resolve("desk").resolve("trade").toUri().toString());
        uc.table("main.desk.counterparty", root.resolve("desk").resolve("counterparty").toUri().toString());
        DeltaSourcePlugin p = new DeltaSourcePlugin();
        p.start(context(Map.of("unity.host", uc.host(), "unity.token", MockUnityCatalog.TOKEN, "unity.catalog", "main", "unity.schema", "desk",
                "table.trade", "trade", "table.counterparty", "main.desk.counterparty", "mode.counterparty", "effective", "source-name", "unity-lake")));
        plugin = p;
        return p;
    }

    @AfterAll
    static void stop() throws Exception {
        if (plugin != null) {
            plugin.close();
        }
        if (uc != null) {
            uc.close();
        }
    }

    @Test
    void aTableUnityRefusesIsLeftOutAndSaidInWords() throws Exception {
        plugin();
        uc.table("main.desk.secret", root.resolve("desk").resolve("trade").toUri().toString());
        uc.denied.add("main.desk.secret");
        DeltaSourcePlugin p = new DeltaSourcePlugin();
        p.start(context(Map.of("unity.host", uc.host(), "unity.token", MockUnityCatalog.TOKEN, "table.trade", "main.desk.trade",
                "table.secret", "main.desk.secret", "source-name", "unity-partial")));
        try {
            assertThat(p.manifest().kinds()).containsExactly("trade");
        } finally {
            p.close();
        }
        DeltaSourcePlugin only = new DeltaSourcePlugin();
        try {
            only.start(context(Map.of("unity.host", uc.host(), "unity.token", MockUnityCatalog.TOKEN, "table.secret", "main.desk.secret",
                    "source-name", "unity-none")));
            assertThat(only.health()).startsWith("DOWN").contains("no grant").contains("main.desk.secret");
        } finally {
            only.close();
        }
        assertThatThrownBy(() -> new DeltaSourcePlugin().start(context(Map.of("unity.host", uc.host(), "unity.token", MockUnityCatalog.TOKEN,
                "kinds", "secret", "table.secret", "main.desk.secret", "source-name", "unity-explicit")))).hasMessageContaining("no grant");
    }
}
