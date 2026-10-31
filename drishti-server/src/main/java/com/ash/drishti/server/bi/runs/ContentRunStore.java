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
import com.ash.drishti.identity.bi.BiDocs;
import com.ash.drishti.identity.content.ContentStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The result store of BI runs in the content store (docs/architecture/BI_DESIGN.md › BI runs: every BI query as a bounded, visible job (built in 2.6.7), section 8, DATA_ARCHITECTURE.md › The content store: user content every node reaches, section 9), so a run's result reads
 * from any node of a cluster:
 *
 * <pre>
 *  bi/runs/results/&lt;result key&gt;/answer.json     the answer with the signature it was computed for (as {@link RunStore})
 *  bi/runs/results/&lt;result key&gt;/meta.json       its owner, size and last open (the budget and the sweeper read only these)
 *  bi/runs/snapshots/&lt;name&gt;/&lt;version&gt;.json      promoted snapshots (a new version is a create: two nodes never take one number)
 * </pre>
 *
 * The rules are {@link RunStore}'s: a result is served only to a reader of the same security signature, the size limits, retention by last open,
 * the budgets. A write is one document put, so a reader never sees half a result.
 */
public final class ContentRunStore extends RunStore {

    static final String RESULTS = "bi/runs/results/";
    static final String SNAPSHOTS = "bi/runs/snapshots/";

    /** What the budget and the sweeper read of a result. */
    record Meta(String owner, long bytes, Instant opened) {}

    private final ContentStore store;
    private final RunsProperties.Store settings;
    private final RunsProperties.Retention retention;

    public ContentRunStore(RunsProperties props, ContentStore store) {
        super(props);
        this.store = store;
        this.settings = props.store();
        this.retention = props.retention();
    }

    private static String folder(String key) {
        if (key == null || !key.matches("[0-9a-f]{16,64}")) {
            throw new IllegalArgumentException("not a result key: " + key);
        }
        return RESULTS + key + "/";
    }

    @Override
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
        byte[] bytes = BiDocs.bytes(doc);
        if (bytes.length > settings.maxResultBytes()) {
            throw new DrishtiException(ErrorCode.RUN_TOO_LARGE, "the result is " + bytes.length + " bytes, over store.max-result-bytes ("
                    + settings.maxResultBytes() + "): aggregate it, or add a limit");
        }
        try {
            store.putLatest(folder(key) + "answer.json", bytes);
            store.putLatest(folder(key) + "meta.json", BiDocs.bytes(new Meta(owner == null ? "" : owner, bytes.length, Instant.now())));
        } catch (DrishtiException e) {
            if (e.errorCode() == ErrorCode.CONTENT_CONFLICT) {
                throw e;
            }
            throw new DrishtiException(ErrorCode.RUN_STORE_FULL, "the result could not be written to " + store.root() + ": " + e.getMessage());
        }
        return bytes.length;
    }

    @Override
    public boolean has(String key) {
        return key != null && store.exists(folder(key) + "answer.json");
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> read(String key, String signature) {
        if (key == null) {
            return Optional.empty();
        }
        Optional<ContentStore.Item> it = store.get(folder(key) + "answer.json");
        if (it.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> doc = BiDocs.parse(it.get().bytes(), Map.class);
        if (!String.valueOf(doc.get("signature")).equals(signature)) {
            return Optional.empty();
        }
        try {                                                                       // counts as its last open for retention
            Meta m = meta(key).orElse(new Meta(String.valueOf(doc.getOrDefault("owner", "")), it.get().bytes().length, null));
            store.putLatest(folder(key) + "meta.json", BiDocs.bytes(new Meta(m.owner(), m.bytes(), Instant.now())));
        } catch (RuntimeException ignored) {
            // retention counts from the write
        }
        return Optional.ofNullable((Map<String, Object>) doc.get("answer"));
    }

    private Optional<Meta> meta(String key) {
        return store.get(folder(key) + "meta.json").map(i -> BiDocs.parse(i.bytes(), Meta.class));
    }

    @Override
    public void delete(String key) {
        if (key == null) {
            return;
        }
        for (String k : store.list(folder(key))) {
            store.deleteLatest(k);
        }
    }

    // ---- snapshots (section 9.4) ----------------------------------------------------------------------------------------

    private static String snapshotDir(String name) {
        if (name == null || !name.matches("[a-z0-9][a-z0-9._-]{0,127}") || name.contains("..")) {
            throw new IllegalArgumentException("not a snapshot name: " + name);
        }
        return SNAPSHOTS + BiDocs.seg(name) + "/";
    }

    @Override
    public List<Integer> snapshotVersions(String name) {
        String p = snapshotDir(name);
        return store.list(p).stream().map(k -> k.substring(p.length())).filter(n -> n.matches("[0-9]{10}\\.json"))
                .map(n -> Integer.parseInt(n.substring(0, 10))).sorted().toList();
    }

    @Override
    public int promote(String name, Map<String, Object> doc, int keep) {
        List<Integer> versions = snapshotVersions(name);
        int next = versions.isEmpty() ? 1 : versions.get(versions.size() - 1) + 1;
        for (; ; next++) {
            Map<String, Object> out = new LinkedHashMap<>(doc);
            out.put("name", name);
            out.put("version", next);
            out.put("created", Instant.now().toString());
            try {
                store.put(snapshotDir(name) + BiDocs.num(next) + ".json", BiDocs.bytes(out), null);
                break;
            } catch (DrishtiException e) {
                if (e.errorCode() != ErrorCode.CONTENT_CONFLICT) {
                    throw new DrishtiException(ErrorCode.RUN_STORE_FULL, "the snapshot could not be written to " + store.root() + ": " + e.getMessage());
                }                                                                   // another node took this number: the next one
            }
        }
        List<Integer> now = snapshotVersions(name);
        for (int i = 0; i < now.size() - Math.max(1, keep); i++) {
            store.deleteLatest(snapshotDir(name) + BiDocs.num(now.get(i)) + ".json");
        }
        return next;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> snapshot(String name, String version) {
        List<Integer> versions = snapshotVersions(name);
        if (versions.isEmpty()) {
            return Optional.empty();
        }
        int v = version == null || version.isBlank() || "latest".equals(version) ? versions.get(versions.size() - 1) : Integer.parseInt(version);
        return store.get(snapshotDir(name) + BiDocs.num(v) + ".json").map(i -> (Map<String, Object>) BiDocs.parse(i.bytes(), Map.class));
    }

    @Override
    public void rewriteSnapshot(String name, int version, Map<String, Object> doc) {
        store.putLatest(snapshotDir(name) + BiDocs.num(version) + ".json", BiDocs.bytes(doc));
    }

    @Override
    public Map<String, Object> snapshotsUsage() {
        long bytes = 0;
        long versions = 0;
        for (String k : store.list(SNAPSHOTS)) {
            Optional<ContentStore.Item> it = store.get(k);
            if (it.isPresent()) {
                bytes += it.get().bytes().length;
                versions++;
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bytes", bytes);
        m.put("snapshots", versions);
        return m;
    }

    // ---- the budget and the sweeper ---------------------------------------------------------------------------------------

    private record Entry(String key, Meta meta) {}

    private List<Entry> entries() {
        List<Entry> out = new ArrayList<>();
        for (String k : store.list(RESULTS)) {
            if (k.endsWith("/meta.json")) {
                String key = k.substring(RESULTS.length(), k.length() - "/meta.json".length());
                store.get(k).ifPresent(i -> out.add(new Entry(key, BiDocs.parse(i.bytes(), Meta.class))));
            }
        }
        return out;
    }

    @Override
    public Map<String, Object> usage() {
        long bytes = 0;
        int count = 0;
        Instant oldest = null;
        Map<String, Long> byUser = new HashMap<>();
        for (Entry e : entries()) {
            bytes += e.meta().bytes();
            count++;
            Instant t = e.meta().opened();
            oldest = t != null && (oldest == null || t.isBefore(oldest)) ? t : oldest;
            byUser.merge(e.meta().owner() == null ? "" : e.meta().owner(), e.meta().bytes(), Long::sum);
        }
        List<Map<String, Object>> top = byUser.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(5)
                .map(x -> Map.<String, Object>of("user", x.getKey(), "bytes", x.getValue())).toList();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bytes", bytes);
        m.put("results", count);
        m.put("oldest", oldest == null ? null : oldest.toString());
        m.put("topUsers", top);
        return m;
    }

    @Override
    public Map<String, Object> usageOf(String user) {
        long bytes = 0;
        int count = 0;
        for (Entry e : entries()) {
            if (user.equals(e.meta().owner())) {
                bytes += e.meta().bytes();
                count++;
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bytes", bytes);
        m.put("results", count);
        return m;
    }

    @Override
    public List<String> sweep(Instant now) {
        List<String> removed = new ArrayList<>();
        Instant cutoff = now.minus(Duration.ofDays(retention.days()));
        long total = 0;
        List<Entry> kept = new ArrayList<>();
        for (Entry e : entries()) {
            if (e.meta().opened() != null && e.meta().opened().isBefore(cutoff)) {
                delete(e.key());
                removed.add(e.key());
            } else {
                kept.add(e);
                total += e.meta().bytes();
            }
        }
        kept.sort(Comparator.comparing((Entry e) -> e.meta().opened(), Comparator.nullsFirst(Comparator.naturalOrder())));
        Map<String, Long> perUser = new HashMap<>();
        kept.forEach(e -> perUser.merge(e.meta().owner() == null ? "" : e.meta().owner(), e.meta().bytes(), Long::sum));
        for (java.util.Iterator<Entry> it = kept.iterator(); it.hasNext();) {
            Entry e = it.next();
            String owner = e.meta().owner() == null ? "" : e.meta().owner();
            if (!owner.isEmpty() && perUser.get(owner) > settings.maxBytesPerUser()) {
                delete(e.key());
                removed.add(e.key());
                perUser.merge(owner, -e.meta().bytes(), Long::sum);
                total -= e.meta().bytes();
                it.remove();
            }
        }
        for (Entry e : kept) {
            if (total <= settings.maxBytes()) {
                break;
            }
            delete(e.key());
            removed.add(e.key());
            total -= e.meta().bytes();
        }
        return removed;
    }
}
