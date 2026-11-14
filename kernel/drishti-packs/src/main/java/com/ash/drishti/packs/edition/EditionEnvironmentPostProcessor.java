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
import com.ash.drishti.packs.distribution.Distribution;
import com.ash.drishti.packs.distribution.Distributions;

/**
 * Loads the distribution's filter (docs/architecture/DISTRIBUTIONS.md) as a property layer just above the product's own defaults
 * ({@code application.yaml} in the jar) and below the site's configuration (application.local.yaml, the packs overlay, environment
 * variables, arguments), before the packs load (it runs right after the configuration files are read; the packs' post-processor runs
 * last). Drishti All: only the distribution's facts, no filter.
 *
 * <p>The layer holds the filter's keys under {@code drishti.edition-spec.*} (read by {@link Editions#of}) and the server settings the
 * distribution presets: {@code drishti.packs.enabled}, {@code drishti.branding.*}, and each feature it names through the mapping in
 * {@code kernel.yaml} beside the distribution files ({@code features:}). The three layers of switches (ADMIN_GUIDE.md): the distribution
 * says what exists, a module's switch whether it is on at this site, an engine's switch whether its compute runs here, so a distribution
 * presets an engine off (it does not exist there) and never on. A preset of a setting the product writes as {@code ${VARIABLE:default}}
 * keeps the variable: {@code DRISHTI_PACKS} set still wins over the distribution's packs. A lens presets nothing: it only arranges.
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
        Distribution dist = Distributions.of(env);                         // the distribution file: the one way (DISTRIBUTIONS.md)
        Map<String, Object> props = new LinkedHashMap<>(distributionProps(env, dist, deprecatedKeys(env, dist)));
        // the product's name and notices: the distribution file is their one home, above every setting a site made (the old keys are not read)
        Map<String, Object> brand = new LinkedHashMap<>();
        for (String k : List.of("product", "tagline", "owner", "copyright", "notice")) {
            String v = dist.brand().get(k);
            if (v != null) {
                brand.put("drishti.branding." + k, v);
            }
        }
        if (!brand.isEmpty()) {
            env.getPropertySources().addFirst(new MapPropertySource(SOURCE + "-brand", brand));
        }
        if (dist.all()) {
            addLayer(env, new MapPropertySource(SOURCE, props));
            env.getPropertySources().addFirst(new MapPropertySource(SOURCE + "-name", Map.of(Editions.NAME, "")));   // no filter at all
            return;                                                          // Drishti All: the full Drishti
        }
        String name = dist.id();
        JsonNode spec = dist.editionSpec();
        props.putAll(Editions.flatten(spec, Editions.SPEC));
        List<String> packs = new ArrayList<>();
        spec.path("packs").path("enabled").forEach(p -> {
            if (!packs.contains(p.asText().strip())) {
                packs.add(p.asText().strip());
            }
        });
        if (!packs.isEmpty() || spec.path("packs").path("none").asBoolean(false)) {   // a distribution of no packs (Genomics) has none
            props.put("drishti.packs.enabled", preset(env, "drishti.packs.enabled", String.join(",", packs)));
        }
        spec.path("branding").fields().forEachRemaining(e -> props.put("drishti.branding." + e.getKey(), preset(env, "drishti.branding." + e.getKey(), e.getValue().asText())));
        Map<String, List<String>> mapping = featureMapping(Distributions.dir(env), "server");   // kernel.yaml's features: beside the files
        spec.path("features").fields().forEachRemaining(e -> {
            String value = e.getValue().asText().strip();
            boolean off = value.equalsIgnoreCase("off") || value.equalsIgnoreCase("false");
            for (String key : mapping.getOrDefault(e.getKey(), List.of())) {
                if (off || !key.startsWith("drishti.engines.")) {            // an engine: off only; where it runs is its engine switch
                    props.put(key, preset(env, key, off ? "false" : "true"));
                }
            }
        });
        // a module the distribution does not carry (nor requires) is not loaded: the about answer does not list it, so the console draws
        // none of its menu entries, pages or assets (a site's own drishti.modules.<id>.enabled still wins)
        com.ash.drishti.packs.distribution.Distributions.known(env).keySet().stream()
                .filter(id -> !dist.modules().contains(id) && !"core".equals(id))
                .forEach(id -> props.put("drishti.modules." + id + ".enabled", preset(env, "drishti.modules." + id + ".enabled", "false")));
        addLayer(env, new MapPropertySource(SOURCE, props));
        env.getPropertySources().addFirst(new MapPropertySource(SOURCE + "-name", Map.of(Editions.NAME, name)));   // the distribution's name
        System.getLogger(EditionEnvironmentPostProcessor.class.getName()).log(System.Logger.Level.INFO,
                "distribution {0} ({1}) from {2}: packs {3}", name, dist.title(), dist.file().toAbsolutePath().normalize(),
                packs.isEmpty() ? "the default list" : packs);
    }

    private static void addLayer(ConfigurableEnvironment env, MapPropertySource layer) {
        String above = productDefaults(env);
        if (above == null) {
            env.getPropertySources().addLast(layer);
        } else {
            env.getPropertySources().addBefore(above, layer);
        }
    }

    /** Where the running distribution's facts live, for Health and the about answer. */
    public static final String INFO = "drishti.distribution-info";

    /** The settings the old product keys used to hold, as the distribution file says them (under the site's configuration). */
    static Map<String, Object> distributionProps(ConfigurableEnvironment env, Distribution d, List<String> deprecated) {
        Map<String, Object> props = new LinkedHashMap<>();
        if (!d.all()) {
            props.put(Editions.LENSES, String.join(",", d.lenses()));
        }
        props.put(INFO + ".id", d.id());
        props.put(INFO + ".title", d.title());
        props.put(INFO + ".file", d.file() == null ? "" : d.file().toAbsolutePath().normalize().toString());
        props.put(INFO + ".all", String.valueOf(d.all()));
        props.put(INFO + ".modules", String.join(",", d.modules()));
        props.put(INFO + ".domains", String.join(",", d.modules().stream().map(m -> m + "=" + d.domains().get(m)).toList()));
        props.put(INFO + ".deprecated", String.join("\n", deprecated));
        return props;
    }

    /**
     * The old product keys a site still sets ({@code drishti.edition}, {@code drishti.branding.*}, {@code DRISHTI_PRODUCT}), each named on
     * Health ({@code drishti.branding.*} is no longer read: the distribution file is the one way, KERNEL_AND_MODULES.md section 6).
     */
    static List<String> deprecatedKeys(ConfigurableEnvironment env, Distribution d) {
        List<String> out = new ArrayList<>();
        for (PropertySource<?> ps : env.getPropertySources()) {
            if (isProductDefaults(ps) || isEngineFile(ps) || ps.getName().startsWith(SOURCE)
                    || !(ps instanceof org.springframework.core.env.EnumerablePropertySource<?> e)) {
                continue;
            }
            for (String k : e.getPropertyNames()) {
                String key = k.equals("DRISHTI_EDITION") ? Editions.NAME : k.equals("DRISHTI_PRODUCT") ? "drishti.branding.product" : k;
                boolean old = key.equals(Editions.NAME) || key.startsWith("drishti.branding.");
                Object v = ps.getProperty(k);
                if (!old || v == null || v.toString().isBlank() || out.stream().anyMatch(o -> o.startsWith(key + " "))) {
                    continue;
                }
                out.add(key + (key.equals(Editions.NAME)
                        ? " is no longer read: a distribution says what exists (--distribution, DRISHTI_DISTRIBUTION); a lens only arranges"
                        : " is no longer read: set brand: in the distribution file"));
            }
        }
        return out;
    }

    /**
     * Which settings each feature of a distribution presets on one side ({@code server} or {@code console}): {@code features:} of
     * {@code kernel.yaml} in the folder of the distribution files, {@code feature -> {server: [keys], console: [keys]}}. Missing: no presets.
     */
    public static Map<String, List<String>> featureMapping(Path distributionsDir, String side) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        Path f = distributionsDir == null ? null : distributionsDir.resolve(Distributions.KERNEL);
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

    /**
     * The name of the highest product-default source (an application*.yaml inside a jar or on the class path), or of an engine's file
     * ({@code config/engines/<id>.yaml}, docs/guides/ADMIN_GUIDE.md section 3) when one is higher: an edition's presets sit above
     * both, so an edition that presets {@code var: off} still turns VaR off; null when there is none.
     */
    private static String productDefaults(ConfigurableEnvironment env) {
        for (PropertySource<?> ps : env.getPropertySources()) {
            if (isProductDefaults(ps) || isEngineFile(ps)) {
                return ps.getName();
            }
        }
        return null;
    }

    /** A site's engine file, imported by application.yaml from {@code DRISHTI_ENGINES_DIR} (default ./config/engines). */
    static boolean isEngineFile(PropertySource<?> ps) {
        String n = ps.getName().replace('\\', '/');
        return n.startsWith("Config resource") && n.contains("file [") && n.matches("(?s).*engines/[a-z0-9-]+\\.yaml\\].*");
    }

    private static boolean isProductDefaults(PropertySource<?> ps) {
        return ps.getName().startsWith("Config resource") && ps.getName().contains("class path resource");
    }
}
