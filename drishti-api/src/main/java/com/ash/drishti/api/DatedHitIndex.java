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
package com.ash.drishti.api;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Type-ahead over several business days. Per kind: one dictionary of ids, each stored once and sorted for prefix binary
 * search, and one compact bitmap per business day marking which ids existed that day. A lookup is for one requested day
 * and never mixes days. Reads are lock-free (they use an immutable snapshot that writers swap); writers (the newest day's
 * refresh, a restated past day, the background warm-up) take a per-kind lock. When the kept days roll forward the oldest
 * day's bitmap drops and ids that no kept day holds are purged, so memory stays flat. {@link DayIndexSettings#maxMemoryBytes}
 * caps the connector's total and drops the oldest days early.
 *
 * <p>A connector that keeps one day ({@code days} 1) does not use this class: {@link #search} answers empty and the
 * connector's own {@link HitIndex} serves, as before.
 */
public final class DatedHitIndex {

    /** Reads the ids of one kind on one business day (a table query, a folder listing). */
    @FunctionalInterface
    public interface DayLoader {
        List<EntityHit> load(String kind, LocalDate day) throws Exception;
    }

    /** What Health shows. */
    public record Stats(int kinds, int days, int ids, long memoryBytes, String warm, List<LocalDate> indexedDays) {}

    /** An immutable version of one kind: dictionary (parallel arrays by ordinal), id order, and a bitmap per day. */
    private record Snap(String[] lower, EntityHit[] hits, int[] order, TreeMap<LocalDate, BitSet> days) {
        static final Snap EMPTY = new Snap(new String[0], new EntityHit[0], new int[0], new TreeMap<>());
    }

    private static final class KindIndex {
        final ReentrantLock lock = new ReentrantLock();
        final AtomicReference<Snap> snap = new AtomicReference<>(Snap.EMPTY);
        volatile NavigableSet<LocalDate> wanted = new TreeSet<>();
    }

    private final Map<String, KindIndex> kinds = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<Runnable> warmQueue = new ConcurrentLinkedDeque<>();
    private final AtomicBoolean warming = new AtomicBoolean();
    private final AtomicInteger warmPending = new AtomicInteger();
    private final DayLoader loader;
    private volatile Map<String, String> settings;

    public DatedHitIndex(Map<String, String> settings, DayLoader loader) {
        this.settings = Map.copyOf(settings);
        this.loader = loader;
    }

    /** The settings that apply to {@code kind}. */
    public DayIndexSettings settingsFor(String kind) {
        return DayIndexSettings.of(settings, kind);
    }

    /** The most days any kind keeps (the connector default or a kind's override). */
    public int maxDays() {
        int max = DayIndexSettings.of(settings, null).days();
        for (String k : settings.keySet()) {
            if (k.startsWith("index.kinds.") && k.endsWith(".days")) {
                max = Math.max(max, DayIndexSettings.of(settings, k.substring(12, k.length() - 5)).days());
            }
        }
        return max;
    }

    /** True when any kind keeps more than one day (so a connector can skip all of this at the default). */
    public boolean enabled() {
        return DayIndexSettings.of(settings, null).multiDay()
                || settings.keySet().stream().anyMatch(k -> k.startsWith("index.kinds.") && k.endsWith(".days"));
    }

    /** Applies new settings live: windows shrink at once (a grown window fills as {@link #plan} is next called). */
    public void reconfigure(Map<String, String> next) {
        this.settings = Map.copyOf(next);
        kinds.forEach((kind, ki) -> locked(ki, () -> ki.snap.set(trimmed(kind, ki.snap.get()))));
    }

    // ---- writes ------------------------------------------------------------------------------------------------

    /**
     * Sets the ids of {@code kind} on {@code day} (the newest day's refresh, a restated day, a warmed day), then keeps
     * only the newest {@code days} days and purges ids no kept day holds. Ignored when {@code day} is older than the whole
     * kept window.
     */
    public void putDay(String kind, LocalDate day, Collection<EntityHit> hits) {
        KindIndex ki = kinds.computeIfAbsent(kind, k -> new KindIndex());
        locked(ki, () -> {
            Snap old = ki.snap.get();
            if (old.days().size() >= settingsFor(kind).days() && day.isBefore(old.days().firstKey()) && !old.days().containsKey(day)) {
                return;
            }
            ki.snap.set(trimmed(kind, with(old, day, hits)));
        });
    }

    /** Drops {@code day} from the kind (a day that no longer exists). */
    public void dropDay(String kind, LocalDate day) {
        KindIndex ki = kinds.get(kind);
        if (ki != null) {
            locked(ki, () -> {
                TreeMap<LocalDate, BitSet> d = new TreeMap<>(ki.snap.get().days());
                if (d.remove(day) != null) {
                    ki.snap.set(purge(ki.snap.get(), d));
                }
            });
        }
    }

    /**
     * Declares the business dates the kind has. The newest {@code days} are the window: days outside it are dropped (a
     * business-date roll) and, per the warm mode, missing days inside it are loaded in the background newest first
     * (background) or on the first search for them (lazy). The newest day is expected to be put by the caller.
     */
    public void plan(String kind, NavigableSet<LocalDate> available) {
        DayIndexSettings s = settingsFor(kind);
        if (!s.multiDay() || available.isEmpty()) {
            return;
        }
        KindIndex ki = kinds.computeIfAbsent(kind, k -> new KindIndex());
        TreeSet<LocalDate> want = new TreeSet<>();
        for (LocalDate d : available.descendingSet()) {
            if (want.size() >= s.days()) {
                break;
            }
            want.add(d);
        }
        ki.wanted = want;
        locked(ki, () -> {
            Snap cur = ki.snap.get();
            TreeMap<LocalDate, BitSet> d = new TreeMap<>(cur.days());
            if (d.keySet().removeIf(x -> !want.contains(x))) {
                cur = purge(cur, d);
            }
            ki.snap.set(trimmed(kind, cur));
        });
        if (s.warm() == DayIndexSettings.Warm.BACKGROUND) {
            for (LocalDate d : want.descendingSet()) {
                if (!ki.snap.get().days().containsKey(d)) {
                    enqueue(kind, d);
                }
            }
        }
    }

    /** A past day was restated or a data load named it: its bitmap is rebuilt in the background (only if kept). */
    public void restated(String kind, LocalDate day) {
        KindIndex ki = kinds.get(kind);
        if (ki != null && ki.snap.get().days().containsKey(day)) {
            enqueue(kind, day);
        }
    }

    /** A data load that names no date: every kept day is rebuilt in the background, newest first. */
    public void restatedAll(String kind) {
        KindIndex ki = kinds.get(kind);
        if (ki != null) {
            ki.snap.get().days().descendingKeySet().forEach(d -> enqueue(kind, d));
        }
    }

    private void enqueue(String kind, LocalDate day) {
        if (loader == null) {
            return;
        }
        warmPending.incrementAndGet();
        warmQueue.addLast(() -> {
            try {
                putDay(kind, day, loader.load(kind, day));
            } catch (Exception e) {
                // the day stays unindexed (or as it was): searches for it fall back, the next plan tries again
            }
        });
        startDrain();
    }

    private void startDrain() {
        if (warming.compareAndSet(false, true)) {
            Thread.ofVirtual().name("dated-index-warm").start(this::drain);
        }
    }

    private void drain() {
        try {
            Runnable r;
            while ((r = warmQueue.pollFirst()) != null) {
                try {
                    r.run();
                } finally {
                    warmPending.decrementAndGet();
                }
            }
        } finally {
            warming.set(false);
            if (!warmQueue.isEmpty()) {
                startDrain();
            }
        }
    }

    /** Waits until the background queue is empty (tests and shutdown). */
    public boolean awaitWarm(long millis) throws InterruptedException {
        long end = System.nanoTime() + millis * 1_000_000;
        while (warmPending.get() > 0 || warming.get()) {
            if (System.nanoTime() > end) {
                return false;
            }
            Thread.sleep(5);
        }
        return true;
    }

    private static void locked(KindIndex ki, Runnable r) {
        ki.lock.lock();
        try {
            r.run();
        } finally {
            ki.lock.unlock();
        }
    }

    /** {@code s} cut to the kind's window and, with the other kinds, to the connector's memory cap (oldest days first). */
    private Snap trimmed(String kind, Snap s) {
        DayIndexSettings cfg = settingsFor(kind);
        Snap out = s;
        if (out.days().size() > cfg.days()) {
            TreeMap<LocalDate, BitSet> d = new TreeMap<>(out.days());
            while (d.size() > cfg.days()) {
                d.pollFirstEntry();
            }
            out = purge(out, d);
        }
        long cap = DayIndexSettings.of(settings, null).maxMemoryBytes();
        if (cap > 0) {
            long others = kinds.entrySet().stream().filter(e -> !e.getKey().equals(kind)).mapToLong(e -> memory(e.getValue().snap.get())).sum();
            while (out.days().size() > 1 && others + memory(out) > cap) {
                TreeMap<LocalDate, BitSet> d = new TreeMap<>(out.days());
                d.pollFirstEntry();
                out = purge(out, d);
            }
        }
        return out;
    }

    /** {@code old} with {@code day} set to {@code hits}: new ids are appended to the dictionary and merged into the order. */
    private static Snap with(Snap old, LocalDate day, Collection<EntityHit> hits) {
        Map<String, Integer> at = new HashMap<>(old.hits().length * 2);
        for (int i = 0; i < old.hits().length; i++) {
            at.put(old.hits()[i].ref().id(), i);
        }
        Map<String, EntityHit> distinct = new LinkedHashMap<>();
        hits.forEach(h -> distinct.put(h.ref().id(), h));
        BitSet bits = new BitSet(old.hits().length);
        List<EntityHit> fresh = new ArrayList<>();
        int next = old.hits().length;
        for (EntityHit h : distinct.values()) {
            Integer i = at.get(h.ref().id());
            if (i == null) {
                i = next++;
                fresh.add(h);
            }
            bits.set(i);
        }
        EntityHit[] hitArr = old.hits();
        String[] lower = old.lower();
        int[] order = old.order();
        if (!fresh.isEmpty()) {
            hitArr = Arrays.copyOf(hitArr, next);
            lower = Arrays.copyOf(lower, next);
            int[] added = new int[fresh.size()];
            for (int k = 0; k < fresh.size(); k++) {
                int i = old.hits().length + k;
                hitArr[i] = fresh.get(k);
                lower[i] = fresh.get(k).ref().id().toLowerCase(Locale.ROOT);
                added[k] = i;
            }
            order = merge(order, added, lower);
        }
        TreeMap<LocalDate, BitSet> days = new TreeMap<>(old.days());
        days.put(day, bits);
        return new Snap(lower, hitArr, order, days);
    }

    private static int[] merge(int[] order, int[] added, String[] lower) {
        Integer[] boxed = Arrays.stream(added).boxed().toArray(Integer[]::new);
        Arrays.sort(boxed, (a, b) -> lower[a].compareTo(lower[b]));
        int[] out = new int[order.length + boxed.length];
        int i = 0;
        int j = 0;
        int o = 0;
        while (i < order.length || j < boxed.length) {
            if (j >= boxed.length || i < order.length && lower[order[i]].compareTo(lower[boxed[j]]) <= 0) {
                out[o++] = order[i++];
            } else {
                out[o++] = boxed[j++];
            }
        }
        return out;
    }

    /** {@code s} with the given days, and the dictionary compacted to ids some kept day holds. */
    private static Snap purge(Snap s, TreeMap<LocalDate, BitSet> days) {
        BitSet union = new BitSet(s.hits().length);
        days.values().forEach(union::or);
        int keep = union.cardinality();
        if (keep == s.hits().length) {
            return new Snap(s.lower(), s.hits(), s.order(), days);
        }
        int[] remap = new int[s.hits().length];
        Arrays.fill(remap, -1);
        EntityHit[] hits = new EntityHit[keep];
        String[] lower = new String[keep];
        int n = 0;
        for (int i = union.nextSetBit(0); i >= 0; i = union.nextSetBit(i + 1)) {
            remap[i] = n;
            hits[n] = s.hits()[i];
            lower[n] = s.lower()[i];
            n++;
        }
        int[] order = new int[keep];
        int o = 0;
        for (int ord : s.order()) {
            if (remap[ord] >= 0) {
                order[o++] = remap[ord];
            }
        }
        TreeMap<LocalDate, BitSet> remapped = new TreeMap<>();
        days.forEach((d, b) -> {
            BitSet nb = new BitSet(keep);
            for (int i = b.nextSetBit(0); i >= 0; i = b.nextSetBit(i + 1)) {
                nb.set(remap[i]);
            }
            remapped.put(d, nb);
        });
        return new Snap(lower, hits, order, remapped);
    }

    // ---- reads (lock-free) ---------------------------------------------------------------------------------------

    /** True when the kind's {@code day} is indexed (so {@link #search} answers for it). */
    public boolean has(String kind, LocalDate day) {
        KindIndex ki = kinds.get(kind);
        return ki != null && ki.snap.get().days().containsKey(day);
    }

    /**
     * Hits of {@code kind} (null or blank: every kind with the day) on {@code day} matching {@code text}, ranked as
     * {@link HitIndex} does. Empty (not an empty list) when the day is not indexed here: the caller answers from its
     * newest-day index or loads the day on demand. In lazy mode a search for a kept-window day that is not built starts
     * building it.
     */
    public Optional<List<EntityHit>> search(String kind, String text, int limit, LocalDate day) {
        if (day == null || limit <= 0) {
            return Optional.empty();
        }
        String q = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        List<EntityHit> out = new ArrayList<>();
        boolean any = false;
        List<String> names = kind == null || kind.isBlank() ? kinds.keySet().stream().sorted().toList() : List.of(kind);
        for (String k : names) {
            KindIndex ki = kinds.get(k);
            if (ki == null) {
                continue;
            }
            Snap s = ki.snap.get();
            BitSet bits = s.days().get(day);
            if (bits == null) {
                if (ki.wanted.contains(day) && settingsFor(k).warm() == DayIndexSettings.Warm.LAZY) {
                    enqueue(k, day);
                }
                continue;
            }
            any = true;
            rank(s, bits, q, limit - out.size(), out);
            if (out.size() < limit && settingsFor(k).anyRecentDay() && day.equals(s.days().lastKey())) {
                recent(s, day, q, limit - out.size(), out);
            }
            if (out.size() >= limit) {
                break;
            }
        }
        return any ? Optional.of(out) : Optional.empty();
    }

    /** Ids seen on earlier kept days only, marked with the last day they were seen. */
    private static void recent(Snap s, LocalDate day, String q, int room, List<EntityHit> out) {
        BitSet others = new BitSet(s.hits().length);
        s.days().forEach((d, b) -> {
            if (!d.equals(day)) {
                others.or(b);
            }
        });
        others.andNot(s.days().get(day));
        List<EntityHit> found = new ArrayList<>();
        rank(s, others, q, room, found);
        for (EntityHit h : found) {
            int ord = ordinal(s, h.ref().id());
            LocalDate last = null;
            for (LocalDate d : s.days().descendingKeySet()) {
                if (ord >= 0 && s.days().get(d).get(ord)) {
                    last = d;
                    break;
                }
            }
            String sub = h.subtitle() == null || h.subtitle().isEmpty() ? "" : h.subtitle() + " · ";
            out.add(new EntityHit(h.ref(), h.title(), sub + "last seen " + last));
        }
    }

    private static int ordinal(Snap s, String id) {
        String lo = id.toLowerCase(Locale.ROOT);
        for (int i = lowerBound(s, lo); i < s.order().length && s.lower()[s.order()[i]].equals(lo); i++) {
            if (s.hits()[s.order()[i]].ref().id().equals(id)) {
                return s.order()[i];
            }
        }
        return -1;
    }

    private static void rank(Snap s, BitSet bits, String q, int room, List<EntityHit> out) {
        if (room <= 0) {
            return;
        }
        int start = out.size();
        int[] order = s.order();
        for (int i = lowerBound(s, q); i < order.length && out.size() - start < room && s.lower()[order[i]].startsWith(q); i++) {
            if (bits.get(order[i])) {
                out.add(s.hits()[order[i]]);
            }
        }
        if (q.isEmpty() || out.size() - start >= room) {
            return;
        }
        for (int rank = 1; rank <= 2 && out.size() - start < room; rank++) {
            for (int i = 0; i < order.length && out.size() - start < room; i++) {
                int ord = order[i];
                if (!bits.get(ord)) {
                    continue;
                }
                String id = s.lower()[ord];
                boolean hit = rank == 1 ? !id.startsWith(q) && id.contains(q) : !id.contains(q) && text(s.hits()[ord]).contains(q);
                if (hit) {
                    out.add(s.hits()[ord]);
                }
            }
        }
    }

    private static String text(EntityHit h) {
        return ((h.title() == null ? "" : h.title()) + " " + (h.subtitle() == null ? "" : h.subtitle())).toLowerCase(Locale.ROOT);
    }

    private static int lowerBound(Snap s, String q) {
        int lo = 0;
        int hi = s.order().length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (s.lower()[s.order()[mid]].compareTo(q) < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    // ---- stats ---------------------------------------------------------------------------------------------------

    /** A rough size of one kind's snapshot: dictionary strings and hits, the order array and the bitmaps. */
    private static long memory(Snap s) {
        long m = 0;
        for (EntityHit h : s.hits()) {
            m += 120 + 2L * h.ref().id().length() + 2L * (h.title() == null ? 0 : h.title().length());
        }
        m += 4L * s.order().length;
        for (BitSet b : s.days().values()) {
            m += b.size() / 8 + 32;
        }
        return m;
    }

    public Stats stats() {
        int ids = 0;
        int days = 0;
        long mem = 0;
        TreeSet<LocalDate> all = new TreeSet<>();
        for (KindIndex ki : kinds.values()) {
            Snap s = ki.snap.get();
            ids += s.hits().length;
            days = Math.max(days, s.days().size());
            mem += memory(s);
            all.addAll(s.days().keySet());
        }
        int pending = warmPending.get();
        String warm = pending > 0 ? "warming (" + pending + " days to build)" : "warm";
        return new Stats(kinds.size(), days, ids, mem, warm, new ArrayList<>(all.descendingSet()));
    }

    /** One line for a connector's health: {@code type-ahead index: 5 days, 500,000 ids, 12.3 MB, warm}. */
    public String summary() {
        Stats st = stats();
        return String.format(Locale.ROOT, "type-ahead index: %d days, %,d ids, %.1f MB, %s", st.days(), st.ids(), st.memoryBytes() / 1048576.0, st.warm());
    }

    /** The stats as a plain map for health and the connector page. */
    public Map<String, Object> health() {
        Stats st = stats();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("indexDays", st.days());
        m.put("indexIds", st.ids());
        m.put("indexMemoryMb", Math.round(st.memoryBytes() / 1048.576) / 1000.0);
        m.put("indexWarm", st.warm());
        return m;
    }
}
