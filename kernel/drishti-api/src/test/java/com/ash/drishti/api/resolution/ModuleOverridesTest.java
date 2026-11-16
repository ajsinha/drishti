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

    private Path modules() throws Exception {
        Path m = tmp.resolve("modules");
        write(m.resolve("banking/module.yaml"), "id: banking\npacks: [trading]\n");
        write(m.resolve("banking/packs/trading/pack.yaml"), "pack: trading\n");
        write(m.resolve("banking/packs/trading/sutras/endur/endur-cash.v1.sutra.yaml"), "title: Endur cash\n");
        write(m.resolve("banking/connectors/trade.yaml"), "plugin: file\n");
        write(m.resolve("banking/java/banking-service/pom.xml"), "<project/>\n");
        write(m.resolve("banking/pom.xml"), "<project/>\n");
        write(m.resolve("economy/module.yaml"), "id: economy\n");
        write(m.resolve("economy/console/plugin.yaml"), "format: 2\n");
        return m;
    }

    @Test
    void aSutraOverrideIsReadFromTheViewAndRemovingItRestoresTheModules() throws Exception {
        Path modules = modules();
        Path a = tmp.resolve("over-a");
        Path b = tmp.resolve("over-b");
        write(a.resolve("banking/packs/trading/sutras/endur/endur-cash.v1.sutra.yaml"), "title: Endur cash (a)\n");
        write(b.resolve("banking/packs/trading/sutras/endur/endur-cash.v1.sutra.yaml"), "title: Endur cash (b, right-most)\n");
        write(a.resolve("economy/docs/help.yaml"), "pages: []\n");
        Path view = tmp.resolve("data/overrides/modules");

        ModuleOverrides.Result r = ModuleOverrides.apply(modules, List.of(a, b), view);

        assertThat(r.view()).isEqualTo(view);
        assertThat(r.refused()).isEmpty();
        assertThat(r.files()).extracting(ModuleOverrides.File::path)
                .containsExactly("banking/packs/trading/sutras/endur/endur-cash.v1.sutra.yaml", "economy/docs/help.yaml");
        assertThat(r.files().get(0).source()).isEqualTo(b.resolve("banking/packs/trading/sutras/endur/endur-cash.v1.sutra.yaml"));
        assertThat(r.files().get(0).replaces()).isTrue();
        assertThat(r.files().get(1).replaces()).isFalse();                                    // added: the module has no such file
        assertThat(Files.readString(view.resolve("banking/packs/trading/sutras/endur/endur-cash.v1.sutra.yaml"))).contains("(b, right-most)");
        assertThat(Files.readString(view.resolve("banking/packs/trading/pack.yaml"))).isEqualTo("pack: trading\n");   // the rest as shipped
        assertThat(view.resolve("economy/console/plugin.yaml")).isRegularFile();
        assertThat(view.resolve("banking/java")).doesNotExist();                              // what only builds a module stays out
        assertThat(view.resolve("banking/pom.xml")).doesNotExist();
        assertThat(Files.readString(view.resolve(ModuleOverrides.LIST))).contains("modules-home: '" + modules + "'",
                "banking/packs/trading/sutras/endur/endur-cash.v1.sutra.yaml");
        assertThat(Files.readString(modules.resolve("banking/packs/trading/sutras/endur/endur-cash.v1.sutra.yaml")))
                .isEqualTo("title: Endur cash\n");                                            // the module itself is never written

        Files.delete(b.resolve("banking/packs/trading/sutras/endur/endur-cash.v1.sutra.yaml"));   // the next start: a's copy
        ModuleOverrides.apply(modules, List.of(a, b), view);
        assertThat(Files.readString(view.resolve("banking/packs/trading/sutras/endur/endur-cash.v1.sutra.yaml"))).contains("(a)");

        Files.delete(a.resolve("banking/packs/trading/sutras/endur/endur-cash.v1.sutra.yaml"));   // the economy help is still overridden
        ModuleOverrides.apply(modules, List.of(a, b), view);
        assertThat(Files.readString(view.resolve("banking/packs/trading/sutras/endur/endur-cash.v1.sutra.yaml"))).isEqualTo("title: Endur cash\n");

        Files.delete(a.resolve("economy/docs/help.yaml"));                                     // no override left: no view
        ModuleOverrides.Result none = ModuleOverrides.apply(modules, List.of(a, b), view);
        assertThat(none.view()).isNull();
        assertThat(view).doesNotExist();
    }

    @Test
    void theIdentityTheConnectorTemplatesAndTheCodeAreNeverOverridden() throws Exception {
        Path modules = modules();
        Path o = tmp.resolve("over");
        write(o.resolve("banking/module.yaml"), "id: other\n");
        write(o.resolve("banking/connectors/trade.yaml"), "plugin: jdbc\n");
        write(o.resolve("banking/java/banking-service/pom.xml"), "<project/>\n");
        write(o.resolve("nosuch/packs/x/pack.yaml"), "pack: x\n");
        write(o.resolve("loose.yaml"), "a: 1\n");

        ModuleOverrides.Result r = ModuleOverrides.apply(modules, List.of(o, tmp.resolve("missing")), tmp.resolve("view"));

        assertThat(r.view()).isNull();
        assertThat(r.files()).isEmpty();
        assertThat(String.join("\n", r.refused())).contains("module.yaml is its identity", "config/connectors/<name>.yaml",
                "java only builds the module", "no module 'nosuch'", "the same relative path", "is not a folder");
    }

    @Test
    void aModuleInstalledFromItsDmodIsReadFromItsViewWhileOverridden() throws Exception {
        Path home = tmp.resolve("store/installed/banking/1.0.0");
        write(home.resolve("module.yaml"), "id: banking\npacks: [trading]\n");
        write(home.resolve("packs/trading/sutras/endur/endur-cash.v1.sutra.yaml"), "title: Endur cash\n");
        write(home.resolve("java/drishti-banking.jar"), "jar");
        Path o = tmp.resolve("over");
        write(o.resolve("banking/packs/trading/sutras/endur/endur-cash.v1.sutra.yaml"), "title: Endur cash (site)\n");
        write(o.resolve("banking/module.yaml"), "id: other\n");
        try {
            ModuleOverrides.activate(List.of(o), tmp.resolve("data"));
            Path view = ModuleOverrides.installed(home, "banking");
            assertThat(view).isEqualTo(tmp.resolve("data/overrides/installed/banking/1.0.0"));
            assertThat(Files.readString(view.resolve("packs/trading/sutras/endur/endur-cash.v1.sutra.yaml"))).contains("(site)");
            assertThat(Files.readString(view.resolve("module.yaml"))).contains("id: banking");         // the identity is never overridden
            assertThat(view.resolve("java")).doesNotExist();
            assertThat(ModuleOverrides.installed(home, "economy")).isEqualTo(home);                   // nothing for it: its own folder

            Files.delete(o.resolve("banking/packs/trading/sutras/endur/endur-cash.v1.sutra.yaml"));
            Files.delete(o.resolve("banking/module.yaml"));
            assertThat(ModuleOverrides.installed(home, "banking")).isEqualTo(home);
            assertThat(view).doesNotExist();
            assertThat(tmp.resolve("data/overrides/installed")).doesNotExist();
        } finally {
            ModuleOverrides.activate(List.of(), null);
        }
    }

    @Test
    void theSettingNamesFoldersAbsoluteOrUnderTheHome() {
        Path home = tmp.resolve("home");
        assertThat(ModuleOverrides.dirs(" /abs/a , rel/b ,, ", home)).containsExactly(Path.of("/abs/a"), home.resolve("rel/b").toAbsolutePath());
        assertThat(ModuleOverrides.dirs("", home)).isEmpty();
        assertThat(ModuleOverrides.dirs(null, home)).isEmpty();
    }

    private static void write(Path f, String text) throws Exception {
        Files.createDirectories(f.getParent());
        Files.writeString(f, text);
    }
}
