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
package com.ash.drishti.packs;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The launch roots: every one absolute, from a setting or the anchor, never the process's folder; a missing required one stops the start. */
class LaunchRootsTest {

    private static LaunchRoots.Result resolve(Map<String, String> settings) {
        return resolve(settings, Map.of());
    }

    private static LaunchRoots.Result resolve(Map<String, String> settings, Map<String, String> origins) {
        return LaunchRoots.resolve(settings::get, k -> origins.getOrDefault(k, "(test)"));
    }

    private static Path install(Path dir) throws Exception {
        Files.createDirectories(dir.resolve("modules"));
        Files.createDirectories(dir.resolve("config/packs/core"));
        Files.createDirectories(dir.resolve("data/identity"));
        return dir;
    }

    @Test
    void everyRootIsUnderTheAnchorAndAbsolute(@TempDir Path tmp) throws Exception {
        Path home = install(tmp);
        LaunchRoots.Result r = resolve(Map.of("drishti.home", home.toString()));
        assertThat(r.problems()).isEmpty();
        assertThat(r.values()).containsEntry("drishti.modules.home", home.resolve("modules").toString())
                .containsEntry("drishti.packs.site-dir", home.resolve("config/packs").toString())
                .containsEntry("drishti.kernel.packs-dir", home.resolve("config/packs/core").toString())
                .containsEntry("drishti.data.dir", home.resolve("data").toString())
                .containsEntry("drishti.dataload.dir", home.resolve("data/dataload").toString())
                .containsEntry("lake.lakes.dir", home.resolve("config/lakes").toString())
                .containsEntry("drishti.sources.connectors-dir", home.resolve("config/connectors").toString());
        assertThat(r.values().values()).allSatisfy(v -> assertThat(Path.of(v.toString())).isAbsolute());
        assertThat(r.report()).startsWith("resolved paths:").contains("modules").contains("default under drishti.home");
    }

    @Test
    void explicitSettingsAloneNeedNoAnchor(@TempDir Path tmp) throws Exception {
        Path home = install(tmp);
        Map<String, String> s = new HashMap<>();
        s.put("drishti.data.dir", home.resolve("data").toString());
        s.put("drishti.config.dir", home.resolve("config").toString());
        s.put("drishti.modules.home", home.resolve("modules").toString());
        LaunchRoots.Result r = resolve(s);
        assertThat(r.problems()).isEmpty();
        assertThat(r.values()).containsEntry("drishti.packs.site-dir", home.resolve("config/packs").toString());
    }

    @Test
    void nothingNamedStopsTheStartNamingTheSettings() {
        LaunchRoots.Result r = resolve(Map.of());
        assertThat(String.join("; ", r.problems())).contains("modules: nothing names its folder").contains("drishti.modules.home")
                .contains("DRISHTI_MODULES_HOME").contains("drishti.home");
    }

    @Test
    void aModulesFolderThatIsNotThereStopsTheStartNamingWhereItLooked(@TempDir Path tmp) {
        LaunchRoots.Result r = resolve(Map.of("drishti.home", tmp.toString(), "drishti.data.dir", tmp.resolve("data").toString()));
        assertThat(r.problems()).singleElement().asString().contains("modules: " + tmp.resolve("modules") + " is not there")
                .contains("drishti.modules.home");
    }

    @Test
    void aRelativeSettingIsUnderTheAnchorNeverTheProcessFolder(@TempDir Path tmp) throws Exception {
        Path home = install(tmp);
        Files.createDirectories(home.resolve("elsewhere"));
        LaunchRoots.Result r = resolve(Map.of("drishti.home", home.toString(), "drishti.modules.home", "elsewhere"));
        assertThat(r.problems()).isEmpty();
        assertThat(r.values()).containsEntry("drishti.modules.home", home.resolve("elsewhere").toString());
        LaunchRoots.Result without = resolve(Map.of("drishti.modules.home", "modules", "drishti.data.dir", home.resolve("data").toString()));
        assertThat(String.join("; ", without.problems())).contains("'modules', a relative path").contains("drishti.home");
    }

    @Test
    void aDataFolderNobodyNamedWithNoIdentityIsRefused(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("modules"));
        LaunchRoots.Result defaulted = resolve(Map.of("drishti.home", tmp.toString()));
        assertThat(String.join("; ", defaulted.problems())).contains("data: " + tmp.resolve("data") + " was not named");
        LaunchRoots.Result shipped = resolve(Map.of("drishti.home", tmp.toString(), "drishti.data.dir", tmp.resolve("data").toString()),
                Map.of("drishti.data.dir", "(Config resource 'class path resource [application.yaml]' via location 'optional:classpath:/')"));
        assertThat(String.join("; ", shipped.problems())).contains("was not named");
        LaunchRoots.Result named = resolve(Map.of("drishti.home", tmp.toString(), "drishti.data.dir", tmp.resolve("fresh").toString()));
        assertThat(named.problems()).isEmpty();                                   // named outright: a new data folder is made
    }

    @Test
    void noneSaysThereAreNoShippedModuleFolders(@TempDir Path tmp) {
        LaunchRoots.Result r = resolve(Map.of("drishti.home", tmp.toString(), "drishti.modules.home", "none",
                "drishti.data.dir", tmp.resolve("data").toString()));
        assertThat(r.problems()).isEmpty();
        assertThat(r.values()).doesNotContainKey("drishti.modules.home");
    }

    @Test
    void extraPackFoldersMustBeThere(@TempDir Path tmp) throws Exception {
        Path home = install(tmp);
        LaunchRoots.Result r = resolve(Map.of("drishti.home", home.toString(), "drishti.packs.extra-dirs", home.resolve("gone").toString()));
        assertThat(String.join("; ", r.problems())).contains("extra packs: " + home.resolve("gone") + " (drishti.packs.extra-dirs) is not there");
    }
    @Test
    void everyOtherRelativePathSettingIsTakenUnderTheInstallFolder(@TempDir Path tmp) {
        Map<String, String> s = new java.util.LinkedHashMap<>();
        s.put("drishti.collab.dir", "site/collab");
        s.put("drishti.rachana.dirs", "sutras, " + tmp.resolve("abs"));
        s.put("drishti.reports.folder", tmp.resolve("reports").toString());
        s.put("drishti.packs.registry.url-file", "s3://bucket/x");
        s.put("drishti.store.root", "");
        s.put("drishti.feeds.path", "/{kind}/{id}");
        s.put("drishti.sources.routes.exposure-path", "ccr-lake");
        s.put("drishti.sources.plugins.x.settings.root", "$.ROOT");
        s.put("DRISHTI_INDEX_DIR", "idx");
        s.put("drishti.identity.database-url", "jdbc:sqlite:target/id/drishti.db");
        s.put("drishti.other.database-url", "jdbc:sqlite::memory:");
        s.put("drishti.ai.base-url", "localhost:8080");
        s.put("DRISHTI_LIB_PATH", "lib1,lib2");
        java.util.List<String> problems = new java.util.ArrayList<>();
        Map<String, String> out = LaunchRoots.anchorSettings(java.util.List.copyOf(s.keySet()), s::get, tmp, problems);
        assertThat(problems).isEmpty();
        assertThat(out).containsEntry("drishti.collab.dir", tmp.resolve("site/collab").toString())
                .containsEntry("drishti.rachana.dirs", tmp.resolve("sutras") + "," + " " + tmp.resolve("abs"))
                .containsEntry("DRISHTI_INDEX_DIR", tmp.resolve("idx").toString())
                .containsEntry("drishti.identity.database-url", "jdbc:sqlite:" + tmp.resolve("target/id/drishti.db"))
                .containsEntry("DRISHTI_LIB_PATH", tmp.resolve("lib1") + "," + tmp.resolve("lib2"))
                .doesNotContainKeys("drishti.other.database-url", "drishti.ai.base-url", "drishti.sources.routes.exposure-path", "drishti.sources.plugins.x.settings.root", "drishti.reports.folder", "drishti.packs.registry.url-file", "drishti.store.root", "drishti.feeds.path");
        assertThat(LaunchRoots.PATH_KEY.matcher("drishti.logging.max-files").matches()).isFalse();
        assertThat(LaunchRoots.PATH_ENV.matcher("DRISHTI_HOME").matches()).isFalse();
    }

    @Test
    void aRelativePathSettingWithNoInstallFolderStopsTheStart() {
        java.util.List<String> problems = new java.util.ArrayList<>();
        LaunchRoots.anchorSettings(java.util.List.of("drishti.collab.dir"), k -> "collab", null, problems);
        assertThat(String.join("; ", problems)).contains("drishti.collab.dir is 'collab', a relative path").contains("drishti.home");
    }

    private static final String GENOMICS = """
            format: 1
            id: genomics
            version: 0.1.0
            title: Genomics
            about: A module built in tests
            domain: testing
            publisher: {name: Drishti tests, id: drishti-tests}
            licence: {feature: genomics}
            kernel: ">=0.4"
            rachana: ">=1.0"
            contracts: ">=1.0"
            plugin-api: ">=2.0"
            elements: ">=2.4 <3"
            packs: {folder: packs, list: [genes]}
            """;

    /** The resolution of a server whose settings carry the boot's result: its packs folder from the boot. */
    private static com.ash.drishti.api.resolution.ConfigResolution resolution(Path home, Map<String, String> published) {
        Map<String, String> p = new HashMap<>(published);
        p.put("drishti.packs.site-dir", home.resolve("config/packs").toString());
        p.put("drishti.data.dir", home.resolve("data").toString());
        p.put(com.ash.drishti.module.runtime.ModuleBoot.HOME_SETTING, home.resolve("modules").toString());
        return com.ash.drishti.api.resolution.ConfigResolution.build(com.ash.drishti.api.resolution.ResolutionSettings.of(p::get), id -> true);
    }

    /**
     * i7a I5: when the modules folder holds packages the module boot runs on them (KERNEL_AND_MODULES.md section 4.2.1); its result is
     * published as settings, and the resolution reads each module from its cache folder, or from its own view when the site overrides a file.
     */
    @Test
    void theModuleBootRunsOnPackagesWhenTheModulesFolderHoldsAny(@TempDir Path tmp) throws Exception {
        Path home = install(tmp.resolve("home"));
        Path src = tmp.resolve("src/genomics");
        com.ash.drishti.module.runtime.Dmod.write(src.resolve("module.yaml"), GENOMICS);
        com.ash.drishti.module.runtime.Dmod.write(src.resolve("packs/genes/pack.yaml"), "pack: genes\n");
        com.ash.drishti.module.runtime.Dmod.pack(src, home.resolve("modules/genomics-0.1.0.dmod"));
        Files.createDirectories(home.resolve("modules/trading"));                          // a source folder beside the packages: not read
        Files.writeString(home.resolve("modules/trading/module.yaml"), "id: trading\nversion: 1.0.0\n");
        Path data = home.resolve("data");
        try {
            LaunchRoots.Booted b = LaunchRoots.bootModules(home.resolve("modules"), data, false, java.util.List.of(),
                    data.resolve("overrides/modules"), false);

            assertThat(b.homes().homes().keySet()).containsExactly("genomics");
            Path dir = b.homes().dir("genomics").orElseThrow();
            assertThat(dir).startsWith(data.resolve("cache")).isNotEqualTo(src);
            assertThat(b.properties()).containsKeys(com.ash.drishti.api.resolution.ModuleHomes.PROPERTY, "drishti.module.dir.genomics");
            assertThat(com.ash.drishti.api.resolution.ModuleHomes.current()).contains(b.homes());
            assertThat(resolution(home, b.properties()).packDir("genes")).isEqualTo(dir.resolve("packs/genes"));
            assertThat(resolution(home, b.properties()).packs().modules()).containsOnlyKeys("genomics");

            Path site = tmp.resolve("site-overrides");
            Files.createDirectories(site.resolve("genomics/packs/genes"));
            Files.writeString(site.resolve("genomics/packs/genes/notes.yaml"), "note: the site's\n");
            LaunchRoots.Booted v = LaunchRoots.bootModules(home.resolve("modules"), data, false, java.util.List.of(site),
                    data.resolve("overrides/modules"), false);
            Path view = v.homes().dir("genomics").orElseThrow();
            assertThat(view).startsWith(data.resolve("overrides/modules/genomics"));
            assertThat(view.resolve("packs/genes/notes.yaml")).isRegularFile();
            assertThat(dir.resolve("packs/genes/notes.yaml")).as("the cache folder is never written").doesNotExist();
            assertThat(v.overrides().view()).isEqualTo(data.resolve("overrides/modules"));
        } finally {
            com.ash.drishti.api.resolution.ModuleOverrides.activate(java.util.List.of(), null);
        }
    }

    /** With no package in the modules folder no module is booted (source folders are read only under the development switch). */
    @Test
    void withNoPackageNoModuleIsBootedUnlessDevelopment(@TempDir Path tmp) throws Exception {
        Path home = install(tmp.resolve("home"));
        Files.createDirectories(home.resolve("modules/trading"));
        Files.writeString(home.resolve("modules/trading/module.yaml"), GENOMICS.replace("genomics", "trading"));

        LaunchRoots.Booted b = LaunchRoots.bootModules(home.resolve("modules"), home.resolve("data"), false, java.util.List.of(),
                home.resolve("data/overrides/modules"), true);
        assertThat(b.homes().homes()).isEmpty();

        LaunchRoots.Booted d = LaunchRoots.bootModules(home.resolve("modules"), home.resolve("data"), true, java.util.List.of(),
                home.resolve("data/overrides/modules"), true);
        assertThat(d.homes().dir("trading")).contains(home.resolve("modules/trading").toAbsolutePath().normalize());
        assertThat(d.properties()).containsKeys("drishti.module.dir.trading", com.ash.drishti.api.resolution.ModuleHomes.PROPERTY);
        assertThat(LaunchRoots.bootModules(null, null, false, java.util.List.of(), null, true).homes().homes()).isEmpty();
    }
}
