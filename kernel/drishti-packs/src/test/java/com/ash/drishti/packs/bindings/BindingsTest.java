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
package com.ash.drishti.packs.bindings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.packs.ConnectorFiles;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** config/bindings.yaml: the file's shape, its refusals, the kinds a connector serves, and the one-time upgrade from connectors' kinds: lists. */
class BindingsTest {

    @Test
    void parsesAndRendersTheFile(@TempDir Path dir) {
        Bindings b = Bindings.parse(Map.of("format", 1, "bindings", Map.of(
                "trade", Map.of("read", List.of("trade-endur", "trade-calypso"), "union", true, "live", "trade-kafka"),
                "var-official", Map.of("write", Map.of("lake", "risklake", "table", "banking.var-official")),
                "pnl-vector", Map.of("demo", true))));
        BindingsFile f = new BindingsFile(dir.resolve("bindings.yaml"));
        f.write(b);
        Bindings again = f.read();
        assertThat(again).isEqualTo(b);
        assertThat(again.of("trade").orElseThrow().connectors()).containsExactly("trade-endur", "trade-calypso", "trade-kafka");
        assertThat(again.readOrder()).containsOnlyKeys("trade");
        assertThat(again.kindsOf("trade-kafka")).containsExactly("trade");
        f.write(again.with("pnl-vector", null));
        assertThat(Files.exists(dir.resolve("bindings.yaml.bak"))).as("the previous file kept").isTrue();
        assertThat(f.read().of("pnl-vector")).isEmpty();
    }

    @Test
    void refusesWithWords() {
        assertThatThrownBy(() -> Bindings.binding("trade", Map.of("read", List.of("a"), "for", Map.of())))
                .hasMessageContaining("one binding per kind");
        assertThatThrownBy(() -> Bindings.binding("trade", Map.of("read", List.of("jdbc:postgresql://db/x"))))
                .hasMessageContaining("credential");
        assertThatThrownBy(() -> Bindings.binding("trade", Map.of("read", List.of("risk-store"), "note", "password=x")))
                .hasMessageContaining("credential");
        assertThatThrownBy(() -> Bindings.binding("trade", Map.of("read", List.of("a"), "demo", true))).hasMessageContaining("demo or real");
        assertThatThrownBy(() -> Bindings.binding("trade", Map.of("read", List.of("a"), "union", true))).hasMessageContaining("two or more");
        assertThatThrownBy(() -> Bindings.binding("trade", Map.of())).hasMessageContaining("names nothing");
        assertThatThrownBy(() -> Bindings.binding("trade", Map.of("write", Map.of("lake", "risklake")))).hasMessageContaining("lake and a table");
        assertThatThrownBy(() -> Bindings.parse(Map.of("format", 2))).hasMessageContaining("format 2");
        assertThatThrownBy(() -> Bindings.parse(Map.of("connectors", Map.of()))).hasMessageContaining("unknown key");
    }

    @Test
    void aConnectorsKindsFollowTheBindings() {
        Bindings b = Bindings.parse(Map.of("bindings", Map.of("scenario-pv", Map.of("read", List.of("risk-store")))));
        assertThat(b.effectiveKinds("risk-store", List.of("pnl-vector"))).containsExactly("pnl-vector", "scenario-pv");
        assertThat(b.effectiveKinds("risk-store", List.of())).as("no list: it serves what its lake holds").isEmpty();
        assertThat(b.effectiveKinds("other", List.of("trade"))).containsExactly("trade");
    }

    @Test
    void theUpgradeWritesBindingsFromTheKindsListsOnce(@TempDir Path dir) throws Exception {
        Path conn = Files.createDirectories(dir.resolve("connectors"));
        Files.writeString(conn.resolve("trade-endur.yaml"), "plugin: delta\nkinds: [trade]\nsettings:\n  union: true\n", StandardCharsets.UTF_8);
        Files.writeString(conn.resolve("trade-murex.yaml"), "plugin: delta\nkinds: [trade]\nsettings:\n  union: true\n", StandardCharsets.UTF_8);
        Files.writeString(conn.resolve("risk-store.yaml"), "plugin: delta\nkinds: [scenario-pv, pnl-vector]\n", StandardCharsets.UTF_8);
        Files.writeString(conn.resolve("lake.yaml"), "plugin: delta\n", StandardCharsets.UTF_8);
        Files.writeString(conn.resolve("var-engine.yaml"), "plugin: reactive\nkinds: [var-result]\n", StandardCharsets.UTF_8);
        ConnectorFiles files = new ConnectorFiles(conn, dir.resolve("data"));
        Map<String, ConnectorFiles.Definition> defs = files.readAll(new java.util.HashMap<>());
        BindingsFile f = BindingsFile.besideConnectors(conn);
        assertThat(f.path()).isEqualTo(dir.resolve("bindings.yaml").toAbsolutePath());

        Bindings written = f.upgradeFrom(defs).orElseThrow();
        assertThat(written.of("trade").orElseThrow().read()).containsExactly("trade-endur", "trade-murex");
        assertThat(written.of("trade").orElseThrow().union()).isTrue();
        assertThat(written.of("scenario-pv").orElseThrow().read()).containsExactly("risk-store");
        assertThat(written.of("var-result")).as("a reactive connector computes it: not a site binding").isEmpty();
        assertThat(f.read()).isEqualTo(written);
        assertThat(f.upgradeFrom(defs)).as("once: a file that exists is the site's").isEmpty();
        assertThat(BindingsFile.besideConnectors(dir.resolve("site-a")).path().getFileName().toString()).isEqualTo("site-a-bindings.yaml");
    }
}
