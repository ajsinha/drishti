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
package com.ash.drishti.api.resolution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The boot result's plain value: its rules, its identity lines and the fingerprint two nodes compare. */
class ModuleHomesTest {

    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);

    @Test
    void homesAreKeyedAndOrderedById() {
        ModuleHomes h = ModuleHomes.of(List.of(home("trading", A), home("banking", B)));

        assertThat(h.homes().keySet()).containsExactly("banking", "trading");
        assertThat(h.dir("trading")).contains(Path.of("/cache/trading"));
        assertThat(h.home("absent")).isEmpty();
        assertThat(h.home("banking").orElseThrow().contentHash16()).isEqualTo("b".repeat(16));
    }

    @Test
    void theFingerprintFollowsIdentityNotFolders() {
        ModuleHomes one = ModuleHomes.of(List.of(home("banking", A)));
        ModuleHomes elsewhere = ModuleHomes.of(List.of(new ModuleHomes.Home("banking", "1.0.0", Path.of("/other/node"), A,
                ModuleHomes.Origin.FIRST_PARTY)));
        ModuleHomes changed = ModuleHomes.of(List.of(home("banking", B)));

        assertThat(elsewhere.fingerprint()).isEqualTo(one.fingerprint());
        assertThat(changed.fingerprint()).isNotEqualTo(one.fingerprint());
        assertThat(ModuleHomes.none().fingerprint()).hasSize(64);
    }

    @Test
    void aDevelopmentFolderHasNoContentHashAndAPackageAlwaysHasOne() {
        ModuleHomes.Home dev = new ModuleHomes.Home("genomics", "0.1.0", Path.of("/src/genomics"), null, ModuleHomes.Origin.DEVELOPMENT);

        assertThat(dev.identity()).isEqualTo("genomics 0.1.0 development");
        assertThat(dev.contentHash16()).isNull();
        assertThatThrownBy(() -> new ModuleHomes.Home("x", "1", Path.of("/x"), null, ModuleHomes.Origin.THIRD_PARTY))
                .hasMessageContaining("has a content hash");
        assertThatThrownBy(() -> new ModuleHomes.Home("x", "1", Path.of("/x"), "ABC", ModuleHomes.Origin.THIRD_PARTY))
                .hasMessageContaining("lower-case hex");
        assertThatThrownBy(() -> ModuleHomes.of(List.of(home("x", A), home("x", B)))).hasMessageContaining("two homes");
    }

    @Test
    void publishedIsCurrent() {
        ModuleHomes h = ModuleHomes.of(List.of(home("banking", A)));

        assertThat(ModuleHomes.publish(h)).isSameAs(h);
        assertThat(ModuleHomes.current()).containsSame(h);
        assertThat(h.dirProperties()).containsOnlyKeys("drishti.module.dir.banking");
    }

    private static ModuleHomes.Home home(String id, String hash) {
        return new ModuleHomes.Home(id, "1.0.0", Path.of("/cache/" + id), hash, ModuleHomes.Origin.FIRST_PARTY);
    }
}
