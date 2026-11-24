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
 * The modules a distribution may list, read from their own manifests (docs/architecture/KERNEL_AND_MODULES.md section 10): a content module's
 * {@code modules/<id>/module.yaml} (the {@code .dmod} manifest), a module with code's {@code META-INF/drishti/module.yaml} in its
 * sources ({@code modules/<id>/java/<part>/src/main/resources/...}), and the packaged modules the registry reads with the same reader
 * (the module boot's {@code ModuleHomes}: the {@code .dmod} packages of the modules folder),
 * whose manifest wins over a folder's copy. The console reads none of them: it draws from the server's
 * installation answer (THIN_CONSOLE_DESIGN.md 3.2). Each comes to its packs, its function words, the engines it owns, its top menus, the Help
 * domains it owns and the modules it requires; the list is in requirement order (a required module before the modules that need it).
 */
public final class ModuleManifests {

    /**
     * One module as a distribution sees it ({@code source}: its manifest file; null for a package). {@code title} is its manifest's title
     * (blank: none), {@code consoleMenus} its top menus in the sitemap's form ({@code console.menus}, each a map) and {@code domainLabel} the
     * name the console's Domains menu takes when this is the one active domain ({@code console.domain-label}): what the console draws from
     * the module's manifest, served in the installation's answer (THIN_CONSOLE_DESIGN.md 3.2).
     */
    public record Entry(String id, String domain, List<String> packs, List<String> words, List<String> features, List<String> menus,
            List<String> docs, List<String> requires, Path source, String title, List<Object> consoleMenus, String domainLabel) {

        /** As before the console's fields: no title, no menus in the sitemap's form, no domain label. */
        public Entry(String id, String domain, List<String> packs, List<String> words, List<String> features, List<String> menus,
                List<String> docs, List<String> requires, Path source) {
            this(id, domain, packs, words, features, menus, docs, requires, source, "", List.of(), "");
        }
    }

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final String IN_JAR = "src/main/resources/META-INF/drishti/module.yaml";
    /** A module's Maven projects, each a part ({@code modules/<id>/java/<part>}); the module's own files are at its folder's top. */
    private static final String JAVA = "java";

    private ModuleManifests() {
    }

    /**
     * Every module manifest of the module boot's result ({@link com.ash.drishti.api.resolution.ModuleHomes}: each module's folder), by id,
     * in requirement order.
     */
    public static Map<String, Entry> read(com.ash.drishti.api.resolution.ModuleHomes homes) {
        Map<String, Entry> byId = new TreeMap<>();
        for (com.ash.drishti.api.resolution.ModuleHomes.Home h : homes.homes().values()) {
            module(h.dir(), byId);
        }
        Map<String, Entry> ordered = new LinkedHashMap<>();
        byId.keySet().forEach(id -> visit(id, byId, ordered, new LinkedHashSet<>()));
        return ordered;
    }

    /** One module's manifests: its {@code module.yaml} at its folder's top (one layout), then its parts' jar manifests in its sources. */
    private static void module(Path m, Map<String, Entry> byId) {
        Path dmod = m.resolve("module.yaml");
        if (Files.isRegularFile(dmod)) {
            Entry e = ofDmod(tree(dmod), dmod);
            byId.put(e.id(), e);
        }
        if (!Files.isDirectory(m.resolve(JAVA))) {
            return;
        }
        try (DirectoryStream<Path> parts = Files.newDirectoryStream(m.resolve(JAVA), Files::isDirectory)) {
            for (Path part : parts) {
                Path jar = part.resolve(IN_JAR);
                if (Files.isRegularFile(jar)) {
                    for (Entry e : ofJar(tree(jar), jar)) {
                        byId.put(e.id(), e);
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("module manifests under " + m + " cannot be read: " + e.getMessage(), e);
        }
    }

    /**
     * The packs a module turns on by default: its manifest's {@code packs.list} without {@code packs.off-by-default} (packs that read a
     * lake that must be built first: a site, or the demo profile, names them in {@code drishti.packs.enabled}). A module jar's manifest
     * lists only packs that are on by default.
     */
    public static List<String> defaultPacks(Entry e) {
        if (e.source() == null || !e.source().getFileName().toString().equals("module.yaml") || !Files.isRegularFile(e.source())) {
            return e.packs();
        }
        Set<String> off = new LinkedHashSet<>(texts(tree(e.source()).path("packs").path("off-by-default")));
        return e.packs().stream().filter(p -> !off.contains(p)).toList();
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
        List<Object> consoleMenus = new ArrayList<>();
        m.path("console").path("menus").forEach(x -> {
            if (x.isObject() && !x.path("id").asText("").isBlank()) {
                consoleMenus.add(YAML.convertValue(x, Map.class));
            }
        });
        return new Entry(id, m.path("domain").asText(id), texts(m.path("packs").path("list")), words(m.path("functions")), engines,
                menuIds(m.path("console").path("menus")), texts(m.path("docs").path("domains")), requires, source,
                m.path("title").asText("").strip(), List.copyOf(consoleMenus), m.path("console").path("domain-label").asText("").strip());
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

    /** A manifest's top menus ({@code console.menus}): each menu's {@code id} (the menu itself, in the sitemap's form), or a plain id. */
    static List<String> menuIds(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n != null && n.isArray()) {
            n.forEach(x -> {
                String id = (x.isObject() ? x.path("id") : x).asText("").strip();
                if (!id.isEmpty() && !out.contains(id)) {
                    out.add(id);
                }
            });
        }
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
