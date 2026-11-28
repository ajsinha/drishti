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
package com.ash.drishti.packs.module;

import com.ash.drishti.packs.distribution.Distributions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * A module's settings, described by its manifest ({@link ModuleSettingsSchema}) and read as {@code drishti.module.<id>.*}. The server key a
 * setting had before is retired: a site that still sets it stops the start, naming the new key ({@link RetiredNames}); nothing reads it.
 */
public final class ModuleSettingsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    public static final String SOURCE = "drishti-module-settings";
    /** Where the notes and the module list are put for Health and Admin. */
    public static final String INFO = "drishti.module-settings-info";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 13;   // after the edition (+ 12), which is after the launch roots
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        Map<String, ModuleSettingsSchema> schemas = schemas(env);   // a manifest that cannot be read stops the start: its retired names would go unchecked
        List<Path> dirs = Distributions.homes(env).homes().values().stream().map(com.ash.drishti.api.resolution.ModuleHomes.Home::dir).toList();
        RetiredNames.Check check = RetiredNames.in(env, dirs);
        List<String> retired = new ArrayList<>();
        for (ModuleSettingsSchema s : schemas.values()) {
            retired.addAll(s.retiredLines(check::whereSet));
        }
        String modulesDir = env.getProperty("drishti.modules.dir");   // the site's module switch files (config/modules unless set)
        retired.addAll(RetiredModules.lines(com.ash.drishti.api.Roots.anchored(modulesDir == null || modulesDir.isBlank() ? com.ash.drishti.api.Roots.config("modules") : modulesDir.strip()),
                check::whereSet));
        RetiredNames.refuse(retired);
        Map<String, Object> info = new LinkedHashMap<>();
        info.put(INFO + ".modules", String.join(",", schemas.keySet()));
        env.getPropertySources().addLast(new MapPropertySource(SOURCE + "-info", info));
    }

    /**
     * The refusal lines of one module's retired setting names that a site sets (its manifest in {@code moduleDir}; none without one): the
     * check a module loaded after the start runs before it joins (the module host), as the start runs it for every module.
     */
    public static List<String> retired(ConfigurableEnvironment env, Path moduleDir) {
        Map<String, ModuleSettingsSchema> one = new LinkedHashMap<>();
        Path f = moduleDir == null ? null : moduleDir.resolve("module.yaml");
        if (f == null || !Files.isRegularFile(f)) {
            return List.of();
        }
        try {
            add(one, YAML.readTree(java.nio.file.Files.readAllBytes(f)));
        } catch (IOException e) {
            throw new IllegalStateException("the module manifest " + f + " cannot be read: " + e.getMessage(), e);
        }
        RetiredNames.Check check = RetiredNames.in(env, List.of(moduleDir));
        List<String> out = new ArrayList<>();
        one.values().forEach(s -> out.addAll(s.retiredLines(check::whereSet)));
        return out;
    }

    /** The settings schema of every module whose manifest declares one, by module id (the module boot's modules, then the packaged ones). */
    public static Map<String, ModuleSettingsSchema> schemas(org.springframework.core.env.Environment env) {
        Map<String, ModuleSettingsSchema> out = new LinkedHashMap<>();
        for (com.ash.drishti.api.resolution.ModuleHomes.Home h : Distributions.homes(env).homes().values()) {
            Path f = h.dir().resolve("module.yaml");
            if (Files.isRegularFile(f)) {
                try {
                    add(out, YAML.readTree(java.nio.file.Files.readAllBytes(f)));
                } catch (IOException e) {
                    throw new IllegalStateException("the module manifest " + f + " cannot be read: " + e.getMessage(), e);
                }
            }
        }
        return out;
    }

    private static void add(Map<String, ModuleSettingsSchema> out, JsonNode manifest) {
        String id = manifest.path("id").asText("");
        ModuleSettingsSchema s = id.isEmpty() ? null : ModuleSettingsSchema.of(id, manifest);
        if (s != null && !s.items().isEmpty()) {
            out.put(id, s);
        }
    }

    /** Where a site sets {@code key} (a property source's name, or {@code environment NAME}), or null: {@link RetiredNames#in}'s one rule. */
    static String siteSets(ConfigurableEnvironment env, String key) {
        return RetiredNames.in(env).whereSet(key);
    }
}
