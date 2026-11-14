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
package com.ash.drishti.server.liveset;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * A set paged from the durable store (docs/guides/LIVE_RESULTS_GUIDE.md section 7, "Scale"): the snapshot stays in the lake (Delta, Iceberg or
 * files, through the kind's bindings). For an order (the key, else the chosen sort), a filter and an audience it keeps an <em>index</em>:
 * the keys of the records that hold the condition and the reader's scope, in order, with the sort column's values, read in one pass of
 * those columns (the store's columns where it keeps them, else its documents once). Never the records: a page fetches its rows whole by
 * id. The live side (the set's own map of changed rows and removals) is merged into each page by key: a changed record shadows the
 * store's, a removed one hides it, a new one takes its place in the order; totals are the index's count with the same corrections.
 * A few indexes are kept (the latest ones asked for); a new store version (a load) builds them again.
 */
public final class StorePages {

    /** The durable store of a kind as a set pages it. */
    public interface Store {

        /** The store's version of the kind now ({@code router.dataVersion}); an index is built again when it moves. Empty when not known. */
        String version();

        /**
         * Every record's id and these fields' values (a field not held gives null) to the sink, in one pass: the store's columns when it
         * keeps them, else its documents read once. False when the kind cannot be read whole.
         */
        boolean project(List<String> fields, Projection sink) throws Exception;

        /** Records whole, by entity id; one no longer held is left out. */
        Map<String, DataNode> fetch(Collection<String> ids) throws Exception;
    }

    /** One record of a projection. */
    @FunctionalInterface
    public interface Projection {
        void row(String id, Object[] values);
    }

    /** At most this many indexes are kept per set; the oldest asked for goes first. */
    static final int KEPT = 6;
    /** An index of a store that names no version is read again after this long. */
    static final long UNVERSIONED_MS = 60_000;

    private final Store store;
    private final String keyField;
    private final List<String> conditionFields;
    private final Predicate<DataNode> condition;
    private final Map<String, CompletableFuture<Index>> indexes = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<String> recent = new ConcurrentLinkedDeque<>();
    private volatile int lastCount = -1;

    StorePages(Store store, String keyField, List<String> conditionFields, Predicate<DataNode> condition) {
        this.store = store;
        this.keyField = keyField;
        this.conditionFields = conditionFields == null ? List.of() : List.copyOf(conditionFields);
        this.condition = condition;
    }

    /** One order of the store's records for one audience: keys in order, entity ids (null when they are the keys), sort values. */
    static final class Index {
        final String version;
        final long builtAt;
        final String[] keys;
        final String[] ids;
        final Object[] values;
        final String[] byKey;
        final int[] posByKey;
        final boolean descending;

        Index(String version, String[] keys, String[] ids, Object[] values, boolean descending) {
            this.version = version;
            this.builtAt = System.currentTimeMillis();
            this.keys = keys;
            this.ids = ids;
            this.values = values;
            this.descending = descending;
            if (values == null) {
                byKey = keys;                                 // in key order already
                posByKey = null;
            } else {
                Integer[] p = new Integer[keys.length];
                for (int i = 0; i < p.length; i++) {
                    p[i] = i;
                }
                Arrays.parallelSort(p, Comparator.comparing(i -> keys[i]));
                byKey = new String[keys.length];
                posByKey = new int[keys.length];
                for (int i = 0; i < p.length; i++) {
                    byKey[i] = keys[p[i]];
                    posByKey[i] = p[i];
                }
            }
        }

        int size() {
            return keys.length;
        }

        /** The record's position in the order, or -1 when the index does not hold it. */
        int position(String key) {
            int i = Arrays.binarySearch(byKey, key);
            return i < 0 ? -1 : posByKey == null ? i : posByKey[i];
        }

        Object value(int pos) {
            return values == null ? null : values[pos];
        }

        String id(int pos) {
            return ids == null ? keys[pos] : ids[pos];
        }
    }

    /** One record of the live side as a page merges it: its key, its sort value as the reader sees it, and its row. */
    record Live(String key, Object value, LiveSet.Row row) {}

    /** The order of a sort value and a key (values not there last, also when descending; then by key). */
    static int compare(Object va, String ka, Object vb, String kb, boolean sorted, boolean descending) {
        if (sorted) {
            int c = descending ? LiveSet.compareDescending(va, vb) : LiveSet.compareValues(va, vb);
            if (c != 0) {
                return c;
            }
        }
        return ka.compareTo(kb);
    }

    /** The count of the records the reader may see: the store's, less those the live side shadows or removed, plus the live side's. */
    int count(LiveReader reader, Map<String, LiveSet.Row> live, Set<String> removed, Predicate<LiveSet.Row> visible, boolean fresh) {
        Index idx = fresh ? null : kept(reader);
        if (idx == null) {
            idx = index(null, false, null, reader, List.of());
        }
        int n = idx.size();
        for (String k : live.keySet()) {
            if (idx.position(k) >= 0) {
                n--;
            }
        }
        for (String k : removed) {
            if (!live.containsKey(k) && idx.position(k) >= 0) {
                n--;
            }
        }
        for (LiveSet.Row r : live.values()) {
            if (visible.test(r)) {
                n++;
            }
        }
        return n;
    }

    /**
     * One record from the store by its key (an edit's version check, the panel's Raw JSON): its entity id from an index read already,
     * else the key itself; null when the store does not hold it.
     */
    LiveSet.Row lookup(String key, java.util.function.ToLongFunction<DataNode> versionOf) {
        String id = key;
        for (CompletableFuture<Index> f : indexes.values()) {
            if (f.isDone() && !f.isCompletedExceptionally()) {
                Index i = f.join();
                int pos = i.position(key);
                if (pos >= 0) {
                    id = i.id(pos);
                    break;
                }
            }
        }
        try {
            DataNode d = store.fetch(List.of(id)).get(id);
            return d == null ? null : new LiveSet.Row(key, versionOf.applyAsLong(d), 0, d, null);
        } catch (Exception e) {
            throw new DrishtiException(ErrorCode.SOURCE_FAILED, "the record " + key + " could not be read from the store: " + e.getMessage());
        }
    }

    /** The key order's unfiltered index already read for the reader's audience (any store version), or null. */
    private Index kept(LiveReader reader) {
        String tail = "\u001f\u001ffalse\u001f\u001f" + reader.audience();
        for (Map.Entry<String, CompletableFuture<Index>> e : indexes.entrySet()) {
            CompletableFuture<Index> f = e.getValue();
            if (e.getKey().endsWith(tail) && f.isDone() && !f.isCompletedExceptionally()) {
                return f.join();
            }
        }
        return null;
    }

    /** The store's count the last index read (Health). */
    int lastCount() {
        return lastCount;
    }

    int indexes() {
        return indexes.size();
    }

    /**
     * One page of the merged order: the store's records the index holds, less those the live side shadows or removed, merged with the
     * live rows the reader sees ({@code live}, any order), from {@code offset}, at most {@code limit}. The store's rows are fetched whole.
     */
    LiveSet.Page page(int offset, int limit, String sortField, boolean descending, String filter, LiveReader reader, List<String> shown,
            Map<String, LiveSet.Row> liveRows, Set<String> removed, List<Live> live, long seq, java.util.function.ToLongFunction<DataNode> versionOf) {
        Index idx = index(sortField, descending, filter, reader, shown);
        boolean sorted = idx.values != null;
        List<Live> l = new ArrayList<>(live);
        l.sort((a, b) -> compare(a.value(), a.key(), b.value(), b.key(), sorted, descending));
        int[] shadow = shadowed(idx, liveRows.keySet(), removed);
        int n = idx.size();
        int total = n - shadow.length + l.size();
        int base = Math.max(0, offset - l.size());
        int q = Math.min(base, n) - 1;
        while (q >= 0 && Arrays.binarySearch(shadow, q) >= 0) {
            q--;                                              // the last record of the store before the page's reach that is not shadowed
        }
        int nsBefore = q < 0 ? 0 : (q + 1) - countUpTo(shadow, q);
        int j = q < 0 ? 0 : liveBefore(l, idx.value(q), idx.keys[q], sorted, descending);
        long m = (long) nsBefore + j;
        int p = q + 1;
        List<Object> picked = new ArrayList<>();              // an Integer: a store position; a Live: a live row
        int want = Math.max(1, limit);
        while (picked.size() < want) {
            while (p < n && Arrays.binarySearch(shadow, p) >= 0) {
                p++;
            }
            boolean storeLeft = p < n;
            boolean liveLeft = j < l.size();
            if (!storeLeft && !liveLeft) {
                break;
            }
            boolean fromStore = storeLeft && (!liveLeft || compare(idx.value(p), idx.keys[p], l.get(j).value(), l.get(j).key(), sorted, descending) < 0);
            Object next = fromStore ? (Object) p++ : l.get(j++);
            if (m++ >= offset) {
                picked.add(next);
            }
        }
        List<String> ids = new ArrayList<>();
        for (Object o : picked) {
            if (o instanceof Integer pos) {
                ids.add(idx.id(pos));
            }
        }
        Map<String, DataNode> docs;
        try {
            docs = ids.isEmpty() ? Map.of() : store.fetch(ids);
        } catch (Exception e) {
            throw new DrishtiException(ErrorCode.SOURCE_FAILED, "a page of the set could not be read from the store: " + e.getMessage());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : picked) {
            if (o instanceof Integer pos) {
                DataNode d = docs.get(idx.id(pos));
                if (d != null) {                              // gone from the store since the index was read: left out
                    out.add(LiveSet.shown(new LiveSet.Row(idx.keys[pos], versionOf.applyAsLong(d), 0, d, null), reader.redact()));
                }
            } else if (o instanceof Live lv) {
                out.add(LiveSet.shown(lv.row(), reader.redact()));
            }
        }
        return new LiveSet.Page(out, total, seq);
    }

    /** The positions of the index's records the live side shadows (changed) or removed, ascending. */
    private static int[] shadowed(Index idx, Set<String> changed, Set<String> removed) {
        Set<String> all = new LinkedHashSet<>(changed);
        all.addAll(removed);
        int[] out = new int[all.size()];
        int k = 0;
        for (String key : all) {
            int pos = idx.position(key);
            if (pos >= 0) {
                out[k++] = pos;
            }
        }
        out = Arrays.copyOf(out, k);
        Arrays.sort(out);
        return out;
    }

    /** How many of the (ascending) positions are at or before {@code q}. */
    private static int countUpTo(int[] sorted, int q) {
        int i = Arrays.binarySearch(sorted, q);
        return i >= 0 ? i + 1 : -i - 1;
    }

    /** How many live rows (sorted) come before the store's record of this value and key. */
    private static int liveBefore(List<Live> l, Object value, String key, boolean sorted, boolean descending) {
        int lo = 0;
        int hi = l.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (compare(l.get(mid).value(), l.get(mid).key(), value, key, sorted, descending) < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** The index of an order, filter and audience: kept, or read now from the store. */
    Index index(String sortField, boolean descending, String filter, LiveReader reader, List<String> shown) {
        String version = store.version();
        String f = filter == null || filter.isBlank() ? null : filter.toLowerCase(Locale.ROOT);
        String s = sortField == null || sortField.isBlank() ? null : sortField;
        String key = String.join("\u001f", version == null ? "" : version, s == null ? "" : s, String.valueOf(descending && s != null),
                f == null ? "" : f, reader.audience());
        CompletableFuture<Index> got = indexes.get(key);
        if (got != null && got.isDone() && !got.isCompletedExceptionally()) {
            Index i = got.join();
            if ((version == null || version.isEmpty()) && i.builtAt + UNVERSIONED_MS < System.currentTimeMillis()) {
                indexes.remove(key, got);
                got = null;
            }
        }
        if (got == null) {
            CompletableFuture<Index> mine = new CompletableFuture<>();
            got = indexes.putIfAbsent(key, mine);
            if (got == null) {
                got = mine;
                try {
                    mine.complete(build(version, s, descending, f, reader, shown));
                } catch (RuntimeException e) {
                    mine.completeExceptionally(e);
                    indexes.remove(key, mine);
                }
                recent.remove(key);
                recent.addLast(key);
                forgetOld(version);
            }
        }
        try {
            return got.join();
        } catch (CompletionException e) {
            throw e.getCause() instanceof RuntimeException r ? r : e;
        }
    }

    /** Keeps the latest few indexes, and none of an older store version. */
    private void forgetOld(String version) {
        String v = version == null ? "" : version;
        for (String k : List.copyOf(indexes.keySet())) {
            if (!k.startsWith(v + "\u001f")) {
                indexes.remove(k);
                recent.remove(k);
            }
        }
        while (recent.size() > KEPT) {
            String old = recent.pollFirst();
            if (old != null) {
                indexes.remove(old);
            }
        }
    }

    /** Reads the store's columns once and orders the records that hold the condition, the reader's scope and the filter. */
    private Index build(String version, String sortField, boolean descending, String filter, LiveReader reader, List<String> shown) {
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        if (keyField != null) {
            fields.add(keyField);
        }
        fields.addAll(conditionFields);
        fields.addAll(reader.scope().fields());
        if (sortField != null) {
            fields.add(sortField);
        }
        if (filter != null) {
            fields.addAll(shown);
        }
        List<String> asked = List.copyOf(fields);
        Map<String, Integer> at = new HashMap<>();
        for (int i = 0; i < asked.size(); i++) {
            at.put(asked.get(i), i);
        }
        List<String> keys = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        List<Object> values = sortField == null ? null : new ArrayList<>();
        boolean[] differ = {false};
        boolean scoped = !reader.scope().all();
        UnaryOperator<DataNode> redact = reader.redact();
        boolean masked = !reader.unmasked();
        Integer keyAt = keyField == null ? null : at.get(keyField);
        Integer sortAt = sortField == null ? null : at.get(sortField);
        boolean read;
        try {
            read = store.project(asked, (id, v) -> {
                if (condition != null && !conditionFields.isEmpty() && !condition.test(node(asked, v, conditionFields, at))) {
                    return;
                }
                if (scoped && !reader.scope().test().test(node(asked, v, reader.scope().fields(), at))) {
                    return;
                }
                if (filter != null) {
                    DataNode seen = node(asked, v, shown, at);
                    if (!LiveSet.text(masked ? redact.apply(seen) : seen).contains(filter)) {
                        return;
                    }
                }
                Object k = keyAt == null ? null : v[keyAt];
                String key = k == null ? id : LiveSet.keyText(k);
                keys.add(key);
                ids.add(id);
                if (!key.equals(id)) {
                    differ[0] = true;
                }
                if (values != null) {
                    Object sv = v[sortAt];
                    if (masked) {
                        sv = redact.apply(node(asked, v, List.of(sortField), at)).get(sortField).unwrap();
                    }
                    values.add(sv);
                }
            });
        } catch (DrishtiException e) {
            throw e;
        } catch (Exception e) {
            throw new DrishtiException(ErrorCode.SOURCE_FAILED, "the set's records could not be read from the store: " + e.getMessage());
        }
        if (!read) {
            throw new DrishtiException(ErrorCode.SOURCE_FAILED, "the store cannot read the kind's records whole");
        }
        int n = keys.size();
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        boolean sorted = values != null;
        Arrays.parallelSort(order, (a, b) -> compare(sorted ? values.get(a) : null, keys.get(a), sorted ? values.get(b) : null, keys.get(b), sorted,
                descending));
        String[] k = new String[n];
        String[] id = differ[0] ? new String[n] : null;
        Object[] v = sorted ? new Object[n] : null;
        for (int i = 0; i < n; i++) {
            int o = order[i];
            k[i] = keys.get(o);
            if (id != null) {
                id[i] = ids.get(o);
            }
            if (v != null) {
                v[i] = values.get(o);
            }
        }
        if (sortField == null && filter == null) {
            lastCount = n;
        }
        return new Index(version, k, id, v, descending);
    }

    /** A document of some projected fields ({@code A.B} as {@code {A: {B: v}}}), for a condition, a scope, a filter or a mask. */
    @SuppressWarnings("unchecked")
    static DataNode node(List<String> asked, Object[] values, Collection<String> fields, Map<String, Integer> at) {
        Map<String, Object> doc = new LinkedHashMap<>();
        for (String f : fields) {
            Integer i = at.get(f);
            if (i == null) {
                continue;
            }
            String[] parts = f.split("\\.");
            Map<String, Object> m = doc;
            boolean ok = true;
            for (int p = 0; p < parts.length - 1 && ok; p++) {
                Object next = m.computeIfAbsent(parts[p], x -> new LinkedHashMap<String, Object>());
                if (next instanceof Map<?, ?> nm) {
                    m = (Map<String, Object>) nm;
                } else {
                    ok = false;
                }
            }
            if (ok) {
                m.put(parts[parts.length - 1], values[i]);
            }
        }
        return DataNode.of(doc);
    }
}
