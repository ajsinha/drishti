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
        return new EmbeddedAccessRegistry(new YamlAccessStore(Path.of(modules), Path.of(access), loader), overrides, Clock.systemUTC());
    }

    private static String orDefault(String v, String d) {
        return v == null || v.isBlank() ? d : v.strip();
    }
}
