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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.stream.Stream;

/**
 * Type-ahead over several business days. Every lookup is by a concrete business date: the index is a map of date to day
 * segment, and "live" or "current" is resolved to the kind's newest date before it gets here (a {@code null} date is
 * answered as the newest date, never as a separate key). A kind with no dates uses {@link #UNDATED}.
 *
 * <p>Tiers. The newest day and, by default, the closed days live on the heap: per kind one dictionary of ids, each stored
 * once and sorted for prefix binary search, and one compact bitmap per day. When {@link DayIndexSettings#maxMemoryBytes}
 * is reached (or {@code index.storage: mmap} says so) a closed day moves to a {@link DaySegment}: a file of sorted,
 * block-compressed ids read through a memory map, so its prefix lookups stay fast with nothing on the heap. A day neither
 * tier holds is built on demand by the caller's fallback. Segment files live under {@code drishti.index.dir} (default
 * {@code <data dir>/index}), are capped by {@code index.max-disk}, record the source watermark they were built from and
 * are reused after a restart only while it is unchanged.
 *
 * <p>Reads are lock-free (immutable snapshots that writers swap); writers (the newest day's refresh, a restated past day,
 * the background warm-up) take a per-kind lock. When the business date rolls, a new day is added, the oldest day (heap
 * bitmap or file) is dropped, and ids no kept day holds are purged, so memory and disk stay flat.
 *
 * <p>A connector that keeps one day ({@code days} 1) does not use this class: {@link #search} answers empty and the
 * connector's own {@link HitIndex} serves, as before.
 */
public final class DatedHitIndex {

    /** The key of a kind with no business dates. */
    public static final LocalDate UNDATED = LocalDate.MIN;

    /** Reads the ids of one kind on one business day (a table query, a folder listing). */
    @FunctionalInterface
    public interface DayLoader {
        List<EntityHit> load(String kind, LocalDate day) throws Exception;
    }

    /** The version of the source a day was read from (a load time, a table version); null when unknown. */
    @FunctionalInterface
    public interface Watermark {
        String of(String kind, LocalDate day);
    }

    /** What Health shows. */
    public record Stats(int kinds, int days, int ids, long memoryBytes, String warm, List<LocalDate> indexedDays, int diskDays, long diskBytes) {}

    /** An immutable version of one kind: dictionary (parallel arrays by ordinal), id order, and a bitmap per heap day. */
    private record Snap(String[] lower, EntityHit[] hits, int[] order, TreeMap<LocalDate, BitSet> days) {
        static final Snap EMPTY = new Snap(new String[0], new EntityHit[0], new int[0], new TreeMap<>());
    }

    private static final class KindIndex {
        final ReentrantLock lock = new ReentrantLock();
        final AtomicReference<Snap> snap = new AtomicReference<>(Snap.EMPTY);
        final AtomicReference<TreeMap<LocalDate, DaySegment>> spilled = new AtomicReference<>(new TreeMap<>());
        final Map<LocalDate, String> marks = new HashMap<>();            // the source watermark each day was read at (under lock)
        volatile NavigableSet<LocalDate> wanted = new TreeSet<>();

        LocalDate newest() {
            Snap s = snap.get();
            TreeMap<LocalDate, DaySegment> sp = spilled.get();
            LocalDate a = s.days().isEmpty() ? null : s.days().lastKey();
            LocalDate b = sp.isEmpty() ? null : sp.lastKey();
            return a == null ? b : b == null ? a : a.isAfter(b) ? a : b;
        }
    }

    private final Map<String, KindIndex> kinds = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<Runnable> warmQueue = new ConcurrentLinkedDeque<>();
    private final AtomicBoolean warming = new AtomicBoolean();
    private final AtomicInteger warmPending = new AtomicInteger();
    private final DayLoader loader;
    private final Watermark watermark;
    private final Path dir;                                              // this connector's segment folder, or null (no disk tier)
    private volatile Map<String, String> settings;

    /** An index with no disk tier (tests, connectors that hold days on the heap only). */
    public DatedHitIndex(Map<String, String> settings, DayLoader loader) {
        this(null, null, settings, loader, null);
    }

    /**
     * @param namespace names this connector's folder under {@code root}; null with a null {@code root} for no disk tier
     * @param root the index folder ({@link #defaultRoot()})
     * @param watermark the source version of a day, so a restart can reuse a file; null never reuses
     */
    public DatedHitIndex(String namespace, Path root, Map<String, String> settings, DayLoader loader, Watermark watermark) {
        this.settings = Map.copyOf(settings);
        this.loader = loader;
        this.watermark = watermark;
        this.dir = root == null || namespace == null ? null : root.resolve(safe(namespace));
    }

    /** {@code drishti.index.dir}, default {@code <data dir>/index} (never the temporary folder). */
    public static Path defaultRoot() {
        String p = System.getProperty("drishti.index.dir");
        return Path.of(p == null || p.isBlank() ? DataDir.under("index") : p).toAbsolutePath().normalize();
    }

    private static String safe(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** The settings that apply to {@code kind}. */
    public DayIndexSettings settingsFor(String kind) {
        return DayIndexSettings.of(settings, kind);
    }

    /** True when any kind keeps more than one day (so a connector can skip all of this at the default). */
    public boolean enabled() {
        return DayIndexSettings.of(settings, null).multiDay()
                || settings.keySet().stream().anyMatch(k -> k.startsWith("index.kinds.") && k.endsWith(".days"));
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

    /** Applies new settings live: windows shrink at once (a grown window fills as {@link #plan} is next called). */
    public void reconfigure(Map<String, String> next) {
        this.settings = Map.copyOf(next);
        kinds.forEach((kind, ki) -> locked(ki, () -> commit(kind, ki, ki.snap.get())));
    }

    private Path segmentPath(String kind, LocalDate day) {
        return dir.resolve(safe(kind) + "-" + (UNDATED.equals(day) ? "undated" : day.toString()) + ".seg");
    }

    // ---- writes ------------------------------------------------------------------------------------------------

    /**
     * Sets the ids of {@code kind} on {@code day} (the newest day's refresh, a restated day, a warmed day), then keeps
     * only the newest {@code days} days and purges ids no kept day holds. Ignored when {@code day} is older than the whole
     * kept window.
     */
    public void putDay(String kind, LocalDate day, Collection<EntityHit> hits) {
        put(kind, day, hits, watermark == null ? null : watermark.of(kind, day));
    }

    private void put(String kind, LocalDate day, Collection<EntityHit> hits, String mark) {
        KindIndex ki = kinds.computeIfAbsent(kind, k -> new KindIndex());
        locked(ki, () -> {
            Snap old = ki.snap.get();
            TreeMap<LocalDate, DaySegment> sp = ki.spilled.get();
            TreeSet<LocalDate> all = union(old, sp);
            DayIndexSettings cfg = settingsFor(kind);
            if (all.size() >= cfg.days() && day.isBefore(all.first()) && !all.contains(day)) {
                return;
            }
            LocalDate newest = all.isEmpty() ? null : all.last();
            boolean toDisk = dir != null
                    && (sp.containsKey(day) || cfg.storage() == DayIndexSettings.Storage.MMAP && newest != null && day.isBefore(newest));
            ki.marks.put(day, mark);
            if (toDisk) {
                try {
                    DaySegment seg = DaySegment.write(segmentPath(kind, day), mark, new ArrayList<>(distinct(hits).values()));
                    TreeMap<LocalDate, DaySegment> next = new TreeMap<>(sp);
                    next.put(day, seg);
                    ki.spilled.set(next);
                    Snap cur = old;
                    if (old.days().containsKey(day)) {
                        TreeMap<LocalDate, BitSet> d = new TreeMap<>(old.days());
                        d.remove(day);
                        cur = purge(old, d);
                    }
                    commit(kind, ki, cur);
                    return;
                } catch (java.io.UncheckedIOException e) {
                    // the disk refused: this day goes on the heap instead
                }
            }
            commit(kind, ki, with(old, day, hits));
        });
    }

    /** Drops {@code day} from the kind (a day that no longer exists). */
    public void dropDay(String kind, LocalDate day) {
        KindIndex ki = kinds.get(kind);
        if (ki != null) {
            locked(ki, () -> {
                TreeMap<LocalDate, DaySegment> sp = new TreeMap<>(ki.spilled.get());
                DaySegment seg = sp.remove(day);
                if (seg != null) {
                    seg.delete();
                    ki.spilled.set(sp);
                }
                TreeMap<LocalDate, BitSet> d = new TreeMap<>(ki.snap.get().days());
                Snap cur = ki.snap.get();
                if (d.remove(day) != null) {
                    cur = purge(cur, d);
                }
                ki.marks.remove(day);
                ki.snap.set(cur);
            });
        }
    }

    /**
     * Declares the business dates the kind has. The newest {@code days} are the window: days outside it are dropped (a
     * business-date roll: the oldest day's bitmap or file goes) and a segment file left by an earlier run is reused when
     * its watermark still matches the source; per the warm mode, other missing days inside the window are loaded in the
     * background newest first (background) or on the first search for them (lazy). The caller puts the newest day.
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
            TreeMap<LocalDate, DaySegment> sp = new TreeMap<>(ki.spilled.get());
            sp.entrySet().removeIf(e -> {
                boolean out = !want.contains(e.getKey());
                if (out) {
                    e.getValue().delete();
                }
                return out;
            });
            adoptFiles(kind, want, cur, sp, ki);
            ki.spilled.set(sp);
            commit(kind, ki, cur);
        });
        if (s.warm() == DayIndexSettings.Warm.BACKGROUND) {
            for (LocalDate d : want.descendingSet()) {
                if (!has(kind, d)) {
                    enqueue(kind, d);
                }
            }
        }
    }

    /** Reuses the files an earlier run left whose watermark still matches; removes stray and temporary files. */
    private void adoptFiles(String kind, TreeSet<LocalDate> want, Snap cur, TreeMap<LocalDate, DaySegment> sp, KindIndex ki) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        for (LocalDate d : want) {
            if (cur.days().containsKey(d) || sp.containsKey(d) || watermark == null) {
                continue;
            }
            String now = watermark.of(kind, d);
            Path f = segmentPath(kind, d);
            Optional<DaySegment> seg = now == null ? Optional.empty() : DaySegment.open(f);
            if (seg.isPresent() && now.equals(seg.get().watermark())) {
                sp.put(d, seg.get());
                ki.marks.put(d, now);
            } else if (Files.exists(f)) {
                try {
                    Files.deleteIfExists(f);                            // the source changed since: rebuilt below
                } catch (IOException e) {
                    // retried at the next plan
                }
            }
        }
        String prefix = safe(kind) + "-";
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.toList()) {
                String n = f.getFileName().toString();
                boolean stray = n.contains(".tmp-")
                        || n.startsWith(prefix) && n.endsWith(".seg") && sp.values().stream().noneMatch(x -> x.file().equals(f))
                                && want.stream().noneMatch(d -> segmentPath(kind, d).equals(f));
                if (stray) {
                    Files.deleteIfExists(f);                            // a crash mid-write, or a day that rolled out
                }
            }
        } catch (IOException e) {
            // cleaned at the next plan
        }
    }

    /** A past day was restated or a data load named it: its bitmap or file is rebuilt in the background (only if kept). */
    public void restated(String kind, LocalDate day) {
        if (has(kind, day)) {
            enqueue(kind, day);
        }
    }

    /** A data load that names no date: every kept day is rebuilt in the background, newest first. */
    public void restatedAll(String kind) {
        KindIndex ki = kinds.get(kind);
        if (ki != null) {
            union(ki.snap.get(), ki.spilled.get()).descendingSet().forEach(d -> enqueue(kind, d));
        }
    }

    private void enqueue(String kind, LocalDate day) {
        if (loader == null) {
            return;
        }
        warmPending.incrementAndGet();
        warmQueue.addLast(() -> {
            try {
                String mark = watermark == null ? null : watermark.of(kind, day);       // read before the data: never newer than it
                put(kind, day, loader.load(kind, day), mark);
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

    private static TreeSet<LocalDate> union(Snap s, Map<LocalDate, DaySegment> sp) {
        TreeSet<LocalDate> all = new TreeSet<>(s.days().keySet());
        all.addAll(sp.keySet());
        return all;
    }

    private static Map<String, EntityHit> distinct(Collection<EntityHit> hits) {
        Map<String, EntityHit> distinct = new LinkedHashMap<>();
        hits.forEach(h -> distinct.put(h.ref().id(), h));
        return distinct;
    }

    /**
     * Publishes {@code s} for the kind after applying the window (oldest days out, their files deleted), the storage mode
     * (closed days to files) and the memory and disk caps (oldest closed days to files, then out). Under the kind's lock.
     */
    private void commit(String kind, KindIndex ki, Snap s) {
        DayIndexSettings cfg = settingsFor(kind);
        Snap out = s;
        TreeMap<LocalDate, DaySegment> sp = new TreeMap<>(ki.spilled.get());
        TreeSet<LocalDate> all = union(out, sp);
        while (all.size() > cfg.days()) {
            LocalDate oldest = all.pollFirst();
            DaySegment seg = sp.remove(oldest);
            if (seg != null) {
                seg.delete();
            } else {
                TreeMap<LocalDate, BitSet> d = new TreeMap<>(out.days());
                d.remove(oldest);
                out = purge(out, d);
            }
            ki.marks.remove(oldest);
        }
        LocalDate newest = all.isEmpty() ? null : all.last();
        if (dir != null && cfg.storage() == DayIndexSettings.Storage.MMAP) {
            for (LocalDate d : new ArrayList<>(out.days().keySet())) {
                if (!d.equals(newest)) {
                    out = spill(kind, ki, out, sp, d);
                }
            }
        }
        long cap = DayIndexSettings.of(settings, null).maxMemoryBytes();
        if (cap > 0) {
            long others = kinds.entrySet().stream().filter(e -> !e.getKey().equals(kind)).mapToLong(e -> memory(e.getValue().snap.get())).sum();
            while (others + memory(out) > cap) {
                LocalDate oldest = out.days().keySet().stream().filter(d -> !d.equals(newest)).findFirst().orElse(null);
                if (oldest == null) {
                    break;
                }
                out = dir != null ? spill(kind, ki, out, sp, oldest) : dropHeap(out, oldest, ki);
            }
        }
        long diskCap = cfg.maxDiskBytes();
        if (dir != null && diskCap > 0) {
            long others = kinds.entrySet().stream().filter(e -> !e.getKey().equals(kind)).mapToLong(e -> diskBytes(e.getValue().spilled.get())).sum();
            while (!sp.isEmpty() && others + diskBytes(sp) > diskCap) {
                sp.pollFirstEntry().getValue().delete();                 // the oldest closed day goes; a search for it falls back
            }
        }
        ki.spilled.set(sp);
        ki.snap.set(out);
    }

    private static Snap dropHeap(Snap s, LocalDate day, KindIndex ki) {
        TreeMap<LocalDate, BitSet> d = new TreeMap<>(s.days());
        d.remove(day);
        ki.marks.remove(day);
        return purge(s, d);
    }

    /** Moves a heap day to a segment file (or drops it when the disk refuses). */
    private Snap spill(String kind, KindIndex ki, Snap s, TreeMap<LocalDate, DaySegment> sp, LocalDate day) {
        BitSet bits = s.days().get(day);
        List<EntityHit> hits = new ArrayList<>(bits.cardinality());
        for (int i = bits.nextSetBit(0); i >= 0; i = bits.nextSetBit(i + 1)) {
            hits.add(s.hits()[i]);
        }
        try {
            sp.put(day, DaySegment.write(segmentPath(kind, day), ki.marks.get(day), hits));
        } catch (java.io.UncheckedIOException e) {
            return dropHeap(s, day, ki);
        }
        TreeMap<LocalDate, BitSet> d = new TreeMap<>(s.days());
        d.remove(day);
        return purge(s, d);
    }

    private static long diskBytes(Map<LocalDate, DaySegment> sp) {
        return sp.values().stream().mapToLong(DaySegment::bytes).sum();
    }

    /** {@code old} with {@code day} set to {@code hits}: new ids are appended to the dictionary and merged into the order. */
    private static Snap with(Snap old, LocalDate day, Collection<EntityHit> hits) {
        Map<String, Integer> at = new HashMap<>(old.hits().length * 2);
        for (int i = 0; i < old.hits().length; i++) {
            at.put(old.hits()[i].ref().id(), i);
        }
        BitSet bits = new BitSet(old.hits().length);
        List<EntityHit> fresh = new ArrayList<>();
        int next = old.hits().length;
        for (EntityHit h : distinct(hits).values()) {
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

    /** True when the kind's {@code day} is indexed, on the heap or on disk (so {@link #search} answers for it). */
    public boolean has(String kind, LocalDate day) {
        KindIndex ki = kinds.get(kind);
        return ki != null && (ki.snap.get().days().containsKey(day) || ki.spilled.get().containsKey(day));
    }

    /** True when the day is held as a memory-mapped segment file. */
    public boolean onDisk(String kind, LocalDate day) {
        KindIndex ki = kinds.get(kind);
        return ki != null && ki.spilled.get().containsKey(day);
    }

    /** The kind's newest indexed date: what "live" or "current" resolves to here. */
    public Optional<LocalDate> newest(String kind) {
        KindIndex ki = kinds.get(kind);
        return ki == null ? Optional.empty() : Optional.ofNullable(ki.newest());
    }

    /**
     * Hits of {@code kind} (null or blank: every kind with the day) on {@code day} matching {@code text}, ranked as
     * {@link HitIndex} does. A {@code null} day is the kind's newest date. Empty (not an empty list) when the day is not
     * indexed here: the caller answers from its newest-day index or loads the day on demand. In lazy mode a search for a
     * kept-window day that is not built starts building it.
     */
    public Optional<List<EntityHit>> search(String kind, String text, int limit, LocalDate day) {
        if (limit <= 0) {
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
            TreeMap<LocalDate, DaySegment> sp = ki.spilled.get();
            LocalDate d = day != null ? day : ki.newest();
            if (d == null) {
                continue;
            }
            if (!searchDay(s, sp, d, q, limit - out.size(), out)) {
                if (ki.wanted.contains(d) && settingsFor(k).warm() == DayIndexSettings.Warm.LAZY) {
                    enqueue(k, d);
                }
                continue;
            }
            any = true;
            if (out.size() < limit && settingsFor(k).anyRecentDay() && d.equals(ki.newest())) {
                recent(s, sp, d, q, limit - out.size(), out);
            }
            if (out.size() >= limit) {
                break;
            }
        }
        return any ? Optional.of(out) : Optional.empty();
    }

    /** Appends the day's matches from whichever tier holds it; false when neither does. */
    private static boolean searchDay(Snap s, Map<LocalDate, DaySegment> sp, LocalDate day, String q, int room, List<EntityHit> out) {
        BitSet bits = s.days().get(day);
        if (bits != null) {
            rank(s, bits, q, room, out);
            return true;
        }
        DaySegment seg = sp.get(day);
        if (seg != null) {
            seg.search(q, room, out);
            return true;
        }
        return false;
    }

    private static boolean holds(Snap s, Map<LocalDate, DaySegment> sp, LocalDate day, String id) {
        BitSet bits = s.days().get(day);
        if (bits != null) {
            int ord = ordinal(s, id);
            return ord >= 0 && bits.get(ord);
        }
        DaySegment seg = sp.get(day);
        if (seg == null) {
            return false;
        }
        List<EntityHit> probe = new ArrayList<>();
        seg.search(id.toLowerCase(Locale.ROOT), 4, probe);
        return probe.stream().anyMatch(h -> h.ref().id().equals(id));
    }

    /** Ids seen on earlier kept days only, marked with the last day they were seen, in id order. */
    private static void recent(Snap s, Map<LocalDate, DaySegment> sp, LocalDate day, String q, int room, List<EntityHit> out) {
        Map<String, EntityHit> extra = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (LocalDate d : union(s, sp).descendingSet()) {
            if (d.equals(day)) {
                continue;
            }
            List<EntityHit> found = new ArrayList<>();
            searchDay(s, sp, d, q, room * 3 + 20, found);
            for (EntityHit h : found) {
                if (!extra.containsKey(h.ref().id()) && !holds(s, sp, day, h.ref().id())) {
                    String sub = h.subtitle() == null || h.subtitle().isEmpty() ? "" : h.subtitle() + " · ";
                    extra.put(h.ref().id(), new EntityHit(h.ref(), h.title(), sub + "last seen " + d));
                }
            }
        }
        extra.values().stream().limit(room).forEach(out::add);
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

    /** A rough size of one kind's heap snapshot: dictionary strings and hits, the order array and the bitmaps. */
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
        int diskDays = 0;
        long mem = 0;
        long disk = 0;
        TreeSet<LocalDate> all = new TreeSet<>();
        for (KindIndex ki : kinds.values()) {
            Snap s = ki.snap.get();
            TreeMap<LocalDate, DaySegment> sp = ki.spilled.get();
            ids += s.hits().length;
            for (DaySegment seg : sp.values()) {
                ids += seg.count();
                disk += seg.bytes();
            }
            diskDays += sp.size();
            days = Math.max(days, union(s, sp).size());
            mem += memory(s);
            all.addAll(union(s, sp));
        }
        int pending = warmPending.get();
        String warm = pending > 0 ? "warming (" + pending + " days to build)" : "warm";
        return new Stats(kinds.size(), days, ids, mem, warm, new ArrayList<>(all.descendingSet()), diskDays, disk);
    }

    /** One line for a connector's health: {@code type-ahead index: 5 days (2 on disk), 500,000 ids, 12.3 MB, warm}. */
    public String summary() {
        Stats st = stats();
        String disk = st.diskDays() == 0 ? "" : String.format(Locale.ROOT, " (%d on disk, %.1f MB)", st.diskDays(), st.diskBytes() / 1048576.0);
        return String.format(Locale.ROOT, "type-ahead index: %d days%s, %,d ids, %.1f MB, %s", st.days(), disk, st.ids(), st.memoryBytes() / 1048576.0,
                st.warm());
    }

    /** The stats as a plain map for health and the connector page. */
    public Map<String, Object> health() {
        Stats st = stats();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("indexDays", st.days());
        m.put("indexDaysOnDisk", st.diskDays());
        m.put("indexIds", st.ids());
        m.put("indexMemoryMb", Math.round(st.memoryBytes() / 1048.576) / 1000.0);
        m.put("indexDiskMb", Math.round(st.diskBytes() / 1048.576) / 1000.0);
        m.put("indexWarm", st.warm());
        return m;
    }
}
