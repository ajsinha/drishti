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
 * One live result set (docs/guides/TRADING_GUIDE.md section 7): the snapshot of a kind's records that hold a condition, read from the
 * durable store through the bindings, with the changes of the kind's {@code live:} binding merged in by key and version: a newer version
 * replaces the row, an older or equal one is dropped (no duplicates, no going back), a deletion or a row that stops holding the condition
 * leaves. The live side is subscribed to before the snapshot is read, so nothing between the two is lost; what arrived meanwhile is applied
 * after it, by version.
 *
 * <p>Shared by every viewer of the same declaration and condition, and nothing in it belongs to a person: rows are kept as the source
 * holds them; each viewer's {@link Window} masks what it sends as that viewer may see it. Changes reach a window coalesced (the latest per
 * record) and are flushed every few milliseconds; a window that falls further behind than its capacity is told to resync instead
 * (back-pressure: a burst never queues without bound). A paged window is sent changes of the rows it shows and the new total only.
 */
public final class LiveSet implements AutoCloseable {

    /** One record of the set. */
    public record Row(String id, long version, long seq, DataNode doc, Instant arrived) {}

    /** Reads the snapshot: every record of the kind, to the sink. */
    @FunctionalInterface
    public interface Snapshot {
        void read(Consumer<EntityDocument> sink) throws Exception;
    }

    private final LiveSetSpec spec;
    private final String condition;
    private final Predicate<DataNode> holds;
    private final Clock clock;
    private final Map<String, Row> rows = new ConcurrentHashMap<>();
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

    LiveSet(LiveSetSpec spec, String condition, Predicate<DataNode> holds, Clock clock) {
        this.spec = spec;
        this.condition = condition;
        this.holds = holds == null ? d -> true : holds;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** Subscribes to the live side, reads the snapshot, then applies what arrived meanwhile. */
    void start(Function<Consumer<EntityDocument>, Subscription> subscribe, Snapshot snapshot) throws Exception {
        Subscription s = subscribe.apply(this::offer);
        subscription = s == null ? Subscription.NONE : s;
        live = subscription != Subscription.NONE;
        snapshot.read(d -> apply(d, false));
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
        lock.lock();
        try {
            String id = keyOf(d);
            if (id == null) {
                return;
            }
            Row old = rows.get(id);
            if (d.deleted()) {
                if (old != null) {
                    rows.remove(id);
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
            if (!holds.test(d.data())) {
                if (old != null) {
                    rows.remove(id);
                    changed(null, id);
                }
                return;
            }
            Row r = new Row(id, v, seq.incrementAndGet(), d.data(), Instant.now(clock));
            rows.put(id, r);
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
                return v == null ? null : String.valueOf(v);
            }
        }
        return d.ref().id();
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
            if (ret.ttl() != null) {
                Instant cut = now.minus(ret.ttl());
                for (Row r : List.copyOf(rows.values())) {
                    if (age(r).isBefore(cut)) {
                        rows.remove(r.id());
                        changed(null, r.id());
                        dropped++;
                    }
                }
            }
            if (ret.maxRows() != null && rows.size() > ret.maxRows()) {
                List<Row> all = new ArrayList<>(rows.values());
                all.sort(Comparator.comparing(this::age));
                for (int i = 0; i < all.size() - ret.maxRows(); i++) {
                    rows.remove(all.get(i).id());
                    changed(null, all.get(i).id());
                    dropped++;
                }
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
     * One page of the rows that also hold {@code filter} (a text every shown value is searched for, case ignored), sorted, as maps
     * masked by {@code redact}. {@code total} is the count before paging.
     */
    public Page page(int offset, int limit, String sortField, boolean descending, String filter, UnaryOperator<DataNode> redact) {
        String f = filter == null || filter.isBlank() ? null : filter.toLowerCase(Locale.ROOT);
        List<Row> all = new ArrayList<>();
        for (Row r : rows.values()) {
            if (f == null || text(r.doc()).contains(f)) {
                all.add(r);
            }
        }
        String by = sortField != null ? sortField : spec.sortField();
        boolean desc = sortField != null ? descending : spec.descending();
        Comparator<Row> cmp = by == null ? Comparator.comparingLong(Row::seq).reversed() : Comparator.comparing(r -> sortKey(r, by), LiveSet::compare);
        if (by != null && desc) {
            cmp = cmp.reversed();
        }
        all.sort(cmp.thenComparing(Row::id));
        int from = Math.max(0, Math.min(offset, all.size()));
        int to = Math.min(all.size(), from + Math.max(1, limit));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Row r : all.subList(from, to)) {
            out.add(shown(r, redact));
        }
        return new Page(out, all.size(), seq.get());
    }

    /** A page: its rows, the total before paging and the sequence it is consistent with. */
    public record Page(List<Map<String, Object>> rows, int total, long seq) {}

    /** One record as a viewer may see it; empty when it is not in the set. */
    public java.util.Optional<Map<String, Object>> row(String id, UnaryOperator<DataNode> redact) {
        Row r = rows.get(id);
        return r == null ? java.util.Optional.empty() : java.util.Optional.of(shown(r, redact));
    }

    /** The record's current version, or null when the set does not hold it. */
    public Row current(String id) {
        return rows.get(id);
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

    private static Comparable<Object> sortKey(Row r, String field) {
        Object v = r.doc().get(field).unwrap();
        @SuppressWarnings("unchecked")
        Comparable<Object> c = v instanceof Comparable<?> cc ? (Comparable<Object>) cc : null;
        return c;
    }

    private static int compare(Comparable<Object> a, Comparable<Object> b) {
        if (a == null || b == null) {
            return a == null ? (b == null ? 0 : 1) : -1;
        }
        if (a instanceof Number x && b instanceof Number y) {
            return Double.compare(x.doubleValue(), y.doubleValue());
        }
        try {
            return a.compareTo(b);
        } catch (ClassCastException e) {
            return String.valueOf(a).compareTo(String.valueOf(b));
        }
    }

    private static String text(DataNode d) {
        return String.valueOf(d.unwrap()).toLowerCase(Locale.ROOT);
    }

    /** Opens a viewer's window. */
    Window window(UnaryOperator<DataNode> redact, Consumer<Map<String, Object>> sink, int capacity) {
        Window w = new Window(redact, sink, capacity);
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
     * A viewer's window: changes coalesced per record until the next flush, masked as the viewer may see them. Paged ({@link #paged}),
     * it is sent changes of the rows it shows and the total only. Over {@code capacity} records waiting, it drops them and asks the viewer
     * to resync.
     */
    public final class Window {

        private final UnaryOperator<DataNode> redact;
        private final Consumer<Map<String, Object>> sink;
        private final int capacity;
        private final ReentrantLock wl = new ReentrantLock();
        private Map<String, Row> pending = new LinkedHashMap<>();
        private boolean overflow;
        private boolean totalChanged;
        private volatile Set<String> visible;             // null: the viewer holds every row

        Window(UnaryOperator<DataNode> redact, Consumer<Map<String, Object>> sink, int capacity) {
            this.redact = redact;
            this.sink = sink;
            this.capacity = Math.max(16, capacity);
        }

        /** The rows the viewer shows (a page), or null when it holds them all. */
        public void paged(Set<String> ids) {
            visible = ids == null ? null : Set.copyOf(ids);
        }

        void offer(String id, Row r) {
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
                pending.put(id, r);
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
                ev.put("reason", "more than " + capacity + " records changed before they could be sent");
            } else {
                List<Map<String, Object>> up = new ArrayList<>();
                List<String> rm = new ArrayList<>();
                take.forEach((id, r) -> {
                    if (r == null) {
                        rm.add(id);
                    } else {
                        up.add(shown(r, redact));
                    }
                });
                ev.put("type", "rows");
                ev.put("upserts", up);
                ev.put("removes", rm);
                ev.put("countOnly", up.isEmpty() && rm.isEmpty() && count);
            }
            ev.put("total", rows.size());
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
