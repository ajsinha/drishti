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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * Reads editions: the edition file ({@code config/editions/<name>.yaml}, folder {@code drishti.editions.dir}), its keys as properties
 * under {@link #SPEC}, and the {@link Edition} the environment describes. Also the one default pack list
 * ({@code config/editions/default-packs.yaml}, copied into this jar at build).
 */
public final class Editions {

    /** The edition's name: blank for the full Drishti. */
    public static final String NAME = "drishti.edition";
    /** The folder of the edition files. */
    public static final String DIR = "drishti.editions.dir";
    public static final String DEFAULT_DIR = "./config/editions";
    /**
     * Where the edition file's keys live as properties, so the site's configuration can override any one of them: for example
     * {@code drishti.edition-spec.packs.allow-only}, {@code drishti.edition-spec.commands.also-allow},
     * {@code drishti.edition-spec.bi.datasets}, {@code drishti.edition-spec.commands.words.form} (a word on or off).
     */
    public static final String SPEC = "drishti.edition-spec";

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Pattern NAME_RULE = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
    private static final List<String> DEFAULT_PACKS = readDefaultPacks();

    private Editions() {
    }

    /** The packs a server enables when neither {@code drishti.packs.enabled} nor an edition names them. */
    public static List<String> defaultPacks() {
        return DEFAULT_PACKS;
    }

    /** The edition the environment describes: {@link Edition#NONE} when {@code drishti.edition} is blank. */
    public static Edition of(Environment env) {
        String name = env.getProperty(NAME, "").strip();
        if (name.isEmpty()) {
            return Edition.NONE;
        }
        Binder b = Binder.get(env);
        java.util.Set<String> commands = new java.util.LinkedHashSet<>(list(b, SPEC + ".commands.allow"));
        commands.addAll(list(b, SPEC + ".commands.also-allow"));        // the site's additions, without restating the list
        Map<String, Boolean> words = new LinkedHashMap<>();
        for (String w : Edition.WORDS) {
            String v = env.getProperty(SPEC + ".commands.words." + w.toLowerCase(Locale.ROOT));
            if (v != null && !v.isBlank()) {
                words.put(w, on(v));
            }
        }
        Map<String, String> features = b.bind(SPEC + ".features", Bindable.mapOf(String.class, String.class)).orElse(Map.of());
        Map<String, String> branding = b.bind(SPEC + ".branding", Bindable.mapOf(String.class, String.class)).orElse(Map.of());
        return new Edition(name, env.getProperty(SPEC + ".title", ""), list(b, SPEC + ".packs.enabled"),
                on(env.getProperty(SPEC + ".packs.allow-only", "false")), commands, words, features, list(b, SPEC + ".admin.pages"),
                list(b, SPEC + ".bi.datasets"), list(b, SPEC + ".bi.models"), branding);
    }

    /** The site's lens list ({@code drishti.lenses}): the editions a person may open as a lens on a full instance. */
    public static final String LENSES = "drishti.lenses";

    /**
     * The editions a person may open as a <em>lens</em> (EDITIONS.md section 8): {@code drishti.lenses}, each read from its file alone (the
     * site's {@code drishti.edition-spec} overrides apply to the instance's edition only). None when the instance runs an edition itself.
     * A lens hides; it never secures: data access stays with roles, entitlements and masks.
     */
    public static Map<String, Edition> lenses(Environment env) {
        Map<String, Edition> out = new LinkedHashMap<>();
        if (!env.getProperty(NAME, "").isBlank()) {
            return out;
        }
        for (String n : list(Binder.get(env), LENSES)) {
            try {
                Path f = file(env, n);
                org.springframework.core.env.StandardEnvironment le = new org.springframework.core.env.StandardEnvironment();
                le.getPropertySources().remove(org.springframework.core.env.StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                le.getPropertySources().remove(org.springframework.core.env.StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                Map<String, Object> props = new LinkedHashMap<>(flatten(read(f), SPEC));
                props.put(NAME, n);
                le.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("lens " + n, props));
                out.put(n, of(le));
            } catch (RuntimeException e) {
                System.getLogger(Editions.class.getName()).log(System.Logger.Level.WARNING, "lens {0} is not offered: {1}", n, e.getMessage());
            }
        }
        return out;
    }

    /** True for {@code on}, {@code true}, {@code yes} (any case). */
    public static boolean on(String v) {
        String t = v == null ? "" : v.strip().toLowerCase(Locale.ROOT);
        return t.equals("on") || t.equals("true") || t.equals("yes");
    }

    /** The edition file of the name; refuses a name that is not a plain word and a file that does not exist. */
    public static Path file(Environment env, String name) {
        if (!NAME_RULE.matcher(name).matches()) {
            throw new IllegalStateException(NAME + "='" + name + "' is not an edition name (lower-case letters, digits and dashes)");
        }
        Path dir = Path.of(env.getProperty(DIR, DEFAULT_DIR));
        Path f = dir.resolve(name + ".yaml");
        if (!Files.isRegularFile(f)) {
            throw new IllegalStateException(NAME + "='" + name + "': no edition file " + f.toAbsolutePath().normalize()
                    + " (set " + DIR + " / DRISHTI_EDITIONS_DIR to the folder of the edition files, or blank " + NAME + " for the full Drishti)");
        }
        return f;
    }

    /** The edition file read as a tree. */
    public static JsonNode read(Path file) {
        try {
            JsonNode n = YAML.readTree(file.toFile());
            if (n == null || !n.isObject()) {
                throw new IllegalStateException("edition file " + file + " is not a YAML mapping");
            }
            return n;
        } catch (IOException e) {
            throw new IllegalStateException("edition file " + file + " cannot be read: " + e.getMessage(), e);
        }
    }

    /** The file's keys as flat properties under {@code prefix} ({@code a.b[0]}); map keys lower-cased so relaxed binding finds them. */
    public static Map<String, Object> flatten(JsonNode node, String prefix) {
        Map<String, Object> out = new LinkedHashMap<>();
        flatten(node, prefix, out);
        return out;
    }

    private static void flatten(JsonNode n, String key, Map<String, Object> out) {
        if (n.isObject()) {
            n.fields().forEachRemaining(e -> flatten(e.getValue(), key + "." + e.getKey().toLowerCase(Locale.ROOT), out));
        } else if (n.isArray()) {
            for (int i = 0; i < n.size(); i++) {
                flatten(n.get(i), key + "[" + i + "]", out);
            }
            if (n.isEmpty()) {
                out.put(key, "");
            }
        } else if (!n.isNull()) {
            out.put(key, n.asText());
        }
    }

    private static List<String> list(Binder b, String key) {
        return b.bind(key, Bindable.listOf(String.class)).orElse(List.of()).stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    private static List<String> readDefaultPacks() {
        try (InputStream in = Editions.class.getResourceAsStream("default-packs.yaml")) {
            if (in == null) {
                throw new IllegalStateException("default-packs.yaml is missing from the drishti-packs jar (config/editions/default-packs.yaml)");
            }
            List<String> out = new ArrayList<>();
            YAML.readTree(in).path("packs").forEach(p -> out.add(p.asText().strip()));
            return List.copyOf(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
