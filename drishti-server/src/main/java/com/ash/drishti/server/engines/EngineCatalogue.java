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
package com.ash.drishti.server.engines;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The engine catalogue (docs/guides/ADMIN_GUIDE.md section 2): every compute engine of the product, its file, switch, old switch
 * names, what it needs and uses, and everything it brings to the screen. The core's engines are {@code engines/catalogue.yaml} inside the
 * server's jar; each domain module's are {@code META-INF/drishti/engines.yaml} inside its own jar (DOMAIN_MODULES.md: the module owns its
 * engines), read first. A module not installed brings none. Read once; a cycle of needs refuses it (the build test reads it too).
 */
public final class EngineCatalogue {

    /** Where the core's catalogue is inside the jar. */
    public static final String RESOURCE = "engines/catalogue.yaml";
    /** Where a domain module's engines are inside its jar. */
    public static final String MODULE_RESOURCE = "META-INF/drishti/engines.yaml";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /**
     * One engine of the catalogue.
     *
     * @param id its id ({@code frtb-sa})
     * @param title its name for people ({@code FRTB-SA})
     * @param about one line of what it computes
     * @param file its file under config/engines/
     * @param switchKey its switch ({@code drishti.engines.frtb-sa.enabled})
     * @param env the switch's environment variable
     * @param aliases the switch's old names, still accepted (DRS-1024)
     * @param shipped the switch as shipped
     * @param installed whether this build holds the engine (false: known to the switch framework, built later)
     * @param liveOn whether turning it on takes effect without a restart
     * @param lists every other list of the entry by its key: prefixes, needs, uses, api, console, words, menus, help, landings, tiles,
     *     lenses, bi, cli, playgrounds, connectors, kinds, inputs, ai-tools
     */
    public record Entry(String id, String title, String about, String file, String switchKey, String env, List<String> aliases, boolean shipped,
            boolean installed, boolean liveOn, Map<String, List<String>> lists) {

        public List<String> list(String key) {
            return lists.getOrDefault(key, List.of());
        }

        public List<String> needs() {
            return list("needs");
        }

        public List<String> uses() {
            return list("uses");
        }

        public List<String> api() {
            return list("api");
        }

        /** What turning the engine off hides, from the catalogue: each list that names something on the screen. */
        public Map<String, List<String>> hides() {
            Map<String, List<String>> out = new LinkedHashMap<>();
            for (String k : HIDES) {
                if (!list(k).isEmpty()) {
                    out.put(k, list(k));
                }
            }
            return out;
        }
    }

    /** The lists that say what an engine brings to the screen (what turning it off hides). */
    public static final List<String> HIDES = List.of("words", "menus", "help", "landings", "tiles", "lenses", "bi", "cli", "playgrounds",
            "connectors", "kinds", "ai-tools");
    private static final List<String> LISTS = List.of("prefixes", "needs", "uses", "api", "console", "words", "menus", "help", "landings", "tiles",
            "lenses", "bi", "cli", "playgrounds", "connectors", "kinds", "inputs", "ai-tools");

    private final Map<String, Entry> entries;

    public EngineCatalogue(List<Entry> entries) {
        Map<String, Entry> m = new LinkedHashMap<>();
        for (Entry e : entries) {
            if (m.put(e.id(), e) != null) {
                throw new IllegalArgumentException("engines/catalogue.yaml: two engines are named " + e.id());
            }
        }
        this.entries = Map.copyOf(m);
        this.order = List.copyOf(m.keySet());
        check();
    }

    private final List<String> order;

    /** The product's catalogue, from the jar. */
    public static EngineCatalogue load() {
        ClassLoader cl = EngineCatalogue.class.getClassLoader();
        try {
            ArrayNode engines = YAML.createArrayNode();
            for (URL u : Collections.list(cl.getResources(MODULE_RESOURCE))) {            // each installed module's engines, first
                try (InputStream in = u.openStream()) {
                    YAML.readTree(in).path("engines").forEach(engines::add);
                }
            }
            try (InputStream in = cl.getResourceAsStream(RESOURCE)) {
                if (in == null) {
                    throw new IllegalStateException(RESOURCE + " is not in the jar");
                }
                YAML.readTree(in).path("engines").forEach(engines::add);
            }
            ObjectNode root = YAML.createObjectNode();
            root.set("engines", engines);
            return parse(root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static EngineCatalogue parse(JsonNode root) {
        List<Entry> out = new ArrayList<>();
        for (JsonNode n : root.path("engines")) {
            Map<String, List<String>> lists = new LinkedHashMap<>();
            for (String k : LISTS) {
                lists.put(k, strings(n.path(k)));
            }
            String id = n.path("id").asText();
            out.add(new Entry(id, n.path("title").asText(id), n.path("about").asText(""), n.path("file").asText(id + ".yaml"),
                    n.path("switch").asText("drishti.engines." + id + ".enabled"), n.path("env").asText(""), strings(n.path("aliases")),
                    n.path("shipped").asBoolean(false), n.path("installed").asBoolean(false), n.path("live-on").asBoolean(true), Map.copyOf(lists)));
        }
        return new EngineCatalogue(out);
    }

    private static List<String> strings(JsonNode n) {
        List<String> out = new ArrayList<>();
        n.forEach(x -> out.add(x.asText()));
        return List.copyOf(out);
    }

    /** Every engine, in the catalogue's order. */
    public List<Entry> all() {
        return order.stream().map(entries::get).toList();
    }

    public Optional<Entry> get(String id) {
        return Optional.ofNullable(id == null ? null : entries.get(id));
    }

    /** The engines that need {@code id} (hard), in the catalogue's order. */
    public List<String> neededBy(String id) {
        return all().stream().filter(e -> e.needs().contains(id)).map(Entry::id).toList();
    }

    /** The engines that use {@code id} (soft). */
    public List<String> usedBy(String id) {
        return all().stream().filter(e -> e.uses().contains(id)).map(Entry::id).toList();
    }

    /** The engine whose API a request path is under (the longest prefix wins: /api/v1/frtb vs /api/v1/sensitivities), or empty. */
    public Optional<Entry> forPath(String path) {
        Entry best = null;
        int len = -1;
        for (Entry e : all()) {
            for (String p : e.api()) {
                if ((path.equals(p) || path.startsWith(p.endsWith("/") ? p : p + "/")) && p.length() > len) {
                    best = e;
                    len = p.length();
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Refuses an entry that needs an engine the catalogue does not hold, and a cycle of needs. A soft use of an engine the catalogue does not
     * hold is no use: that engine's module is not installed here (the pivot uses the VaR engine when the banking module is there).
     */
    private void check() {
        for (Entry e : entries.values()) {
            for (String n : e.needs()) {
                if (!entries.containsKey(n)) {
                    throw new IllegalArgumentException("engines/catalogue.yaml: " + e.id() + " names the engine " + n + ", which is not in the catalogue");
                }
            }
            visit(e.id(), new HashSet<>());
        }
    }

    private void visit(String id, Set<String> path) {
        if (!path.add(id)) {
            throw new IllegalArgumentException("engines/catalogue.yaml: a cycle of needs through " + path);
        }
        for (String n : entries.get(id).needs()) {
            visit(n, new HashSet<>(path));
        }
    }
}
