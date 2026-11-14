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
    void severalLiveConnectorsPerKindAndOneStillAccepted() {
        Bindings.Binding two = Bindings.binding("trade", Map.of("read", List.of("trade-endur", "trade-calypso"), "union", true,
                "live", List.of("trade-endur-kafka", "trade-calypso-kafka")));
        assertThat(two.live()).containsExactly("trade-endur-kafka", "trade-calypso-kafka");
        assertThat(two.connectors()).containsExactly("trade-endur", "trade-calypso", "trade-endur-kafka", "trade-calypso-kafka");
        assertThat(two.toMap().get("live")).isEqualTo(List.of("trade-endur-kafka", "trade-calypso-kafka"));
        Bindings.Binding one = Bindings.binding("trade", Map.of("live", "trade-kafka"));
        assertThat(one.live()).containsExactly("trade-kafka");
        assertThat(one.toMap().get("live")).as("one connector is written as a name").isEqualTo("trade-kafka");
        assertThat(new Bindings(Map.of("trade", two)).kindsOf("trade-calypso-kafka")).containsExactly("trade");
        assertThatThrownBy(() -> Bindings.binding("trade", Map.of("live", List.of("k", "k")))).hasMessageContaining("named twice in 'live'");
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
    void theFileIsBesideTheConnectorsFolder(@TempDir Path dir) {
        assertThat(BindingsFile.besideConnectors(dir.resolve("connectors")).path()).isEqualTo(dir.resolve("bindings.yaml").toAbsolutePath());
        assertThat(BindingsFile.besideConnectors(dir.resolve("site-a")).path().getFileName().toString()).isEqualTo("site-a-bindings.yaml");
    }
}
