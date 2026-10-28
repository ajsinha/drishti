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
package com.ash.drishti.lakeload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everything one load does, from a YAML file (docs/guides/TOOLS.md section 4.13 lists every key): where the JSON Lines are, what a row
 * is (the same words as {@code lake build} and a pack's connector: {@code id} is {@code --key} and the ingest key, {@code date},
 * {@code promote} is {@code layout.<kind>.columns}, {@code vectors} is {@code layout.<kind>.vectors}, {@code flatten},
 * {@code partition-by}), where the table is ({@code lake}, {@code domain}, {@code kind}, {@code table}, or {@code path}), how it is laid
 * out and how many threads do the work. {@code --set key.path=value} overrides any key. A value may name an environment variable
 * ({@code ${MINIO_ENDPOINT}}); keys and secrets are never taken from the file: S3 credentials come from the AWS default chain.
 */
record LoadConfig(Source source, Target target, Parallel parallel, Memory memory, Rejects rejects, Report report, boolean lock) {

    /** The input files and what a row is. */
    record Source(List<String> files, boolean recursive, Map<String, String> storage, String id, String date, LocalDate asOf,
            List<String> promote, List<String> vectors, Map<String, String> flatten, List<String> partitionBy) {}

    /** The table and its layout. */
    record Target(String format, String path, String lake, String domain, String kind, String table, Map<String, String> storage,
            Map<String, String> properties, Map<String, String> iceberg, Layout layout) {

        boolean isIceberg() {
            return format.equals("iceberg");
        }
    }

    /**
     * How a date's rows are laid out: sorted by {@code sortBy} ({@code id} is the row id), cut into files of about {@code fileBytes},
     * row groups of {@code rowGroupBytes} (0: the format's own default), the codecs, and with {@code shardAligned} a new file at a group
     * boundary (the sort columns but the id) once a file holds a quarter of its size.
     */
    record Layout(List<String> sortBy, long fileBytes, long rowGroupBytes, String compression, String vectorCompression, boolean byteStreamSplit,
            boolean shardAligned) {}

    /** Threads, and how large a range of a plain file one reader takes. */
    record Parallel(int readers, int parsers, int writers, long splitBytes) {}

    /**
     * The memory a load may use, whatever the size of its input: {@code budget} (memory.budget-mb, default half the heap) shared by the
     * reader queue (15%), the parsers' sort buffers (55%) and the writers (30%: merge buffers and open Parquet row groups), unless
     * parallel.queue-mb and memory.sort-buffer-mb say otherwise (within the budget). Rows beyond the sort buffers are spilled to
     * {@code spillDir} as sorted runs; {@code checkSpace} compares the spill folder's free space with an estimate before reading.
     */
    record Memory(long budget, long queueBytes, long sortBytes, long writeBytes, String spillDir, boolean checkSpace) {}

    record Rejects(String file, double maxPct, int keep) {}

    record Report(String summary, String manifest, String progress, boolean runLog, boolean dryRun) {}

    static final List<String> DEFAULT_SORT = List.of("LEGAL_ENTITY", "DESK", "id");
    private static final Pattern ENV = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?}");
    private static final Set<String> SECRETS = Set.of("access-key", "secret-key", "s3.access-key", "s3.secret-key", "session-token",
            "s3.session-token", "credential", "token");
    private static final Map<String, Set<String>> KEYS = Map.of(
            "", Set.of("source", "target", "parallel", "memory", "rejects", "report", "lock"),
            "source", Set.of("files", "recursive", "storage-options", "format", "id", "date", "as-of", "promote", "vectors", "vector", "flatten",
                    "partition-by", "pack", "connector"),
            "target", Set.of("format", "path", "lake", "domain", "kind", "table", "storage-options", "mode", "properties", "iceberg", "layout"),
            "target.layout", Set.of("sort-by", "file-size-mb", "row-group-mb", "compression", "vector-compression", "byte-stream-split",
                    "shard-aligned"),
            "parallel", Set.of("readers", "parsers", "writers", "queue-mb", "split-mb"),
            "memory", Set.of("budget-mb", "sort-buffer-mb", "spill-dir", "check-spill-space"),
            "rejects", Set.of("file", "max-pct", "keep"),
            "report", Set.of("summary", "manifest", "progress", "run-log", "dry-run"));

    /** Reads {@code file} (YAML or JSON) and applies {@code overrides} ({@code key.path=value}). */
    static LoadConfig read(Path file, List<String> overrides) throws IOException {
        return read(file, overrides, System::getenv);
    }

    static LoadConfig read(Path file, List<String> overrides, Function<String, String> env) throws IOException {
        JsonNode root = file == null ? JsonNodeFactory.instance.objectNode() : new YAMLMapper().readTree(Files.readString(file));
        if (root == null || root.isMissingNode() || root.isNull()) {
            root = JsonNodeFactory.instance.objectNode();
        }
        if (!root.isObject()) {
            throw new IllegalArgumentException(file + ": a load configuration is a mapping (source:, target:, ...)");
        }
        ObjectNode tree = (ObjectNode) root;
        for (String o : overrides) {
            set(tree, o);
        }
        return of(tree, env);
    }

    /** {@code a.b.c=value}: the value read as YAML ({@code [A, B]} is a list, {@code 8} a number). */
    static void set(ObjectNode tree, String kv) throws IOException {
        int eq = kv.indexOf('=');
        if (eq <= 0) {
            throw new IllegalArgumentException("--set " + kv + ": give KEY.PATH=VALUE");
        }
        String[] path = kv.substring(0, eq).trim().split("\\.");
        String text = kv.substring(eq + 1);
        JsonNode value = text.isEmpty() ? JsonNodeFactory.instance.nullNode() : new YAMLMapper().readTree("v: " + text).get("v");
        ObjectNode at = tree;
        for (int i = 0; i < path.length - 1; i++) {
            JsonNode next = at.get(path[i]);
            if (!(next instanceof ObjectNode)) {
                next = at.putObject(path[i]);
            }
            at = (ObjectNode) next;
        }
        at.set(path[path.length - 1], value);
    }

    static LoadConfig of(ObjectNode tree, Function<String, String> env) {
        check("", tree);
        Reader r = new Reader(env);
        JsonNode s = tree.path("source");
        JsonNode t = tree.path("target");
        JsonNode l = t.path("layout");
        JsonNode p = tree.path("parallel");
        JsonNode mem = tree.path("memory");
        JsonNode rj = tree.path("rejects");
        JsonNode rp = tree.path("report");
        check("source", s);
        check("target", t);
        check("target.layout", l);
        check("parallel", p);
        check("memory", mem);
        check("rejects", rj);
        check("report", rp);
        String format = r.text(s, "format", "jsonl").toLowerCase(Locale.ROOT);
        if (!format.equals("jsonl") && !format.equals("ndjson")) {
            throw new IllegalArgumentException("source.format " + format + ": the loader reads JSON Lines (jsonl); a JSON array goes through "
                    + "drishti.py lake build's Python path");
        }
        List<String> files = r.list(s, "files");
        if (files.isEmpty()) {
            throw new IllegalArgumentException("source.files: name the input (a file, a folder, a glob such as /data/pv/*.jsonl.gz or s3://bucket/pv/*.jsonl)");
        }
        String asOfText = r.text(s, "as-of", null);
        LocalDate asOf;
        try {
            asOf = asOfText == null ? null : LocalDate.parse(asOfText);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("source.as-of " + asOfText + ": give YYYY-MM-DD");
        }
        String date = r.text(s, "date", null);
        // a pack (as lake build --pack): its connector's domain, table, layout columns, vectors and flattened fields, its ingest key and date
        String packDir = r.text(s, "pack", null);
        PackLayout pack = null;
        if (packDir != null) {
            String kind = r.text(t, "kind", null);
            if (kind == null) {
                throw new IllegalArgumentException("source.pack needs target.kind (the kind whose layout the pack gives)");
            }
            try {
                pack = PackLayout.read(Path.of(packDir), kind, r.text(s, "connector", null));
            } catch (IOException e) {
                throw new IllegalArgumentException("source.pack " + packDir + ": " + e.getMessage());
            }
            if (date == null && asOf == null) {
                date = pack.date();
            }
        }
        if (asOf == null && date == null) {
            throw new IllegalArgumentException("source.date: name the business-date field (or give source.as-of YYYY-MM-DD for every row)");
        }
        List<String> vectors = new ArrayList<>(r.list(s, "vectors"));
        vectors.addAll(r.list(s, "vector"));
        List<String> promote = r.list(s, "promote");
        Map<String, String> flatten = new java.util.LinkedHashMap<>();
        String id = r.text(s, "id", null);
        if (pack != null) {
            promote = s.has("promote") ? promote : pack.promote();
            vectors = s.has("vectors") || s.has("vector") ? vectors : pack.vectors();
            flatten.putAll(s.has("flatten") ? Map.of() : pack.flatten());
            id = id != null ? id : pack.key();
        }
        flatten.putAll(r.map(s.path("flatten"), "source.flatten"));
        Map<String, String> sourceStorage = storage(r, s.path("storage-options"), "source.storage-options");
        Source src = new Source(files, r.bool(s, "recursive", false), sourceStorage, id == null ? "id" : id, date, asOf, promote, vectors, flatten,
                r.list(s, "partition-by"));

        String tf = r.text(t, "format", "delta").toLowerCase(Locale.ROOT);
        if (!tf.equals("delta") && !tf.equals("iceberg")) {
            throw new IllegalArgumentException("target.format " + tf + ": delta or iceberg");
        }
        String mode = r.text(t, "mode", "replace-date");
        if (!mode.equals("replace-date") && !mode.equals("overwrite-dates")) {
            throw new IllegalArgumentException("target.mode " + mode + ": replace-date (each business date of the input replaced by one commit; "
                    + "overwrite-dates is the same) is the loader's only mode; lake build's Python path has overwrite-partitions and append");
        }
        if (tf.equals("iceberg") && !src.partitionBy().isEmpty()) {
            throw new IllegalArgumentException("source.partition-by is Delta's: an Iceberg table of the layout is partitioned by business date alone "
                    + "(keep the fields as promote: columns)");
        }
        Map<String, String> targetStorage = t.has("storage-options") ? storage(r, t.path("storage-options"), "target.storage-options") : sourceStorage;
        Map<String, String> iceberg = r.map(t.path("iceberg"), "target.iceberg");
        iceberg.keySet().stream().filter(k -> SECRETS.contains(k) && !isReference(t.path("iceberg").path(k).asText())).findFirst().ifPresent(k -> {
            throw new IllegalArgumentException("target.iceberg." + k + ": a secret is never written in the file; give ${AN_ENVIRONMENT_VARIABLE}");
        });
        List<String> sortBy = l.has("sort-by") || pack == null ? r.list(l, "sort-by") : pack.sortBy();      // the pack's layout.<kind>.sort-by when not set
        Layout layout = new Layout(sortBy, mb(r, l, "file-size-mb", 256), l.has("row-group-mb") && !r.text(l, "row-group-mb", "auto").equals("auto")
                ? mb(r, l, "row-group-mb", 1) : 0, r.text(l, "compression", "auto").toLowerCase(Locale.ROOT),
                r.text(l, "vector-compression", "zstd").toLowerCase(Locale.ROOT), r.bool(l, "byte-stream-split", true), r.bool(l, "shard-aligned", true));
        String domain = r.text(t, "domain", pack == null ? null : pack.domain());
        String table = r.text(t, "table", pack == null ? null : pack.table());
        Target tgt = new Target(tf, r.text(t, "path", null), r.text(t, "lake", null), domain, r.text(t, "kind", null), table, targetStorage, r.map(t.path("properties"), "target.properties"), iceberg, layout);
        if (tgt.path() == null && (tgt.lake() == null || tgt.domain() == null || tgt.kind() == null && tgt.table() == null)) {
            throw new IllegalArgumentException("target: give path (the table) or lake, domain and kind (the table <lake>/<domain>/<kind>)");
        }
        int cores = Runtime.getRuntime().availableProcessors();
        Parallel par = new Parallel(r.threads(p, "readers", cores), r.threads(p, "parsers", cores), r.threads(p, "writers", Math.min(8, cores)),
                mb(r, p, "split-mb", 256));
        Memory memory = memory(r, mem, p, Runtime.getRuntime().maxMemory());
        Rejects rej = new Rejects(r.text(rj, "file", null), rj.has("max-pct") ? rj.get("max-pct").asDouble() : 100.0, rj.path("keep").asInt(50));
        Report rep = new Report(r.text(rp, "summary", null), r.text(rp, "manifest", null), r.text(rp, "progress", null), r.bool(rp, "run-log", true),
                r.bool(rp, "dry-run", false));
        return new LoadConfig(src, tgt, par, memory, rej, rep, tree.path("lock").asBoolean(true));
    }

    /** The budget and its shares; an explicit queue or sort buffer must fit in it with room for the writers. */
    static Memory memory(Reader r, JsonNode mem, JsonNode par, long heap) {
        boolean given = mem.has("budget-mb") && !r.text(mem, "budget-mb", "auto").equals("auto");
        long budget = given ? mb(r, mem, "budget-mb", 1) : heap / 2;
        long queue = par.has("queue-mb") ? mb(r, par, "queue-mb", 1) : budget * 15 / 100;
        long sort = mem.has("sort-buffer-mb") && !r.text(mem, "sort-buffer-mb", "auto").equals("auto") ? mb(r, mem, "sort-buffer-mb", 1) : budget * 55 / 100;
        long write = budget - queue - sort;
        if (write < budget / 10) {
            throw new IllegalArgumentException("parallel.queue-mb and memory.sort-buffer-mb leave the writers less than a tenth of memory.budget-mb ("
                    + budget / 1048576 + " MB): lower them or raise the budget");
        }
        if (given && budget > heap * 9 / 10) {
            throw new IllegalArgumentException("memory.budget-mb " + budget / 1048576 + " is more than nine tenths of the heap (" + heap / 1048576
                    + " MB): raise -Xmx or lower the budget");
        }
        return new Memory(budget, queue, sort, write, r.text(mem, "spill-dir", null), r.bool(mem, "check-spill-space", true));
    }

    /** The table's location: {@code path}, else {@code <lake>/<domain>/<table or kind>}. */
    String tablePath() {
        if (target.path() != null) {
            return trim(target.path());
        }
        return trim(target.lake()) + "/" + target.domain() + "/" + (target.table() != null ? target.table() : target.kind());
    }

    /** The lake root (for the run log and the lock): {@code lake}, else the table's grandparent. */
    String lakeRoot() {
        if (target.lake() != null) {
            return trim(target.lake());
        }
        String p = tablePath();
        int a = p.lastIndexOf('/');
        int b = a > 0 ? p.lastIndexOf('/', a - 1) : -1;
        return b > 0 ? p.substring(0, b) : p;
    }

    /** The table's domain and name ({@code <domain>/<table>}). */
    String[] domainAndTable() {
        String p = tablePath();
        int a = p.lastIndexOf('/');
        int b = a > 0 ? p.lastIndexOf('/', a - 1) : -1;
        String table = p.substring(a + 1);
        String domain = target.domain() != null ? target.domain() : b >= 0 ? p.substring(b + 1, a) : "";
        return new String[] {domain, table};
    }

    private static String trim(String p) {
        String s = p.replace('\\', '/');
        return s.length() > 1 && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static boolean isReference(String v) {
        return v.startsWith("${") && v.endsWith("}");
    }

    private static void check(String section, JsonNode n) {
        if (n.isMissingNode() || n.isNull()) {
            return;
        }
        if (!n.isObject()) {
            throw new IllegalArgumentException((section.isEmpty() ? "the file" : section) + ": a mapping of keys");
        }
        Set<String> known = KEYS.get(section);
        n.fieldNames().forEachRemaining(k -> {
            if (!known.contains(k)) {
                throw new IllegalArgumentException((section.isEmpty() ? "" : section + ".") + k + " is not a key of the loader (" + String.join(", ",
                        known.stream().sorted().toList()) + "); docs/guides/TOOLS.md section 4.13 lists them");
            }
        });
    }

    /** S3 options: {@code region}, {@code endpoint}, {@code path-style}, {@code read-block-kb} (and {@code tls.*}, {@code proxy.*}) as {@code s3.*}. */
    private static Map<String, String> storage(Reader r, JsonNode n, String where) {
        Map<String, String> raw = r.map(n, where);
        Map<String, String> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            if (SECRETS.contains(k)) {
                throw new IllegalArgumentException(where + "." + k + ": keys are never put in the file; the AWS default chain gives them "
                        + "(AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY, a profile, an instance role)");
            }
            out.put(k.startsWith("s3.") || k.startsWith("tls.") || k.startsWith("proxy.") ? k : "s3." + k, v);
        });
        return out;
    }

    static long mb(Reader r, JsonNode n, String key, double dflt) {
        JsonNode v = n.get(key);
        double mb = v == null || v.isNull() ? dflt : v.isNumber() ? v.asDouble() : Double.parseDouble(r.expand(v.asText()));
        if (mb <= 0) {
            throw new IllegalArgumentException(key + " must be more than 0");
        }
        return (long) (mb * 1024 * 1024);
    }

    /** Reads values with {@code ${VAR}} expanded. */
    record Reader(Function<String, String> env) {

        String expand(String s) {
            Matcher m = ENV.matcher(s);
            StringBuilder out = new StringBuilder();
            while (m.find()) {
                String v = env.apply(m.group(1));
                if (v == null) {
                    v = m.group(2);
                }
                if (v == null) {
                    throw new IllegalArgumentException("${" + m.group(1) + "} is not set in the environment");
                }
                m.appendReplacement(out, Matcher.quoteReplacement(v));
            }
            m.appendTail(out);
            return out.toString();
        }

        String text(JsonNode n, String key, String dflt) {
            JsonNode v = n.get(key);
            if (v == null || v.isNull()) {
                return dflt;
            }
            String s = expand(v.asText()).trim();
            return s.isEmpty() ? dflt : s;
        }

        boolean bool(JsonNode n, String key, boolean dflt) {
            JsonNode v = n.get(key);
            return v == null || v.isNull() ? dflt : v.isBoolean() ? v.asBoolean() : Boolean.parseBoolean(expand(v.asText()).trim());
        }

        /** A list, or one comma-separated text. */
        List<String> list(JsonNode n, String key) {
            JsonNode v = n.get(key);
            List<String> out = new ArrayList<>();
            if (v == null || v.isNull()) {
                return out;
            }
            if (v.isArray()) {
                v.forEach(x -> {
                    String s = expand(x.asText()).trim();
                    if (!s.isEmpty()) {
                        out.add(s);
                    }
                });
                return out;
            }
            for (String s : expand(v.asText()).split(",")) {
                if (!s.isBlank()) {
                    out.add(s.trim());
                }
            }
            return out;
        }

        Map<String, String> map(JsonNode n, String where) {
            Map<String, String> out = new LinkedHashMap<>();
            if (n.isMissingNode() || n.isNull()) {
                return out;
            }
            if (!n.isObject()) {
                throw new IllegalArgumentException(where + ": a mapping of keys");
            }
            n.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().isNull() ? "" : expand(e.getValue().asText())));
            return out;
        }

        int threads(JsonNode n, String key, int auto) {
            String v = text(n, key, "auto");
            int t = v.equals("auto") ? auto : Integer.parseInt(v);
            if (t < 1) {
                throw new IllegalArgumentException("parallel." + key + " must be at least 1 (or auto)");
            }
            return t;
        }
    }
}
