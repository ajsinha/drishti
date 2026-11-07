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

    /**
     * Creates {@code <lakesDir>/<name>.yaml} for a lake a load names that has no file yet (the data inbox, DATA_LAKE_GUIDE.md section 4): a
     * Delta lake in its own folder, {@code root: ${drishti.data.dir:./data}/delta/<name>}, with {@code tables} (the routed packs' recommended
     * entries). A file that exists is never touched (false); the name must be a lake's name ({@link Lakes#NAME}).
     *
     * @return true when the file was created
     */
    public static boolean create(Path lakesDir, String name, List<LakeTable> tables, String why) throws IOException {
        if (!Lakes.validName(name)) {
            throw new IllegalArgumentException("lake " + name + ": a lake's name is lower-case letters, digits, '-' and '_' ([a-z0-9][a-z0-9_-]*)");
        }
        Path file = lakesDir.resolve(name + ".yaml");
        if (Files.exists(file)) {
            return false;
        }
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("lake", name);
        root.put("format", "delta");
        root.put("root", Lakes.defaultRoot(name));
        ObjectNode ts = root.putObject("tables");
        for (LakeTable t : tables) {
            ts.set(t.name(), entry(t));
        }
        Files.createDirectories(lakesDir);
        String text = header(lakesDir) + "# config/lakes/" + name + ".yaml: the lake " + name + ", created by " + why + " (docs/guides/DATA_LAKE_GUIDE.md).\n"
                + "# Its tables are folders under its root, <data>/delta/" + name + "/<domain>/<table>; edit the entries here from now on, and\n"
                + "# point a pack's connectors at it with lake: " + name + " (then restart).\n" + YAML.writeValueAsString(root);
        try {
            Files.writeString(file, text, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
        } catch (java.nio.file.FileAlreadyExistsException e) {
            return false;                                       // another writer made it a moment ago: its file is the truth
        }
        return true;
    }

    /** One table's entry as a lake file writes it (only what differs from the defaults). */
    static ObjectNode entry(LakeTable t) {
        ObjectNode e = JsonNodeFactory.instance.objectNode();
        if (!t.kind().equals(t.table())) {
            e.put("kind", t.kind());
        }
        if (!t.path().equals(t.domain() + "/" + t.table())) {
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
     * {@code domain}, {@code table.<kind>} and {@code layout.<kind>.*}; each connector is rewritten to {@code lake: <name>} (and
     * {@code lake-tables:} when a table is not named after its kind). A lake file that exists already is never overwritten: the new one
     * gets another name. With {@code write} false nothing is written.
     */
    public static Migration migrate(Path connectorsDir, Path lakesDir, boolean write) throws IOException {
        Migration demo = adoptDemo(connectorsDir, lakesDir, write);          // the demo lake's old default root: lake: demo
        Map<Path, String> lakes = new LinkedHashMap<>(demo.lakeFiles());
        Map<Path, String> connectors = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>(demo.notes());
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
            if (demo.connectorFiles().containsKey(f)) {
                connectors.put(f, demo.connectorFiles().get(f));
                continue;
            }
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
            List<String> mine = new ArrayList<>();
            boolean named = false;
            for (String kind : kinds) {
                String folder = flat.getOrDefault("table." + kind, kind);
                String tname = (domain.isEmpty() ? plugin : domain) + "." + folder;
                mine.add(tname);
                named |= !folder.equals(kind);
                if (tables.has(tname)) {
                    continue;
                }
                ObjectNode e = tables.putObject(tname);
                if (!folder.equals(kind)) {
                    e.put("kind", kind);
                }
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
            if (named) {                                       // a table not named after its kind (trade_endur): the connector names its entries
                ns.put(ConnectorLake.TABLES, String.join(",", mine));
            }
            ns.setAll(keep);
            rewritten.set("settings", ns);
            connectors.put(f, comments(f) + YAML.writeValueAsString(rewritten));
            notes.add(f.getFileName() + ": now lake: " + name + " (" + kinds.size() + " tables)");
        }
        for (ObjectNode lake : byRoot.values()) {
            Path target = lakesDir.resolve(lake.get("lake").asText() + ".yaml");
            lakes.put(target, header(lakesDir) + "# " + target.getFileName() + ": written by drishti.py lake migrate-config from the connector files"
                    + " (docs/guides/DATA_LAKE_GUIDE.md); edit here from now on\n" + YAML.writeValueAsString(lake));
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

    /**
     * The roots a connector generated before every lake had its own folder names for the demo lake: {@code ${DRISHTI_DELTA_ROOT:./data/delta}},
     * {@code ${DRISHTI_DELTA_ROOT:${drishti.data.dir:./data}/delta}}, {@code ./data/delta}. Such a connector meant "the demo lake".
     */
    static final java.util.regex.Pattern DEMO_ROOTS = java.util.regex.Pattern.compile(
            "\\$\\{DRISHTI_DELTA_ROOT(:-?(\\./)?data/delta/?|:\\$\\{drishti\\.data\\.dir(:-?\\./data)?}/delta/?)?}|(\\./)?data/delta/?");

    /** Whether a connector's root (as written) is the demo lake's old default ({@link #DEMO_ROOTS}). */
    public static boolean demoRoot(String raw) {
        return raw != null && DEMO_ROOTS.matcher(raw.strip()).matches();
    }

    /**
     * Moves the connectors that read the demo lake by its old default root ({@link #demoRoot}: generated from a pack's template before the
     * lake form) to {@code lake: demo}, so they follow the demo lake into its own folder ({@code <data>/delta/demo}): each one's tables named
     * by {@code lake-tables:} (its {@code domain} and {@code table.<kind>}, else its kinds), a table the demo lake file lacks added to it from
     * the connector's layout, every reading setting kept. A {@code var-pv-stream} connector's {@code lake:} folder in that form becomes the
     * name {@code demo}. A connector that names its root otherwise (a path or URI of its own) is left as it is: that form is permanent.
     * With {@code write}, each file is kept beside it first ({@code <name>.yaml.before-lake-<time>.bak}).
     */
    public static Migration adoptDemo(Path connectorsDir, Path lakesDir, boolean write) throws IOException {
        Map<Path, String> lakeFiles = new LinkedHashMap<>();
        Map<Path, String> connectors = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        if (connectorsDir == null || !Files.isDirectory(connectorsDir)) {
            return new Migration(lakeFiles, connectors, notes);
        }
        Path demoFile = lakesDir.resolve("demo.yaml");
        ObjectNode demo;
        if (Files.isRegularFile(demoFile)) {
            demo = (ObjectNode) YAML.readTree(Files.readString(demoFile));
        } else {
            try (java.io.InputStream in = LakeFiles.class.getClassLoader().getResourceAsStream("drishti/lakes/demo.yaml")) {
                if (in == null) {
                    return new Migration(lakeFiles, connectors, notes);
                }
                demo = (ObjectNode) YAML.readTree(in);
            }
        }
        ObjectNode tables = demo.with("tables");
        boolean grew = false;
        List<Path> files;
        try (Stream<Path> s = Files.list(connectorsDir)) {
            files = s.filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted().toList();
        }
        for (Path f : files) {
            JsonNode c = YAML.readTree(Files.readString(f));
            if (c == null || !c.isObject() || !(c.path("settings") instanceof ObjectNode settings)) {
                continue;
            }
            String plugin = c.path("plugin").asText();
            Map<String, String> flat = flat(settings);
            ObjectNode rewritten = ((ObjectNode) c).deepCopy();
            if (plugin.equals("var-pv-stream") && demoRoot(flat.get(ConnectorLake.LAKE))) {
                ((ObjectNode) rewritten.get("settings")).put(ConnectorLake.LAKE, "demo");
                connectors.put(f, comments(f) + YAML.writeValueAsString(rewritten));
                notes.add(f.getFileName() + ": writes into the demo lake by name now (lake: demo)");
                continue;
            }
            if (!ConnectorLake.PLUGINS.contains(plugin) || flat.containsKey(ConnectorLake.LAKE) || !demoRoot(flat.get("root"))) {
                continue;
            }
            String domain = flat.getOrDefault("domain", "");
            List<String> kinds = new ArrayList<>();
            c.path("kinds").forEach(k -> kinds.add(k.asText()));
            List<String> mine = new ArrayList<>();
            if (kinds.isEmpty()) {
                tables.fieldNames().forEachRemaining(n -> {
                    if (n.startsWith(domain + ".")) {
                        mine.add(n);
                    }
                });
            }
            for (String kind : kinds) {
                String folder = flat.getOrDefault("table." + kind, kind);
                String tname = (domain.isEmpty() ? plugin : domain) + "." + folder;
                mine.add(tname);
                if (tables.has(tname)) {
                    continue;
                }
                ObjectNode e = tables.putObject(tname);
                grew = true;
                if (!folder.equals(kind)) {
                    e.put("kind", kind);
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
            ObjectNode ns = JsonNodeFactory.instance.objectNode();
            ns.put(ConnectorLake.LAKE, "demo");
            if (!mine.isEmpty()) {
                ns.put(ConnectorLake.TABLES, String.join(",", mine));
            }
            flat.forEach((k, v) -> {
                if (!ConnectorLake.lakeKey(k)) {
                    ns.put(k, v);
                }
            });
            rewritten.set("settings", ns);
            connectors.put(f, comments(f) + YAML.writeValueAsString(rewritten));
            notes.add(f.getFileName() + ": reads the demo lake by name now (lake: demo, " + mine.size() + " tables), so it follows the lake's folder");
        }
        if (grew) {
            lakeFiles.put(demoFile, header(demoFile) + YAML.writeValueAsString(demo));
        }
        if (write) {
            String at = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            for (var e : lakeFiles.entrySet()) {
                Files.createDirectories(e.getKey().getParent());
                Files.writeString(e.getKey(), e.getValue());
            }
            for (var e : connectors.entrySet()) {
                Path bak = e.getKey().resolveSibling(e.getKey().getFileName() + ".before-lake-" + at + ".bak");
                if (!Files.exists(bak)) {
                    Files.copy(e.getKey(), bak);
                }
                Path tmp = e.getKey().resolveSibling(e.getKey().getFileName() + ".writing");
                Files.writeString(tmp, e.getValue());
                Files.move(tmp, e.getKey(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            }
        }
        return new Migration(lakeFiles, connectors, notes);
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
