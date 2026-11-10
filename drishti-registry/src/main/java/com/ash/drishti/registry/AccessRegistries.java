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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Locale;
import java.util.function.Function;

/**
 * Makes the registry from a server's settings, so the server's wiring never names the store: {@code drishti.modules.dir} (default
 * {@value #MODULES_DIR}), {@code drishti.access.dir} (default {@value #ACCESS_DIR}) and each module's {@code drishti.modules.<id>.enabled}
 * (set on the command line or in the environment, {@code DRISHTI_MODULES_<ID>_ENABLED}: it wins over the site's file).
 */
public final class AccessRegistries {

    /** The site's module files. */
    public static final String MODULES_DIR = "./config/modules";
    /** The site's access files. */
    public static final String ACCESS_DIR = "./config/access";
    /** The content modules' own manifests ({@code <id>/module.yaml}), {@code drishti.module-manifests-dir}. */
    public static final String MANIFESTS_DIR = "./modules";

    private AccessRegistries() {
    }

    /**
     * The embedded registry over the YAML store, from the server's settings.
     *
     * @param property a setting of the server, resolved (null when not set)
     * @param loader   where the modules' shipped manifests are looked for (the server's class loader)
     */
    public static EmbeddedAccessRegistry embedded(Function<String, String> property, ClassLoader loader) {
        String modules = orDefault(property.apply("drishti.modules.dir"), MODULES_DIR);
        String access = orDefault(property.apply("drishti.access.dir"), ACCESS_DIR);
        Function<String, Boolean> overrides = id -> {
            String v = property.apply("drishti.modules." + id + ".enabled");
            if (v == null || v.isBlank()) {
                return null;
            }
            String s = v.strip().toLowerCase(Locale.ROOT);
            return "true".equals(s) || "on".equals(s) || "yes".equals(s);
        };
        return new EmbeddedAccessRegistry(new YamlAccessStore(Path.of(modules), Path.of(access), loader, manifests(property)), overrides,
                Clock.systemUTC());
    }

    /**
     * The content modules' manifests folder: {@code drishti.module-manifests-dir} (default {@value #MANIFESTS_DIR}, the folder the
     * distributions read too); when that folder is not there (a server started in a scratch folder), the {@code modules} folder beside the
     * packs' {@code config} folder ({@code drishti.packs.dir}'s real path, two folders up).
     */
    static Path manifests(Function<String, String> property) {
        Path named = Path.of(orDefault(property.apply("drishti.module-manifests-dir"), MANIFESTS_DIR));
        if (Files.isDirectory(named)) {
            return named;
        }
        try {
            Path packs = Path.of(orDefault(property.apply("drishti.packs.dir"), "./config/packs")).toRealPath();
            Path beside = packs.getParent() == null || packs.getParent().getParent() == null ? null : packs.getParent().getParent().resolve("modules");
            return beside != null && Files.isDirectory(beside) ? beside : named;
        } catch (IOException | RuntimeException e) {
            return named;
        }
    }

    private static final java.util.Map<Object, EmbeddedAccessRegistry> SHARED = new java.util.WeakHashMap<>();

    /**
     * The one registry of a server, shared by everything that asks before the server's beans exist (the packs' start, the connector
     * plugins' discovery, the modules' conditions) and by the server's {@code AccessRegistry} bean: made the first time the key (the
     * server's environment) asks, held as long as the key lives.
     */
    public static synchronized EmbeddedAccessRegistry shared(Object key, Function<String, String> property, ClassLoader loader) {
        return SHARED.computeIfAbsent(key, k -> embedded(property, loader));
    }

    private static String orDefault(String v, String d) {
        return v == null || v.isBlank() ? d : v.strip();
    }
}
