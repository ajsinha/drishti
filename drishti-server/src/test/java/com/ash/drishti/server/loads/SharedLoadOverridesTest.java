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
package com.ash.drishti.server.loads;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.identity.content.ContentStore;
import com.ash.drishti.identity.content.FileContentStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Per-pack data loads overrides saved through Admin are documents every node reads (CONTENT_STORE.md section 11). */
class SharedLoadOverridesTest {

    @Test
    void anOverrideSavedOnOneNodeIsInForceOnTheOtherAndResetRemovesItEverywhere(@TempDir Path dir) {
        ContentStore store = new FileContentStore(dir.resolve("fstore"));
        LoadsConfig.Overrides a = new LoadsConfig.Overrides(dir.resolve("a"), List.of("admin"), 10, store);
        LoadsConfig.Overrides b = new LoadsConfig.Overrides(dir.resolve("b"), List.of("admin"), 10, store);
        a.write("trading", Map.of("notify", Map.of("roles", List.of("ops"))));
        assertThat(b.overridden("trading")).isTrue();
        assertThat(store.get("loads/config/trading.yaml").orElseThrow().text()).contains("ops");
        assertThat(Files.exists(dir.resolve("a/config/trading.yaml"))).isFalse();
        assertThat(b.reset("trading")).isTrue();
        assertThat(a.overridden("trading")).isFalse();
    }

    @Test
    void theOldFilesAreImportedOnceAndKept(@TempDir Path dir) throws Exception {
        Path old = Files.createDirectories(dir.resolve("a/config"));
        Files.writeString(old.resolve("trading.yaml"), "notify:\n  roles: [desk]\n");
        ContentStore store = new FileContentStore(dir.resolve("fstore"));
        LoadsConfig.Overrides a = new LoadsConfig.Overrides(dir.resolve("a"), List.of("admin"), 10, store);
        assertThat(a.overridden("trading")).isTrue();
        assertThat(store.get("system/imports/loads-config.json")).isPresent();
        assertThat(old.resolve("trading.yaml")).exists();
        a.reset("trading");
        new LoadsConfig.Overrides(dir.resolve("a"), List.of("admin"), 10, store);                 // a restart: the marker keeps it from coming back
        assertThat(a.overridden("trading")).isFalse();
    }

    @Test
    void withFilesItStaysOnTheNode(@TempDir Path dir) {
        LoadsConfig.Overrides a = new LoadsConfig.Overrides(dir.resolve("a"), List.of("admin"), 10);
        a.write("trading", Map.of("notify", Map.of("roles", List.of("ops"))));
        assertThat(dir.resolve("a/config/trading.yaml")).exists();
    }
}
