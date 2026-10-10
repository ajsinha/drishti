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
package com.ash.drishti.packs;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads {@code <dir>/<name>/pack.yaml} for each enabled pack, in the order given, and turns their content into
 * Drishti properties. A pack contributes: mnemonics, reference patterns and fields, link badges, roles, Sutra
 * directories, format and semantic-hint files, and sample directories for the demo source.
 *
 * <p>Packs inherit ({@code extends: [parent, …]}, see {@link PackLineage}): a child has everything its parents have,
 * and where two related packs define the same mnemonic, field, badge, role, route or connector, the more specific one
 * wins (the child over its parents, the rightmost parent over the others). Every such override is reported
 * ({@code drishti.packs.overrides}). Unrelated packs defining the same thing are still an error, and a kind always
 * belongs to exactly one pack.
 */
public final class PackLoader {

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
    /** The connector files of the site; a pack's template for a connector is only used when no file of that name exists. */
    private final ConnectorFiles connectorFiles;

    public PackLoader() {
        this(null);
    }

    /** A loader that leaves a pack's connector template out where the site has a connector file of that name. */
    public PackLoader(ConnectorFiles files) {
        this.connectorFiles = files;
    }
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * The enabled packs and everything they require, dependencies first (a pack that {@code requires:} another
     * loads after it). A missing pack or a cycle stops the server with a clear message.
     */
    public List<Pack> load(Path dir, List<String> enabled) {
        return load(List.of(dir), enabled);
    }

    /**
     * As {@link #load(Path, List)}, looking for each pack in the folders in order: the first that has
     * {@code <name>/pack.yaml} wins (packs installed from a registry come before the shipped ones).
     */
    public List<Pack> load(List<Path> dirs, List<String> enabled) {
        Map<String, Pack> done = new LinkedHashMap<>();
        for (String name : enabled) {
            String n = name.trim();
            if (!n.isEmpty()) {
                visit(dirs, n, done, new java.util.ArrayDeque<>());
            }
        }
        return new ArrayList<>(done.values());
    }

    /** The folders packs are looked for in: installed ones ({@code drishti.packs.installed-dir}) first, if it exists. */
    public static List<Path> dirs(String packsDir, String installedDir) {
        List<Path> out = new ArrayList<>();
        if (installedDir != null && !installedDir.isBlank() && Files.isDirectory(Path.of(installedDir))) {
            out.add(Path.of(installedDir).toAbsolutePath().normalize());
        }
        out.add(Path.of(packsDir).toAbsolutePath().normalize());
        return out;
    }

    /** Reads one pack from the first folder that has it; throws {@link IllegalStateException} when none does or the manifest is wrong. */
    public Pack readOne(List<Path> dirs, String name) {
        Path dir = dirs.stream().filter(d -> Files.isRegularFile(d.resolve(name).normalize().resolve("pack.yaml"))).findFirst()
                .orElse(dirs.get(dirs.size() - 1));
        return read(dir, name, null);
    }

    /** The names of the pack folders (those holding a {@code pack.yaml}) in the folders, the first folder winning a name. */
    public static List<String> folders(List<Path> dirs) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (Path d : dirs) {
            if (!Files.isDirectory(d)) {
                continue;
            }
            try (java.util.stream.Stream<Path> s = Files.list(d)) {
                s.filter(x -> Files.isRegularFile(x.resolve("pack.yaml"))).map(x -> x.getFileName().toString()).sorted().forEach(out::add);
            } catch (IOException e) {
                // an unreadable folder has no packs to offer
            }
        }
        return List.copyOf(out);
    }

    private void visit(List<Path> dirs, String name, Map<String, Pack> done, java.util.Deque<String> path) {
        if (done.containsKey(name)) {
            return;
        }
        if (path.contains(name)) {
            throw new IllegalStateException("packs require each other in a cycle: " + String.join(" -> ", path) + " -> " + name);
        }
        path.addLast(name);
        String requiredBy = path.size() > 1 ? path.toArray(new String[0])[path.size() - 2] : null;
        Path dir = dirs.stream().filter(d -> Files.isRegularFile(d.resolve(name).normalize().resolve("pack.yaml"))).findFirst()
                .orElse(dirs.get(dirs.size() - 1));
        Pack p = read(dir, name, requiredBy);
        for (String r : p.parents()) {
            visit(dirs, r.trim(), done, path);
        }
        path.removeLast();
        done.put(name, p);
    }

    private Pack read(Path dir, String n, String requiredBy) {
        Path packDir = dir.resolve(n).normalize();
        Path manifest = packDir.resolve("pack.yaml");
        if (!packDir.startsWith(dir.normalize()) || !Files.isRegularFile(manifest)) {
            throw new IllegalStateException("pack '" + n + "' not found at " + manifest
                    + (requiredBy == null ? "" : " (required by '" + requiredBy + "')"));
        }
        try {
            Map<String, Object> m = yaml.readValue(manifest.toFile(), new TypeReference<Map<String, Object>>() {});
            if (!n.equals(m.get("pack"))) {
                throw new IllegalStateException(manifest + " declares pack '" + m.get("pack") + "', expected '" + n + "'");
            }
            return new Pack(n, String.valueOf(m.getOrDefault("version", "0")), String.valueOf(m.getOrDefault("title", n)),
                    String.valueOf(m.getOrDefault("description", "")), packDir.toAbsolutePath(), m);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + manifest, e);
        }
    }

    /** The inheritance order of the given packs (they must include every pack they inherit from). */
    public static PackLineage lineage(List<Pack> packs) {
        Map<String, List<String>> parents = new LinkedHashMap<>();
        packs.forEach(p -> parents.put(p.name(), p.parents()));
        java.util.Set<String> inherited = new java.util.HashSet<>();
        parents.values().forEach(inherited::addAll);
        List<String> leaves = packs.stream().map(Pack::name).filter(n -> !inherited.contains(n)).toList();
        return new PackLineage(parents, leaves);
    }

    /** One definition of a named thing (a mnemonic, a role, …) by one pack: the properties it contributes. */
    private record Claim(String pack, Map<String, Object> props) {}

    /** Flattened Spring properties for the given packs, lowest precedence. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> properties(List<Pack> packs) {
        PackLineage lineage = lineage(packs);
        List<String> order = lineage.order();                                  // most specific first
        List<Pack> general = new ArrayList<>(packs);
        general.sort(java.util.Comparator.comparingInt((Pack x) -> order.indexOf(x.name())).reversed());   // most general first
        Map<String, Object> p = new LinkedHashMap<>();
        Map<String, String> kindOwner = new LinkedHashMap<>();
        Map<String, Claim> claims = new LinkedHashMap<>();
        List<String> overrides = new ArrayList<>();
        int patterns = 0;
        int follows = 0;
        List<String> sutraDirs = new ArrayList<>();
        List<String> formats = new ArrayList<>();
        List<String> semantics = new ArrayList<>();
        List<String> samples = new ArrayList<>();
        for (Pack pack : general) {
            Map<String, Object> m = pack.manifest();
            List<Object> owned = (List<Object>) m.getOrDefault("kinds", List.of());
            for (int i = 0; i < owned.size(); i++) {
                claim(kindOwner, "kind " + owned.get(i), pack.name());         // a kind belongs to one pack: never overridden
                p.put("drishti.packs.kinds." + pack.name() + "[" + i + "]", owned.get(i));
            }
            for (Map.Entry<String, Object> e : map(m.get("mnemonics")).entrySet()) {
                Map<String, Object> def = map(e.getValue());
                Map<String, Object> props = new LinkedHashMap<>();
                props.put("drishti.commands.mnemonics." + e.getKey() + ".kind", def.get("kind"));
                props.put("drishti.commands.mnemonics." + e.getKey() + ".label", def.getOrDefault("label", e.getKey()));
                offer(claims, overrides, lineage, "mnemonic " + e.getKey(), pack.name(), props);
            }
            Map<String, Object> graph = map(m.get("graph"));
            for (Object o : (List<Object>) graph.getOrDefault("id-patterns", List.of())) {
                Map<String, Object> ip = map(o);
                p.put("drishti.graph.id-patterns[" + patterns + "].pattern", ip.get("pattern"));
                p.put("drishti.graph.id-patterns[" + patterns + "].kind", ip.get("kind"));
                patterns++;
            }
            for (Map.Entry<String, Object> e : map(graph.get("fields")).entrySet()) {
                Map<String, Object> f = map(e.getValue());
                Map<String, Object> props = new LinkedHashMap<>();
                props.put("drishti.graph.fields." + e.getKey() + ".kind", f.get("kind"));
                if (f.get("label") != null) {
                    props.put("drishti.graph.fields." + e.getKey() + ".label", f.get("label"));
                }
                offer(claims, overrides, lineage, "field " + e.getKey(), pack.name(), props);
            }
            Map<String, Object> impact = map(graph.get("impact"));
            for (Object f : (List<Object>) impact.getOrDefault("follow", List.of())) {
                p.put("drishti.graph.impact.follow[" + follows++ + "]", f);
            }
            map(impact.get("measures")).forEach((kind, expr) -> p.put("drishti.graph.impact.measures." + kind, expr));   // general first: specific wins
            map(impact.get("formats")).forEach((kind, fmt) -> p.put("drishti.graph.impact.formats." + kind, fmt));
            map(graph.get("badges")).forEach((kind, expr) ->
                    offer(claims, overrides, lineage, "badge " + kind, pack.name(), Map.of("drishti.graph.badges." + kind, expr)));
            // columns: a kind's key fields, shown in pick lists (TRD MX-200000) and searches beside each entity
            for (Map.Entry<String, Object> e : map(m.get("columns")).entrySet()) {
                List<Object> cols = e.getValue() instanceof List<?> l ? (List<Object>) l : List.of();
                Map<String, Object> props = new LinkedHashMap<>();
                for (int i = 0; i < cols.size(); i++) {
                    props.put("drishti.search.columns." + e.getKey() + "[" + i + "]", String.valueOf(cols.get(i)));
                }
                offer(claims, overrides, lineage, "columns " + e.getKey(), pack.name(), props);
            }
            // pivot: the kinds whose search results and pick lists offer a Pivot tab (true, or { fields, rows, columns,
            // values, filters, heat, chart }); kept as JSON, read and checked by the search engine (SearchProperties#pivotOf)
            for (Map.Entry<String, Object> e : map(m.get("pivot")).entrySet()) {
                Object v = e.getValue();
                if (!(v instanceof Boolean) && !(v instanceof Map<?, ?>)) {
                    throw new IllegalStateException("pack '" + pack.name() + "': pivot of " + e.getKey() + " is true, false or a mapping, not '" + v + "'");
                }
                String text;
                try {
                    text = v instanceof Boolean b ? b.toString() : JSON.writeValueAsString(v);
                } catch (IOException x) {
                    throw new UncheckedIOException(x);
                }
                offer(claims, overrides, lineage, "pivot " + e.getKey(), pack.name(), Map.of("drishti.search.pivot." + e.getKey(), text));
            }
            for (Map.Entry<String, Object> e : map(m.get("roles")).entrySet()) {
                Map<String, Object> r = map(e.getValue());
                Map<String, Object> props = new LinkedHashMap<>();
                List<Object> kinds = (List<Object>) r.getOrDefault("kinds", List.of());
                for (int i = 0; i < kinds.size(); i++) {
                    props.put("drishti.security.roles." + e.getKey() + ".kinds[" + i + "]", kinds.get(i));
                }
                for (String flag : new String[] {"raw", "author", "admin", "approve", "calc", "layout"}) {
                    if (r.get(flag) != null) {
                        props.put("drishti.security.roles." + e.getKey() + "." + flag, r.get(flag));
                    }
                }
                offer(claims, overrides, lineage, "role " + e.getKey(), pack.name(), props);
            }
            // connectors: the pack names site connectors (config/connectors/<name>.yaml). What it defines inline is a
            // template, in force only while no file of that name exists (the server writes one from it at start).
            for (Map.Entry<String, Map<String, Object>> e : pack.connectorTemplates().entrySet()) {
                if (connectorFiles != null && connectorFiles.exists(e.getKey())) {
                    continue;
                }
                Map<String, Object> c = e.getValue();
                String base = "drishti.sources.connectors." + e.getKey();
                Map<String, Object> props = new LinkedHashMap<>();
                props.put(base + ".plugin", c.get("plugin"));
                if (c.get("enabled") != null) {
                    props.put(base + ".enabled", c.get("enabled"));
                }
                List<Object> ck = (List<Object>) c.getOrDefault("kinds", List.of());
                for (int i = 0; i < ck.size(); i++) {
                    props.put(base + ".kinds[" + i + "]", ck.get(i));
                }
                flatten(base + ".settings.", map(c.get("settings")), props);
                offer(claims, overrides, lineage, "connector " + e.getKey(), pack.name(), props);
            }
            // routes: which connector answers each of the pack's kinds (the query inside a pack picks the connector)
            for (Map.Entry<String, Object> e : map(m.get("routes")).entrySet()) {
                offer(claims, overrides, lineage, "route " + e.getKey(), pack.name(), Map.of("drishti.sources.routes." + e.getKey(), String.valueOf(e.getValue())));
            }
            addIfExists(sutraDirs, pack, m.getOrDefault("sutras", "sutras"));          // general first: a later directory may override a Sutra
            addIfExists(formats, pack, m.getOrDefault("formats", "config/formats.yaml")); // general first: later files win
            addIfExists(samples, pack, m.getOrDefault("samples", "samples"));
        }
        for (Pack pack : packs.stream().sorted(java.util.Comparator.comparingInt(x -> order.indexOf(x.name()))).toList()) {
            addIfExists(semantics, pack, pack.manifest().getOrDefault("semantics", "config/semantics.yaml"));   // specific first: first wins
        }
        claims.values().forEach(c -> p.putAll(c.props()));
        indexed(p, "drishti.rachana.pack-dirs", sutraDirs);
        indexed(p, "drishti.rachana.pack-formats-files", formats);
        indexed(p, "drishti.inference.pack-semantics-files", semantics);
        aboutSources(p, packs, lineage, order);
        indexed(p, "drishti.packs.overrides", overrides);
        if (!samples.isEmpty()) {
            p.put("drishti.sources.plugins.demo.settings.dirs", String.join(",", samples));
        }
        p.put("drishti.packs.loaded", String.join(",", packs.stream().map(Pack::name).toList()));
        return p;
    }

    /**
     * Connector settings are flat strings; a pack may nest them for readability ({@code book-pnl: {from: trade}} is
     * {@code book-pnl.from: trade}). Lists become comma lists.
     */
    @SuppressWarnings("unchecked")
    public static void flatten(String prefix, Map<String, Object> settings, Map<String, Object> out) {
        settings.forEach((k, v) -> {
            if (v instanceof Map<?, ?> nested) {
                flatten(prefix + k + ".", (Map<String, Object>) nested, out);
            } else if (v instanceof List<?> list) {
                out.put(prefix + k, String.join(",", list.stream().map(String::valueOf).toList()));
            } else {
                out.put(prefix + k, String.valueOf(v));
            }
        });
    }

    /**
     * A pack defines {@code what}. Identical to what another pack defined: nothing to do. Different, from a related
     * pack: the more specific definition wins and the override is recorded. From an unrelated pack: an error.
     */
    private static void offer(Map<String, Claim> claims, List<String> overrides, PackLineage lineage, String what, String pack, Map<String, Object> props) {
        Claim prev = claims.get(what);
        if (prev == null) {
            claims.put(what, new Claim(pack, props));
            return;
        }
        if (prev.props().equals(props) || prev.pack().equals(pack)) {
            return;
        }
        String winner = lineage.winner(prev.pack(), pack);
        if (winner == null) {
            throw new IllegalStateException(what + " is defined by both pack '" + prev.pack() + "' and pack '" + pack
                    + "', which do not inherit from each other; declare it identically, or make one pack extend the other");
        }
        String loser = winner.equals(pack) ? prev.pack() : pack;
        if (winner.equals(pack)) {
            claims.put(what, new Claim(pack, props));
        }
        overrides.add(what + ": " + winner + " overrides " + loser);
    }

    private static void claim(Map<String, String> owner, String what, String pack) {
        String prev = owner.putIfAbsent(what, pack);
        if (prev != null && !prev.equals(pack)) {
            throw new IllegalStateException(what + " is defined by both pack '" + prev + "' and pack '" + pack + "'");
        }
    }

    private static void addIfExists(List<String> into, Pack pack, Object rel) {
        Path path = pack.resolve(String.valueOf(rel));
        if (Files.exists(path)) {
            into.add(path.toString());
        }
    }

    /**
     * The about catalogue's view of the packs, most specific first: name, title, the about file (default {@code
     * config/about.yaml}) when it exists, the Sutra directory, the kinds the pack owns and the pack's lineage. A child's file
     * is read before its parents', so its entries win.
     */
    @SuppressWarnings("unchecked")
    private static void aboutSources(Map<String, Object> p, List<Pack> packs, PackLineage lineage, List<String> order) {
        List<Pack> specific = packs.stream().sorted(java.util.Comparator.comparingInt(x -> order.indexOf(x.name()))).toList();
        for (int i = 0; i < specific.size(); i++) {
            Pack pack = specific.get(i);
            String base = "drishti.about.packs[" + i + "].";
            p.put(base + "name", pack.name());
            if (pack.title() != null) {
                p.put(base + "title", pack.title());
            }
            Path file = pack.resolve(String.valueOf(pack.manifest().getOrDefault("about", "config/about.yaml")));
            if (Files.exists(file)) {
                p.put(base + "file", file.toString());
            }
            Path sutras = pack.resolve(String.valueOf(pack.manifest().getOrDefault("sutras", "sutras")));
            if (Files.exists(sutras)) {
                p.put(base + "sutra-dir", sutras.toString());
            }
            List<Object> owned = (List<Object>) pack.manifest().getOrDefault("kinds", List.of());
            indexed(p, base + "kinds", owned.stream().map(String::valueOf).toList());
            indexed(p, base + "lineage", lineage.linearisation(pack.name()));
        }
    }

    private static void indexed(Map<String, Object> p, String key, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            p.put(key + "[" + i + "]", values.get(i));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
