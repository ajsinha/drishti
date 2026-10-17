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
package com.ash.drishti.server.loads;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.packs.Pack;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Loads a whole data file into the File connector of a pack, from the browser (Admin &rarr; Packs &rarr; a pack &rarr; Load data): a JSON array
 * or JSON Lines, streamed (one document in memory at a time), each document written as the connector's row
 * ({@code {domain, kind, id, date, doc, columns}}) under {@code <root>/<domain>/<business date>/<kind>.jsonl}. The business date is the pack's
 * date field ({@code ingest.<kind>.date}), or, for a snapshot kind ({@code ingest.<kind>.snapshot: true}), the day asked for (default today).
 * A day's file is written beside itself and moved into place, so a running server never reads half a day; a day loaded again is replaced.
 * Every row is checked against the pack's Sutras for the kind: a row whose type (the value the Sutras split by) has no Sutra, and the fields
 * that rows of a type fill but no Sutra shows, are counted and reported. Then the load is announced, so the connector re-reads its folders
 * and the search, type-ahead and caches catch up. See docs/guides/DATA_LOADS.md.
 */
@Component
public class PackDataLoader {

    private static final Pattern WHERE = Pattern.compile("\\$\\.([A-Za-z_][A-Za-z0-9_]*)\\s*==\\s*'((?:[^'\\\\]|\\\\.)*)'");
    private static final Pattern PATH = Pattern.compile("[$@]\\.([A-Za-z_][A-Za-z0-9_]*)");
    private static final int OPEN_DAYS = 64;

    /** What a load did. */
    public record Result(String pack, String kind, String connector, long rows, long rejected, List<String> rejections, Map<String, Long> days,
            List<String> files, List<Map<String, Object>> checks, List<String> loads, List<Map<String, Object>> steps, long bytes, long millis) {}

    private final LoadService loads;
    private final Environment env;
    private final com.ash.drishti.packs.ConnectorFiles files;
    private final com.ash.drishti.engine.time.BusinessDates dates;
    private final ObjectMapper json = new ObjectMapper();
    private final ReentrantLock lock = new ReentrantLock();          // one load at a time: two would race on the same day's file

    public PackDataLoader(LoadService loads, Environment env, org.springframework.beans.factory.ObjectProvider<com.ash.drishti.packs.ConnectorFiles> files,
            com.ash.drishti.engine.time.BusinessDates dates) {
        this.dates = dates;
        this.loads = loads;
        this.env = env;
        this.files = files.getIfAvailable();
    }

    /** Where a kind of a pack is loaded: its File connector's folder, its id field and its date rule. */
    record Target(String connector, Path dir, String domain, String key, String dateField, boolean snapshot, List<String> columns) {}

    @SuppressWarnings("unchecked")
    Target target(Pack pack, String kind) {
        Map<String, Object> ing = pack.manifest().get("ingest") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        if (!(ing.get(kind) instanceof Map<?, ?> spec)) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "pack '" + pack.name() + "' says nothing about loading '" + kind
                    + "' (no ingest." + kind + " in its pack.yaml): only kinds with an id field and a business date (or snapshot: true) are loaded here");
        }
        String connector = pack.routes().get(kind);
        if (connector == null) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "pack '" + pack.name() + "' routes '" + kind + "' to no connector");
        }
        // the connector's own file (Admin -> Connectors) first; the server's properties for one an application file defines
        com.ash.drishti.packs.ConnectorFiles.Definition def = null;
        try {
            def = files == null ? null : files.read(connector);
        } catch (RuntimeException e) {
            def = null;
        }
        String base = "drishti.sources.connectors." + connector;
        Map<String, String> settings = def != null ? def.settings() : Map.of();
        java.util.function.Function<String, String> setting = k -> {
            String v = settings.containsKey(k) ? settings.get(k) : env.getProperty(base + ".settings." + k);
            return v == null ? null : env.resolvePlaceholders(v);
        };
        String plugin = def != null ? def.plugin() : env.getProperty(base + ".plugin", "");
        if (!"file".equals(plugin)) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "'" + kind + "' is served by the connector '" + connector + "' (" + (plugin == null || plugin.isBlank() ? "not defined on this server" : plugin)
                    + "); loading from the browser writes the File connector's folders only. Load another store with: drishti.py data ingest");
        }
        String root = setting.apply("root");
        if (root == null || root.isBlank()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the connector '" + connector + "' has no root folder (settings.root)");
        }
        String domain = java.util.Objects.requireNonNullElse(setting.apply("domain"), "");
        Path dir = Path.of(root).toAbsolutePath().normalize();
        dir = domain.isBlank() ? dir : dir.resolve(domain).normalize();
        String cols = java.util.Objects.requireNonNullElse(setting.apply("layout." + kind + ".columns"), "");
        List<String> columns = java.util.Arrays.stream(cols.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        String key = String.valueOf(spec.get("key") == null ? "id" : spec.get("key"));
        Object date = spec.get("date");
        return new Target(connector, dir, domain, key, date == null ? null : String.valueOf(date), Boolean.TRUE.equals(spec.get("snapshot")), columns);
    }

    /** Today's business date (the day a snapshot is stored under unless another is asked for). */
    public LocalDate businessDate() {
        return dates.current();
    }

    /** The kinds of a pack this page can load, with where they go: [{kind, connector, folder, key, date, snapshot}] (errors as text). */
    public List<Map<String, Object>> targets(Pack pack) {
        List<Map<String, Object>> out = new ArrayList<>();
        Object ing = pack.manifest().get("ingest");
        if (!(ing instanceof Map<?, ?> m)) {
            return out;
        }
        for (Object k : m.keySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", String.valueOf(k));
            try {
                Target t = target(pack, String.valueOf(k));
                row.put("connector", t.connector());
                row.put("folder", t.dir().toString());
                row.put("key", t.key());
                row.put("date", t.dateField());
                row.put("snapshot", t.snapshot());
            } catch (DrishtiException e) {
                row.put("problem", e.getMessage());
            }
            out.add(row);
        }
        return out;
    }

    /** The Sutras' view of the kind: the field they split by, the types that have a Sutra, whether a catch-all exists, every field they read. */
    record Coverage(String splitField, Set<String> types, boolean fallback, Set<String> shown) {}

    Coverage coverage(Pack pack, String kind) {
        Path dir = pack.resolve("sutras").resolve(kind);
        String split = null;
        Set<String> types = new LinkedHashSet<>();
        Set<String> shown = new LinkedHashSet<>();
        boolean fallback = false;
        if (Files.isDirectory(dir)) {
            try (var files = Files.list(dir)) {
                for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".sutra.yaml")).toList()) {
                    String text = Files.readString(f, StandardCharsets.UTF_8);
                    String match = text.lines().filter(l -> l.startsWith("match:")).findFirst().orElse("");
                    Matcher w = WHERE.matcher(match);
                    if (w.find()) {
                        split = w.group(1);
                        types.add(w.group(2).replace("\\'", "'"));
                    } else {
                        fallback = true;
                    }
                    Matcher p = PATH.matcher(text);
                    while (p.find()) {
                        shown.add(p.group(1));
                    }
                }
            } catch (IOException e) {
                // no Sutras read: nothing is checked against them
            }
        }
        return new Coverage(split, types, fallback || types.isEmpty(), shown);
    }

    /**
     * Streams {@code in} (a JSON array or JSON Lines) into the connector's folders and announces the load.
     *
     * @param day the business date of a snapshot kind (null: today); ignored for a kind with a date field
     */
    public Result load(String packName, String kindAsked, InputStream in, LocalDate day, String fileName, String user, long maxBytes) throws IOException {
        Pack pack = loads.pack(packName);
        String kind = kindAsked;
        if (kind == null || kind.isBlank()) {
            List<String> loadable = targets(pack).stream().filter(t -> !t.containsKey("problem")).map(t -> (String) t.get("kind")).toList();
            if (loadable.size() != 1) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "say which kind the file holds (kind=): pack '" + packName + "' loads " + loadable);
            }
            kind = loadable.get(0);
        }
        Target t = target(pack, kind);
        Coverage cov = coverage(pack, kind);
        LocalDate snapshotDay = day != null ? day : businessDate();
        if (!lock.tryLock()) {
            throw new DrishtiException(ErrorCode.CONNECTOR_CONFLICT, "another data load is running on this server: try again when it ends");
        }
        long t0 = System.nanoTime();
        Map<LocalDate, Path> tmp = new TreeMap<>();
        Map<LocalDate, BufferedWriter> open = new LinkedHashMap<>(OPEN_DAYS, 0.75f, true);
        Map<LocalDate, Long> perDay = new TreeMap<>();
        List<String> rejections = new ArrayList<>();
        long[] rejected = {0};
        long rows = 0;
        Map<String, Long> perType = new TreeMap<>();
        Map<String, Map<String, Long>> unseen = new TreeMap<>();          // type -> field filled but read by no Sutra -> rows
        Counting counted = new Counting(in, maxBytes);
        String batch = "upload-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        try {
            Files.createDirectories(t.dir());
            JsonParser p = new JsonFactory().createParser(counted);
            JsonToken tok = p.nextToken();
            boolean array = tok == JsonToken.START_ARRAY;
            if (array) {
                tok = p.nextToken();
            }
            while (tok != null && !(array && tok == JsonToken.END_ARRAY)) {
                JsonNode doc = json.readTree(p);
                tok = p.nextToken();
                if (!(doc instanceof ObjectNode o)) {
                    reject(rejected, rejections, "a value that is not a JSON object");
                    continue;
                }
                JsonNode idNode = at(o, t.key());
                if (idNode == null || idNode.isNull() || idNode.isContainerNode() || idNode.asText().isBlank()) {
                    reject(rejected, rejections, "no " + t.key() + " (the id)");
                    continue;
                }
                LocalDate d = snapshotDay;
                if (t.dateField() != null) {
                    JsonNode dn = at(o, t.dateField());
                    try {
                        d = LocalDate.parse(dn == null ? "" : dn.asText().length() >= 10 ? dn.asText().substring(0, 10) : dn.asText());
                    } catch (DateTimeParseException e) {
                        reject(rejected, rejections, idNode.asText() + ": no business date in " + t.dateField());
                        continue;
                    }
                }
                ObjectNode row = json.createObjectNode();
                row.put("domain", t.domain());
                row.put("kind", kind);
                row.put("id", idNode.asText());
                row.put("date", d.toString());
                row.set("doc", o);
                ObjectNode cols = row.putObject("columns");
                for (String c : t.columns()) {
                    JsonNode v = at(o, c);
                    if (v != null && v.isValueNode() && !v.isNull()) {
                        cols.set(c, v);
                    }
                }
                writer(t, kind, d, tmp, open).write(json.writeValueAsString(row));
                writer(t, kind, d, tmp, open).write('\n');
                perDay.merge(d, 1L, Long::sum);
                rows++;
                check(o, cov, perType, unseen);
            }
            for (BufferedWriter w : open.values()) {
                w.close();
            }
            open.clear();
            List<String> files = new ArrayList<>();
            for (Map.Entry<LocalDate, Path> e : tmp.entrySet()) {
                Path target = t.dir().resolve(e.getKey().toString()).resolve(kind + ".jsonl");
                Files.move(e.getValue(), target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                files.add(t.dir().relativize(target).toString());
            }
            tmp.clear();
            List<String> loadIds = new ArrayList<>();
            List<Map<String, Object>> steps = new ArrayList<>();
            for (Map.Entry<LocalDate, Long> e : perDay.entrySet()) {
                LoadService.Outcome o = loads.announce(packName, new LoadService.Announcement(kind, e.getKey().toString(), null, LoadRecord.READY,
                        e.getValue(), rejected[0], "upload" + (fileName == null ? "" : ": " + fileName), batch + "-" + e.getKey(), "loaded from the browser by " + user), user);
                loadIds.add(o.load().id());
                for (LoadRecord.Step st : o.load().steps() == null ? List.<LoadRecord.Step>of() : o.load().steps()) {
                    steps.add(Map.of("date", e.getKey().toString(), "name", st.name(), "status", st.status(), "detail", st.detail() == null ? "" : st.detail()));
                }
            }
            Map<String, Long> days = new LinkedHashMap<>();
            perDay.forEach((d, n) -> days.put(d.toString(), n));
            return new Result(packName, kind, t.connector(), rows, rejected[0], rejections, days, files, checks(cov, perType, unseen), loadIds, steps,
                    counted.count, (System.nanoTime() - t0) / 1_000_000);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the file is not a JSON array or JSON Lines past row " + (rows + rejected[0]) + ": "
                    + e.getOriginalMessage());
        } finally {
            for (BufferedWriter w : open.values()) {
                try {
                    w.close();
                } catch (IOException ignored) {
                    // being cleaned up
                }
            }
            for (Path p : tmp.values()) {
                Files.deleteIfExists(p);                    // a load that failed half-way leaves the old days as they were
            }
            lock.unlock();
        }
    }

    private BufferedWriter writer(Target t, String kind, LocalDate d, Map<LocalDate, Path> tmp, Map<LocalDate, BufferedWriter> open) throws IOException {
        BufferedWriter w = open.get(d);
        if (w != null) {
            return w;
        }
        if (open.size() >= OPEN_DAYS) {                          // many days in one file: close the least recently written
            var eldest = open.entrySet().iterator().next();
            eldest.getValue().close();
            open.remove(eldest.getKey());
        }
        Path dayDir = t.dir().resolve(d.toString()).normalize();
        if (!dayDir.startsWith(t.dir())) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a date that would write outside the connector's folder: " + d);
        }
        Files.createDirectories(dayDir);
        boolean first = !tmp.containsKey(d);
        Path f = tmp.computeIfAbsent(d, x -> dayDir.resolve(kind + ".jsonl.upload.tmp"));
        w = Files.newBufferedWriter(f, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE,
                first ? java.nio.file.StandardOpenOption.TRUNCATE_EXISTING : java.nio.file.StandardOpenOption.APPEND);
        open.put(d, w);
        return w;
    }

    private static void reject(long[] n, List<String> why, String reason) {
        n[0]++;
        if (why.size() < 10) {
            why.add(reason);
        }
    }

    /** The value at a dotted path, or null. */
    static JsonNode at(JsonNode doc, String path) {
        JsonNode cur = doc;
        for (String part : path.split("\\.")) {
            cur = cur == null ? null : cur.get(part);
        }
        return cur;
    }

    private static boolean blank(JsonNode v) {
        return v == null || v.isNull() || (v.isTextual() && v.asText().isBlank()) || (v.isContainerNode() && v.isEmpty());
    }

    private static void check(ObjectNode o, Coverage cov, Map<String, Long> perType, Map<String, Map<String, Long>> unseen) {
        String type = cov.splitField() == null ? "" : (blank(o.get(cov.splitField())) ? "" : o.get(cov.splitField()).asText());
        perType.merge(type, 1L, Long::sum);
        if (cov.shown().isEmpty()) {
            return;
        }
        var it = o.fields();
        while (it.hasNext()) {
            var e = it.next();
            if (!blank(e.getValue()) && !cov.shown().contains(e.getKey())) {
                unseen.computeIfAbsent(type, x -> new TreeMap<>()).merge(e.getKey(), 1L, Long::sum);
            }
        }
    }

    /** The mismatches between the rows and the Sutras, in words, worst first. */
    private static List<Map<String, Object>> checks(Coverage cov, Map<String, Long> perType, Map<String, Map<String, Long>> unseen) {
        List<Map<String, Object>> out = new ArrayList<>();
        perType.forEach((type, n) -> {
            if (cov.splitField() != null && !type.isEmpty() && !cov.types().contains(type)) {
                out.add(Map.of("level", cov.fallback() ? "warn" : "bad", "type", type, "rows", n,
                        "message", String.format("%,d %s rows have no Sutra of their own%s", n, type, cov.fallback() ? ": the catch-all shows them" : " and no catch-all: they open without a designed view")));
            } else if (cov.splitField() != null && type.isEmpty()) {
                out.add(Map.of("level", cov.fallback() ? "info" : "warn", "type", "", "rows", n,
                        "message", String.format("%,d rows have no %s%s", n, cov.splitField(), cov.fallback() ? ": the catch-all shows them" : " and there is no catch-all Sutra")));
            }
        });
        unseen.forEach((type, fields) -> {
            long most = fields.values().stream().mapToLong(Long::longValue).max().orElse(0);
            List<String> names = fields.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(6).map(Map.Entry::getKey).toList();
            out.add(Map.of("level", "info", "type", type, "rows", most, "fields", names,
                    "message", String.format("%,d %srows have fields no Sutra shows: %s%s", most, type.isEmpty() ? "" : type + " ", String.join(", ", names),
                            fields.size() > names.size() ? " and " + (fields.size() - names.size()) + " more" : "")));
        });
        out.sort((a, b) -> Long.compare((Long) b.get("rows"), (Long) a.get("rows")));
        return out;
    }

    /** The upload, counted as it is read: past the limit it stops with 413. */
    static final class Counting extends java.io.FilterInputStream {
        long count;
        private final long max;

        Counting(InputStream in, long max) {
            super(in);
            this.max = max;
        }

        private void add(long n) throws IOException {
            if (n > 0) {
                count += n;
                if (max > 0 && count > max) {
                    throw new DrishtiException(ErrorCode.PAYLOAD_TOO_LARGE, "the file is over " + (max >> 20) + " MB (drishti.loads.upload-max-mb)");
                }
            }
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            add(b < 0 ? 0 : 1);
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            add(n);
            return n;
        }
    }
}
