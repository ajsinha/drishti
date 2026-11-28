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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The one rule of site overrides of module files (KERNEL_AND_MODULES.md section 18.10): a file at the same relative path in an override
 * folder replaces the module's in the view, the right-most folder wins, module.yaml, connector templates and java/ are refused, the module
 * folder is never written, and removing the override restores the module's file (no override: no view).
 */
class ModuleOverridesTest {

    @TempDir
    Path tmp;

    @Test
    void anOverrideCanNeverInjectCodeNorFollowALink() throws Exception {
        Path home = tmp.resolve("modules-cache/banking/1.0.0");
        write(home.resolve("module.yaml"), "id: banking\n");
        write(home.resolve("packs/trading/sutras/endur/endur-cash.v1.sutra.yaml"), "title: Endur cash\n");
        write(home.resolve("python/var/model.py"), "x = 1\n");
        Path o = tmp.resolve("over");
        write(o.resolve("banking/lib/evil.jar"), "jar");
        write(o.resolve("banking/python/var/model.py"), "import os\n");
        write(o.resolve("banking/engines/var.yaml"), "class: x\n");
        write(o.resolve("banking/packs/trading/hook.py"), "import os\n");
        write(o.resolve("banking/packs/trading/Hook.class"), "cafebabe");
        write(o.resolve("banking/packs/trading/native.so"), "elf");
        write(o.resolve("banking/docs/native.DLL"), "mz");
        write(o.resolve("banking/packs/trading/plugin.JAR"), "jar");
        write(o.resolve("banking/packs/trading/sutras/endur/endur-cash.v1.sutra.yaml"), "title: Endur cash (site)\n");   // a Sutra: kept
        Path outside = tmp.resolve("outside");
        write(outside.resolve("pack.yaml"), "pack: hijacked\n");
        write(outside.resolve("dir/pack.yaml"), "pack: hijacked\n");
        Files.createDirectories(o.resolve("banking/packs/x"));
        Files.createSymbolicLink(o.resolve("banking/packs/x/pack.yaml"), outside.resolve("pack.yaml"));
        Files.createSymbolicLink(o.resolve("banking/packs/linked"), outside.resolve("dir"));
        try {
            ModuleOverrides.activate(List.of(o), tmp.resolve("data"));
            Path view = viewOf(home, "banking", "1.0.0");

            assertThat(ModuleOverrides.installedFiles()).extracting(ModuleOverrides.File::path)
                    .containsExactly("banking/packs/trading/sutras/endur/endur-cash.v1.sutra.yaml");
            String refused = String.join("\n", ModuleOverrides.installedRefused()).replace('\\', '/');     // Windows: the paths read with its separator
            assertThat(refused).contains("lib/ holds the module's code", "python/ holds the module's code", "engines/ holds the module's code",
                    "a .py file is code", "a .class file is code", "a .so file is code", "a .dll file is code", "a .jar file is code",
                    "banking/packs/x/pack.yaml: a symbolic link is never followed", "banking/packs/linked: a symbolic link is never followed");
            assertThat(ModuleOverrides.installedRefused()).hasSize(10);
            assertThat(Files.readString(view.resolve("python/var/model.py"))).isEqualTo("x = 1\n");      // the module's own code, as shipped
            assertThat(view.resolve("lib")).doesNotExist();
            assertThat(view.resolve("packs/x")).doesNotExist();
            assertThat(view.resolve("packs/linked")).doesNotExist();
        } finally {
            ModuleOverrides.activate(List.of(), null);
        }
    }

    @Test
    void theModuleIdIsTheOneKeyForTheModulesFolderAndTheStoreAlike() throws Exception {
        Path o = tmp.resolve("over");
        write(o.resolve("capitalmarkets/packs/us-equities/pack.yaml"), "pack: us-equities (site)\n");
        write(o.resolve("capital-markets/packs/us-equities/pack.yaml"), "pack: us-equities (by folder)\n");
        Path home = tmp.resolve("modules-cache/capitalmarkets/2.0.0");                                    // the same key for the store
        write(home.resolve("module.yaml"), "id: capitalmarkets\n");
        write(home.resolve("packs/us-equities/pack.yaml"), "pack: us-equities\n");
        try {
            ModuleOverrides.activate(List.of(o), tmp.resolve("data"));
            Path iv = viewOf(home, "capitalmarkets", "2.0.0");
            assertThat(Files.readString(iv.resolve("packs/us-equities/pack.yaml"))).contains("(site)");
            assertThat(ModuleOverrides.installedFiles()).extracting(ModuleOverrides.File::path)
                    .containsExactly("capitalmarkets/packs/us-equities/pack.yaml");
            assertThat(ModuleOverrides.installedFiles().get(0).applied()).isEqualTo(iv.resolve("packs/us-equities/pack.yaml"));
        } finally {
            ModuleOverrides.activate(List.of(), null);
        }
        assertThat(ModuleOverrides.installedFiles()).isEmpty();
    }

    @Test
    void aModuleInstalledFromItsDmodIsReadFromItsViewWhileOverridden() throws Exception {
        Path home = tmp.resolve("modules-cache/banking/1.0.0");
        write(home.resolve("module.yaml"), "id: banking\npacks: [trading]\n");
        write(home.resolve("packs/trading/sutras/endur/endur-cash.v1.sutra.yaml"), "title: Endur cash\n");
        write(home.resolve("java/drishti-banking.jar"), "jar");
        Path o = tmp.resolve("over");
        write(o.resolve("banking/packs/trading/sutras/endur/endur-cash.v1.sutra.yaml"), "title: Endur cash (site)\n");
        write(o.resolve("banking/module.yaml"), "id: other\n");
        write(o.resolve("banking/java/drishti-banking.jar"), "evil");
        write(o.resolve("banking/packs/trading/run.sh"), "rm -rf /\n");
        try {
            ModuleOverrides.activate(List.of(o), tmp.resolve("data"));
            Path view = viewOf(home, "banking", "1.0.0");
            assertThat(ModuleOverrides.installedRefused()).hasSize(3).anySatisfy(x -> assertThat(x).contains("java/ holds the module's code"))
                    .anySatisfy(x -> assertThat(x).contains("a .sh file is code"));
            assertThat(view.resolve("packs/trading/run.sh")).doesNotExist();
            assertThat(view).isEqualTo(tmp.resolve("data/overrides/modules/banking/1.0.0-development"));
            assertThat(Files.readString(view.resolve("packs/trading/sutras/endur/endur-cash.v1.sutra.yaml"))).contains("(site)");
            assertThat(Files.readString(view.resolve("module.yaml"))).contains("id: banking");         // the identity is never overridden
            assertThat(view.resolve("java")).doesNotExist();
            assertThat(viewOf(home, "economy", "1.0.0")).isEqualTo(home);                   // nothing for it: its own folder

            Files.delete(o.resolve("banking/packs/trading/sutras/endur/endur-cash.v1.sutra.yaml"));
            Files.delete(o.resolve("banking/module.yaml"));
            Files.delete(o.resolve("banking/java/drishti-banking.jar"));
            Files.delete(o.resolve("banking/packs/trading/run.sh"));
            assertThat(viewOf(home, "banking", "1.0.0")).isEqualTo(home);
            assertThat(view).doesNotExist();
            assertThat(tmp.resolve("data/overrides/modules")).doesNotExist();
        } finally {
            ModuleOverrides.activate(List.of(), null);
        }
    }

    @Test
    void theSettingNamesFoldersAbsoluteOrUnderTheHome() {
        Path home = tmp.resolve("home");
        assertThat(ModuleOverrides.dirs(" /abs/a , rel/b ,, ", home)).containsExactly(Path.of("/abs/a").toAbsolutePath(), home.resolve("rel/b").toAbsolutePath());
        assertThat(ModuleOverrides.dirs("", home)).isEmpty();
        assertThat(ModuleOverrides.dirs(null, home)).isEmpty();
    }

    private static void write(Path f, String text) throws Exception {
        Files.createDirectories(f.getParent());
        Files.writeString(f, text);
    }

    /** The view of a booted module's folder under the data folder's {@code overrides/modules} (the rule a boot's modules get). */
    private Path viewOf(Path home, String id, String version) {
        ModuleHomes.Home h = new ModuleHomes.Home(id, version, home, null, ModuleHomes.Origin.DEVELOPMENT);
        return ModuleOverrides.view(h, tmp.resolve("data").resolve("overrides").resolve("modules")).dir();
    }
}
