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

import com.ash.drishti.registry.AccessRegistries;
import com.ash.drishti.registry.EmbeddedAccessRegistry;
import java.util.Map;
import java.util.WeakHashMap;
import org.springframework.core.env.Environment;

/**
 * The access registry of a server's environment (docs/architecture/ACCESS_MODEL.md): one per environment, made the first time it is asked,
 * so the packs' start ({@link PackEnvironmentPostProcessor}), the modules' conditions and the server's {@code AccessRegistry} bean ask the
 * same one. The settings it reads are {@link AccessRegistries}'s.
 */
public final class Modules {

    private static final Map<Environment, EmbeddedAccessRegistry> BY_ENV = new WeakHashMap<>();

    private Modules() {
    }

    /** The registry of the environment. */
    public static synchronized EmbeddedAccessRegistry of(Environment env) {
        return BY_ENV.computeIfAbsent(env, e -> AccessRegistries.embedded(k -> property(e, k), Modules.class.getClassLoader()));
    }

    private static String property(Environment env, String key) {
        try {
            return env.getProperty(key);
        } catch (IllegalArgumentException e) {
            return null;                                     // a placeholder that does not resolve: not set
        }
    }
}
