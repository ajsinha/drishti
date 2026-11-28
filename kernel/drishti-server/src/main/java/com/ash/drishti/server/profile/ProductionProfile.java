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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.core.env.PropertyResolver;

/**
 * The production profile (docs/admin/PRODUCTION_STANDARD.md, section 1): {@code drishti.profile=production} (or the Spring profile
 * {@code prod} / {@code production}). Its safe defaults come from two switches {@link ProductionProfileProcessor} sets and
 * {@code application.yaml} reads; this class is the other half, the start check: it lists every unsafe setting, one line each naming
 * the setting and the fix, and the server does not start while the list is not empty. Pure: it reads a resolver and the files named.
 */
public final class ProductionProfile {

    /** The profile names. */
    public static final String PRODUCTION = "production";
    public static final String DEMO = "demo";
    /** The setting that names the profile. */
    public static final String KEY = "drishti.profile";
    /** The built-in first administrator's name and the password it ships with (never in production). */
    public static final String BUILT_IN_ADMIN = "drishti-dev-admin";

    private ProductionProfile() {
    }

    /** The profile this start runs: {@code production}, {@code demo}, or {@code development} (nothing named: today's open defaults). */
    public static String name(PropertyResolver env) {
        String v = env.getProperty(KEY);
        String n = v == null ? "" : v.strip().toLowerCase(Locale.ROOT);
        if (n.equals("prod")) {
            return PRODUCTION;
        }
        if (n.isEmpty() && env instanceof org.springframework.core.env.Environment e
                && e.acceptsProfiles(org.springframework.core.env.Profiles.of("prod", "production"))) {
            return PRODUCTION;
        }
        return n.isEmpty() ? "development" : n;
    }

    public static boolean production(PropertyResolver env) {
        return PRODUCTION.equals(name(env));
    }

    /** Every unsafe setting of this configuration, one line each (empty: safe to start). Empty too when the profile is not production. */
    public static List<String> refusals(PropertyResolver env) {
        List<String> out = new ArrayList<>();
        String profile = name(env);
        if (!PRODUCTION.equals(profile)) {
            return out;
        }
        if (!bool(env, "drishti.security.enabled", false)) {
            out.add("drishti.security.enabled is off: no one signs in. Fix: set DRISHTI_SECURITY_ENABLED=true (or remove it: production defaults to on)");
        }
        String secret = env.getProperty("drishti.security.secret", "");
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            out.add("drishti.security.secret (DRISHTI_TOKEN_SECRET) is missing or shorter than 32 bytes. Fix: set a random secret of at least 32 bytes, the same on the console");
        }
        if (bool(env, "drishti.identity.seed-admin", false)) {
            String user = env.getProperty("drishti.identity.seed-username", BUILT_IN_ADMIN);
            String password = env.getProperty("drishti.identity.seed-password", "");
            if (BUILT_IN_ADMIN.equals(user)) {
                out.add("drishti.identity.seed-admin creates the built-in administrator '" + BUILT_IN_ADMIN + "'. Fix: set DRISHTI_SEED_USERNAME to the first administrator you choose, or DRISHTI_SEED_ADMIN=false");
            }
            if (password.isBlank() || password.equals(com.ash.drishti.identity.IdentityProperties.DEFAULT_SEED_PASSWORD)) {
                out.add("drishti.identity.seed-password is empty or the built-in default. Fix: set DRISHTI_SEED_PASSWORD to your own (from the secrets file), or DRISHTI_SEED_ADMIN=false");
            }
        }
        if (bool(env, "drishti.identity.seed-demo-users", false)) {
            out.add("drishti.identity.seed-demo-users is on: the demo accounts have published passwords. Fix: remove DRISHTI_SEED_DEMO_USERS (production never creates them)");
        }
        if (bool(env, "drishti.sources.plugins.demo.enabled", false)) {
            out.add("drishti.sources.plugins.demo.enabled is on: the demo source would serve sample data. Fix: set DRISHTI_DEMO_ENABLED=false (or remove it: production defaults to off)");
        }
        if (bool(env, "drishti.modules.dev", false)) {
            out.add("drishti.modules.dev is on: unverified module folders would load. Fix: remove DRISHTI_MODULES_DEV");
        }
        if (bool(env, "drishti.rachana.studio-save", false)) {
            out.add("drishti.rachana.studio-save is on: Sutra Studio would write files on the server. Fix: remove DRISHTI_STUDIO_SAVE (Sutras go live by propose and approve)");
        }
        if (bool(env, "springdoc.api-docs.enabled", false) || bool(env, "springdoc.swagger-ui.enabled", false)) {
            out.add("springdoc.api-docs.enabled (the API description and its UI) is on. Fix: set DRISHTI_API_DOCS=false (or remove it: production defaults to off)");
        }
        if (!"pinned".equalsIgnoreCase(env.getProperty("drishti.engine-lib.trust", "folder").strip())) {
            out.add("drishti.engine-lib.trust is 'folder': any jar in the engine folder may run. Fix: set DRISHTI_ENGINE_LIB_TRUST=pinned and pin each engine (version, sha384)");
        }
        missingFile(env, "drishti.engine-lib.licence", "DRISHTI_ENGINE_LIB_LICENCE", "the licence file of the engines", out);
        String access = env.getProperty("drishti.access.dir");
        if (access == null || access.isBlank() || !Files.isRegularFile(Path.of(access).resolve("licence.yaml"))) {
            out.add("the licence file is missing: " + (access == null || access.isBlank() ? "drishti.access.dir is not set" : access + "/licence.yaml is not a file")
                    + ", so every module would run. Fix: put the licence you were issued there (DRISHTI_ACCESS_DIR)");
        }
        String site = env.getProperty(com.ash.drishti.api.Roots.SITE, "").strip();
        String home = env.getProperty(com.ash.drishti.api.Roots.HOME, "").strip();
        if (site.isEmpty()) {
            out.add("drishti.site.dir is not set: Admin's changes and the site's files would be written into the release folder. Fix: name the site folder outside the release (DRISHTI_SITE_DIR, /etc/drishti)");
        } else if (!home.isEmpty() && Path.of(site).toAbsolutePath().normalize().startsWith(Path.of(home).toAbsolutePath().normalize())) {
            out.add("drishti.site.dir (" + site + ") is inside the release folder (" + home + "), which is never written. Fix: name a site folder outside it (/etc/drishti)");
        }
        String db = env.getProperty("drishti.identity.database-url", "");
        if (db.startsWith("jdbc:sqlite") && clustered(env)) {
            out.add("drishti.identity.database-url is SQLite but this server is a node of a cluster. Fix: point every node at one PostgreSQL identity database (db.identity.url)");
        }
        String tls = env.getProperty("drishti.production.tls", "").strip().toLowerCase(Locale.ROOT);
        boolean ssl = bool(env, "server.ssl.enabled", false);
        if (!ssl && !tls.equals("proxy")) {
            out.add("TLS is not stated: the server speaks plain HTTP. Fix: enable server.ssl (key store), or declare that a proxy ends TLS in front with drishti.production.tls=proxy (DRISHTI_PRODUCTION_TLS=proxy)");
        }
        return out;
    }

    /** The one exception text of a refused start: a heading, then each refusal on its own line. */
    public static String message(List<String> refusals) {
        StringBuilder b = new StringBuilder("Drishti will not start under the production profile (docs/admin/PRODUCTION_STANDARD.md): ")
                .append(refusals.size()).append(refusals.size() == 1 ? " setting is unsafe" : " settings are unsafe");
        refusals.forEach(r -> b.append("\n  - ").append(r));
        return b.toString();
    }

    private static boolean clustered(PropertyResolver env) {
        String dir = env.getProperty("drishti.net.dir");
        if (dir == null || dir.isBlank()) {
            return false;
        }
        try {
            Path f = Path.of(dir).resolve("node.yaml");
            return Files.isRegularFile(f) && Files.readAllLines(f).stream().anyMatch(l -> l.strip().replaceAll("\\s+", " ").equals("enabled: true"));
        } catch (java.io.IOException | RuntimeException e) {
            return false;
        }
    }

    private static void missingFile(PropertyResolver env, String key, String var, String what, List<String> out) {
        String v = env.getProperty(key);
        if (v == null || v.isBlank() || !Files.isRegularFile(Path.of(v))) {
            out.add(key + " (" + var + ") is not a file" + (v == null || v.isBlank() ? "" : ": " + v) + ", so " + what + " would not be enforced. Fix: install the file you were issued and name it");
        }
    }

    private static boolean bool(PropertyResolver env, String key, boolean dflt) {
        String v = env.getProperty(key);
        return v == null || v.isBlank() ? dflt : Boolean.parseBoolean(v.strip());
    }
}
