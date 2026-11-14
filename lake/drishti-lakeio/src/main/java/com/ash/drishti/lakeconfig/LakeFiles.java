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

import com.ash.drishti.kernel.api.vocabulary.DomainVocabulary;
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
        Map<String, JsonNode> one = new LinkedHashMap<>();
        one.put(table.name(), entry(table));
        return !addEntries(lakeFile, one).isEmpty();
    }

    /**
     * Adds to the lake file the entries it has no entry for, and only those: every byte already in the file stays as it is (its comments,
     * its compact style), the new entries written at the end of {@code tables:} ({@link #appendTables}). Nothing is written when the file
     * has every one already, so a lake file that holds what is needed (a shipped one) is never touched.
     *
     * @return the names added, in order (empty: the file was not written)
     */
    public static List<String> addEntries(Path lakeFile, Map<String, ? extends JsonNode> entries) throws IOException {
        String text = Files.readString(lakeFile);
        Map<String, JsonNode> missing = missing(text, entries);
        if (missing.isEmpty()) {
            return List.of();
        }
        writeAtomically(lakeFile, withTables(header(lakeFile), text, missing));
        return new ArrayList<>(missing.keySet());
    }

    /** The names of {@code entries} the lake file has no entry for (nothing is written). */
    public static List<String> missing(Path lakeFile, Map<String, ? extends JsonNode> entries) throws IOException {
        return new ArrayList<>(missing(Files.readString(lakeFile), entries).keySet());
    }

    /** The entries a lake file's text has no entry for. */
    private static Map<String, JsonNode> missing(String text, Map<String, ? extends JsonNode> entries) throws IOException {
        JsonNode root = YAML.readTree(text);
        JsonNode have = root == null ? JsonNodeFactory.instance.objectNode() : root.path("tables");
        Map<String, JsonNode> missing = new LinkedHashMap<>();
        entries.forEach((n, e) -> {
            if (!have.has(n)) {
                missing.put(n, e);
            }
        });
        return missing;
    }

    /** The text with {@code missing} added under {@code tables:}: in place when it can be ({@link #appendTables}), else the file re-written. */
    private static String withTables(String header, String text, Map<String, JsonNode> missing) throws IOException {
        String appended = appendTables(text, missing);
        if (appended != null) {
            return appended;
        }
        ObjectNode root = (ObjectNode) YAML.readTree(text);    // a hand-written form text cannot be added to (tables: {a: ...} on one line)
        ObjectNode tables = root.with("tables");
        missing.forEach(tables::set);
        return header + YAML.writeValueAsString(root);
    }

    private static void writeAtomically(Path file, String text) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".writing");
        Files.writeString(tmp, text);
        try {
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** The widest line an added entry is written on before it is folded, as the shipped lake files are. */
    static final int WIDTH = 150;

    /**
     * {@code text} (a lake file) with {@code entries} added at the end of its {@code tables:} block, in the files' compact style (one line
     * per entry, {@code name: {id: X}}, else the name and a line per setting with flow lists folded at {@value #WIDTH} columns);
     * {@code tables: {}} becomes the block. Every other line is kept byte for byte. Null when the text has a form this cannot add to without
     * re-writing it (a one-line {@code tables: {...}} with entries in it), or when the result would not read back as the file plus the entries.
     */
    static String appendTables(String text, Map<String, ? extends JsonNode> entries) throws IOException {
        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        if (text.endsWith("\n")) {
            lines.remove(lines.size() - 1);
        }
        int at = -1;
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i).replaceFirst("\r$", "");
            if (l.startsWith("tables:")) {
                String rest = l.substring(7).replaceFirst("(^|\\s+)#.*$", "").strip();
                if (rest.matches("\\{\\s*}")) {
                    lines.set(i, "tables:");
                } else if (!rest.isEmpty()) {
                    return null;
                }
                at = i;
                break;
            }
        }
        String indent = "  ";
        int insert;
        if (at < 0) {
            lines.add("tables:");
            insert = lines.size();
        } else {
            int last = at;
            boolean seen = false;
            for (int i = at + 1; i < lines.size(); i++) {
                String l = lines.get(i);
                if (l.isBlank()) {
                    continue;
                }
                if (Character.isWhitespace(l.charAt(0))) {
                    if (!seen && !l.strip().startsWith("#")) {
                        indent = l.substring(0, l.length() - l.stripLeading().length());
                        seen = true;
                    }
                    last = i;
                } else if (!l.startsWith("#")) {
                    break;                                          // the next top-level key: the block ended at its last indented line
                }
            }
            insert = last + 1;
        }
        List<String> added = new ArrayList<>();
        String in = indent;
        entries.forEach((n, e) -> added.addAll(render(n, e, in)));
        lines.addAll(insert, added);
        String out = String.join("\n", lines) + "\n";
        JsonNode before = YAML.readTree(text);
        ObjectNode expect = before instanceof ObjectNode o ? o.deepCopy() : JsonNodeFactory.instance.objectNode();
        ObjectNode t = expect.path("tables") instanceof ObjectNode o ? o : expect.putObject("tables");
        entries.forEach((n, v) -> t.set(n, v == null || v.isNull() ? JsonNodeFactory.instance.objectNode() : v));
        return expect.equals(YAML.readTree(out)) ? out : null;
    }

    /** One entry's lines: {@code name: {flow}} when it fits, else the name and a line per setting (lists folded). */
    static List<String> render(String name, JsonNode e, String indent) {
        String key = indent + scalar(name) + ":";
        String one = key + " " + flow(e == null || e.isNull() ? JsonNodeFactory.instance.objectNode() : e);
        if (one.length() <= WIDTH || e == null || !e.isObject()) {
            return List.of(one);
        }
        List<String> out = new ArrayList<>();
        out.add(key);
        String inner = indent + indent;
        e.fields().forEachRemaining(f -> {
            String head = inner + scalar(f.getKey()) + ": ";
            if (!f.getValue().isArray() || f.getValue().isEmpty()) {
                out.add(head + flow(f.getValue()));
                return;
            }
            StringBuilder line = new StringBuilder(head).append('[');
            int n = f.getValue().size();
            for (int i = 0; i < n; i++) {
                String item = flow(f.getValue().get(i)) + (i < n - 1 ? "," : "]");
                boolean first = i == 0;
                if (!first && line.length() + 1 + item.length() > WIDTH) {
                    out.add(line.toString());
                    line = new StringBuilder(inner + "  ");
                } else if (!first) {
                    line.append(' ');
                }
                line.append(item);
            }
            out.add(line.toString());
        });
        return out;
    }

    /** A value in YAML flow style: {@code {a: b, c: [d, e]}}. */
    static String flow(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return "null";
        }
        if (n.isObject()) {
            List<String> parts = new ArrayList<>();
            n.fields().forEachRemaining(f -> parts.add(scalar(f.getKey()) + ": " + flow(f.getValue())));
            return "{" + String.join(", ", parts) + "}";
        }
        if (n.isArray()) {
            List<String> parts = new ArrayList<>();
            n.forEach(x -> parts.add(flow(x)));
            return "[" + String.join(", ", parts) + "]";
        }
        if (n.isNumber() || n.isBoolean()) {
            return n.asText();
        }
        return scalar(n.asText());
    }

    private static final java.util.Set<String> LAKE_WRITERS = DomainVocabulary.union(DomainVocabulary::namedLakeWriters);

    private static final java.util.Set<String> RESERVED = java.util.Set.of("true", "false", "null", "yes", "no", "on", "off", "y", "n");

    /** A plain scalar when it reads back as the same text, else single-quoted. */
    static String scalar(String s) {
        if (s.matches("[A-Za-z_][A-Za-z0-9_.\\-/]*") && !RESERVED.contains(s.toLowerCase(Locale.ROOT))) {
            return s;
        }
        return "'" + s.replace("'", "''") + "'";
    }

    /**
     * The tables the Delta and Iceberg connector templates of {@code packFiles} read from the lake {@code lake} ({@code lake: <lake>}; the
     * tables by {@code lake-tables:}, else by the connector's domain and kinds), each with its pack's recommended entry ({@link PackEntry})
     * as a lake file writes it: what that lake's file must hold for every connector those packs generate to find its table.
     */
    public static Map<String, JsonNode> packTables(List<Path> packFiles, String lake) throws IOException {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        for (Path file : packFiles) {
            JsonNode m = PackEntry.manifest(file);
            if (m == null || !m.isObject()) {
                continue;
            }
            String packName = m.path("pack").asText(file.toAbsolutePath().getParent().getFileName().toString());
            JsonNode defs = m.path("connectors").isObject() ? m.path("connectors") : m.path("connector-templates");
            for (var it = defs.fields(); it.hasNext(); ) {
                var c = it.next();
                JsonNode st = c.getValue().path("settings");
                if (!ConnectorLake.PLUGINS.contains(c.getValue().path("plugin").asText()) || !lake.equals(st.path(ConnectorLake.LAKE).asText())) {
                    continue;
                }
                List<String> names = new ArrayList<>();
                if (st.path(ConnectorLake.TABLES).isTextual()) {
                    for (String n : st.path(ConnectorLake.TABLES).asText().split(",")) {
                        if (!n.isBlank()) {
                            names.add(n.strip());
                        }
                    }
                } else {
                    String domain = st.path("domain").asText(packName);
                    for (JsonNode k : c.getValue().path("kinds")) {
                        JsonNode t = st.path("table").isObject() ? st.path("table").path(k.asText()) : st.path("table." + k.asText());
                        names.add(domain + "." + (t.isTextual() && !t.asText().isBlank() ? t.asText() : k.asText()));
                    }
                }
                for (String name : names) {
                    int dot = name.indexOf('.');
                    if (out.containsKey(name) || dot <= 0) {
                        continue;
                    }
                    String kind = m.path("lake").path("tables").path(name).path("kind").asText(name.substring(dot + 1));
                    out.put(name, entry(PackEntry.read(file, kind, c.getKey()).toTable(kind)));
                }
            }
        }
        return out;
    }

    /** The {@code pack.yaml} files of the packs under the given folders (each folder's packs, sorted by name), for {@link #packTables}. */
    public static List<Path> packFiles(List<Path> packRoots) throws IOException {
        List<Path> out = new ArrayList<>();
        for (Path root : packRoots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            com.ash.drishti.api.PackFolders.list(root).stream().map(d -> d.resolve("pack.yaml")).forEach(out::add);   // and its domain folders
        }
        return out;
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
        if (Files.exists(Lakes.file(lakesDir, name))) {
            return false;
        }
        Path file = lakesDir.resolve(name + ".yaml");                     // a new lake: in the folder itself
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
        if (t.rowRetention().isEmpty()) {
            put(e, "retention", t.retention());
        } else {
            ObjectNode rr = e.putObject("retention");
            t.rowRetention().forEach((k, x) -> {
                if (k.equals("max-rows") && x.matches("\\d+")) {
                    rr.put(k, Integer.parseInt(x));
                } else {
                    rr.put(k, x);
                }
            });
            put(rr, "dates", t.retention());
        }
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
                if (RemovedKinds.replacement(kind) != null) {
                    continue;                                      // a kind no pack serves any more: never a table of the lake
                }
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
     * the connector's layout, every reading setting kept. A named lake writer's ({@link DomainVocabulary#namedLakeWriters()}) {@code lake:} folder in that form becomes the
     * name {@code demo}. A connector that names its root otherwise (a path or URI of its own) is left as it is: that form is permanent.
     * With {@code write}, each file is kept beside it first ({@code <name>.yaml.before-lake-<time>.bak}).
     */
    public static Migration adoptDemo(Path connectorsDir, Path lakesDir, boolean write) throws IOException {
        return adoptDemo(connectorsDir, lakesDir, write, (before, after) -> after);
    }

    /**
     * {@link #adoptDemo(Path, Path, boolean)}, each rewritten file's text passed through {@code restamp(before, after)} first: the server
     * gives the pack templates' stamp ({@code TemplateStamp.keepPristine}), so a stamped file nobody edited is stamped again over its new
     * text and still counts as unedited (it keeps following its pack's template at upgrades); an edited one stays edited.
     */
    public static Migration adoptDemo(Path connectorsDir, Path lakesDir, boolean write, java.util.function.BinaryOperator<String> restamp) throws IOException {
        Map<Path, String> lakeFiles = new LinkedHashMap<>();
        Map<Path, String> connectors = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        if (connectorsDir == null || !Files.isDirectory(connectorsDir)) {
            return new Migration(lakeFiles, connectors, notes);
        }
        Path demoFile = lakesDir.resolve("demo.yaml");
        String demoText;
        if (Files.isRegularFile(demoFile)) {
            demoText = Files.readString(demoFile);
        } else {
            try (java.io.InputStream in = LakeFiles.class.getClassLoader().getResourceAsStream("drishti/lakes/demo.yaml")) {
                if (in == null) {
                    return new Migration(lakeFiles, connectors, notes);
                }
                demoText = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        ObjectNode demo = (ObjectNode) YAML.readTree(demoText);
        ObjectNode tables = demo.with("tables");
        Map<String, JsonNode> added = new LinkedHashMap<>();       // only the tables the demo lake file lacks: the rest of it is never re-written
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
            if (LAKE_WRITERS.contains(plugin) && demoRoot(flat.get(ConnectorLake.LAKE))) {
                ((ObjectNode) rewritten.get("settings")).put(ConnectorLake.LAKE, "demo");
                connectors.put(f, restamp.apply(Files.readString(f), comments(f) + YAML.writeValueAsString(rewritten)));
                notes.add(f.getFileName() + ": writes into the demo lake by name now (lake: demo)");
                continue;
            }
            if (!ConnectorLake.PLUGINS.contains(plugin) || flat.containsKey(ConnectorLake.LAKE) || !demoRoot(flat.get("root"))) {
                continue;
            }
            String domain = flat.getOrDefault("domain", "");
            List<String> kinds = new ArrayList<>();
            c.path("kinds").forEach(k -> kinds.add(k.asText()));
            String owner = otherLake(lakesDir, kinds.stream().map(k -> (domain.isEmpty() ? plugin : domain) + "." + flat.getOrDefault("table." + k, k)).toList());
            if (owner != null) {                                   // the official results' reader (risk-results): another lake's tables, never the demo lake's
                notes.add(f.getFileName() + ": reads the tables of lake " + owner + " (its root is the same folder); left as it is");
                continue;
            }
            List<String> mine = new ArrayList<>();
            if (kinds.isEmpty()) {
                tables.fieldNames().forEachRemaining(n -> {
                    if (n.startsWith(domain + ".")) {
                        mine.add(n);
                    }
                });
            }
            for (String kind : kinds) {
                if (RemovedKinds.replacement(kind) != null) {
                    continue;                                      // a kind no pack serves any more: never a table of the lake
                }
                String folder = flat.getOrDefault("table." + kind, kind);
                String tname = (domain.isEmpty() ? plugin : domain) + "." + folder;
                mine.add(tname);
                if (tables.has(tname)) {
                    continue;
                }
                ObjectNode e = tables.putObject(tname);
                added.put(tname, e);
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
            connectors.put(f, restamp.apply(Files.readString(f), comments(f) + YAML.writeValueAsString(rewritten)));
            notes.add(f.getFileName() + ": reads the demo lake by name now (lake: demo, " + mine.size() + " tables), so it follows the lake's folder");
        }
        if (!added.isEmpty()) {                                    // a site's own connector read a table the file lacks: that entry only
            lakeFiles.put(demoFile, withTables(header(demoFile), demoText, added));
        }
        if (write) {
            String at = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            for (var e : lakeFiles.entrySet()) {
                writeAtomically(e.getKey(), e.getValue());
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

    /**
     * The lake other than the demo lake whose file (in {@code lakesDir}, else shipped in the jar) holds every one of {@code names}, or null:
     * a connector reading such tables by the old root (the official results' readers, {@code risk-results.*}) belongs to that lake.
     */
    static String otherLake(Path lakesDir, List<String> names) throws IOException {
        if (names.isEmpty()) {
            return null;
        }
        // every lake file in the folder (a module's lakes sit in its own subfolder, shipped by that module's jar), then the jar's own
        java.util.Set<String> lakes = new java.util.LinkedHashSet<>();
        for (Path f : Lakes.files(lakesDir)) {
            lakes.add(f.getFileName().toString().replaceFirst("\\.yaml$", ""));
        }
        lakes.addAll(Lakes.shipped());
        for (String lake : lakes) {
            if (lake.equals("demo")) {
                continue;
            }
            Path f = Lakes.file(lakesDir, lake);
            JsonNode root = null;
            if (Files.isRegularFile(f)) {
                try {
                    root = YAML.readTree(Files.readString(f));
                } catch (com.fasterxml.jackson.core.JacksonException e) {
                    continue;                                       // not a lake file this can read: it owns nothing
                }
            } else {
                try (java.io.InputStream in = Lakes.shippedFile(lake)) {
                    root = in == null ? null : YAML.readTree(in);
                }
            }
            JsonNode t = root == null ? null : root.path("tables");
            if (t != null && names.stream().allMatch(t::has)) {
                return lake;
            }
        }
        return null;
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
        while (taken.contains(n) || Files.exists(Lakes.file(lakesDir, n))) {
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
