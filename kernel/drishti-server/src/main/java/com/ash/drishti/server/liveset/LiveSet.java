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
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.Subscription;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * One live result set (docs/guides/LIVE_RESULTS_GUIDE.md section 7): the snapshot of a kind's records that hold a condition, read from the
 * durable store through the bindings, with the changes of the kind's {@code live:} binding merged in by key and version: a newer version
 * replaces the row, an older or equal one is dropped (no duplicates, no going back), a deletion or a row that stops holding the condition
 * leaves. The live side is subscribed to before the snapshot is read, so nothing between the two is lost; what arrived meanwhile is applied
 * after it, by version.
 *
 * <p>Shared by every viewer of the same declaration and condition (a search's set: of the same audience, {@link LiveReader}), and nothing
 * in it belongs to a person: rows are kept as the source holds them; every read and each viewer's {@link Window} keeps to the reader's row
 * scope and masks what it sends as that reader may see it. Changes reach a window coalesced (the latest per record) and are flushed every
 * few milliseconds; a window that falls further behind than its capacity is told to resync instead (back-pressure: a burst never queues
 * without bound). A paged window is sent changes of the rows it shows and the new total only.
 *
 * <p>Paged from the store ({@link StorePages}): the snapshot stays in the lake and {@code rows} holds the live side only (the records
 * changed since the set opened, and {@code removed} the ones deleted or that stopped holding the condition); pages merge the two by key.
 */
public final class LiveSet implements AutoCloseable {

    /**
     * One record of the set: its key, version, the sequence of its last change, the document, when it arrived, and its rank (the sequence
     * it first came in with, kept when it changes: a search's set keeps the search's order by it).
     */
    public record Row(String id, long version, long seq, DataNode doc, Instant arrived, long rank) {

        public Row(String id, long version, long seq, DataNode doc, Instant arrived) {
            this(id, version, seq, doc, arrived, seq);
        }
    }

    /** Reads the snapshot: every record of the kind, to the sink. */
    @FunctionalInterface
    public interface Snapshot {
        void read(Consumer<EntityDocument> sink) throws Exception;
    }

    private volatile LiveSetSpec spec;
    private final String condition;
    private final Predicate<DataNode> holds;
    private final Clock clock;
    private final Map<String, Row> rows = new ConcurrentHashMap<>();
    /** Paged from the store: the keys the live side deleted or that stopped holding the condition (they hide the store's record). */
    private final Set<String> removed = ConcurrentHashMap.newKeySet();
    /** The store the set pages from, or null: the snapshot is read whole into {@code rows}. */
    private volatile StorePages pages;
    /** A search's set: the audience it was filled for (only readers of it read it); null for a declared set. */
    private volatile String audience;
    private final AtomicLong seq = new AtomicLong();
    private final ReentrantLock lock = new ReentrantLock();
    private final Deque<EntityDocument> early = new ArrayDeque<>();
    private final List<Window> windows = new CopyOnWriteArrayList<>();
    private final AtomicLong applied = new AtomicLong();
    private final AtomicLong stale = new AtomicLong();
    private final AtomicLong gaps = new AtomicLong();
    private final AtomicLong expired = new AtomicLong();
    private volatile boolean ready;
    private volatile boolean live;
    private volatile Subscription subscription = Subscription.NONE;
    private volatile Instant lastChange;
    private volatile Instant openedAt;
    /** A search's set: its snapshot rows are the search's matches (not tested again) and kept in the search's order. */
    private final boolean matched;
    /** What the search that filled it said (columns, labels, matched, scanned, failed, ...); empty for a declared set. */
    private volatile Map<String, Object> origin = Map.of();

    LiveSet(LiveSetSpec spec, String condition, Predicate<DataNode> holds, Clock clock) {
        this(spec, condition, holds, clock, false);
    }

    LiveSet(LiveSetSpec spec, String condition, Predicate<DataNode> holds, Clock clock, boolean matched) {
        this.spec = spec;
        this.condition = condition;
        this.holds = holds == null ? d -> true : holds;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.matched = matched;
    }

    /** What the search that filled it said; empty for a declared set. */
    public Map<String, Object> origin() {
        return origin;
    }

    /** A search's set learns its columns from the search that fills it. */
    void spec(LiveSetSpec s) {
        spec = s;
    }

    void origin(Map<String, Object> m) {
        origin = m == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(m));
    }

    private volatile boolean capped;

    /** True when its snapshot stopped at the default declarations' row cap ({@code drishti.live-sets.default-max-rows}). */
    public boolean capped() {
        return capped;
    }

    void capped(boolean c) {
        capped = c;
    }

    /** Pages from the store: the snapshot stays there and only the live side is kept here. */
    void pages(StorePages p) {
        pages = p;
    }

    public boolean paged() {
        return pages != null;
    }

    /** The audience a search's set was filled for; null for a declared set. */
    public String audience() {
        return audience;
    }

    void audience(String a) {
        audience = a;
    }

    /** When its snapshot was read. */
    public Instant openedAt() {
        return openedAt;
    }

    /** Subscribes to the live side, reads the snapshot, then applies what arrived meanwhile. */
    void start(Function<Consumer<EntityDocument>, Subscription> subscribe, Snapshot snapshot) throws Exception {
        Subscription s = subscribe.apply(this::offer);
        subscription = s == null ? Subscription.NONE : s;
        live = subscription != Subscription.NONE;
        openedAt = Instant.now(clock);
        if (snapshot != null) {
            snapshot.read(d -> apply(d, false, matched));
        }
        lock.lock();
        try {
            while (!early.isEmpty()) {
                apply(early.pollFirst(), true);
            }
            ready = true;
        } finally {
            lock.unlock();
        }
    }

    /** A change from the live side. */
    void offer(EntityDocument d) {
        if (!ready) {
            lock.lock();
            try {
                if (!ready) {
                    early.addLast(d);
                    return;
                }
            } finally {
                lock.unlock();
            }
        }
        apply(d, true);
    }

    /** Merges one document by key and version. */
    void apply(EntityDocument d, boolean push) {
        apply(d, push, false);
    }

    /** Merges one document; {@code known} when it is a search's match already (its condition is not tested again). */
    private void apply(EntityDocument d, boolean push, boolean known) {
        lock.lock();
        try {
            String id = keyOf(d);
            if (id == null) {
                return;
            }
            Row old = rows.get(id);
            boolean paged = pages != null;
            if (d.deleted()) {
                if (old != null || paged) {
                    rows.remove(id);
                    if (paged) {
                        removed.add(id);                      // the store's record of it is hidden too
                    }
                    changed(null, id);
                }
                return;
            }
            long v = versionOf(d.data());
            if (old != null && v >= 0 && old.version() >= v) {
                stale.incrementAndGet();                      // a duplicate or an older version: never back in time
                return;
            }
            if (old != null && v > old.version() + 1 && old.version() >= 0) {
                gaps.incrementAndGet();                       // versions skipped: the record is whole, so the newest is still right
            }
            if (!known && !holds.test(d.data())) {
                if (old != null || paged) {
                    rows.remove(id);
                    if (paged) {
                        removed.add(id);
                    }
                    changed(null, id);
                }
                return;
            }
            long n = seq.incrementAndGet();
            Row r = new Row(id, v, n, d.data(), Instant.now(clock), old == null ? n : old.rank());
            rows.put(id, r);
            removed.remove(id);
            applied.incrementAndGet();
            if (push) {
                changed(r, id);
            }
        } finally {
            lock.unlock();
        }
    }

    private void changed(Row r, String id) {
        lastChange = Instant.now(clock);
        for (Window w : windows) {
            w.offer(id, r);
        }
    }

    /** The record's key: the declared key field, else the entity id. */
    String keyOf(EntityDocument d) {
        if (spec.key() != null && !d.deleted()) {
            DataNode k = d.data().get(spec.key());
            if (!k.isNull()) {
                Object v = k.unwrap();
                return v == null ? null : keyText(v);
            }
        }
        return d.ref().id();
    }

    /** A key's text: a whole number without its decimals (a store's column may read {@code 42} as {@code 42.0}). */
    static String keyText(Object v) {
        if (v instanceof Double x && x == Math.rint(x) && !Double.isInfinite(x) && Math.abs(x) < 1e15) {
            return String.valueOf(x.longValue());
        }
        return String.valueOf(v);
    }

    /** The record's version, or -1 when the declaration names none (the latest arrival wins). */
    long versionOf(DataNode doc) {
        if (spec.version() == null) {
            return -1;
        }
        Object v = doc.get(spec.version()).unwrap();
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return v == null ? -1 : Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Drops rows past the retention (age by the declared field, else arrival) and beyond its row cap, oldest first. */
    int trim() {
        LiveSetSpec.Retention ret = spec.retention();
        if (ret == null) {
            return 0;
        }
        lock.lock();
        try {
            int dropped = 0;
            Instant now = Instant.now(clock);
            boolean paged = pages != null;                    // paged: a dropped change shows the store's record again
            if (ret.ttl() != null) {
                Instant cut = now.minus(ret.ttl());
                for (Row r : List.copyOf(rows.values())) {
                    if (age(r).isBefore(cut)) {
                        rows.remove(r.id());
                        if (!paged) {
                            changed(null, r.id());
                        }
                        dropped++;
                    }
                }
            }
            if (ret.maxRows() != null && rows.size() > ret.maxRows()) {
                List<Row> all = new ArrayList<>(rows.values());
                all.sort(Comparator.comparing(this::age));
                for (int i = 0; i < all.size() - ret.maxRows(); i++) {
                    rows.remove(all.get(i).id());
                    if (!paged) {
                        changed(null, all.get(i).id());
                    }
                    dropped++;
                }
            }
            if (paged && dropped > 0) {
                windows.forEach(Window::resync);
            }
            expired.addAndGet(dropped);
            return dropped;
        } finally {
            lock.unlock();
        }
    }

    /** When a row counts as made: the retention's field read as a time or a date, else when it arrived here. */
    Instant age(Row r) {
        LiveSetSpec.Retention ret = spec.retention();
        if (ret == null || ret.on() == null) {
            return r.arrived();
        }
        Object v = r.doc().get(ret.on()).unwrap();
        Instant t = instant(v);
        return t == null ? r.arrived() : t;
    }

    static Instant instant(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            long x = n.longValue();
            return x > 100_000_000_000L ? Instant.ofEpochMilli(x) : Instant.ofEpochSecond(x);
        }
        String s = String.valueOf(v).trim();
        try {
            return Instant.parse(s);
        } catch (RuntimeException e) {
            // other shapes below
        }
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (RuntimeException e) {
            // other shapes below
        }
        try {
            return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC);
        } catch (RuntimeException e) {
            // other shapes below
        }
        try {
            return LocalDate.parse(s.length() >= 10 ? s.substring(0, 10) : s).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * One page of the rows the reader may see (their row scope) that also hold {@code filter} (a text the shown values are searched for,
     * case ignored), sorted, as maps masked as the reader sees them. {@code total} is the count before paging. Paged from the store, the
     * store's records and the live side are merged by key.
     */
    public Page page(int offset, int limit, String sortField, boolean descending, String filter, LiveReader reader) {
        LiveReader who = reader == null ? LiveReader.everyone() : reader;
        String f = filter == null || filter.isBlank() ? null : filter.toLowerCase(Locale.ROOT);
        String by = sortField != null && !sortField.isBlank() ? sortField : spec.sortField();
        boolean desc = sortField != null && !sortField.isBlank() ? descending : spec.descending();
        List<String> shown = shownFields();
        StorePages p = pages;
        if (p != null) {
            Map<String, Row> now = new java.util.HashMap<>(rows);       // one view of the live side for the whole page
            Set<String> gone = Set.copyOf(removed);
            List<StorePages.Live> live = new ArrayList<>();
            for (Row r : now.values()) {
                if (!who.scope().test().test(r.doc())) {
                    continue;
                }
                DataNode seen = who.redact().apply(r.doc());
                if (f != null && !text(project(seen, shown)).contains(f)) {
                    continue;
                }
                live.add(new StorePages.Live(r.id(), by == null ? null : seen.get(by).unwrap(), r));
            }
            return p.page(offset, limit, by, desc, f, who, shown, now, gone, live, seq.get(), this::versionOf);
        }
        // filtered and sorted on what the viewer may see: a masked value never decides which rows show or their order
        List<Row> all = new ArrayList<>();
        for (Row r : rows.values()) {
            if (!who.scope().test().test(r.doc())) {
                continue;                                     // outside the reader's row scope: never counted, never shown
            }
            Row seen = who.unmasked() ? r : new Row(r.id(), r.version(), r.seq(), who.redact().apply(r.doc()), r.arrived(), r.rank());
            if (f == null || text(project(seen.doc(), shown)).contains(f)) {
                all.add(seen);
            }
        }
        Comparator<Row> cmp = by == null ? (matched ? Comparator.comparingLong(Row::rank) : Comparator.comparingLong(Row::seq).reversed())
                : (a, b) -> desc ? compareDescending(a.doc().get(by).unwrap(), b.doc().get(by).unwrap())
                        : compareValues(a.doc().get(by).unwrap(), b.doc().get(by).unwrap());
        all.sort(cmp.thenComparing(Row::id));
        int from = Math.max(0, Math.min(offset, all.size()));
        int to = Math.min(all.size(), from + Math.max(1, limit));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Row r : all.subList(from, to)) {
            out.add(shown(r, null));
        }
        return new Page(out, all.size(), seq.get());
    }

    /** The fields the grid shows (the filter searches their values); empty: the whole record. */
    List<String> shownFields() {
        return spec.columns().stream().map(c -> String.valueOf(c.get("field"))).filter(x -> !"null".equals(x)).toList();
    }

    /** The shown fields of a record; the record itself when none are declared. */
    static DataNode project(DataNode d, List<String> fields) {
        if (fields.isEmpty()) {
            return d;
        }
        Object[] v = new Object[fields.size()];
        Map<String, Integer> at = new java.util.HashMap<>();
        for (int i = 0; i < fields.size(); i++) {
            v[i] = d.get(fields.get(i)).unwrap();
            at.put(fields.get(i), i);
        }
        return StorePages.node(fields, v, fields, at);
    }

    /** How many rows the reader may see (their row scope): the store's and the live side's when paged. */
    public int total(LiveReader reader) {
        return total(reader, true);
    }

    /** {@link #total(LiveReader)}; {@code fresh} false (a stream's flush): the index read last for the reader, never a read of the store. */
    int total(LiveReader reader, boolean fresh) {
        LiveReader who = reader == null ? LiveReader.everyone() : reader;
        StorePages p = pages;
        if (p != null) {
            return p.count(who, new java.util.HashMap<>(rows), Set.copyOf(removed), r -> who.scope().test().test(r.doc()), fresh);
        }
        if (who.scope().all()) {
            return rows.size();
        }
        int n = 0;
        for (Row r : rows.values()) {
            if (who.scope().test().test(r.doc())) {
                n++;
            }
        }
        return n;
    }

    /** A page: its rows, the total before paging and the sequence it is consistent with. */
    public record Page(List<Map<String, Object>> rows, int total, long seq) {}

    /**
     * One record as the reader may see it; empty when it is not in the set or outside the reader's row scope. Paged from the store, a
     * record the live side does not hold is read from the store and must hold the set's condition.
     */
    public java.util.Optional<Map<String, Object>> row(String id, LiveReader reader) {
        LiveReader who = reader == null ? LiveReader.everyone() : reader;
        Row r = current(id);
        if (r != null && pages != null && !rows.containsKey(id) && !holds.test(r.doc())) {
            r = null;
        }
        if (r == null || !who.scope().test().test(r.doc())) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(shown(r, who.redact()));
    }

    /** The record's current version, or null when the set does not hold it (paged: the live side's, else the store's). */
    public Row current(String id) {
        Row r = rows.get(id);
        StorePages p = pages;
        if (r == null && p != null && !removed.contains(id)) {
            r = p.lookup(id, this::versionOf);
        }
        return r;
    }

    static Map<String, Object> shown(Row r, UnaryOperator<DataNode> redact) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("version", r.version());
        m.put("seq", r.seq());
        DataNode d = redact == null ? r.doc() : redact.apply(r.doc());
        m.put("doc", d.unwrap());
        return m;
    }

    /** Descending, with values that are not there still last. */
    static int compareDescending(Object a, Object b) {
        if (a == null || b == null) {
            return a == null ? (b == null ? 0 : 1) : -1;
        }
        return -compareValues(a, b);
    }

    /** Ascending, values that are not there last; numbers as numbers, else the values' own order, else their text. */
    @SuppressWarnings("unchecked")
    static int compareValues(Object a, Object b) {
        if (a == null || b == null) {
            return a == null ? (b == null ? 0 : 1) : -1;
        }
        if (a instanceof Number x && b instanceof Number y) {
            return Double.compare(x.doubleValue(), y.doubleValue());
        }
        if (a instanceof Comparable<?> ca && a.getClass() == b.getClass()) {
            try {
                return ((Comparable<Object>) ca).compareTo(b);
            } catch (ClassCastException e) {
                // text below
            }
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    /** The values of a record as one lower-case text (no field names: a filter finds values only). */
    static String text(DataNode d) {
        StringBuilder b = new StringBuilder();
        values(d.unwrap(), b);
        return b.toString().toLowerCase(Locale.ROOT);
    }

    private static void values(Object o, StringBuilder b) {
        if (o instanceof Map<?, ?> m) {
            m.values().forEach(v -> values(v, b));
        } else if (o instanceof java.util.Collection<?> c) {
            c.forEach(v -> values(v, b));
        } else if (o != null) {
            b.append(o).append('\n');
        }
    }

    /** Opens a reader's window. */
    Window window(LiveReader reader, Consumer<Map<String, Object>> sink, int capacity) {
        Window w = new Window(reader == null ? LiveReader.everyone() : reader, sink, capacity);
        windows.add(w);
        return w;
    }

    void closeWindow(Window w) {
        windows.remove(w);
    }

    int windows() {
        return windows.size();
    }

    List<Window> openWindows() {
        return windows;
    }

    /**
     * A reader's window: changes coalesced per record until the next flush, kept to the reader's row scope and masked as they may see
     * them (a record that leaves their scope is a removal to them, one outside it is never shown). Paged ({@link #paged}), it is sent
     * changes of the rows it shows and the total only. Over {@code capacity} records waiting, it drops them and asks the viewer to resync.
     */
    public final class Window {

        private final LiveReader reader;
        private final Consumer<Map<String, Object>> sink;
        private final int capacity;
        private final ReentrantLock wl = new ReentrantLock();
        private Map<String, Row> pending = new LinkedHashMap<>();
        private boolean overflow;
        private boolean totalChanged;
        private volatile Set<String> visible;             // null: the viewer holds every row

        Window(LiveReader reader, Consumer<Map<String, Object>> sink, int capacity) {
            this.reader = reader;
            this.sink = sink;
            this.capacity = Math.max(16, capacity);
        }

        /** The rows the viewer shows (a page), or null when it holds them all. */
        public void paged(Set<String> ids) {
            visible = ids == null ? null : Set.copyOf(ids);
        }

        /** Asks the viewer to read its page again (the live side dropped rows the store holds). */
        void resync() {
            wl.lock();
            try {
                pending = new LinkedHashMap<>();
                overflow = true;
            } finally {
                wl.unlock();
            }
        }

        void offer(String id, Row r) {
            Row mine = r != null && !reader.scope().test().test(r.doc()) ? null : r;   // outside the reader's scope: a removal to them
            wl.lock();
            try {
                Set<String> v = visible;
                if (v != null && !v.contains(id)) {
                    totalChanged = true;                      // not on the viewer's page: only the count moves
                    return;
                }
                if (overflow) {
                    return;
                }
                pending.put(id, mine);
                if (pending.size() > capacity) {
                    pending = new LinkedHashMap<>();
                    overflow = true;
                }
            } finally {
                wl.unlock();
            }
        }

        /** Sends what waits: one {@code rows} event (upserts, removes, total, seq), or one {@code resync}. True when it sent. */
        boolean flush() {
            Map<String, Row> take;
            boolean resync;
            boolean count;
            wl.lock();
            try {
                if (pending.isEmpty() && !overflow && !totalChanged) {
                    return false;
                }
                take = pending;
                pending = new LinkedHashMap<>();
                resync = overflow;
                count = totalChanged;
                overflow = false;
                totalChanged = false;
            } finally {
                wl.unlock();
            }
            Map<String, Object> ev = new LinkedHashMap<>();
            if (resync) {
                ev.put("type", "resync");
                ev.put("reason", "more than " + capacity + " records changed before they could be sent, or the live side was trimmed");
            } else {
                List<Map<String, Object>> up = new ArrayList<>();
                List<String> rm = new ArrayList<>();
                take.forEach((id, r) -> {
                    if (r == null) {
                        rm.add(id);
                    } else {
                        up.add(shown(r, reader.redact()));
                    }
                });
                ev.put("type", "rows");
                ev.put("upserts", up);
                ev.put("removes", rm);
                ev.put("countOnly", up.isEmpty() && rm.isEmpty() && count);
            }
            ev.put("total", total(reader, false));           // the flush thread never reads the store
            ev.put("seq", seq.get());
            sink.accept(ev);
            return true;
        }
    }

    /** Figures for Health and the screen. */
    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", spec.id());
        m.put("kind", spec.kind());
        m.put("condition", condition);
        m.put("rows", rows.size());
        m.put("live", live);
        m.put("ready", ready);
        m.put("seq", seq.get());
        m.put("applied", applied.get());
        m.put("staleDropped", stale.get());
        m.put("versionGaps", gaps.get());
        m.put("expired", expired.get());
        m.put("viewers", windows.size());
        m.put("lastChange", lastChange == null ? null : lastChange.toString());
        m.put("retention", spec.retention() == null ? null : spec.retention().describe());
        m.put("search", matched);
        m.put("capped", capped);
        StorePages p = pages;
        m.put("paged", p != null);
        if (p != null) {
            m.put("storeRows", p.lastCount() < 0 ? null : p.lastCount());
            m.put("indexes", p.indexes());
            m.put("removed", removed.size());
        }
        return m;
    }

    public LiveSetSpec spec() {
        return spec;
    }

    public boolean live() {
        return live;
    }

    public int size() {
        return rows.size();
    }

    @Override
    public void close() {
        subscription.close();
        windows.clear();
    }
}
