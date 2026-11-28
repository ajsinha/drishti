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
package com.ash.drishti.server.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * The production profile (docs/admin/PRODUCTION_STANDARD.md, section 1): a safe configuration starts, and each unsafe setting is refused by
 * one line that names the setting and the fix. Nothing is refused outside production.
 */
class ProductionProfileTest {

    @TempDir Path dir;
    Map<String, Object> safe;

    @BeforeEach
    void safeSet() throws IOException {
        Path licence = Files.writeString(dir.resolve("engines-licence.yaml"), "licence:\n  products: [var]\n");
        Path access = Files.createDirectories(dir.resolve("access"));
        Files.writeString(access.resolve("licence.yaml"), "licence: {}\n");
        safe = new java.util.LinkedHashMap<>(Map.of(
                "drishti.profile", "production",
                "drishti.security.enabled", "true",
                "drishti.security.secret", "a-production-secret-of-at-least-32-bytes",
                "drishti.identity.seed-admin", "false",
                "drishti.sources.plugins.demo.enabled", "false",
                "drishti.engine-lib.trust", "pinned",
                "drishti.engine-lib.licence", licence.toString(),
                "drishti.access.dir", access.toString(),
                "drishti.identity.database-url", "jdbc:postgresql://db.bank.internal:5432/drishti",
                "drishti.production.tls", "proxy"));
        safe.put("drishti.site.dir", dir.resolve("etc-drishti").toString());
    }

    private StandardEnvironment env(Map<String, Object> m) {
        StandardEnvironment e = new StandardEnvironment();
        e.getPropertySources().addFirst(new MapPropertySource("test", m));
        return e;
    }

    private List<String> refusals(Consumer<Map<String, Object>> change) {
        Map<String, Object> m = new java.util.LinkedHashMap<>(safe);
        change.accept(m);
        return ProductionProfile.refusals(env(m));
    }

    @Test
    void aSafeConfigurationIsNotRefused() {
        assertEquals(List.of(), ProductionProfile.refusals(env(safe)));
    }

    @Test
    void theProcessorStartsWithSafeSettingsAndSetsTheSafeDefaults() {
        StandardEnvironment e = env(safe);
        new ProductionProfileProcessor().postProcessEnvironment(e, new SpringApplication());
        assertEquals("true", e.getProperty("drishti.defaults.secure"));
        assertEquals("false", e.getProperty("drishti.defaults.open"));
    }

    @Test
    void theProcessorRefusesWithOneLinePerUnsafeSetting() {
        Map<String, Object> m = new java.util.LinkedHashMap<>(safe);
        m.put("drishti.security.enabled", "false");
        m.put("drishti.sources.plugins.demo.enabled", "true");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new ProductionProfileProcessor().postProcessEnvironment(env(m), new SpringApplication()));
        String[] lines = e.getMessage().split("\n");
        assertEquals(3, lines.length, e.getMessage());
        assertTrue(lines[0].contains("2 settings are unsafe"));
        assertTrue(lines[1].contains("drishti.security.enabled") && lines[1].contains("Fix:"));
        assertTrue(lines[2].contains("drishti.sources.plugins.demo.enabled") && lines[2].contains("Fix:"));
    }

    @Test
    void developmentAndDemoAreNeverRefused() {
        for (String profile : new String[] {"", "demo"}) {
            Map<String, Object> open = new java.util.LinkedHashMap<>();
            open.put("drishti.profile", profile);
            open.put("drishti.security.enabled", "false");
            open.put("drishti.identity.seed-admin", "true");
            assertEquals(List.of(), ProductionProfile.refusals(env(open)));
            StandardEnvironment e = env(open);
            new ProductionProfileProcessor().postProcessEnvironment(e, new SpringApplication());
            assertEquals("true", e.getProperty("drishti.defaults.open"));
        }
    }

    @Test
    void theSpringProfileProdIsProduction() {
        StandardEnvironment e = env(Map.of());
        e.setActiveProfiles("prod");
        assertTrue(ProductionProfile.production(e));
        assertTrue(ProductionProfile.refusals(e).size() >= 5);
    }

    @Test
    void signInOffIsRefused() {
        assertOne(refusals(m -> m.put("drishti.security.enabled", "false")), "drishti.security.enabled");
    }

    @Test
    void aShortOrMissingTokenSecretIsRefused() {
        assertOne(refusals(m -> m.put("drishti.security.secret", "short")), "drishti.security.secret");
        assertOne(refusals(m -> m.remove("drishti.security.secret")), "drishti.security.secret");
    }

    @Test
    void theBuiltInAdministratorIsRefused() {
        assertEquals(2, refusals(m -> m.put("drishti.identity.seed-admin", "true")).size());   // the built-in name and no password of its own
        List<String> own = refusals(m -> {
            m.put("drishti.identity.seed-admin", "true");
            m.put("drishti.identity.seed-username", "alice-admin");
            m.put("drishti.identity.seed-password", "a-first-admin-password-9");
        });
        assertEquals(List.of(), own);                                                               // a first administrator of your own is allowed
        assertOne(refusals(m -> {
            m.put("drishti.identity.seed-admin", "true");
            m.put("drishti.identity.seed-username", "alice-admin");
            m.put("drishti.identity.seed-password", "drishti-dev-admin123");
        }), "drishti.identity.seed-password");
    }

    @Test
    void demoUsersAreRefused() {
        assertOne(refusals(m -> m.put("drishti.identity.seed-demo-users", "true")), "drishti.identity.seed-demo-users");
    }

    @Test
    void theDemoSourceIsRefused() {
        assertOne(refusals(m -> m.put("drishti.sources.plugins.demo.enabled", "true")), "drishti.sources.plugins.demo.enabled");
    }

    @Test
    void developmentSwitchesAreRefused() {
        assertOne(refusals(m -> m.put("drishti.modules.dev", "true")), "drishti.modules.dev");
        assertOne(refusals(m -> m.put("drishti.rachana.studio-save", "true")), "drishti.rachana.studio-save");
        assertOne(refusals(m -> m.put("springdoc.api-docs.enabled", "true")), "springdoc.api-docs.enabled");
    }

    @Test
    void folderTrustAndAMissingLicenceAreRefused() {
        assertOne(refusals(m -> m.put("drishti.engine-lib.trust", "folder")), "drishti.engine-lib.trust");
        assertOne(refusals(m -> m.put("drishti.engine-lib.licence", dir.resolve("none.yaml").toString())), "drishti.engine-lib.licence");
        assertOne(refusals(m -> m.put("drishti.access.dir", dir.resolve("nowhere").toString())), "licence file is missing");
    }

    @Test
    void sqliteWithAClusterIsRefused() throws IOException {
        Path net = Files.createDirectories(dir.resolve("net"));
        Files.writeString(net.resolve("node.yaml"), "enabled: true\n");
        assertOne(refusals(m -> {
            m.put("drishti.identity.database-url", "jdbc:sqlite:/x/drishti.db");
            m.put("drishti.net.dir", net.toString());
        }), "drishti.identity.database-url");
        assertEquals(List.of(), refusals(m -> m.put("drishti.identity.database-url", "jdbc:sqlite:/x/drishti.db")));   // one node: allowed
    }

    @Test
    void plainHttpMustBeStated() {
        assertOne(refusals(m -> m.remove("drishti.site.dir")), "drishti.site.dir is not set");
        assertOne(refusals(m -> {
            m.put("drishti.home", dir.resolve("rel").toString());
            m.put("drishti.site.dir", dir.resolve("rel/config").toString());
        }), "inside the release folder");
        assertOne(refusals(m -> m.remove("drishti.production.tls")), "TLS is not stated");
        assertEquals(List.of(), refusals(m -> {
            m.remove("drishti.production.tls");
            m.put("server.ssl.enabled", "true");
        }));
    }

    private static void assertOne(List<String> lines, String names) {
        assertEquals(1, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains(names) && lines.get(0).contains("Fix:") && !lines.get(0).contains("\n"), lines.get(0));
    }
}
