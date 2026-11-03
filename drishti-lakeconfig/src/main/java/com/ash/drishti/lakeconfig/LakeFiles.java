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
package com.ash.drishti.lakeconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Writes lake files: a pack's recommended entry copied into a lake file the first time a writer meets the kind (L2), and the migration
 * of an installation whose Delta and Iceberg connector files still describe the lake themselves (L4, {@code drishti.py lake
 * migrate-config}): one lake file per distinct root, each connector rewritten to {@code lake: <name>} with only its reading settings.
 * Nothing under the data folder is touched.
 */
public final class LakeFiles {

    static final YAMLMapper YAML = new YAMLMapper(YAMLFactory.builder().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES).build());
    private static final String HEADER_SOURCE = "license-header.txt";

    private LakeFiles() {
    }

    /**
     * Adds {@code table} to the lake file when it has no entry for it, and says so through the result; an entry already there is the
     * truth and is left as it is.
     *
     * @return true when the entry was added
     */
    public static boolean addTable(Path lakeFile, LakeTable table) throws IOException {
        ObjectNode root = (ObjectNode) YAML.readTree(Files.readString(lakeFile));
        ObjectNode tables = root.with("tables");
        if (tables.has(table.name())) {
            return false;
        }
        tables.set(table.name(), entry(table));
        Files.writeString(lakeFile, header(lakeFile) + YAML.writeValueAsString(root));
        return true;
    }

    /** One table's entry as a lake file writes it (only what differs from the defaults). */
    static ObjectNode entry(LakeTable t) {
        ObjectNode e = JsonNodeFactory.instance.objectNode();
        if (!t.path().equals(t.domain() + "/" + t.kind())) {
            e.put("path", t.path());
        }
        list(e, "partition-by", t.partitionBy());
        list(e, "promote", t.promote());
        list(e, "vectors", t.vectors());
        if (!t.flatten().isEmpty()) {
            ObjectNode f = e.putObject("flatten");
            t.flatten().forEach(f::put);
        }
        put(e, "id", t.id());
        put(e, "date", t.date());
        put(e, "latest-by", t.latestBy());
        if (t.appends()) {
            e.put("load-mode", LakeTable.APPEND);
        }
        list(e, "sort-by", t.sortBy());
        t.layout().forEach((k, v) -> {
            if (v.matches("\\d+")) {
                e.put(k, Long.parseLong(v));
            } else {
                e.put(k, v);
            }
        });
        put(e, "retention", t.retention());
        put(e, "time-travel", t.timeTravel());
        if (!t.properties().isEmpty()) {
            ObjectNode p = e.putObject("properties");
            t.properties().forEach(p::put);
        }
        return e;
    }

    /** What {@link #migrate} did: the lake files and connector files written (or that would be, on a dry run), and why each. */
    public record Migration(Map<Path, String> lakeFiles, Map<Path, String> connectorFiles, List<String> notes) {}

    /**
     * Migrates the Delta and Iceberg connector files of {@code connectorsDir}: each distinct (plugin, root) becomes a lake file in
     * {@code lakesDir} (named after the root's last folder, made unique), each of its tables an entry from the connector's
     * {@code domain}, {@code table.<kind>} and {@code layout.<kind>.*}; each connector is rewritten to {@code lake: <name>}. A lake file
     * that exists already is never overwritten: its tables gain the entries they lack. With {@code write} false nothing is written.
     */
    public static Migration migrate(Path connectorsDir, Path lakesDir, boolean write) throws IOException {
        Map<Path, String> lakes = new LinkedHashMap<>();
        Map<Path, String> connectors = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        Map<String, ObjectNode> byRoot = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        List<Path> files;
        try (Stream<Path> s = Files.list(connectorsDir)) {
            files = s.filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted().toList();
        }
        for (Path f : files) {
            JsonNode c = YAML.readTree(Files.readString(f));
            if (c == null || !c.isObject() || !ConnectorLake.PLUGINS.contains(c.path("plugin").asText())) {
                continue;
            }
            ObjectNode settings = c.path("settings") instanceof ObjectNode o ? o : JsonNodeFactory.instance.objectNode();
            Map<String, String> flat = flat(settings);
            if (flat.containsKey(ConnectorLake.LAKE) || !flat.containsKey("root")) {
                notes.add(f.getFileName() + ": " + (flat.containsKey(ConnectorLake.LAKE) ? "names lake " + flat.get(ConnectorLake.LAKE)
                        + " already" : "no root: the plugin's default lake; left as it is"));
                continue;
            }
            String plugin = c.path("plugin").asText();
            String rootPath = flat.get("root");
            String key = plugin + " " + rootPath;
            String name = names.computeIfAbsent(key, k -> unique(lakeName(rootPath), names.values(), lakesDir));
            ObjectNode lake = byRoot.computeIfAbsent(key, k -> {
                ObjectNode l = JsonNodeFactory.instance.objectNode();
                l.put("lake", name);
                l.put("format", plugin);
                l.put("root", rootPath);
                return l;
            });
            String domain = flat.getOrDefault("domain", "");
            List<String> kinds = new ArrayList<>();
            c.path("kinds").forEach(k -> kinds.add(k.asText()));
            flat.keySet().forEach(k -> {
                String kind = k.startsWith("table.") ? k.substring(6) : k.startsWith("layout.") ? k.substring(7, k.lastIndexOf('.')) : null;
                if (kind != null && !kinds.contains(kind)) {
                    kinds.add(kind);
                }
            });
            ObjectNode tables = lake.with("tables");
            for (String kind : kinds) {
                String tname = (domain.isEmpty() ? plugin : domain) + "." + kind;
                if (tables.has(tname)) {
                    continue;
                }
                ObjectNode e = tables.putObject(tname);
                String folder = flat.getOrDefault("table." + kind, kind);
                String path = (domain.isEmpty() ? "" : domain + "/") + folder;
                if (!path.equals(tname.replace('.', '/'))) {
                    e.put("path", path);
                }
                listText(e, "promote", flat.get("layout." + kind + ".columns"));
                listText(e, "vectors", flat.get("layout." + kind + ".vectors"));
                listText(e, "sort-by", flat.get("layout." + kind + ".sort-by"));
                for (String n : List.of("file-rows", "row-group-rows")) {
                    String v = flat.get("layout." + kind + "." + n);
                    if (v != null && v.matches("\\d+")) {
                        e.put(n, Long.parseLong(v));
                    }
                }
            }
            ObjectNode storage = JsonNodeFactory.instance.objectNode();
            ObjectNode catalog = JsonNodeFactory.instance.objectNode();
            ObjectNode keep = JsonNodeFactory.instance.objectNode();
            flat.forEach((k, v) -> {
                if (k.startsWith("s3.") && !k.startsWith("s3.cache")) {
                    storage.put(k.substring(3), v);
                } else if (k.equals("uri") || k.equals("warehouse")) {
                    catalog.put(k, v);
                } else if (!ConnectorLake.lakeKey(k)) {
                    keep.put(k, v);
                }
            });
            if (!storage.isEmpty()) {
                lake.with("storage").setAll(storage);
            }
            if (!catalog.isEmpty()) {
                ObjectNode cat = lake.with("catalog");
                cat.put("type", "rest");
                cat.setAll(catalog);
            }
            ObjectNode rewritten = ((ObjectNode) c).deepCopy();
            ObjectNode ns = JsonNodeFactory.instance.objectNode();
            ns.put(ConnectorLake.LAKE, name);
            ns.setAll(keep);
            rewritten.set("settings", ns);
            connectors.put(f, comments(f) + YAML.writeValueAsString(rewritten));
            notes.add(f.getFileName() + ": now lake: " + name + " (" + kinds.size() + " tables)");
        }
        for (ObjectNode lake : byRoot.values()) {
            Path target = lakesDir.resolve(lake.get("lake").asText() + ".yaml");
            lakes.put(target, header(lakesDir) + "# " + target.getFileName() + ": written by drishti.py lake migrate-config from the connector files"
                    + " (docs/guides/LAKE_GUIDE.md); edit here from now on\n" + YAML.writeValueAsString(lake));
        }
        if (write) {
            Files.createDirectories(lakesDir);
            for (var e : lakes.entrySet()) {
                Files.writeString(e.getKey(), e.getValue());
            }
            for (var e : connectors.entrySet()) {
                Files.writeString(e.getKey(), e.getValue());
            }
        }
        return new Migration(lakes, connectors, notes);
    }

    /** A lake's name from its root: the last folder, lower case ({@code ./data/delta} is {@code delta}). */
    static String lakeName(String root) {
        String r = root.replaceAll("\\$\\{[^}]*}", "").replaceAll("[/\\\\]+$", "");
        String last = r.substring(Math.max(r.lastIndexOf('/'), r.lastIndexOf('\\')) + 1).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-")
                .replaceAll("^-+|-+$", "");
        return last.isEmpty() ? "main" : last;
    }

    private static String unique(String base, java.util.Collection<String> taken, Path lakesDir) {
        String n = base;
        int i = 2;
        while (taken.contains(n) || Files.exists(lakesDir.resolve(n + ".yaml"))) {
            n = base + "-" + i++;
        }
        return n;
    }

    private static Map<String, String> flat(JsonNode n) {
        Map<String, String> out = new LinkedHashMap<>();
        flat("", n, out);
        return out;
    }

    private static void flat(String prefix, JsonNode n, Map<String, String> out) {
        n.fields().forEachRemaining(e -> {
            if (e.getValue().isObject()) {
                flat(prefix + e.getKey() + ".", e.getValue(), out);
            } else if (e.getValue().isArray()) {
                List<String> parts = new ArrayList<>();
                e.getValue().forEach(x -> parts.add(x.asText()));
                out.put(prefix + e.getKey(), String.join(",", parts));
            } else {
                out.put(prefix + e.getKey(), e.getValue().asText());
            }
        });
    }

    private static void list(ObjectNode e, String key, List<String> values) {
        if (!values.isEmpty()) {
            ArrayNode a = e.putArray(key);
            values.forEach(a::add);
        }
    }

    private static void listText(ObjectNode e, String key, String text) {
        if (text != null && !text.isBlank()) {
            ArrayNode a = e.putArray(key);
            for (String s : text.split(",")) {
                if (!s.isBlank()) {
                    a.add(s.trim());
                }
            }
        }
    }

    private static void put(ObjectNode e, String key, String v) {
        if (v != null && !v.isBlank()) {
            e.put(key, v);
        }
    }

    /** The file's leading comment lines (its copyright header and description), kept when the file is rewritten. */
    static String comments(Path f) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String line : Files.readAllLines(f)) {
            if (!line.startsWith("#") && !line.isBlank()) {
                break;
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /** The copyright header for a new lake file: the existing file's own comments, else the configuration folder's header file. */
    static String header(Path at) throws IOException {
        if (Files.isRegularFile(at)) {
            return comments(at);
        }
        Path dir = Files.isDirectory(at) ? at : at.getParent();
        for (Path d = dir == null ? null : dir.toAbsolutePath(); d != null; d = d.getParent()) {
            Path h = d.resolve(HEADER_SOURCE);
            if (Files.isRegularFile(h)) {
                StringBuilder sb = new StringBuilder();
                Files.readAllLines(h).forEach(l -> sb.append(l.isBlank() ? "#" : "# " + l).append('\n'));
                return sb.append('\n').toString();
            }
        }
        return "";
    }
}
