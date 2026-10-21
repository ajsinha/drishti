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
package com.ash.drishti.server.bi.runs;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.bi.engine.FileSwap;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The durable result store of BI runs (docs/architecture/BI_RUNS.md section 8), under the configurable data folder ({@code store.dir}, default
 * {@code <data>/bi-runs}), never a system temporary folder:
 *
 * <pre>
 *  results/&lt;first two hex digits&gt;/&lt;result key&gt;/answer.json   the answer as the API gives it (rows included), with the signature it was computed for
 *  snapshots/                                                promoted results (outside the budget)
 * </pre>
 *
 * A result is written beside its final name and moved into place with {@link FileSwap} (which waits out Windows' held-open files), so a reader
 * never sees half a result. A result is served only to a reader of the <b>same security signature</b> it was computed for (the key holds the
 * signature, and {@link #read} checks the stored one again): a result is never handed across signatures.
 *
 * <p>As built, the rows are stored as JSON inside {@code answer.json}, not as {@code result.parquet} (section 8.1): the engine's answer is
 * already in memory when the run ends, under {@code store.max-result-rows}.
 */
public final class RunStore {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ANSWER = "answer.json";
    static final String OWNER = "owner.txt";

    /** What is kept beside the rows. */
    public record Stored(String key, String signature, String runId, String owner, Instant created, long bytes, int rows) {}

    private final Path root;
    private final RunsProperties.Store settings;
    private final RunsProperties.Retention retention;

    public RunStore(RunsProperties props) {
        this.settings = props.store();
        this.retention = props.retention();
        this.root = settings.dir();
    }

    public Path root() {
        return root;
    }

    private Path folder(String key) {
        if (key == null || !key.matches("[0-9a-f]{16,64}")) {
            throw new IllegalArgumentException("not a result key: " + key);
        }
        return root.resolve("results").resolve(key.substring(0, 2)).resolve(key);
    }

    /** Writes the answer under its key, for {@code signature}; the bytes written. Past {@code store.max-result-rows} or bytes: DRS-9086. */
    public long write(String key, String signature, String runId, String owner, Map<String, Object> body, int rows) {
        if (rows > settings.maxResultRows()) {
            throw new DrishtiException(ErrorCode.RUN_TOO_LARGE, "the result has " + rows + " rows, over store.max-result-rows (" + settings.maxResultRows()
                    + "): aggregate it, or add a limit");
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("key", key);
        doc.put("signature", signature);
        doc.put("run", runId);
        doc.put("owner", owner);
        doc.put("created", Instant.now().toString());
        doc.put("rows", rows);
        doc.put("answer", body);
        try {
            byte[] bytes = JSON.writeValueAsBytes(doc);
            if (bytes.length > settings.maxResultBytes()) {
                throw new DrishtiException(ErrorCode.RUN_TOO_LARGE, "the result is " + bytes.length + " bytes, over store.max-result-bytes ("
                        + settings.maxResultBytes() + "): aggregate it, or add a limit");
            }
            Path dir = folder(key);
            Files.createDirectories(dir);
            Path file = dir.resolve(ANSWER);
            Path tmp = FileSwap.temp(file);
            Files.write(tmp, bytes);
            FileSwap.replace(tmp, file);
            Path who = dir.resolve(OWNER);                                          // whose budget it counts against (8.4), without reading the answer
            Path whoTmp = FileSwap.temp(who);
            Files.writeString(whoTmp, owner == null ? "" : owner, java.nio.charset.StandardCharsets.UTF_8);
            FileSwap.replace(whoTmp, who);
            return bytes.length;
        } catch (IOException e) {
            throw new DrishtiException(ErrorCode.RUN_STORE_FULL, "the result could not be written under " + root + ": " + e.getMessage());
        }
    }

    public boolean has(String key) {
        return key != null && Files.isRegularFile(folder(key).resolve(ANSWER));
    }

    /**
     * The stored answer for a reader of {@code signature}; empty when there is none, or when it was computed for another signature (never
     * served across signatures). Opening it counts as its last open for retention.
     */
    public Optional<Map<String, Object>> read(String key, String signature) {
        if (key == null || !has(key)) {
            return Optional.empty();
        }
        Path file = folder(key).resolve(ANSWER);
        try {
            Map<String, Object> doc = JSON.readValue(file.toFile(), new TypeReference<Map<String, Object>>() {});
            if (!String.valueOf(doc.get("signature")).equals(signature)) {
                return Optional.empty();
            }
            try {
                Files.setLastModifiedTime(file, FileTime.from(Instant.now()));
            } catch (IOException ignored) {
                // a read-only folder: retention counts from the write
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> answer = (Map<String, Object>) doc.get("answer");
            return Optional.ofNullable(answer);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the run result " + key + ": " + e.getMessage(), e);
        }
    }

    /** One page of an answer: its {@code rows} from {@code offset}, at most {@code limit}; the other keys unchanged, {@code page} added. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> page(Map<String, Object> answer, long offset, int limit) {
        Map<String, Object> out = new LinkedHashMap<>(answer);
        Object rows = answer.get("rows");
        if (rows instanceof List<?> l) {
            int from = (int) Math.min(l.size(), Math.max(0, offset));
            int to = (int) Math.min(l.size(), (long) from + Math.max(0, limit));
            out.put("rows", new ArrayList<>(((List<Object>) l).subList(from, to)));
            out.put("page", Map.of("offset", from, "limit", limit, "total", l.size()));
        }
        return out;
    }

    /** Removes a result now (a run's Delete result, or the sweeper). */
    public void delete(String key) {
        if (key == null) {
            return;
        }
        Path dir = folder(key);
        try (Stream<Path> files = Files.exists(dir) ? Files.walk(dir) : Stream.empty()) {
            for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not remove the run result " + key + ": " + e.getMessage(), e);
        }
    }

    /** The bytes and count of stored results, the oldest open, and the five people whose results take most room (Admin, 17.2). */
    /** The promoted snapshots under {@code snapshots/} (outside the budget, section 8.4): {@code {bytes, snapshots}}, a snapshot being one version folder. */
    public Map<String, Object> snapshotsUsage() {
        Path dir = root.resolve("snapshots");
        long bytes = 0;
        long versions = 0;
        if (java.nio.file.Files.isDirectory(dir)) {
            try (java.util.stream.Stream<Path> files = java.nio.file.Files.walk(dir)) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    if (java.nio.file.Files.isRegularFile(f)) {
                        bytes += java.nio.file.Files.size(f);
                        if (f.getFileName().toString().equals("answer.json")) {
                            versions++;
                        }
                    }
                }
            } catch (java.io.IOException e) {
                // a folder removed while it was read: counted as it was
            }
        }
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("bytes", bytes);
        m.put("snapshots", versions);
        return m;
    }

    public Map<String, Object> usage() {
        long bytes = 0;
        int count = 0;
        Instant oldest = null;
        Map<String, Long> byUser = new java.util.HashMap<>();
        for (Path f : answers()) {
            try {
                long b = Files.size(f);
                Instant t = Files.getLastModifiedTime(f).toInstant();
                bytes += b;
                count++;
                oldest = oldest == null || t.isBefore(oldest) ? t : oldest;
                byUser.merge(owner(f), b, Long::sum);
            } catch (IOException ignored) {
                // removed meanwhile
            }
        }
        List<Map<String, Object>> top = byUser.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(5)
                .map(e -> Map.<String, Object>of("user", e.getKey(), "bytes", e.getValue())).toList();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bytes", bytes);
        m.put("results", count);
        m.put("oldest", oldest == null ? null : oldest.toString());
        m.put("topUsers", top);
        return m;
    }

    /** The owner a result counts against: its {@code owner.txt}; "" when it has none (written before the budget per person). */
    private static String owner(Path answer) {
        try {
            Path who = answer.resolveSibling(OWNER);
            return Files.isRegularFile(who) ? Files.readString(who, java.nio.charset.StandardCharsets.UTF_8).strip() : "";
        } catch (IOException e) {
            return "";
        }
    }

    private List<Path> answers() {
        Path results = root.resolve("results");
        if (!Files.isDirectory(results)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(results, 3)) {
            return s.filter(p -> p.getFileName().toString().equals(ANSWER)).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * The sweeper (section 8.4): removes results not opened for {@code retention.days}, then the least recently opened ones while the store is
     * over {@code store.max-bytes}, and temporary files left by a write the process stopped in. The keys removed.
     */
    public List<String> sweep(Instant now) {
        List<String> removed = new ArrayList<>();
        record Entry(Path file, String key, Instant opened, long bytes, String owner) {}
        List<Entry> all = new ArrayList<>();
        for (Path f : answers()) {
            try {
                all.add(new Entry(f, f.getParent().getFileName().toString(), Files.getLastModifiedTime(f).toInstant(), Files.size(f), owner(f)));
            } catch (IOException ignored) {
                // removed meanwhile
            }
        }
        Instant cutoff = now.minus(Duration.ofDays(retention.days()));
        long total = 0;
        List<Entry> kept = new ArrayList<>();
        for (Entry e : all) {
            if (e.opened().isBefore(cutoff)) {
                delete(e.key());
                removed.add(e.key());
            } else {
                kept.add(e);
                total += e.bytes();
            }
        }
        kept.sort(Comparator.comparing(Entry::opened));
        Map<String, Long> perUser = new java.util.HashMap<>();                  // store.max-bytes-per-user: a person's oldest go first, nobody else's
        kept.forEach(e -> perUser.merge(e.owner(), e.bytes(), Long::sum));
        for (java.util.Iterator<Entry> it = kept.iterator(); it.hasNext();) {
            Entry e = it.next();
            if (!e.owner().isEmpty() && perUser.get(e.owner()) > settings.maxBytesPerUser()) {
                delete(e.key());
                removed.add(e.key());
                perUser.merge(e.owner(), -e.bytes(), Long::sum);
                total -= e.bytes();
                it.remove();
            }
        }
        for (Entry e : kept) {
            if (total <= settings.maxBytes()) {
                break;
            }
            delete(e.key());
            removed.add(e.key());
            total -= e.bytes();
        }
        Path results = root.resolve("results");
        if (Files.isDirectory(results)) {
            try (Stream<Path> s = Files.walk(results, 3)) {
                for (Path p : s.filter(x -> x.getFileName().toString().endsWith(".tmp")).toList()) {
                    if (Files.getLastModifiedTime(p).toInstant().isBefore(now.minus(Duration.ofHours(1)))) {
                        Files.deleteIfExists(p);
                    }
                }
            } catch (IOException ignored) {
                // the next sweep tries again
            }
        }
        return removed;
    }
}
