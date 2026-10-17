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
package com.ash.drishti.server.bi.transform;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Runs the Python Transform of a derived dataset (RUPAKA.md section 20.10) and keeps its result: one Parquet file per reader and version of the inputs, so the
 * Transform runs once for a reader until the inputs change or the result expires ({@code drishti.bi.transform.cache-ttl}). A run waits for a place
 * ({@code max-concurrent}), is limited in time, rows and bytes, and is checked against the columns the dataset declares. The columns of the last good result are
 * remembered, so a dataset that declares none still has a typed table ({@link com.ash.drishti.server.bi.gov.ImpactAnalyzer#learned}).
 */
public final class TransformService implements AutoCloseable {

    /** What to run, built only when the result is not already kept: the manifest, the input frames, the parameters. */
    public record Prepared(Map<String, Object> manifest, List<byte[]> frames, Map<String, Object> params) {}

    /** The result for a reader: the Parquet file, the columns typed from the frame, its rows, whether it was kept, and the time the run took (0 when kept). */
    public record Output(Path file, List<TransformRunner.Column> columns, long rows, boolean cached, long millis) {}

    private record Entry(Path file, List<TransformRunner.Column> columns, long rows, Instant expires, Instant made, String dataset) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    private final TransformProperties props;
    private final TransformRunner runner;
    private final Path root;
    private final Clock clock;
    private final Semaphore slots;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> learned = new ConcurrentHashMap<>();
    private final AtomicLong runs = new AtomicLong();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    public TransformService(TransformProperties props, TransformRunner runner, Path root, Clock clock) {
        this.props = props;
        this.runner = runner;
        this.root = root;
        this.clock = clock;
        this.slots = new Semaphore(props.maxConcurrent(), true);
    }

    public TransformProperties properties() {
        return props;
    }

    /**
     * The result for {@code cacheKey} (a reader, a dataset, the version of the inputs): kept, or made now.
     *
     * @param datasetKey the dataset's layer key (results of one dataset are kept together and dropped together)
     * @param expect the columns the dataset declares (name to type): each must be in the result; empty: any
     * @param prepare asks the inputs as the reader and builds the request; called only when the result is not kept
     */
    public Output ensure(String datasetKey, String datasetName, String cacheKey, Map<String, String> expect, Supplier<Prepared> prepare) {
        String id = hash(cacheKey);
        Entry e = fresh(id);
        if (e != null) {
            hits.incrementAndGet();
            return new Output(e.file(), e.columns(), e.rows(), true, 0);
        }
        ReentrantLock lock = locks.computeIfAbsent(id, k -> new ReentrantLock());
        lock.lock();
        try {
            e = fresh(id);
            if (e != null) {
                hits.incrementAndGet();
                return new Output(e.file(), e.columns(), e.rows(), true, 0);
            }
            Prepared in = prepare.get();
            boolean got = false;
            try {
                got = slots.tryAcquire(props.queueWait().toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException x) {
                Thread.currentThread().interrupt();
            }
            if (!got) {
                throw new DrishtiException(ErrorCode.PYTHON_BUSY, "all " + props.maxConcurrent() + " Transform runs are busy; try again in a moment (drishti.bi.transform.max-concurrent)");
            }
            try {
                Path dir = root.resolve(safe(datasetKey));
                Files.createDirectories(dir);
                Path out = dir.resolve(id + ".parquet");
                runs.incrementAndGet();
                TransformRunner.Result r;
                try {
                    r = runner.run(new TransformRunner.Request(in.manifest(), in.frames(), in.params(), out, props.maxRows(), props.maxBytes()));
                } catch (RuntimeException x) {
                    failures.incrementAndGet();
                    Files.deleteIfExists(out);
                    throw x;
                }
                check(r, expect, out);
                Instant now = clock.instant();
                cache.put(id, new Entry(r.file(), r.columns(), r.rows(), now.plus(props.cacheTtl()), now, datasetName));
                learn(datasetName, r.columns());
                evict(dir, datasetKey);
                return new Output(r.file(), r.columns(), r.rows(), false, r.millis());
            } finally {
                slots.release();
            }
        } catch (IOException x) {
            throw new DrishtiException(ErrorCode.PYTHON_BUSY, "the Transform's result could not be kept: " + x.getMessage());
        } finally {
            lock.unlock();
        }
    }

    private Entry fresh(String id) {
        Entry e = cache.get(id);
        if (e == null) {
            return null;
        }
        if (e.expires().isBefore(clock.instant()) || !Files.isRegularFile(e.file())) {
            cache.remove(id);
            return null;
        }
        return e;
    }

    private void check(TransformRunner.Result r, Map<String, String> expect, Path out) throws IOException {
        if (r.rows() > props.maxRows()) {
            Files.deleteIfExists(out);
            throw new DrishtiException(ErrorCode.PYTHON_LIMIT, "the Transform returned " + r.rows() + " rows; the limit is " + props.maxRows() + " (drishti.bi.transform.max-rows)");
        }
        long size = Files.size(r.file());
        if (size > props.maxBytes()) {
            Files.deleteIfExists(out);
            throw new DrishtiException(ErrorCode.PYTHON_LIMIT, "the Transform's result is " + size + " bytes; the limit is " + props.maxBytes() + " (drishti.bi.transform.max-bytes)");
        }
        List<String> have = r.columns().stream().map(TransformRunner.Column::name).toList();
        List<String> missing = expect.keySet().stream().filter(c -> !have.contains(c)).toList();
        if (!missing.isEmpty()) {
            Files.deleteIfExists(out);
            throw new DrishtiException(ErrorCode.TRANSFORM_FAILED, "the Transform's frame lacks the column(s) " + missing + " that the dataset declares (it returned " + have + ")");
        }
    }

    /** Keeps at most {@code cache-entries} results of a dataset: the oldest go, file and all. */
    private void evict(Path dir, String datasetKey) {
        List<Map.Entry<String, Entry>> mine = new ArrayList<>();
        for (Map.Entry<String, Entry> e : cache.entrySet()) {
            if (e.getValue().file().startsWith(dir)) {
                mine.add(e);
            }
        }
        mine.sort(java.util.Comparator.comparing(x -> x.getValue().made()));
        while (mine.size() > props.cacheEntries()) {
            Map.Entry<String, Entry> old = mine.remove(0);
            cache.remove(old.getKey());
            try {
                Files.deleteIfExists(old.getValue().file());
            } catch (IOException ignored) {
                // a leftover file is scratch
            }
        }
    }

    /** Drops every kept result of a dataset (its inputs were refreshed, or a person asked). Returns how many. */
    public int invalidate(String datasetKey) {
        Path dir = root.resolve(safe(datasetKey));
        int n = 0;
        for (Map.Entry<String, Entry> e : new ArrayList<>(cache.entrySet())) {
            if (e.getValue().file().startsWith(dir)) {
                cache.remove(e.getKey());
                n++;
                try {
                    Files.deleteIfExists(e.getValue().file());
                } catch (IOException ignored) {
                    // scratch
                }
            }
        }
        return n;
    }

    /** Drops every kept result of the dataset named {@code datasetName}, whichever model asked it (a person refreshed it). Returns how many. */
    public int invalidateNamed(String datasetName) {
        int n = 0;
        for (Map.Entry<String, Entry> e : new ArrayList<>(cache.entrySet())) {
            if (datasetName.equals(e.getValue().dataset()) && cache.remove(e.getKey(), e.getValue())) {
                n++;
                try {
                    Files.deleteIfExists(e.getValue().file());
                } catch (IOException ignored) {
                    // scratch
                }
            }
        }
        return n;
    }

    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", props.enabled());
        m.put("runs", runs.get());
        m.put("cacheHits", hits.get());
        m.put("failures", failures.get());
        m.put("kept", cache.size());
        m.put("maxConcurrent", props.maxConcurrent());
        return m;
    }

    // ---- the columns of the last good result -------------------------------------------------------------------------

    private void learn(String datasetName, List<TransformRunner.Column> cols) {
        Map<String, String> m = new LinkedHashMap<>();
        cols.forEach(c -> m.put(c.name(), c.type()));
        Map<String, String> before = learned.put(datasetName, m);
        if (!m.equals(before)) {
            try {
                Path f = root.resolve("schemas").resolve(safe(datasetName) + ".json");
                Files.createDirectories(f.getParent());
                Files.writeString(f, JSON.writeValueAsString(m), StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                // remembered in memory; only a restart forgets it
            }
        }
    }

    /** The columns (name to type) the last good result of the named dataset had, from memory or from the store; empty when it never ran. */
    public Map<String, String> learned(String datasetName) {
        Map<String, String> m = learned.get(datasetName);
        if (m != null) {
            return m;
        }
        Path f = root.resolve("schemas").resolve(safe(datasetName) + ".json");
        if (Files.isRegularFile(f)) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, String> read = JSON.readValue(f.toFile(), LinkedHashMap.class);
                learned.put(datasetName, read);
                return read;
            } catch (IOException ignored) {
                return Map.of();
            }
        }
        return Map.of();
    }

    private static String safe(String s) {
        return s.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))).substring(0, 24);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        cache.clear();
    }
}
