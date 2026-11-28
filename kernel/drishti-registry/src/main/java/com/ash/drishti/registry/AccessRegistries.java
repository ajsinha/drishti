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

import java.time.Clock;
import java.util.Locale;
import java.util.function.Function;

/**
 * Makes the registry from a server's settings, so the server's wiring never names the store: {@code drishti.modules.dir} (default
 * {@code <config>/modules}), {@code drishti.access.dir} (default {@code <config>/access}), each absolute (com.ash.drishti.api.Roots) and each module's {@code drishti.modules.<id>.enabled}
 * (set on the command line or in the environment, {@code DRISHTI_MODULES_<ID>_ENABLED}: it wins over the site's file).
 */
public final class AccessRegistries {

    private AccessRegistries() {
    }

    /**
     * The embedded registry over the YAML store, from the server's settings.
     *
     * @param property a setting of the server, resolved (null when not set)
     * @param loader   where the modules' shipped manifests are looked for (the server's class loader)
     */
    public static EmbeddedAccessRegistry embedded(Function<String, String> property, ClassLoader loader) {
        String modules = orDefault(property.apply("drishti.modules.dir"), com.ash.drishti.api.Roots.site("modules"));
        String access = orDefault(property.apply("drishti.access.dir"), com.ash.drishti.api.Roots.site("access"));
        Function<String, Boolean> overrides = id -> {
            String v = property.apply("drishti.modules." + id + ".enabled");
            if (v == null || v.isBlank()) {
                return null;
            }
            String s = v.strip().toLowerCase(Locale.ROOT);
            return "true".equals(s) || "on".equals(s) || "yes".equals(s);
        };
        // the module boot's result (the launch publishes it): each module's manifest is read from its folder
        com.ash.drishti.api.resolution.ModuleHomes booted = com.ash.drishti.api.resolution.ModuleHomes.of(property).orElse(null);
        return new EmbeddedAccessRegistry(new YamlAccessStore(com.ash.drishti.api.Roots.anchored(com.ash.drishti.api.Roots.config("modules")), com.ash.drishti.api.Roots.anchored(modules), com.ash.drishti.api.Roots.anchored(access), loader,
                () -> booted), overrides, Clock.systemUTC());
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
