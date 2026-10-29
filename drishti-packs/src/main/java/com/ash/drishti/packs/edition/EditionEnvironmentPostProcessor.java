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
package com.ash.drishti.packs.edition;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

/**
 * Loads the edition {@code drishti.edition} names (docs/architecture/EDITIONS.md) as a property layer just above the product's own
 * defaults ({@code application.yaml} in the jar) and below the site's configuration (application.local.yaml, the packs overlay, environment
 * variables, arguments), before the packs load (it runs right after the configuration files are read; the packs' post-processor runs
 * last). Blank: nothing happens, the full Drishti.
 *
 * <p>The layer holds the file's keys under {@code drishti.edition-spec.*} (read by {@link Editions#of}) and the server settings the
 * edition presets: {@code drishti.packs.enabled}, {@code drishti.branding.product}/{@code tagline}, each feature of the edition through the
 * mapping in {@code features.yaml} beside the edition files, and the file's raw {@code server:} block. A preset of a setting the product
 * writes as {@code ${VARIABLE:default}} keeps the variable: {@code DRISHTI_PACKS} set still wins over the edition's packs.
 */
public final class EditionEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    public static final String SOURCE = "drishti-edition";
    /** Right after the configuration files are read (ConfigDataEnvironmentPostProcessor is HIGHEST_PRECEDENCE + 10). */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 11;
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{([A-Za-z0-9_.-]+):.*}$", Pattern.DOTALL);

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        String name = env.getProperty(Editions.NAME, "").strip();
        if (name.isEmpty()) {
            return;                                                          // the full Drishti, exactly as without editions
        }
        Path file = Editions.file(env, name);
        JsonNode spec = Editions.read(file);
        Map<String, Object> props = new LinkedHashMap<>(Editions.flatten(spec, Editions.SPEC));
        List<String> packs = new ArrayList<>();
        spec.path("packs").path("enabled").forEach(p -> packs.add(p.asText().strip()));
        if (!packs.isEmpty()) {
            props.put("drishti.packs.enabled", preset(env, "drishti.packs.enabled", String.join(",", packs)));
        }
        spec.path("branding").fields().forEachRemaining(e -> props.put("drishti.branding." + e.getKey(), preset(env, "drishti.branding." + e.getKey(), e.getValue().asText())));
        Map<String, List<String>> mapping = featureMapping(file.getParent(), "server");
        spec.path("features").fields().forEachRemaining(e -> {
            String value = e.getValue().asText().strip();
            for (String key : mapping.getOrDefault(e.getKey(), List.of())) {
                props.put(key, preset(env, key, value.equalsIgnoreCase("off") || value.equalsIgnoreCase("false") ? "false" : "true"));
            }
        });
        spec.path("server").fields().forEachRemaining(e -> props.putAll(Editions.flatten(e.getValue(), e.getKey())));
        MapPropertySource layer = new MapPropertySource(SOURCE, props);
        String above = productDefaults(env);
        if (above == null) {
            env.getPropertySources().addLast(layer);
        } else {
            env.getPropertySources().addBefore(above, layer);
        }
        System.getLogger(EditionEnvironmentPostProcessor.class.getName()).log(System.Logger.Level.INFO,
                "edition {0} ({1}) from {2}: packs {3}", name, spec.path("title").asText(name), file.toAbsolutePath().normalize(),
                packs.isEmpty() ? "the default list" : packs);
    }

    /**
     * Which settings each feature of an edition presets on one side ({@code server} or {@code console}): {@code features.yaml} beside the
     * edition files, {@code feature -> {server: [keys], console: [keys]}}. Missing: no feature presets.
     */
    public static Map<String, List<String>> featureMapping(Path dir, String side) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        Path f = dir == null ? null : dir.resolve("features.yaml");
        if (f == null || !Files.isRegularFile(f)) {
            return out;
        }
        Editions.read(f).path("features").fields().forEachRemaining(e -> {
            List<String> keys = new ArrayList<>();
            e.getValue().path(side).forEach(k -> keys.add(k.asText().strip()));
            out.put(e.getKey(), keys);
        });
        return out;
    }

    /** The value, inside the product default's own variable when the product writes the setting as {@code ${VARIABLE:default}}. */
    static String preset(ConfigurableEnvironment env, String key, String value) {
        for (PropertySource<?> ps : env.getPropertySources()) {
            if (isProductDefaults(ps)) {
                Object raw = ps.getProperty(key);
                if (raw != null) {
                    Matcher m = PLACEHOLDER.matcher(raw.toString().strip());
                    return m.matches() ? "${" + m.group(1) + ":" + value + "}" : value;
                }
            }
        }
        return value;
    }

    /** The name of the highest product-default source (an application*.yaml inside a jar or on the class path), or null. */
    private static String productDefaults(ConfigurableEnvironment env) {
        for (PropertySource<?> ps : env.getPropertySources()) {
            if (isProductDefaults(ps)) {
                return ps.getName();
            }
        }
        return null;
    }

    private static boolean isProductDefaults(PropertySource<?> ps) {
        return ps.getName().startsWith("Config resource") && ps.getName().contains("class path resource");
    }
}
