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

    /** The vector {@code tools/test_resolved.py} checks its own fingerprint of a modules folder against: one formula, two languages. */
    @Test
    void theFingerprintIsTheSha256OfTheIdentityLines() {
        ModuleHomes h = ModuleHomes.of(List.of(home("banking", A),
                new ModuleHomes.Home("genomics", "0.1.0", Path.of("/src/genomics"), null, ModuleHomes.Origin.DEVELOPMENT)));

        assertThat(h.fingerprint()).isEqualTo("f7339103d32cab19ed228794976e07e9be7eb28dc4f104c10dceaba7d2cf2f29");
    }

    /** A colon is not allowed in a Windows file name, so the odd-characters folder carries a semicolon there instead. */
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows");

    @Test
    void theValueTravelsAsOneSettingWhateverItsFolders() {
        ModuleHomes h = ModuleHomes.of(List.of(home("banking", A), new ModuleHomes.Home("genomics", "0.1.0",
                Path.of(WINDOWS ? "/src/a folder, with; odd%chars/genomics" : "/src/a folder, with: odd%chars/genomics"), null, ModuleHomes.Origin.DEVELOPMENT)));

        assertThat(ModuleHomes.fromProperty(h.toProperty())).isEqualTo(h);
        assertThat(ModuleHomes.fromProperty("")).isEqualTo(ModuleHomes.none());
        assertThat(ModuleHomes.of(k -> ModuleHomes.PROPERTY.equals(k) ? h.toProperty() : null)).contains(h);
        assertThat(ModuleHomes.of(k -> null)).isEmpty();
        assertThatThrownBy(() -> ModuleHomes.fromProperty("banking:1.0.0")).hasMessageContaining("is not <id>:<version>");
    }

    @Test
    void sourceModeReadsEveryModuleFolderOfACheckout(@org.junit.jupiter.api.io.TempDir Path tmp) throws java.io.IOException {
        java.nio.file.Files.createDirectories(tmp.resolve("modules/capital-markets"));
        java.nio.file.Files.writeString(tmp.resolve("modules/capital-markets/module.yaml"), "id: CapitalMarkets\nversion: 1.2.0\n");
        java.nio.file.Files.createDirectories(tmp.resolve("modules/dist"));                     // no module.yaml: not a module
        java.nio.file.Files.createDirectories(tmp.resolve("modules/bare"));
        java.nio.file.Files.writeString(tmp.resolve("modules/bare/module.yaml"), "packs: []\n");

        ModuleHomes h = ModuleHomes.source(tmp);

        assertThat(h.homes().keySet()).containsExactly("bare", "capitalmarkets");
        assertThat(h.home("capitalmarkets").orElseThrow().version()).isEqualTo("1.2.0");
        assertThat(h.home("capitalmarkets").orElseThrow().dir()).isEqualTo(tmp.resolve("modules/capital-markets").toAbsolutePath().normalize());
        assertThat(h.home("bare").orElseThrow().origin()).isEqualTo(ModuleHomes.Origin.DEVELOPMENT);
        assertThat(ModuleHomes.source(tmp.resolve("no-checkout")).homes()).isEmpty();
    }

    /** With no published value the readers take no module; a boot's value is the one they take. */
    @Test
    void withNoBootThereIsNoModule(@org.junit.jupiter.api.io.TempDir Path tmp) throws java.io.IOException {
        java.nio.file.Files.createDirectories(tmp.resolve("modules/genomics"));
        java.nio.file.Files.writeString(tmp.resolve("modules/genomics/module.yaml"), "id: genomics\nversion: 0.1.0\n");
        java.util.Map<String, String> p = java.util.Map.of("drishti.data.dir", tmp.toString());

        assertThat(ModuleHomes.resolve(p::get).homes()).as("a folder of modules is not read here").isEmpty();
        ModuleHomes booted = ModuleHomes.of(List.of(home("banking", A)));
        assertThat(ModuleHomes.resolve(k -> ModuleHomes.PROPERTY.equals(k) ? booted.toProperty() : p.get(k))).as("a boot's value wins")
                .isEqualTo(booted);
    }

    private static ModuleHomes.Home home(String id, String hash) {
        return new ModuleHomes.Home(id, "1.0.0", Path.of("/cache/" + id), hash, ModuleHomes.Origin.FIRST_PARTY);
    }
}
