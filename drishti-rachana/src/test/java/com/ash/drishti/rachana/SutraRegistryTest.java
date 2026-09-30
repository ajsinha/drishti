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
package com.ash.drishti.rachana;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SutraRegistryTest {

    @TempDir
    Path dir;

    private static String sutra(String name, int version, String kind) {
        return "sutra: " + name + "\nversion: " + version + "\nmatch: { kind: " + kind + " }\npanels:\n  - { id: p, kind: links }\n";
    }

    private SutraRegistry registry(boolean hot) {
        return new SutraRegistry(new RachanaProperties(List.of(dir.toString()), hot, Duration.ofMillis(50), null, null, null, null, null), new com.ash.drishti.rachana.el.ElCompiler());
    }

    @Test
    void loadsVersionsAndKeepsLastGoodOnError() throws Exception {
        Files.createDirectories(dir.resolve("rates"));
        Files.writeString(dir.resolve("rates/a.v1.yaml"), sutra("alpha", 1, "trade"));
        Files.writeString(dir.resolve("rates/a.v2.yaml"), sutra("alpha", 2, "trade"));
        Files.writeString(dir.resolve("b.yaml"), sutra("beta", 1, "curve"));
        try (SutraRegistry r = registry(false)) {
            assertThat(r.latest("alpha")).get().extracting(s -> s.version()).isEqualTo(2);
            assertThat(r.versions("alpha")).containsExactly(1, 2);
            assertThat(r.get("alpha", 1)).isPresent();
            assertThat(r.forKind("curve")).extracting(s -> s.name()).containsExactly("beta");
            assertThat(r.latest("alpha").orElseThrow().domain()).isEqualTo("rates");

            List<Set<String>> changes = new CopyOnWriteArrayList<>();
            r.onChange(changes::add);
            Files.writeString(dir.resolve("b.yaml"), "sutra: beta\nversion: 1\nmatch: { kind: curve }\npanels: 7\n");
            r.reload();
            assertThat(r.latest("beta")).isPresent();
            assertThat(r.problems()).hasSize(1);
            assertThat(changes).isEmpty();

            Files.writeString(dir.resolve("dup.yaml"), sutra("alpha", 2, "trade"));
            r.reload();
            assertThat(r.problems().values().stream().flatMap(List::stream)).extracting(SutraProblem::code).contains("DRS-2028");
        }
    }

    @Test
    void hotReloadPicksUpNewFiles() throws Exception {
        try (SutraRegistry r = registry(true)) {
            List<Set<String>> changes = new CopyOnWriteArrayList<>();
            r.onChange(changes::add);
            Files.writeString(dir.resolve("g.yaml"), sutra("gamma", 1, "trade"));
            long deadline = System.currentTimeMillis() + 10_000;
            while (r.latest("gamma").isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertThat(r.latest("gamma")).isPresent();
            assertThat(changes).anySatisfy(c -> assertThat(c).contains("gamma@1"));
        }
    }
}
