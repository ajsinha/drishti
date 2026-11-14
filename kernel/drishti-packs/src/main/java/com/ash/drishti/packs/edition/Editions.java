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
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * Reads the distribution's filter: its keys as properties under {@link #SPEC} (written by {@link EditionEnvironmentPostProcessor} from
 * the distribution file) and the {@link Edition} the environment describes; the lens names the site offers. Also the one default pack list
 * ({@code config/packs/default-packs.yaml}, copied into this jar at build).
 */
public final class Editions {

    /** The filter's name (the distribution's id, set by the post-processor): blank for the full Drishti. */
    public static final String NAME = "drishti.edition";
    /**
     * Where the edition file's keys live as properties, so the site's configuration can override any one of them: for example
     * {@code drishti.edition-spec.packs.allow-only}, {@code drishti.edition-spec.commands.also-allow},
     * {@code drishti.edition-spec.bi.datasets}, {@code drishti.edition-spec.commands.words.form} (a word on or off).
     */
    public static final String SPEC = "drishti.edition-spec";

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
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
        // the core's words (Edition.WORDS) and any module's (its manifest's words: VAR, EXP ...): every word the edition names
        for (Map.Entry<String, String> w : b.bind(SPEC + ".commands.words", Bindable.mapOf(String.class, String.class)).orElse(Map.of()).entrySet()) {
            if (w.getValue() != null && !w.getValue().isBlank()) {
                words.put(w.getKey().toUpperCase(Locale.ROOT), on(w.getValue()));
            }
        }
        Map<String, String> features = b.bind(SPEC + ".features", Bindable.mapOf(String.class, String.class)).orElse(Map.of());
        Map<String, String> branding = b.bind(SPEC + ".branding", Bindable.mapOf(String.class, String.class)).orElse(Map.of());
        return new Edition(name, env.getProperty(SPEC + ".title", ""), packs(b, SPEC + ".packs.enabled"),
                on(env.getProperty(SPEC + ".packs.allow-only", "false")), commands, words, features, list(b, SPEC + ".admin.pages"),
                list(b, SPEC + ".bi.datasets"), list(b, SPEC + ".bi.models"), branding);
    }

    /** The site's lens list ({@code drishti.lenses}): the editions a person may open as a lens on a full instance. */
    public static final String LENSES = "drishti.lenses";

    /**
     * The lenses a person may open (LENSES.md): {@code drishti.lenses}. A lens is a workspace: it arranges (the starting Home, menu
     * emphasis, default screens) and never hides anything, so the server needs only the names; what exists is the distribution's.
     */
    public static List<String> lensNames(Environment env) {
        return list(Binder.get(env), LENSES);
    }

    /** True for {@code on}, {@code true}, {@code yes} (any case). */
    public static boolean on(String v) {
        String t = v == null ? "" : v.strip().toLowerCase(Locale.ROOT);
        return t.equals("on") || t.equals("true") || t.equals("yes");
    }

    /** A YAML file read as a tree. */
    public static JsonNode read(Path file) {
        try {
            JsonNode n = YAML.readTree(file.toFile());
            if (n == null || !n.isObject()) {
                throw new IllegalStateException("file " + file + " is not a YAML mapping");
            }
            return n;
        } catch (IOException e) {
            throw new IllegalStateException("file " + file + " cannot be read: " + e.getMessage(), e);
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

    /**
     * A lens's packs ({@code packs.enabled}): by module ({@code {core: [core], banking: [trading, ...]}}, in order) or a plain list (a site's
     * override); flattened in order, each pack once.
     */
    private static List<String> packs(Binder b, String key) {
        List<String> plain = list(b, key);
        if (!plain.isEmpty()) {
            return plain;
        }
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        b.bind(key, Bindable.mapOf(String.class, String[].class)).orElse(Map.of())
                .values().forEach(v -> java.util.Arrays.stream(v).map(String::strip).filter(x -> !x.isEmpty()).forEach(out::add));
        return List.copyOf(out);
    }

    private static List<String> list(Binder b, String key) {
        return b.bind(key, Bindable.listOf(String.class)).orElse(List.of()).stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    private static List<String> readDefaultPacks() {
        try (InputStream in = Editions.class.getResourceAsStream("default-packs.yaml")) {
            if (in == null) {
                throw new IllegalStateException("default-packs.yaml is missing from the drishti-packs jar (config/packs/default-packs.yaml)");
            }
            java.util.Set<String> out = new java.util.LinkedHashSet<>();          // by module, in order (a plain list is read as well)
            JsonNode packs = YAML.readTree(in).path("packs");
            (packs.isObject() ? packs.elements() : List.of(packs).iterator()).forEachRemaining(m -> m.forEach(p -> out.add(p.asText().strip())));
            return List.copyOf(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
