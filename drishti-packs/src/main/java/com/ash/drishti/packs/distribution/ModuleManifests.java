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
package com.ash.drishti.packs.distribution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The modules a distribution may list, read from their own manifests (docs/architecture/DISTRIBUTIONS.md section 10): a content module's
 * {@code modules/<id>/module.yaml} (the {@code .dmod} manifest) and a module with code's {@code META-INF/drishti/module.yaml} in its
 * sources ({@code modules/<id>/<part>/src/main/resources/...}). The console reads the same files the same way
 * (drishti-console/core/distribution.py). Each comes to its packs, its function words, the engines it owns, its top menus, the Help
 * domains it owns and the modules it requires; the list is in requirement order (a required module before the modules that need it).
 */
public final class ModuleManifests {

    /** One module as a distribution sees it. */
    public record Entry(String id, String domain, List<String> packs, List<String> words, List<String> features, List<String> menus,
            List<String> docs, List<String> requires, Path source) {
    }

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final String IN_JAR = "src/main/resources/META-INF/drishti/module.yaml";
    /** A module project made from the SDK's archetype keeps its {@code .dmod} manifest in {@code src/dmod/} (genomics). */
    private static final String SDK_PROJECT = "src/dmod/module.yaml";

    private ModuleManifests() {
    }

    /** Every module manifest under {@code dir}, by id, in requirement order; an empty map when the folder does not exist. */
    public static Map<String, Entry> read(Path dir) {
        Map<String, Entry> byId = new TreeMap<>();
        if (dir == null || !Files.isDirectory(dir)) {
            return byId;
        }
        try (DirectoryStream<Path> modules = Files.newDirectoryStream(dir, Files::isDirectory)) {
            for (Path m : modules) {
                Path dmod = Files.isRegularFile(m.resolve("module.yaml")) ? m.resolve("module.yaml") : m.resolve(SDK_PROJECT);
                if (Files.isRegularFile(dmod)) {
                    Entry e = ofDmod(tree(dmod), dmod);
                    byId.put(e.id(), e);
                }
                try (DirectoryStream<Path> parts = Files.newDirectoryStream(m, Files::isDirectory)) {
                    for (Path part : parts) {
                        Path jar = part.resolve(IN_JAR);
                        if (Files.isRegularFile(jar)) {
                            for (Entry e : ofJar(tree(jar), jar)) {
                                byId.put(e.id(), e);
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("module manifests under " + dir + " cannot be read: " + e.getMessage(), e);
        }
        Map<String, Entry> ordered = new LinkedHashMap<>();
        byId.keySet().forEach(id -> visit(id, byId, ordered, new LinkedHashSet<>()));
        return ordered;
    }

    /** The modules {@code listed} with every module they require, transitively (unknown ones are left to the caller). */
    public static Set<String> withRequired(List<String> listed, Map<String, Entry> known) {
        Set<String> out = new LinkedHashSet<>();
        List<String> todo = new ArrayList<>(listed);
        while (!todo.isEmpty()) {
            String id = todo.remove(0);
            if (out.add(id) && known.containsKey(id)) {
                todo.addAll(known.get(id).requires());
            }
        }
        return out;
    }

    private static void visit(String id, Map<String, Entry> byId, Map<String, Entry> out, Set<String> path) {
        Entry e = byId.get(id);
        if (e == null || out.containsKey(id) || !path.add(id)) {
            return;
        }
        e.requires().forEach(r -> visit(r, byId, out, path));
        out.put(id, e);
    }

    /** A {@code .dmod} manifest: packs.list, functions, compute.engines, console.menus, docs.domains, requires (not optional). */
    static Entry ofDmod(JsonNode m, Path source) {
        String id = m.path("id").asText("").strip();
        List<String> requires = new ArrayList<>();
        m.path("requires").fieldNames().forEachRemaining(k -> { if (!"optional".equals(k)) { requires.add(k); } });
        List<String> engines = new ArrayList<>();
        m.path("compute").path("engines").forEach(x -> engines.add(x.path("id").asText()));
        return new Entry(id, m.path("domain").asText(id), texts(m.path("packs").path("list")), words(m.path("functions")), engines,
                texts(m.path("console").path("menus")), texts(m.path("docs").path("domains")), requires, source);
    }

    /** A module jar's manifest ({@code module:} or a {@code modules:} list): contributes.packs, functions, engines, menus, help. */
    static List<Entry> ofJar(JsonNode root, Path source) {
        List<JsonNode> mods = new ArrayList<>();
        if (root.path("module").isObject()) {
            mods.add(root.path("module"));
        }
        root.path("modules").forEach(mods::add);
        List<Entry> out = new ArrayList<>();
        for (JsonNode m : mods) {
            String id = m.path("id").asText("").strip();
            JsonNode c = m.path("contributes");
            out.add(new Entry(id, m.path("domain").asText(id), texts(c.path("packs")), words(c.path("functions")), texts(c.path("engines")),
                    texts(c.path("menus")), texts(c.path("help")), texts(m.path("requires")), source));
        }
        return out;
    }

    private static List<String> words(JsonNode functions) {
        List<String> out = new ArrayList<>();
        functions.forEach(f -> {
            String w = (f.isObject() ? f.path("word") : f).asText("").strip().toUpperCase(java.util.Locale.ROOT);
            if (!w.isEmpty()) {
                out.add(w);
            }
        });
        return out;
    }

    static List<String> texts(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n != null && n.isArray()) {
            n.forEach(x -> {
                if (!x.asText("").isBlank()) {
                    out.add(x.asText().strip());
                }
            });
        }
        return out;
    }

    private static JsonNode tree(Path f) {
        try {
            JsonNode n = YAML.readTree(f.toFile());
            return n == null ? YAML.createObjectNode() : n;
        } catch (IOException e) {
            throw new IllegalStateException("module manifest " + f + " cannot be read: " + e.getMessage(), e);
        }
    }
}
