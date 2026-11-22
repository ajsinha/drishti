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
import org.springframework.core.env.PropertySource;

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
        Map<String, ModuleSettingsSchema> schemas;
        try {
            schemas = schemas(env);
        } catch (RuntimeException e) {
            System.getLogger(ModuleSettingsEnvironmentPostProcessor.class.getName()).log(System.Logger.Level.WARNING,
                    "module settings: the manifests could not be read: {0}", e.getMessage());
            return;
        }
        List<String> retired = new ArrayList<>();
        for (ModuleSettingsSchema s : schemas.values()) {
            retired.addAll(s.retiredLines(k -> siteSets(env, k)));
        }
        RetiredNames.refuse(retired);
        Map<String, Object> info = new LinkedHashMap<>();
        info.put(INFO + ".modules", String.join(",", schemas.keySet()));
        env.getPropertySources().addLast(new MapPropertySource(SOURCE + "-info", info));
    }

    /** The settings schema of every module whose manifest declares one, by module id (the module boot's modules, then the packaged ones). */
    public static Map<String, ModuleSettingsSchema> schemas(org.springframework.core.env.Environment env) {
        Map<String, ModuleSettingsSchema> out = new LinkedHashMap<>();
        for (com.ash.drishti.api.resolution.ModuleHomes.Home h : Distributions.homes(env).homes().values()) {
            Path f = h.dir().resolve("module.yaml");
            if (Files.isRegularFile(f)) {
                try {
                    add(out, YAML.readTree(f.toFile()));
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

    /** Where the site (not the product's defaults, nor an engine's shipped file) sets {@code key} (a property source's name), or null. The environment's own variables are never read here: {@code DRISHTI_VAR_THREADS} is a name the shipped engine file
     * gives its placeholder, not the key. */
    static String siteSets(ConfigurableEnvironment env, String key) {
        for (PropertySource<?> ps : env.getPropertySources()) {
            String n = ps.getName();
            if (n.startsWith(SOURCE) || "systemEnvironment".equals(n) || n.startsWith("drishti-edition") || n.startsWith("drishti-packs")
                    || (n.startsWith("Config resource") && n.contains("class path resource"))
                    || (n.startsWith("Config resource") && n.replace('\\', '/').matches("(?s).*engines/[a-z0-9-]+\\.yaml\\].*"))) {
                continue;
            }
            Object v = ps.containsProperty(key) ? ps.getProperty(key) : null;
            if (v != null && !v.toString().isBlank()) {
                return n;
            }
        }
        return null;
    }
}
