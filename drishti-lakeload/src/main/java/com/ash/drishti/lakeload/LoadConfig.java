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

import com.ash.drishti.lakeconfig.Lake;
import com.ash.drishti.lakeconfig.LakeTable;
import com.ash.drishti.lakeconfig.Lakes;
import com.ash.drishti.lakeconfig.PackEntry;
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
 * out and how many threads do the work. When {@code target.lake} names a lake file ({@code config/lakes/<lake>.yaml},
 * docs/guides/LAKE_GUIDE.md) the table is the lake file's entry for {@code target.kind}: its location, format, partitioning, promoted
 * columns, vectors, id, date, latest-by, sort, file layout and load mode all come from there, and a key of this file that says
 * otherwise is refused with both values named. A lake that has no entry for the kind is refused: a writer never creates a table its lake
 * file does not describe. {@code --set key.path=value} overrides any key. A value may name an environment variable
 * ({@code ${MINIO_ENDPOINT}}); keys and secrets are never taken from the file: S3 credentials come from the AWS default chain.
 */
record LoadConfig(Source source, Target target, Parallel parallel, Memory memory, Rejects rejects, Report report, boolean lock, Archive archive,
        Map<String, LoadConfig> datasets, String datasetsSummary) {

    /**
     * Archives in the input (DATA_GUIDE.md section 2.7): the name of a folderless archive's dataset ({@code source.dataset}), the caps
     * ({@code source.archive.max-mb}, {@code max-ratio}, {@code max-members}) and, with several datasets, whether every dataset's rows
     * are read and checked before the first commit ({@code validate: rows}, the default) or only the archives' structure
     * ({@code structure}).
     */
    record Archive(String dataset, Archives.Limits limits, String validate) {}

    /** The input files and what a row is. */
    record Source(List<String> files, boolean recursive, Map<String, String> storage, String id, String date, LocalDate asOf,
            List<String> promote, List<String> vectors, Map<String, String> flatten, List<String> partitionBy, String latestBy) {}

    /** The table and its layout. */
    record Target(String format, String path, String lake, String domain, String kind, String table, Map<String, String> storage,
            Map<String, String> properties, Map<String, String> iceberg, Layout layout, String loadMode, String batchId) {

        boolean isIceberg() {
            return format.equals("iceberg");
        }

        /** Whether the load adds its rows (load-mode append, each batch committed once) rather than replacing its dates. */
        boolean appends() {
            return LakeTable.APPEND.equals(loadMode);
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

    /** The timestamp field that decides which of an id's records on one date is kept (TOOLS.md section 4.13); blank: the last read. */
    static final String DEFAULT_LATEST_BY = "COMPUTED_AT";
    static final List<String> DEFAULT_SORT = List.of("LEGAL_ENTITY", "DESK", "id");
    private static final Pattern ENV = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?}");
    private static final Set<String> SECRETS = Set.of("access-key", "secret-key", "s3.access-key", "s3.secret-key", "session-token",
            "s3.session-token", "credential", "token");
    private static final Map<String, Set<String>> KEYS = Map.of(
            "", Set.of("source", "target", "parallel", "memory", "rejects", "report", "lock", "datasets"),
            "source", Set.of("files", "recursive", "storage-options", "format", "id", "date", "as-of", "promote", "vectors", "vector", "flatten",
                    "partition-by", "pack", "connector", "latest-by", "dataset", "archive"),
            "source.archive", Set.of("max-mb", "max-ratio", "max-members", "validate"),
            "target", Set.of("format", "path", "lake", "domain", "kind", "table", "storage-options", "mode", "properties", "iceberg", "layout",
                    "batch-id"),
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
        JsonNode ds = tree.get("datasets");
        if (ds != null && !ds.isNull()) {
            Map<String, LoadConfig> each = datasets(tree, ds, env);
            LoadConfig first = each.values().iterator().next();
            return new LoadConfig(first.source, first.target, first.parallel, first.memory, first.rejects, first.report, first.lock, first.archive,
                    each, new Reader(env).text(tree.path("report"), "summary", null));
        }
        return single(tree, env);
    }

    /**
     * {@code datasets:} (a list of names, or a mapping of name to its own {@code source:}, {@code target:} and {@code rejects:} keys over
     * the file's): each a table {@code <lake>/<domain>/<name>} (its {@code target.kind} the name unless it gives a kind or table), each
     * configuration read and checked now, before anything is read. A dataset's summary, manifest, progress and rejects files get its
     * name ({@code summary-trade.json}); the summary named is the run's, over every dataset.
     */
    private static Map<String, LoadConfig> datasets(ObjectNode tree, JsonNode ds, Function<String, String> env) {
        Map<String, JsonNode> named = new java.util.TreeMap<>(Archives.BY_NAME);
        if (ds.isArray()) {
            ds.forEach(n -> named.put(n.asText().trim(), JsonNodeFactory.instance.objectNode()));
        } else if (ds.isObject()) {
            ds.fields().forEachRemaining(e -> named.put(e.getKey(), e.getValue()));
        } else {
            for (String n : ds.asText().split(",")) {
                if (!n.isBlank()) {
                    named.put(n.trim(), JsonNodeFactory.instance.objectNode());
                }
            }
        }
        if (named.isEmpty()) {
            throw new IllegalArgumentException("datasets: name at least one (a list of names, or name: {target: ...} for each)");
        }
        if (tree.path("target").has("path")) {
            throw new IllegalArgumentException("datasets: each dataset is the table <lake>/<domain>/<name>: give target.lake and target.domain, "
                    + "not target.path");
        }
        Map<String, LoadConfig> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : named.entrySet()) {
            String name = e.getKey();
            if (!Archives.validName(name)) {
                throw new IllegalArgumentException("datasets." + name + ": a dataset is a table's folder (letters, digits, '.', '_' and '-')");
            }
            ObjectNode own = tree.deepCopy();
            own.remove("datasets");
            ObjectNode t = own.with("target");
            t.remove("kind");
            t.remove("table");
            JsonNode over = e.getValue();
            if (over != null && !over.isNull()) {
                if (!over.isObject()) {
                    throw new IllegalArgumentException("datasets." + name + ": a mapping (source:, target:, rejects:) or nothing");
                }
                over.fieldNames().forEachRemaining(k -> {
                    if (!Set.of("source", "target", "rejects").contains(k)) {
                        throw new IllegalArgumentException("datasets." + name + "." + k + ": a dataset sets source:, target: and rejects: only");
                    }
                });
                merge(own, (ObjectNode) over);
            }
            t = own.with("target");
            if (!t.has("kind") && !t.has("table")) {
                t.put("kind", name);
            }
            ObjectNode rp = own.with("report");
            for (String k : List.of("summary", "manifest", "progress")) {
                if (rp.hasNonNull(k)) {
                    rp.put(k, named(rp.get(k).asText(), name));
                }
            }
            if (own.path("rejects").hasNonNull("file")) {
                own.with("rejects").put("file", named(own.path("rejects").get("file").asText(), name));
            }
            try {
                out.put(name, single(own, env));
            } catch (IllegalArgumentException x) {
                throw new IllegalArgumentException("datasets." + name + ": " + x.getMessage());
            }
        }
        return out;
    }

    /** {@code /x/summary.json} -> {@code /x/summary-trade.json}. */
    static String named(String file, String dataset) {
        int slash = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
        int dot = file.lastIndexOf('.');
        return dot > slash ? file.substring(0, dot) + "-" + dataset + file.substring(dot) : file + "-" + dataset;
    }

    private static void merge(ObjectNode into, ObjectNode over) {
        over.fields().forEachRemaining(e -> {
            JsonNode mine = into.get(e.getKey());
            if (mine instanceof ObjectNode m && e.getValue() instanceof ObjectNode o) {
                merge(m, o);
            } else {
                into.set(e.getKey(), e.getValue().deepCopy());
            }
        });
    }

    /** This configuration as a dry run (read and checked, nothing written, no run log). */
    LoadConfig dryRun() {
        Report r = new Report(report.summary(), report.manifest(), report.progress(), false, true);
        return new LoadConfig(source, target, parallel, memory, rejects, r, lock, archive, datasets, datasetsSummary);
    }

    private static LoadConfig single(ObjectNode tree, Function<String, String> env) {
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
        check("source.archive", s.path("archive"));
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
        PackEntry pack = null;
        if (packDir != null) {
            String kind = r.text(t, "kind", null);
            if (kind == null) {
                throw new IllegalArgumentException("source.pack needs target.kind (the kind whose layout the pack gives)");
            }
            try {
                pack = PackEntry.read(Path.of(packDir), kind, r.text(s, "connector", null));
            } catch (IOException e) {
                throw new IllegalArgumentException("source.pack " + packDir + ": " + e.getMessage());
            }
            if (date == null && asOf == null) {
                date = pack.date();
            }
        }
        // a lake file's entry (target.lake naming config/lakes/<lake>.yaml): everything of the table from there, nothing contradicted here
        String lakeRef = r.text(t, "lake", null);
        Lake lake = null;
        LakeTable entry = null;
        if (lakeRef != null && isLakeName(lakeRef)) {
            lake = Lakes.read(Lakes.dir(), env).get(lakeRef);
            String kind = r.text(t, "kind", null);
            if (kind == null) {
                throw new IllegalArgumentException("target.kind: name the kind whose table of lake " + lakeRef + " this load writes");
            }
            entry = lake.require(r.text(t, "domain", null), kind, r.text(t, "table", null));
            date = agree(entry, "date", date, entry.date());
            if (s.has("partition-by")) {
                agree(entry, "partition-by", r.list(s, "partition-by"), entry.partitionBy());
            }
        } else if (lakeRef != null && !lakeRef.isBlank()) {
            warnOnce("target.lake " + lakeRef + " is a folder: name a lake of config/lakes/ instead (drishti.py lake migrate-config; "
                    + "docs/guides/LAKE_GUIDE.md); a folder is read this way for one more release");
        }
        if (asOf == null && date == null) {
            throw new IllegalArgumentException("source.date: name the business-date field (or give source.as-of YYYY-MM-DD for every row)");
        }
        List<String> vectors = new ArrayList<>(r.list(s, "vectors"));
        vectors.addAll(r.list(s, "vector"));
        List<String> promote = r.list(s, "promote");
        Map<String, String> flatten = new java.util.LinkedHashMap<>();
        String id = r.text(s, "id", null);
        String latestBy = r.text(s, "latest-by", null);
        if (entry != null) {
            promote = s.has("promote") ? agree(entry, "promote", promote, entry.promote()) : entry.promote();
            vectors = s.has("vectors") || s.has("vector") ? agree(entry, "vectors", vectors, entry.vectors()) : entry.vectors();
            flatten.putAll(entry.flatten());
            id = id != null ? agree(entry, "id", id, entry.id() == null ? id : entry.id()) : entry.id();
            latestBy = latestBy != null ? agree(entry, "latest-by", latestBy, entry.latestBy() == null ? latestBy : entry.latestBy()) : entry.latestBy();
        } else if (pack != null) {
            promote = s.has("promote") ? promote : pack.promote();
            vectors = s.has("vectors") || s.has("vector") ? vectors : pack.vectors();
            flatten.putAll(s.has("flatten") ? Map.of() : pack.flatten());
            id = id != null ? id : pack.key();
            latestBy = latestBy != null ? latestBy : pack.latestBy();
        }
        if (entry == null) {
            flatten.putAll(r.map(s.path("flatten"), "source.flatten"));
        } else if (s.has("flatten")) {
            agree(entry, "flatten", r.map(s.path("flatten"), "source.flatten"), entry.flatten());
        }
        Map<String, String> sourceStorage = storage(r, s.path("storage-options"), "source.storage-options");
        Source src = new Source(files, r.bool(s, "recursive", false), sourceStorage, id == null ? "id" : id, date, asOf, promote, vectors, flatten,
                entry != null ? entry.partitionBy() : r.list(s, "partition-by"), latestBy == null ? DEFAULT_LATEST_BY : latestBy.strip());

        String tf = r.text(t, "format", entry != null ? entry.format() : "delta").toLowerCase(Locale.ROOT);
        if (entry != null) {
            agree(entry, "format", tf, entry.format());
        }
        if (!tf.equals("delta") && !tf.equals("iceberg")) {
            throw new IllegalArgumentException("target.format " + tf + ": delta or iceberg");
        }
        String mode = r.text(t, "mode", entry != null && entry.appends() ? "append" : "replace-date");
        if (!mode.equals("replace-date") && !mode.equals("overwrite-dates") && !mode.equals("append")) {
            throw new IllegalArgumentException("target.mode " + mode + ": replace-date (each business date of the input replaced by one commit; "
                    + "overwrite-dates is the same) or append (the rows added; target.batch-id names the load so it is committed once)");
        }
        String loadMode = mode.equals("append") ? LakeTable.APPEND : LakeTable.REPLACE;
        if (entry != null) {
            agree(entry, "load-mode", loadMode, entry.loadMode());
        }
        String batchId = r.text(t, "batch-id", null);
        if (loadMode.equals(LakeTable.APPEND) && (batchId == null || batchId.isBlank())) {
            throw new IllegalArgumentException("target.batch-id: an append names its batch (the file's name, a run id), so a repeated or retried "
                    + "load is never appended twice");
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
        if (entry != null) {                                // the lake file's layout where this file gives none; a different value is refused
            ObjectNode lo = l instanceof ObjectNode o ? o : t instanceof ObjectNode to ? to.putObject("layout") : JsonNodeFactory.instance.objectNode();
            if (l.has("sort-by")) {
                agree(entry, "sort-by", r.list(l, "sort-by"), entry.sortBy());
            } else if (!entry.sortBy().isEmpty()) {
                entry.sortBy().forEach(x -> lo.withArray("sort-by").add(x));
            }
            for (String k : List.of("file-size-mb", "row-group-mb", "compression", "vector-compression")) {
                String mine = entry.layout().get(k);
                if (mine != null && l.has(k)) {
                    agree(entry, k, r.text(l, k, null), mine);
                } else if (mine != null) {
                    lo.put(k, mine);
                }
            }
            l = lo;
        }
        List<String> sortBy = l.has("sort-by") || pack == null ? r.list(l, "sort-by") : pack.sortBy();      // the pack's layout.<kind>.sort-by when not set
        Layout layout = new Layout(sortBy, mb(r, l, "file-size-mb", 256), l.has("row-group-mb") && !r.text(l, "row-group-mb", "auto").equals("auto")
                ? mb(r, l, "row-group-mb", 1) : 0, r.text(l, "compression", "auto").toLowerCase(Locale.ROOT),
                r.text(l, "vector-compression", "zstd").toLowerCase(Locale.ROOT), r.bool(l, "byte-stream-split", true), r.bool(l, "shard-aligned", true));
        String domain = r.text(t, "domain", entry != null ? entry.domain() : pack == null ? null : pack.domain());
        String table = r.text(t, "table", entry != null ? entry.folder() : pack == null ? null : pack.table());
        String path = r.text(t, "path", null);
        Map<String, String> props = r.map(t.path("properties"), "target.properties");
        if (entry != null) {
            if (path != null) {
                agree(entry, "path", path, lake.location(entry));
            }
            path = lake.location(entry);
            Map<String, String> lakeStorage = new LinkedHashMap<>();
            lake.storage().forEach((k, v) -> lakeStorage.put(k.startsWith("s3.") || k.startsWith("tls.") || k.startsWith("proxy.") ? k : "s3." + k, v));
            if (!t.has("storage-options")) {
                targetStorage = lakeStorage;
            }
            Map<String, String> merged = new LinkedHashMap<>(entry.properties());
            merged.putAll(props);
            props = merged;
            if (tf.equals("iceberg") && !lake.catalog().isEmpty()) {
                Map<String, String> cat = new LinkedHashMap<>(lake.catalog());
                cat.putAll(iceberg);
                iceberg = cat;
            }
        }
        Target tgt = new Target(tf, path, r.text(t, "lake", null), domain, r.text(t, "kind", null), table, targetStorage, props, iceberg, layout,
                loadMode, batchId);
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
        return new LoadConfig(src, tgt, par, memory, rej, rep, tree.path("lock").asBoolean(true), archive(r, s), Map.of(), null);
    }

    /** Whether {@code target.lake} names a lake of config/lakes/ (a plain name) rather than a folder or a URL (the old form). */
    static boolean isLakeName(String ref) {
        if (!ref.matches("[a-z0-9][a-z0-9_-]*")) {
            return false;                                   // ./data/delta, /lake, s3://bucket/delta: a folder, the old form
        }
        return Lakes.dir().resolve(ref + ".yaml").toFile().isFile() || !new java.io.File(ref).isDirectory();
    }

    /** A value of the load file that the lake file also gives: they must be the same, else the load is refused naming both. */
    static <T> T agree(LakeTable entry, String key, T mine, T lakes) {
        if (mine != null && lakes != null && !mine.equals(lakes)) {
            throw new IllegalArgumentException(key + " " + mine + " differs from " + lakes + ", the " + key + " of table " + entry.name()
                    + " in its lake file: the lake file decides (docs/guides/LAKE_GUIDE.md); remove " + key + " from the load or correct the lake file");
        }
        return mine != null ? mine : lakes;
    }

    private static final Set<String> WARNED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** One warning per message and process: an old form still read (decision L4). */
    static void warnOnce(String message) {
        if (WARNED.add(message)) {
            org.slf4j.LoggerFactory.getLogger(LoadConfig.class).warn(message);
        }
    }

    /** {@code source.dataset} and {@code source.archive.*}: the caps' defaults from DRISHTI_ARCHIVE_MAX_MB, _MAX_RATIO, _MAX_MEMBERS. */
    private static Archive archive(Reader r, JsonNode s) {
        JsonNode a = s.path("archive");
        Archives.Limits d;
        try {
            d = Archives.Limits.defaults(r.env());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("DRISHTI_ARCHIVE_MAX_MB, _MAX_RATIO and _MAX_MEMBERS are numbers");
        }
        long maxBytes = a.has("max-mb") ? mb(r, a, "max-mb", 1) : d.maxBytes();
        double ratio = a.has("max-ratio") ? Double.parseDouble(r.text(a, "max-ratio", "0")) : d.maxRatio();
        int members = a.has("max-members") ? Integer.parseInt(r.text(a, "max-members", "0")) : d.maxMembers();
        if (ratio <= 0 || members <= 0 || maxBytes <= 0) {
            throw new IllegalArgumentException("source.archive: max-mb, max-ratio and max-members must be more than 0");
        }
        String validate = r.text(a, "validate", "rows").toLowerCase(Locale.ROOT);
        if (!validate.equals("rows") && !validate.equals("structure")) {
            throw new IllegalArgumentException("source.archive.validate " + validate + ": rows (every dataset read and checked before the first "
                    + "commit) or structure (the archives' listing only)");
        }
        String dataset = r.text(s, "dataset", null);
        if (dataset != null && !Archives.validName(dataset)) {
            throw new IllegalArgumentException("source.dataset " + dataset + ": a table's folder name (letters, digits, '.', '_' and '-')");
        }
        return new Archive(dataset, new Archives.Limits(maxBytes, ratio, members), validate);
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
        if (target.lake() != null && target.path() == null) {           // a lake by name has its table's path: its grandparent below
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
