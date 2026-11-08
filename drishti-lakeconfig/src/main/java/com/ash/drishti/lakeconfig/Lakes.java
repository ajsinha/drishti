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
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Every lake a server or a tool knows, read from the lake folder ({@code config/lakes/*.yaml}; {@link #dir()} says where) and checked as
 * a whole: an unknown key, a format that is not delta or iceberg, a partition column after {@code business_date} beyond the fourth or
 * {@code business_date} itself, a load mode that is not replace or append, a secret written as a value rather than {@code ${ENV}}, a
 * file whose {@code lake:} differs from its name: each refuses the file with the key named. Environment references
 * ({@code ${NAME}}, {@code ${NAME:default}}, {@code ${NAME:-default}}) are resolved as the file is read.
 */
public final class Lakes {

    /** The system property that names the lake folder; it wins over the environment. */
    public static final String PROPERTY = "drishti.lakes.dir";
    /** The environment variable that names the lake folder. */
    public static final String ENV = "DRISHTI_LAKES_DIR";
    /** The variable that names the demo lake's root (config/lakes/demo.yaml: {@code ${DRISHTI_DELTA_ROOT:<data>/delta/demo}}). */
    public static final String DEMO_ROOT_ENV = "DRISHTI_DELTA_ROOT";
    /** The lake folder when neither is set. */
    public static final String DEFAULT_DIR = "./config/lakes";
    /** At most this many partition columns after {@code business_date}. */
    public static final int MAX_PARTITIONS = 4;

    /** A reference with no reference inside its default: resolved innermost first, so {@code ${A:${B:x}/delta}} nests. */
    private static final Pattern REF = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_.-]*)(?::-?([^}$]*))?}");
    /** A lake's name: lower-case letters, digits, '-' and '_' (its file's name, and its folder under {@link #LAKES_PARENT}). */
    public static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_-]*");
    /** Where lakes are by default: each lake's own folder under it, {@code <data>/delta/<lake>} (a lake file may name another root). */
    public static final String LAKES_PARENT = "${drishti.data.dir:./data}/delta";
    private static final Set<String> SECRETS = Set.of("access-key", "secret-key", "session-token", "credential", "token", "password",
            "client-secret", "s3.access-key", "s3.secret-key", "s3.session-token");
    private static final Set<String> TOP = Set.of("lake", "format", "root", "storage", "catalog", "defaults", "tables", "memory", "description");
    static final Set<String> TABLE = Set.of("kind", "path", "format", "partition-by", "promote", "vectors", "flatten", "id", "date", "latest-by",
            "load-mode", "sort-by", "file-rows", "row-group-rows", "file-size-mb", "row-group-mb", "compression", "vector-compression",
            "retention", "maintenance", "time-travel", "properties");
    static final Set<String> LAYOUT = Set.of("file-rows", "row-group-rows", "file-size-mb", "row-group-mb", "compression", "vector-compression");
    private static final Set<String> MEMORY = Set.of("max-mb", "chunk-kb", "contract", "owner", "idle-trim-minutes");

    private final Map<String, Lake> lakes;
    private final Path dir;

    private Lakes(Map<String, Lake> lakes, Path dir) {
        this.lakes = java.util.Collections.unmodifiableMap(new TreeMap<>(lakes));
        this.dir = dir;
    }

    /** Whether {@code name} is a lake's name ({@link #NAME}). */
    public static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /** A lake's default root, unresolved: {@code ${drishti.data.dir:./data}/delta/<name>}. */
    public static String defaultRoot(String name) {
        return LAKES_PARENT + "/" + name;
    }

    /** A lake's default root for its format: a Delta lake's {@link #defaultRoot(String)}; an Iceberg lake's {@code <data>/iceberg/<name>}. */
    public static String defaultRoot(String name, String format) {
        return "iceberg".equals(format) ? "${drishti.data.dir:./data}/iceberg/" + name : defaultRoot(name);
    }

    /** The lake folder: {@value #PROPERTY}, else {@value #ENV}, else {@value #DEFAULT_DIR}. */
    public static Path dir() {
        String p = System.getProperty(PROPERTY);
        if (p == null || p.isBlank()) {
            p = properties.apply(PROPERTY);
        }
        if (p == null || p.isBlank()) {
            p = properties.apply(ENV);
        }
        return Path.of(p == null || p.isBlank() ? DEFAULT_DIR : p.strip());
    }

    private static volatile Function<String, String> properties = System::getenv;

    /**
     * How a lake file's references are resolved by {@link #current()}: the server installs its own settings (its environment, system
     * properties and configuration, as it resolves a connector file's references), so {@code ${DRISHTI_DELTA_ROOT}} given as a server
     * setting is seen; null: the process's environment.
     */
    public static void useProperties(Function<String, String> lookup) {
        properties = lookup == null ? System::getenv : lookup;
    }

    private static final Pattern NAMED = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_.-]*)");

    /**
     * The settings the lake files refer to, with the values {@link #useProperties} gives them here: what a process started from this one
     * (the data inbox's loader) is handed in its environment, so it resolves every lake file exactly as this process does, through the
     * same reader, whatever was a {@code --setting} here. Only the names the files use; unset ones are left out.
     */
    public static Map<String, String> environment() {
        java.util.Set<String> names = new java.util.TreeSet<>();
        Path d = dir();
        if (Files.isDirectory(d)) {
            try {
                for (Path f : files(d)) {
                    Matcher m = NAMED.matcher(Files.readString(f));
                    while (m.find()) {
                        names.add(m.group(1));
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("cannot list the lake folder " + d + ": " + e.getMessage(), e);
            }
        }
        for (String name : SHIPPED) {
            try (java.io.InputStream in = Lakes.class.getClassLoader().getResourceAsStream("drishti/lakes/" + name + ".yaml")) {
                if (in != null) {
                    Matcher m = NAMED.matcher(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                    while (m.find()) {
                        names.add(m.group(1));
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String n : names) {
            String v = properties.apply(n);
            if (v != null && !v.isEmpty()) {
                out.put(n, v);
            }
        }
        return out;
    }

    /** The lakes of {@link #dir()} with the process's environment. */
    public static Lakes load() {
        return read(dir(), System::getenv);
    }

    /**
     * The lakes of {@link #dir()} as they are now, references resolved with the server's settings ({@link #useProperties}): read at
     * every ask (a connector's start, Admin -> Lakes, the checks), so an edited lake file is seen at once; a few small files.
     */
    public static Lakes current() {
        return read(dir(), properties);
    }

    /**
     * The lake files of a folder: its {@code *.yaml} and those of its <b>domain folders</b> one level down ({@code config/lakes/banking/
     * var-official.yaml}, docs/architecture/DOMAIN_MODULES.md section 8), in name order. A lake's name is its file's, wherever it is.
     */
    public static List<Path> files(Path dir) throws IOException {
        List<Path> out = new java.util.ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) {
            return out;
        }
        List<Path> folders = new java.util.ArrayList<>(List.of(dir));
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isDirectory).sorted().forEach(folders::add);
        }
        for (Path d : folders) {
            try (Stream<Path> s = Files.list(d)) {
                s.filter(f -> Files.isRegularFile(f) && f.getFileName().toString().endsWith(".yaml")).sorted().forEach(out::add);
            }
        }
        return out;
    }

    /** A lake's file in a folder or one of its domain folders ({@link #files}); {@code <dir>/<name>.yaml} (not there) when none holds it. */
    public static Path file(Path dir, String name) {
        try {
            for (Path f : files(dir)) {
                if (f.getFileName().toString().equals(name + ".yaml")) {
                    return f;
                }
            }
        } catch (IOException e) {
            // an unreadable folder holds no lake
        }
        return dir.resolve(name + ".yaml");
    }

    /** Every {@code *.yaml} of {@code dir} and its domain folders (none when the folder does not exist: a server with no lake file). */
    public static Lakes read(Path dir, Function<String, String> env) {
        Map<String, Lake> out = new TreeMap<>();
        if (dir != null && Files.isDirectory(dir)) {
            try {
                for (Path f : files(dir)) {
                    Lake l = readFile(f, env);
                    out.put(l.name(), l);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("cannot list the lake folder " + dir + ": " + e.getMessage(), e);
            }
        }
        for (String name : SHIPPED) {                   // the shipped lakes (the demo lake) when the folder has no file of theirs:
            if (!out.containsKey(name)) {               // a server started in another working folder, a container, a stack node
                String res = "drishti/lakes/" + name + ".yaml";
                try (java.io.InputStream in = Lakes.class.getClassLoader().getResourceAsStream(res)) {
                    if (in != null) {
                        out.put(name, parse(new YAMLMapper().readTree(in), name, null, env));
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException("cannot read the shipped lake " + res + ": " + e.getMessage(), e);
                }
            }
        }
        return new Lakes(out, dir);
    }

    /** The index each jar that ships lakes carries: one lake name per line ({@code #} comments). */
    public static final String SHIPPED_INDEX = "drishti/lakes/shipped.txt";

    /**
     * The lakes shipped in the jars on the class path ({@code drishti/lakes/<name>.yaml}, each a copy of the repository's lake file): the
     * core's demo lake, and a module's own (the banking module's official results) only when its jar is there. Each jar lists its lakes in
     * {@value #SHIPPED_INDEX}.
     */
    public static final List<String> SHIPPED = shipped(Lakes.class.getClassLoader());

    static List<String> shipped(ClassLoader loader) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        try {
            for (java.net.URL u : java.util.Collections.list(loader.getResources(SHIPPED_INDEX))) {
                try (java.io.InputStream in = u.openStream()) {
                    for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\\R")) {
                        String n = line.replaceFirst("#.*", "").strip();
                        if (!n.isEmpty()) {
                            out.add(n);
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + SHIPPED_INDEX, e);
        }
        return List.copyOf(out);
    }

    /** Lakes built in code (tests, a tool that was handed one lake). */
    public static Lakes of(Lake... lakes) {
        Map<String, Lake> m = new TreeMap<>();
        for (Lake l : lakes) {
            m.put(l.name(), l);
        }
        return new Lakes(m, null);
    }

    /** One lake file, checked. */
    public static Lake readFile(Path file, Function<String, String> env) {
        String expected = file.getFileName().toString().replaceFirst("\\.yaml$", "");
        try {
            return parse(new YAMLMapper().readTree(Files.readString(file)), expected, file, env);
        } catch (IOException e) {
            throw new IllegalArgumentException(file + ": not readable YAML: " + e.getMessage(), e);
        }
    }

    /** A lake from its YAML text ({@code expected}: the name its file gives it, or null to take the text's). */
    public static Lake parse(String yaml, String expected, Function<String, String> env) {
        try {
            return parse(new YAMLMapper().readTree(yaml), expected, null, env);
        } catch (IOException e) {
            throw new IllegalArgumentException("not readable YAML: " + e.getMessage(), e);
        }
    }

    static Lake parse(JsonNode root, String expected, Path file, Function<String, String> env) {
        String where = file == null ? "lake" : file.toString();
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException(where + ": a lake file is a mapping (lake:, format:, root:, tables:)");
        }
        unknown(where, "", root, TOP);
        Values v = new Values(where, env);
        String name = v.text(root, "lake", expected);
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(where + ": lake: a name of lower-case letters, digits, '-' and '_' (got " + name + ")");
        }
        if (expected != null && !expected.equals(name)) {
            throw new IllegalArgumentException(where + ": lake: " + name + " differs from the file's name " + expected + ".yaml");
        }
        String format = format(where, "format", v.text(root, "format", "delta"));
        String rootPath = v.text(root, "root", "");
        Map<String, String> storage = v.map(root.path("storage"), "storage");
        secrets(where, "storage", root.path("storage"), storage);
        Map<String, String> catalog = new LinkedHashMap<>();
        JsonNode cat = root.path("catalog");
        if (cat.isTextual()) {
            catalog.put("type", v.resolve("catalog", cat.asText()));
        } else if (cat.isObject()) {
            catalog.putAll(v.map(cat, "catalog"));
            secrets(where, "catalog", cat, catalog);
        }
        String ctype = catalog.getOrDefault("type", "none");
        if (!Set.of("none", "rest", "glue", "s3tables", "hadoop").contains(ctype)) {
            throw new IllegalArgumentException(where + ": catalog.type " + ctype + ": none (path tables), rest, glue or s3tables");
        }
        if (format.equals("delta") && !ctype.equals("none")) {
            throw new IllegalArgumentException(where + ": catalog: is Iceberg's; a Delta lake's tables are paths under its root");
        }
        if (rootPath.isBlank() && !Set.of("rest", "glue", "s3tables").contains(ctype)) {      // a catalog says where its tables are
            rootPath = v.resolve("root", defaultRoot(name, format));     // no root: the lake's own folder, <data>/delta/<lake> (<data>/iceberg/<lake>)
        }
        Map<String, String> memory = v.map(root.path("memory"), "memory");
        unknown(where, "memory.", root.path("memory"), MEMORY);
        boolean inMemory = rootPath.startsWith(Lake.MEMORY_SCHEME);
        if (inMemory && !memory.containsKey("max-mb")) {
            throw new IllegalArgumentException(where + ": memory.max-mb: an in-memory store (" + rootPath + ") has a hard cap");
        }
        if (!inMemory && !memory.isEmpty()) {
            throw new IllegalArgumentException(where + ": memory: is for an in-memory store (root: mem://<store>) only");
        }
        JsonNode defaults = root.path("defaults");
        unknown(where, "defaults.", defaults, TABLE);
        Map<String, LakeTable> tables = new LinkedHashMap<>();
        JsonNode ts = root.path("tables");
        if (!ts.isMissingNode() && !ts.isNull() && !ts.isObject()) {
            throw new IllegalArgumentException(where + ": tables: a mapping of <domain>.<kind> to the table's entry");
        }
        for (var it = ts.fields(); it.hasNext(); ) {
            var e = it.next();
            tables.put(e.getKey(), table(v, where, format, e.getKey(), e.getValue(), defaults));
        }
        return new Lake(name, format, rootPath, storage, catalog, memory, tables, file);
    }

    /** One table's entry over the lake's defaults. */
    static LakeTable table(Values v, String where, String lakeFormat, String key, JsonNode own, JsonNode defaults) {
        int dot = key.indexOf('.');
        if (dot <= 0 || dot == key.length() - 1) {
            throw new IllegalArgumentException(where + ": tables." + key + ": a table is named <domain>.<kind> (trading.trade), or "
                    + "<domain>.<table> with kind: when a kind has several tables (trading.trade_endur, kind: trade)");
        }
        String at = where + ": tables." + key;
        if (own != null && !own.isNull() && !own.isObject()) {
            throw new IllegalArgumentException(at + ": a mapping (path:, partition-by:, promote:, ...) or nothing");
        }
        JsonNode t = own == null || own.isNull() ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode() : own;
        unknown(where, "tables." + key + ".", t, TABLE);
        Function<String, JsonNode> pick = k -> t.has(k) ? t.get(k) : defaults.path(k);
        String domain = key.substring(0, dot);
        String name = key.substring(dot + 1);
        String kind = v.text(t.path("kind"), name);
        String path = v.text(t.path("path"), domain + "/" + name);
        if (path.startsWith("/") || path.contains("..") || path.contains("\\")) {
            throw new IllegalArgumentException(at + ".path " + path + ": a folder under the lake's root (trading/trade_endur)");
        }
        String format = format(at, "format", v.text(pick.apply("format"), lakeFormat));
        List<String> partition = v.list(pick.apply("partition-by"));
        for (String p : partition) {
            if (p.equalsIgnoreCase(LakeTable.BUSINESS_DATE)) {
                throw new IllegalArgumentException(at + ".partition-by: business_date is always the first partition; name only what comes after it");
            }
        }
        if (partition.size() > MAX_PARTITIONS) {
            throw new IllegalArgumentException(at + ".partition-by: at most " + MAX_PARTITIONS + " columns after business_date (got " + partition + ")");
        }
        if (format.equals("iceberg") && !partition.isEmpty()) {
            throw new IllegalArgumentException(at + ".partition-by is Delta's: an Iceberg table of the layout is partitioned by business date "
                    + "alone (keep the fields as promote: columns)");
        }
        String mode = v.text(pick.apply("load-mode"), LakeTable.REPLACE).toLowerCase(Locale.ROOT);
        if (!mode.equals(LakeTable.REPLACE) && !mode.equals(LakeTable.APPEND)) {
            throw new IllegalArgumentException(at + ".load-mode " + mode + ": replace (each business date of a load replaced, the default) or "
                    + "append (rows added; each batch committed once)");
        }
        Map<String, String> layout = new LinkedHashMap<>();
        for (String k : LAYOUT) {
            String x = v.text(pick.apply(k), null);
            if (x != null) {
                if (k.endsWith("-rows") || k.endsWith("-mb")) {
                    try {
                        if (Double.parseDouble(x) <= 0) {
                            throw new NumberFormatException();
                        }
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException(at + "." + k + " " + x + ": a number more than 0");
                    }
                }
                layout.put(k, x);
            }
        }
        List<String> promote = v.list(pick.apply("promote"));
        return new LakeTable(domain, name, kind, path, format, partition, promote, v.list(pick.apply("vectors")),
                v.map(pick.apply("flatten"), "flatten"), v.text(pick.apply("id"), null), v.text(pick.apply("date"), null),
                v.text(pick.apply("latest-by"), null), mode, v.list(pick.apply("sort-by")), layout, v.text(pick.apply("retention"), null),
                v.map(pick.apply("maintenance"), "maintenance"), v.text(pick.apply("time-travel"), null), v.map(pick.apply("properties"), "properties"));
    }

    /** Every lake by name. */
    public Map<String, Lake> all() {
        return lakes;
    }

    /** The folder the lakes were read from (null when built in code). */
    public Path folder() {
        return dir;
    }

    public Optional<Lake> find(String name) {
        return Optional.ofNullable(lakes.get(name));
    }

    /** The lake of that name, or a refusal naming the lakes there are. */
    public Lake get(String name) {
        Lake l = lakes.get(name);
        if (l == null) {
            throw new IllegalArgumentException("no lake named " + name + " (" + (lakes.isEmpty() ? "no lake file in " + dir
                    : "known: " + lakes.keySet() + " in " + dir) + "): add config/lakes/" + name + ".yaml (docs/guides/DATA_LAKE_GUIDE.md)");
        }
        return l;
    }

    /** The lake whose root is {@code mem://<store>}, if any. */
    public Optional<Lake> memoryStore(String store) {
        return lakes.values().stream().filter(l -> l.inMemory() && l.store().equals(store)).findFirst();
    }

    /** Every lake as plain maps (the JSON dump). */
    public List<Map<String, Object>> describe() {
        return lakes.values().stream().map(Lake::describe).toList();
    }

    private static String format(String where, String key, String f) {
        String x = f == null ? "delta" : f.toLowerCase(Locale.ROOT);
        if (!x.equals("delta") && !x.equals("iceberg")) {
            throw new IllegalArgumentException(where + ": " + key + " " + f + ": delta or iceberg");
        }
        return x;
    }

    private static void unknown(String where, String prefix, JsonNode n, Set<String> allowed) {
        if (n == null || !n.isObject()) {
            return;
        }
        n.fieldNames().forEachRemaining(k -> {
            if (!allowed.contains(k)) {
                throw new IllegalArgumentException(where + ": unknown key " + prefix + k + " (allowed: " + new java.util.TreeSet<>(allowed) + ")");
            }
        });
    }

    private static void secrets(String where, String block, JsonNode raw, Map<String, String> resolved) {
        for (String k : resolved.keySet()) {
            if (SECRETS.contains(k) && !REF.matcher(raw.path(k).asText()).find() && !raw.path(k).asText().startsWith("file:")) {
                throw new IllegalArgumentException(where + ": " + block + "." + k + ": a secret is never written in a lake file; give "
                        + "${AN_ENVIRONMENT_VARIABLE} or use the platform's role");
            }
        }
    }

    /** Reads values with the environment references resolved; a reference to a variable that is not set and has no default refuses. */
    static final class Values {
        private final String where;
        private final Function<String, String> env;

        Values(String where, Function<String, String> env) {
            this.where = where;
            this.env = env == null ? k -> null : env;
        }

        String resolve(String key, String raw) {
            if (raw == null) {
                return null;
            }
            String now = raw;
            for (int round = 0; round < 8 && REF.matcher(now).find(); round++) {
                Matcher m = REF.matcher(now);
                StringBuilder out = new StringBuilder();
                while (m.find()) {
                    String val = lookup(m.group(1));
                    if (val == null || val.isEmpty()) {
                        val = m.group(2);
                    }
                    if (val == null) {
                        throw new IllegalArgumentException(where + ": " + key + ": the environment variable " + m.group(1) + " is not set and "
                                + "the reference gives no default (${" + m.group(1) + ":default})");
                    }
                    m.appendReplacement(out, Matcher.quoteReplacement(val));
                }
                m.appendTail(out);
                now = out.toString();
            }
            return now;
        }

        /**
         * A variable: the environment; a dotted name ({@code drishti.data.dir}) also as a system property and as its environment form
         * ({@code DRISHTI_DATA_DIR}), as the server resolves it.
         */
        private String lookup(String name) {
            String v = env.apply(name);
            if ((v == null || v.isEmpty()) && (name.contains(".") || name.contains("-"))) {
                v = System.getProperty(name);
                if (v == null || v.isEmpty()) {
                    v = env.apply(name.toUpperCase(java.util.Locale.ROOT).replace('.', '_').replace('-', '_'));
                }
            }
            return v;
        }

        String text(JsonNode parent, String key, String dflt) {
            return textAt(parent.path(key), dflt, key);
        }

        String text(JsonNode n, String dflt) {
            return textAt(n, dflt, "value");
        }

        private String textAt(JsonNode n, String dflt, String key) {
            if (n == null || n.isMissingNode() || n.isNull()) {
                return dflt;
            }
            String s = n.isValueNode() ? n.asText() : n.toString();
            return s.isBlank() ? dflt : resolve(key, s.strip());
        }

        /** A YAML list, or one comma-separated text. */
        List<String> list(JsonNode n) {
            List<String> out = new ArrayList<>();
            if (n == null || n.isMissingNode() || n.isNull()) {
                return out;
            }
            if (n.isArray()) {
                n.forEach(x -> {
                    if (!x.asText().isBlank()) {
                        out.add(resolve("list", x.asText().trim()));
                    }
                });
            } else {
                for (String s : resolve("list", n.asText()).split(",")) {
                    if (!s.isBlank()) {
                        out.add(s.trim());
                    }
                }
            }
            return out;
        }

        /** A mapping, nesting flattened to dotted keys. */
        Map<String, String> map(JsonNode n, String key) {
            Map<String, String> out = new LinkedHashMap<>();
            if (n == null || n.isMissingNode() || n.isNull()) {
                return out;
            }
            if (!n.isObject()) {
                throw new IllegalArgumentException(where + ": " + key + ": a mapping");
            }
            flat("", n, out, key);
            return out;
        }

        private void flat(String prefix, JsonNode n, Map<String, String> out, String key) {
            n.fields().forEachRemaining(e -> {
                if (e.getValue().isObject()) {
                    flat(prefix + e.getKey() + ".", e.getValue(), out, key);
                } else if (e.getValue().isArray()) {
                    List<String> parts = new ArrayList<>();
                    e.getValue().forEach(x -> parts.add(x.asText()));
                    out.put(prefix + e.getKey(), resolve(key + "." + prefix + e.getKey(), String.join(",", parts)));
                } else if (!e.getValue().isNull()) {
                    out.put(prefix + e.getKey(), resolve(key + "." + prefix + e.getKey(), e.getValue().asText()));
                }
            });
        }
    }
}
