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
import java.nio.file.DirectoryStream;
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
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

/**
 * A module's settings, described by its manifest ({@link ModuleSettingsSchema}) and read as {@code drishti.module.<id>.*}. A key a site sets
 * there is also given to the server key it replaces (the module's key wins), so the code that still reads the old key sees it; the old keys a
 * site still sets are listed for Health ({@link #INFO}{@code .deprecated}) and read for one release.
 */
public final class ModuleSettingsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    public static final String SOURCE = "drishti-module-settings";
    /** Where the notes and the module list are put for Health and Admin. */
    public static final String INFO = "drishti.module-settings-info";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 12;
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
        Map<String, Object> oldKeys = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        for (ModuleSettingsSchema s : schemas.values()) {
            oldKeys.putAll(s.asOldKeys(env::getProperty));
            notes.addAll(s.deprecated(k -> siteSets(env, k)));
        }
        if (!oldKeys.isEmpty()) {
            env.getPropertySources().addFirst(new MapPropertySource(SOURCE, new LinkedHashMap<>(oldKeys)));
        }
        Map<String, Object> info = new LinkedHashMap<>();
        info.put(INFO + ".modules", String.join(",", schemas.keySet()));
        info.put(INFO + ".deprecated", String.join("\n", notes));
        env.getPropertySources().addLast(new MapPropertySource(SOURCE + "-info", info));
    }

    /** The settings schema of every module whose manifest declares one, by module id (the manifests folder, then the packaged modules). */
    public static Map<String, ModuleSettingsSchema> schemas(org.springframework.core.env.Environment env) {
        Map<String, ModuleSettingsSchema> out = new LinkedHashMap<>();
        Path dir = Path.of(env.getProperty(Distributions.MODULES_DIR, Distributions.DEFAULT_MODULES_DIR));
        if (Files.isDirectory(dir)) {
            try (DirectoryStream<Path> modules = Files.newDirectoryStream(dir, Files::isDirectory)) {
                for (Path m : modules) {
                    for (Path f : List.of(m.resolve("module.yaml"), m.resolve("src/dmod/module.yaml"), m.resolve("dmod").resolve("module.yaml"))) {
                        if (Files.isRegularFile(f)) {
                            add(out, YAML.readTree(f.toFile()));
                            break;
                        }
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("module manifests under " + dir + " cannot be read: " + e.getMessage(), e);
            }
        }
        for (com.ash.drishti.registry.ModulePackages.Package p : Distributions.packages(env)) {
            add(out, YAML.valueToTree(p.manifest()));
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

    /** Whether the site (not the product's defaults, nor an engine's shipped file) sets {@code key}. */
    static boolean siteSets(ConfigurableEnvironment env, String key) {
        for (PropertySource<?> ps : env.getPropertySources()) {
            String n = ps.getName();
            if (n.startsWith(SOURCE) || n.startsWith("drishti-edition") || n.startsWith("drishti-packs") || !(ps instanceof EnumerablePropertySource<?> e)
                    || (n.startsWith("Config resource") && n.contains("class path resource"))
                    || (n.startsWith("Config resource") && n.replace('\\', '/').matches("(?s).*engines/[a-z0-9-]+\\.yaml\\].*"))) {
                continue;
            }
            Object v = ps.getProperty(key);
            if (v != null && !v.toString().isBlank()) {
                return true;
            }
            for (String name : e.getPropertyNames()) {                       // the environment's form: DRISHTI_VAR_CONFIDENCE is not a key of this kind
                if (name.equals(key)) {
                    return true;
                }
            }
        }
        return false;
    }
}
