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

import java.util.Arrays;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Loads the enabled packs ({@code drishti.packs.enabled}, plus {@code drishti.packs.added}: the packs an administrator
 * loaded from Admin → Packs, kept in the overlay file), from {@code drishti.packs.dir}, before any bean is built, and
 * adds their content as the lowest-precedence property source, so the site's own configuration always wins over a
 * pack. The core modules only read ordinary properties and never depend on this module.
 */
public final class PackEnvironmentPostProcessor implements EnvironmentPostProcessor {

    public static final String SOURCE = "drishti-packs";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        String dir = env.getProperty("drishti.packs.dir", "./packs");
        String enabled = env.getProperty("drishti.packs.enabled", "finance");
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>(
                Arrays.stream(enabled.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
        names.addAll(org.springframework.boot.context.properties.bind.Binder.get(env)
                .bind("drishti.packs.added", org.springframework.boot.context.properties.bind.Bindable.listOf(String.class))
                .orElse(List.of()));
        PackLoader loader = new PackLoader();
        List<Pack> packs = loader.load(PackLoader.dirs(dir, env.getProperty("drishti.packs.installed-dir", "./data/packs/installed")),
                List.copyOf(names));
        env.getPropertySources().addLast(new MapPropertySource(SOURCE, loader.properties(packs)));
    }
}
