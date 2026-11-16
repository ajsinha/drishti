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
package com.ash.drishti.server.console;

import com.ash.drishti.packs.distribution.Distribution;
import com.ash.drishti.packs.edition.Editions;
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
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The {@code distribution} part of the installation's answer (docs/architecture/THIN_CONSOLE_DESIGN.md 3.2, step S2): a distribution as
 * the console draws it, compiled here once, so the console reads no distribution, kernel or lens file. It holds decisions only: the
 * compiled visibility filter (the edition file's shape, {@code filter}), the brand, the console settings the file presets ({@code
 * settings}: the layer under the site's own, the pinned names and notices, and the feature presets mapped to console keys through
 * {@code kernel.yaml}'s {@code features:}), and each offered lens's arrangement ({@code lenses}). No folder and no file path is in it.
 */
public final class ConsoleDistribution {

    /** The brand keys and the console settings they fill ({@code ui.*}); the five names and notices ({@link #NAMES}) are pinned. */
    static final Map<String, String> BRAND_KEYS = brandKeys();
    /** The product's name and legal notices: the distribution file is their one home. */
    static final List<String> NAMES = List.of("product", "tagline", "owner", "copyright", "notice");

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Pattern LENS = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");

    private ConsoleDistribution() {
    }

    private static Map<String, String> brandKeys() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String k : List.of("product", "product_native", "tagline", "owner", "copyright", "notice", "logo", "favicon", "about", "headline",
                "headline_second", "headline_second_accent")) {
            m.put(k, k);
        }
        return m;
    }

    /**
     * The distribution as the console draws it.
     *
     * @param d        the compiled distribution
     * @param lensesDir the folder of the lens files ({@code config/lenses/<module>/<lens>.yaml})
     * @param offered  the lenses the server offers (its {@code drishti.lenses}, those of modules that are off taken out)
     * @param mapping  {@code kernel.yaml}'s {@code features:} for the console side: feature to console keys
     */
    public static Map<String, Object> of(Distribution d, Path lensesDir, List<String> offered, Map<String, List<String>> mapping) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", d.id());
        out.put("name", d.shortName());
        out.put("title", d.title());
        out.put("all", d.all());
        out.put("modules", d.modules());
        out.put("domains", new java.util.TreeMap<>(d.domains()));
        out.put("packs", d.packs());
        Map<String, Object> brand = new LinkedHashMap<>(d.brand());
        out.put("brand", brand);
        JsonNode theme = d.spec().path("theme");
        Map<String, Object> th = new LinkedHashMap<>();
        for (String k : List.of("tokens", "density", "mode")) {
            th.put(k, theme.path(k).asText(""));
        }
        out.put("theme", th);
        String helpTitle = d.spec().path("docs").path("title").asText("");
        out.put("help-title", helpTitle.isBlank() ? (d.title().isBlank() ? "" : d.title() + " Help") : helpTitle);
        Map<String, Object> filter = d.all() || d.editionSpec() == null ? null : YAML.convertValue(d.editionSpec(), Map.class);
        out.put("filter", filter);
        Map<String, Object> lenses = new LinkedHashMap<>();
        String def = d.spec().path("lenses").path("default").asText("");
        lenses.put("default", offered.contains(def) ? def : "");
        lenses.put("offered", offered);
        Map<String, Object> specs = new LinkedHashMap<>();
        for (String name : offered) {
            Map<String, Object> spec = lens(lensesDir, name);
            if (spec != null) {
                specs.put(name, spec);
            }
        }
        lenses.put("specs", specs);
        out.put("lenses", lenses);
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("layer", layer(d, brand, theme, helpTitle));
        Map<String, Object> pinned = new LinkedHashMap<>();
        NAMES.stream().filter(brand::containsKey).forEach(k -> pinned.put(k, brand.get(k)));
        settings.put("pinned", Map.of("ui", pinned));
        settings.put("presets", filter == null ? null : presets(filter, mapping));
        out.put("settings", settings);
        out.put("feature-keys", new java.util.TreeMap<>(mapping));    // which console settings each feature switches (kernel.yaml features:)
        return out;
    }

    /** The console settings the file presets under the site's own: the brand (not its names), the support links, the theme, Help's title. */
    private static Map<String, Object> layer(Distribution d, Map<String, Object> brand, JsonNode theme, String helpTitle) {
        Map<String, Object> ui = new LinkedHashMap<>();
        BRAND_KEYS.forEach((k, key) -> {
            if (brand.get(k) != null && !NAMES.contains(k)) {
                ui.put(key, brand.get(k));
            }
        });
        JsonNode support = d.spec().path("brand").path("support");
        if (support.isObject() && (!support.path("email").asText("").isBlank() || !support.path("url").asText("").isBlank())) {
            ui.put("support", Map.of("email", support.path("email").asText(""), "url", support.path("url").asText("")));
        }
        for (String[] t : new String[][] {{"tokens", "default_theme"}, {"density", "default_density"}, {"mode", "default_mode"}}) {
            if (!theme.path(t[0]).asText("").isBlank()) {
                ui.put(t[1], theme.path(t[0]).asText());
            }
        }
        if (!d.all()) {                                                  // Drishti All keeps Help as it was
            ui.put("help_title", helpTitle.isBlank() ? d.title() + " Help" : helpTitle);
        }
        return Map.of("ui", ui);
    }

    /**
     * The console settings the filter presets (a layer under the site's configuration): each named feature's console keys, {@code
     * ui.product} and {@code ui.tagline} from its branding, Home's quick cards and the placeholder, and the raw {@code console:} block.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> presets(Map<String, Object> filter, Map<String, List<String>> mapping) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (filter.get("features") instanceof Map<?, ?> f) {
            f.forEach((feature, value) -> {
                Boolean on = on(value);
                for (String key : mapping.getOrDefault(String.valueOf(feature), List.of())) {
                    put(out, key, Boolean.TRUE.equals(on));
                }
            });
        }
        if (filter.get("branding") instanceof Map<?, ?> b) {
            for (String k : List.of("product", "tagline")) {
                if (b.get(k) != null && !String.valueOf(b.get(k)).isBlank()) {
                    put(out, "ui." + k, String.valueOf(b.get(k)));
                }
            }
        }
        if (filter.get("home") instanceof Map<?, ?> h && h.get("cards") instanceof List<?> cards) {
            put(out, "ui.home_cards", cards);
        }
        if (filter.get("menus") instanceof Map<?, ?> m && m.get("placeholder") != null && !String.valueOf(m.get("placeholder")).isBlank()) {
            put(out, "ui.placeholder", m.get("placeholder"));
        }
        if (filter.get("console") instanceof Map<?, ?> c) {
            merge(out, (Map<String, Object>) c);
        }
        return out;
    }

    /** A switch as a file writes it: {@code on/off, true/false, yes/no}; null when unset. */
    static Boolean on(Object v) {
        if (v == null || "".equals(v)) {
            return null;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        String t = String.valueOf(v).strip().toLowerCase(Locale.ROOT);
        return !(t.equals("off") || t.equals("false") || t.equals("no") || t.equals("0"));
    }

    @SuppressWarnings("unchecked")
    private static void put(Map<String, Object> out, String dotted, Object value) {
        Map<String, Object> node = out;
        String[] parts = dotted.split("\\.");
        for (int i = 0; i < parts.length - 1; i++) {
            node = (Map<String, Object>) node.computeIfAbsent(parts[i], k -> new LinkedHashMap<String, Object>());
        }
        node.put(parts[parts.length - 1], value);
    }

    @SuppressWarnings("unchecked")
    private static void merge(Map<String, Object> base, Map<String, Object> over) {
        over.forEach((k, v) -> {
            if (v instanceof Map<?, ?> vm && base.get(k) instanceof Map<?, ?> bm) {
                Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) bm);
                merge(copy, (Map<String, Object>) vm);
                base.put(String.valueOf(k), copy);
            } else {
                base.put(String.valueOf(k), v);
            }
        });
    }

    /**
     * A lens's file as data ({@code config/lenses/<lens>.yaml}, else {@code config/lenses/<module>/<lens>.yaml}): its arrangement only
     * (a lens never hides). Null when there is no such file or it does not read.
     */
    public static Map<String, Object> lens(Path dir, String name) {
        if (dir == null || name == null || !LENS.matcher(name).matches()) {
            return null;
        }
        Path f = dir.resolve(name + ".yaml");
        if (!Files.isRegularFile(f) && Files.isDirectory(dir)) {
            List<Path> sub = new ArrayList<>();
            try (DirectoryStream<Path> s = Files.newDirectoryStream(dir, Files::isDirectory)) {
                s.forEach(sub::add);
            } catch (IOException e) {
                return null;
            }
            f = sub.stream().sorted().map(p -> p.resolve(name + ".yaml")).filter(Files::isRegularFile).findFirst().orElse(f);
        }
        if (!Files.isRegularFile(f)) {
            return null;
        }
        try {
            return YAML.convertValue(Editions.read(f), Map.class);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
