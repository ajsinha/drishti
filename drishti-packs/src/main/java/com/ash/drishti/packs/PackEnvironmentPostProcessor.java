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
    /** The connector files, ahead of the packs' own values and behind the server's configuration. */
    public static final String FILES_SOURCE = "drishti-connector-files";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        String dir = env.getProperty("drishti.packs.dir", "./packs");
        String enabled = env.getProperty("drishti.packs.enabled", "finance");
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>(
                Arrays.stream(enabled.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
        names.addAll(org.springframework.boot.context.properties.bind.Binder.get(env)
                .bind("drishti.packs.added", org.springframework.boot.context.properties.bind.Bindable.listOf(String.class))
                .orElse(List.of()));
        String connDir = env.getProperty("drishti.sources.connectors-dir", System.getenv().getOrDefault("DRISHTI_CONNECTORS_DIR", "./config/connectors"));
        ConnectorFiles files = new ConnectorFiles(java.nio.file.Path.of(connDir));
        PackSettings overrides = new PackSettings(java.nio.file.Path.of(env.getProperty("drishti.packs.settings-dir", "./data/packs/settings")));
        PackLoader loader = new PackLoader(files);
        List<Pack> packs = loader.load(PackLoader.dirs(dir, env.getProperty("drishti.packs.installed-dir", "./data/packs/installed")),
                List.copyOf(names));
        // connector files: migrate old per-pack overrides once, write files from the packs' templates where none exist
        ConnectorBootstrap.Result boot = ConnectorBootstrap.run(packs, files, overrides,
                Boolean.parseBoolean(env.getProperty("drishti.sources.connectors-generate", "true")));
        // the packs' own properties are computed after the files exist, so a template is left out where a file stands in for it
        env.getPropertySources().addLast(new MapPropertySource(SOURCE, loader.properties(packs)));
        java.util.Map<String, String> problems = new java.util.LinkedHashMap<>();
        java.util.Map<String, Object> fileProps = new java.util.LinkedHashMap<>(ConnectorFiles.properties(files.readAll(problems)));
        for (int i = 0; i < boot.messages().size(); i++) {
            fileProps.put("drishti.sources.connectors-bootstrap[" + i + "]", boot.messages().get(i));
        }
        for (int i = 0; i < boot.generated().size(); i++) {
            fileProps.put("drishti.sources.connectors-generated[" + i + "]", boot.generated().get(i));
        }
        for (int i = 0; i < boot.migrated().size(); i++) {
            fileProps.put("drishti.sources.connectors-migrated[" + i + "]", boot.migrated().get(i));
        }
        problems.forEach((n, m) -> fileProps.put("drishti.sources.connector-file-problems." + n, m));
        env.getPropertySources().addBefore(SOURCE, new MapPropertySource(FILES_SOURCE, fileProps));
    }
}
