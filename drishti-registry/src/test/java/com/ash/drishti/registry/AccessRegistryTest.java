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
package com.ash.drishti.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The access model's decisions over the YAML store (ACCESS_MODEL.md sections 1 to 4): module, licence, lens, permission. */
class AccessRegistryTest {

    @TempDir
    Path dir;

    Path jar;
    Path modules;
    Path access;
    final Map<String, Boolean> overrides = new HashMap<>();
    final Clock clock = Clock.fixed(Instant.parse("2026-11-07T12:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() throws IOException {
        jar = dir.resolve("jar");
        modules = dir.resolve("config/modules");
        access = dir.resolve("config/access");
        write(jar.resolve(YamlAccessStore.MANIFEST), """
                module:
                  id: banking
                  domain: banking
                  title: Banking and risk
                  contributes:
                    packages: [com.example.banking]
                    engines: [var, exposure]
                    packs: [market-risk]
                    connectors: [var-engine]
                    api: [/api/v1/risk]
                    functions:
                      - {word: VAR, api: [/api/v1/risk/var]}
                      - {word: EXP, api: [/api/v1/risk/exposure]}
                """);
        write(jar.resolve(YamlAccessStore.LENSES), """
                lenses:
                  - {id: risk, title: Risk, modules: [banking], functions: [VAR, EXP, TRD], home: /home/risk}
                  - {id: world, title: World, modules: [economy]}
                  - {id: cio, title: CIO, modules: []}
                """);
        write(modules.resolve("economy.yaml"), """
                id: economy
                domain: economy
                code: false
                contributes:
                  packs: [world-economy]
                """);
    }

    EmbeddedAccessRegistry registry() throws IOException {
        ClassLoader cl = new URLClassLoader(new URL[] {jar.toUri().toURL()}, null);
        return new EmbeddedAccessRegistry(new YamlAccessStore(modules, access, cl), overrides::get, clock);
    }

    @Test
    void developmentLicenceRunsEveryModuleAsShipped() throws IOException {
        EmbeddedAccessRegistry r = registry();
        assertThat(r.licence().development()).isTrue();
        assertThat(r.moduleOn("core")).isTrue();
        assertThat(r.moduleOn("banking")).isTrue();
        assertThat(r.moduleOn("economy")).isTrue();
        assertThat(r.modules()).extracting(ModuleState::id).containsExactly("core", "banking", "economy");
        assertThat(r.lenses()).extracting(Lens::id).containsExactly("risk", "world", "cio");
        assertThat(r.words()).containsExactlyInAnyOrder("VAR", "EXP");
        assertThat(r.warnings()).isEmpty();
    }

    @Test
    void moduleOffTakesItsClassesEnginesPacksConnectorsApiWordsAndLensesWithIt() throws IOException {
        write(modules.resolve("banking.yaml"), "enabled: false\n");
        EmbeddedAccessRegistry r = registry();
        assertThat(r.moduleOn("banking")).isFalse();
        assertThat(r.module("banking").orElseThrow().reason()).contains("switched off").contains("config/modules/banking.yaml");
        assertThat(r.classOn("com.example.banking.var.VarService")).isFalse();
        assertThat(r.classOn("com.example.core.Thing")).isTrue();
        assertThat(r.moduleOfEngine("var")).contains("banking");
        assertThat(r.moduleOfApi("/api/v1/risk/var/explorer")).contains("banking");
        assertThat(r.moduleOfApi("/api/v1/riskier")).isEmpty();
        assertThat(r.moduleOfConnector("var-engine")).contains("banking");
        assertThat(r.packOn("market-risk", "banking")).isFalse();
        assertThat(r.packOn("trading", "banking")).isFalse();          // by its domain: no module of the domain is on
        assertThat(r.packOn("core", "")).isTrue();
        assertThat(r.packOn("world-economy", "economy")).isTrue();
        assertThat(r.packOn("own", "health")).isTrue();               // a domain no module declares loads as the core's
        assertThat(r.words()).isEmpty();
        assertThat(r.lenses()).extracting(Lens::id).containsExactly("world", "cio");
    }

    @Test
    void theSettingWinsOverTheSiteFile() throws IOException {
        write(modules.resolve("banking.yaml"), "enabled: false\n");
        overrides.put("banking", true);
        EmbeddedAccessRegistry r = registry();
        assertThat(r.moduleOn("banking")).isTrue();
        assertThat(r.module("banking").orElseThrow().switchSource()).isEqualTo("setting drishti.modules.banking.enabled");
    }

    @Test
    void aLicenceNamesItsModulesAndItsDates() throws IOException {
        write(access.resolve("licence.yaml"), """
                licence:
                  customer: A bank
                  modules: [economy]
                  valid-to: 2027-12-31
                """);
        EmbeddedAccessRegistry r = registry();
        assertThat(r.licence().development()).isFalse();
        assertThat(r.licence().signed()).isFalse();
        assertThat(r.moduleOn("banking")).isFalse();
        assertThat(r.module("banking").orElseThrow().reason()).contains("not licensed").contains("does not include the module 'banking'");
        assertThat(r.moduleOn("economy")).isTrue();

        write(access.resolve("licence.yaml"), "licence: {customer: A bank, modules: ['*'], valid-to: 2026-01-31}\n");
        r.refresh();
        assertThat(r.moduleOn("economy")).isFalse();
        assertThat(r.module("economy").orElseThrow().reason()).contains("ended on 2026-01-31");
        assertThat(r.moduleOn("core")).isTrue();
    }

    @Test
    void aModuleWhoseRequirementIsOffIsOff() throws IOException {
        write(modules.resolve("aml.yaml"), "id: aml\ncode: false\nrequires: [banking]\n");
        write(modules.resolve("banking.yaml"), "enabled: false\n");
        EmbeddedAccessRegistry r = registry();
        assertThat(r.moduleOn("aml")).isFalse();
        assertThat(r.module("aml").orElseThrow().reason()).contains("needs the module 'banking'");
    }

    @Test
    void aCodeModuleNamedWithoutItsJarIsNotInstalled() throws IOException {
        write(modules.resolve("gov.yaml"), "id: gov\ndomain: gov\ncontributes: {packs: [budget]}\n");
        EmbeddedAccessRegistry r = registry();
        assertThat(r.module("gov").orElseThrow().installed()).isFalse();
        assertThat(r.module("gov").orElseThrow().reason()).contains("not installed");
        assertThat(r.packOn("budget", "gov")).isFalse();
    }

    @Test
    void rolesOpenLensesAndActions() throws IOException {
        write(access.resolve("roles.yaml"), """
                roles:
                  - {id: risk-analyst, lenses: [risk], actions: {risk: [view, edit]}}
                  - {id: viewer, lenses: ['*']}
                """);
        write(access.resolve("assignments.yaml"), """
                assignments:
                  - {subject: alice, roles: [risk-analyst], attributes: {desk: RATES}}
                  - {subject: 'group:econ', roles: [viewer]}
                """);
        EmbeddedAccessRegistry r = registry();
        Subject alice = new Subject("alice", Set.of(), Set.of(), false);
        Subject bob = new Subject("bob", Set.of("econ"), Set.of(), false);
        Subject carol = new Subject("carol", Set.of(), Set.of(), false);
        Subject admin = new Subject("root", Set.of(), Set.of(), true);
        assertThat(r.lensesFor(alice)).extracting(Lens::id).containsExactly("risk");
        assertThat(r.allowed(alice, "risk", "edit")).isTrue();
        assertThat(r.allowed(alice, "risk", "approve")).isFalse();
        assertThat(r.lensesFor(bob)).extracting(Lens::id).containsExactly("risk", "world", "cio");
        assertThat(r.allowed(bob, "world", "view")).isTrue();
        assertThat(r.allowed(bob, "world", "edit")).isFalse();
        assertThat(r.lensesFor(carol)).isEmpty();
        assertThat(r.lensesFor(admin)).hasSize(3);
    }

    @Test
    void withoutRolesEveryOfferedLensIsOpen() throws IOException {
        EmbeddedAccessRegistry r = registry();
        Subject anyone = new Subject("x", Set.of(), Set.of(), false);
        assertThat(r.lensesFor(anyone)).hasSize(3);
        assertThat(r.allowed(anyone, "risk", "view")).isTrue();
        assertThat(r.allowed(anyone, "nope", "view")).isFalse();
    }

    @Test
    void theAdministratorsSwitchIsWrittenAndHeard() throws IOException {
        EmbeddedAccessRegistry r = registry();
        AtomicInteger heard = new AtomicInteger();
        r.onChange(heard::incrementAndGet);
        r.setEnabled("banking", false, "admin");
        assertThat(Files.readString(modules.resolve("banking.yaml"))).contains("enabled: false");
        assertThat(r.moduleOn("banking")).isFalse();
        assertThat(heard.get()).isEqualTo(1);
        r.setEnabled("banking", true, "admin");
        assertThat(Files.readString(modules.resolve("banking.yaml"))).contains("enabled: true").doesNotContain("enabled: false");
        assertThat(r.moduleOn("banking")).isTrue();
        assertThatThrownBy(() -> r.setEnabled("core", false, "admin")).hasMessageContaining("always on");
        assertThatThrownBy(() -> r.setEnabled("nope", false, "admin")).hasMessageContaining("no module 'nope'");
    }

    @Test
    void aBadFileIsAWarningNeverFatal() throws IOException {
        write(modules.resolve("banking.yaml"), "enabled: [unclosed\n");
        write(access.resolve("lenses.yaml"), "lenses:\n  - {title: no id}\n");
        EmbeddedAccessRegistry r = registry();
        assertThat(r.moduleOn("banking")).isTrue();
        assertThat(r.warnings()).anyMatch(w -> w.contains("banking.yaml")).anyMatch(w -> w.contains("without an id"));
    }

    @Test
    void theSiteReplacesALensAndALensNeedingAnUnknownModuleIsNotOffered() throws IOException {
        write(access.resolve("lenses.yaml"), """
                lenses:
                  - {id: cio, title: Chief information officer, modules: []}
                  - {id: gov, modules: [gov]}
                """);
        EmbeddedAccessRegistry r = registry();
        assertThat(r.lenses()).extracting(Lens::title).contains("Chief information officer");
        assertThat(r.lensOffered("gov")).isFalse();
        assertThat(r.warnings()).anyMatch(w -> w.contains("the lens 'gov'"));
    }

    @Test
    void functionsBelongToOneModuleAndGoWithIt() throws IOException {
        EmbeddedAccessRegistry r = registry();
        Map<String, Boolean> ledger = new HashMap<>();
        r.functionSwitches(ledger::get);
        assertThat(r.moduleOfFunction("var")).contains("banking");
        assertThat(r.moduleOfFunction("BI")).isEmpty();                       // a word no module declares is the core's
        assertThat(r.functionOfApi("/api/v1/risk/var/explorer")).contains("VAR");
        assertThat(r.functionOfApi("/api/v1/risk/exposure.xlsx")).contains("EXP");
        assertThat(r.functionOfApi("/api/v1/pivot/query")).isEmpty();
        assertThat(r.functionOn("VAR")).isTrue();
        assertThat(r.lenses().get(0).functions()).containsExactly("VAR", "EXP", "TRD");
        Subject anyone = new Subject("x", Set.of(), Set.of(), false);
        assertThat(r.functionAllowed(anyone, "VAR")).isTrue();               // everyone until per-person grants (2.9.11.i)

        ledger.put("VAR", false);                                              // the administrator's switch (the mnemonic switch)
        assertThat(r.functionOn("VAR")).isFalse();
        assertThat(r.functionOffReason("VAR")).get().asString().contains("switched it off");
        assertThat(r.functionAllowed(anyone, "VAR")).isFalse();
        assertThat(r.functionOn("EXP")).isTrue();

        write(modules.resolve("banking.yaml"), "enabled: false\n");
        r.refresh();
        assertThat(r.functionOn("EXP")).isFalse();
        assertThat(r.functionOffReason("EXP")).get().asString().contains("module 'banking' is off");
        assertThat(r.functionOn("BI")).isTrue();
    }

    @Test
    void theStoreStampFollowsTheFiles() throws IOException {
        YamlAccessStore s = new YamlAccessStore(modules, access, new URLClassLoader(new URL[] {jar.toUri().toURL()}, null));
        long before = s.stamp();
        write(access.resolve("licence.yaml"), "licence: {modules: ['*']}\n");
        assertThat(s.stamp()).isNotEqualTo(before);
    }

    static void write(Path f, String text) throws IOException {
        Files.createDirectories(f.getParent());
        Files.writeString(f, text, StandardCharsets.UTF_8);
    }
}
