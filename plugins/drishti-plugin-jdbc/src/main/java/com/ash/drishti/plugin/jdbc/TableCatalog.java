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
package com.ash.drishti.plugin.jdbc;

import com.ash.drishti.api.ColumnSet;
import com.ash.drishti.api.EntityHit;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.HitIndex;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.function.Predicate;

/**
 * What table mode keeps in memory so that a table of a million entities a business day over years answers quickly
 * (see {@code docs/POSTGRES_CONNECTOR.md}): each kind's business dates (from the loader's dates table, else a skip
 * scan of the index), the newest day's ids for type-ahead (read from the index alone), and days of promoted columns
 * for searches, pick lists, derived kinds, impact and reverse lookups, read in parallel by id range and kept by memory.
 * Nothing here ever reads a document.
 */
final class TableCatalog {

    /** Work on a pooled connection. */
    @FunctionalInterface
    interface Work<T> {
        T run(Connection c) throws Exception;
    }

    /** The connector's connection pool. */
    interface Db {
        <T> T with(Work<T> work) throws Exception;
    }

    private record DayKey(String kind, LocalDate day) {}

    private static final System.Logger LOG = System.getLogger(TableCatalog.class.getName());

    private final EntityTable table;
    private final Db db;
    private final String sourceName;
    private final Predicate<String> effective;
    private final int lookbackDays;
    private final Map<String, List<String>> promoted;                  // kind -> paths the pack declares
    private final int scanThreads;
    private final HitIndex index = new HitIndex();
    private final Map<String, List<EntityHit>> hitsByKind = new ConcurrentHashMap<>();
    private final Map<String, Object> indexedFrom = new ConcurrentHashMap<>();   // kind -> the day (or load) its ids came from
    private final Cache<DayKey, ColumnSet> columnSets;
    private final Semaphore dayReads = new Semaphore(2);
    private volatile Map<String, NavigableSet<LocalDate>> dates = Map.of();
    private volatile Map<String, Boolean> present = Map.of();          // promoted column -> number
    private volatile Instant loadedAt;
    private volatile String problem;

    TableCatalog(EntityTable table, Db db, String sourceName, Predicate<String> effective, int lookbackDays, Map<String, List<String>> promoted,
            int scanThreads, long columnsCacheMb, Duration columnsTtl) {
        this.table = table;
        this.db = db;
        this.sourceName = sourceName;
        this.effective = effective;
        this.lookbackDays = lookbackDays;
        this.promoted = Map.copyOf(promoted);
        this.scanThreads = Math.max(1, scanThreads);
        this.columnSets = Caffeine.newBuilder().maximumWeight(columnsCacheMb * 1024 * 1024)
                .weigher((DayKey k, ColumnSet v) -> (int) Math.min(Integer.MAX_VALUE, weight(v))).expireAfterWrite(columnsTtl).build();
    }

    /**
     * Re-reads the dates and the table's columns; when a kind's newest day (or the last load) changed, its ids are read
     * again for type-ahead and the newest day's columns are loaded in the background. False when the database did not
     * answer (the last catalogue stays).
     */
    boolean refresh(Collection<String> configuredKinds) {
        Map<String, NavigableSet<LocalDate>> ds;
        Instant loaded;
        try {
            Object[] got = db.with(c -> {
                boolean keeps = table.hasDates(c);
                Map<String, NavigableSet<LocalDate>> d = table.dates(c, keeps, configuredKinds.isEmpty() ? table.kinds(c) : configuredKinds);
                return new Object[] {d, keeps ? table.loadedAt(c) : null, PostgresLayout.columns(c, table.schema(), table.name())};
            });
            @SuppressWarnings("unchecked")
            Map<String, NavigableSet<LocalDate>> d = (Map<String, NavigableSet<LocalDate>>) got[0];
            ds = d;
            loaded = (Instant) got[1];
            @SuppressWarnings("unchecked")
            Map<String, Boolean> cols = (Map<String, Boolean>) got[2];
            present = Map.copyOf(cols);
        } catch (Exception e) {
            problem = "catalogue not refreshed: " + e.getMessage();
            LOG.log(System.Logger.Level.WARNING, "{0}: {1}", sourceName, problem);
            return false;
        }
        if (!configuredKinds.isEmpty()) {
            ds.keySet().retainAll(configuredKinds);
        }
        if (!Objects.equals(loaded, loadedAt)) {
            columnSets.invalidateAll();                                 // a load replaced days: read them again
        }
        dates = ds;
        loadedAt = loaded;
        problem = null;
        boolean changed = false;
        for (var e : ds.entrySet()) {
            String kind = e.getKey();
            if (e.getValue().isEmpty()) {
                continue;
            }
            Object from = effective.test(kind) ? loaded : e.getValue().last() + "@" + loaded;
            if (from == null || !from.equals(indexedFrom.get(kind))) {
                List<EntityHit> hits = new ArrayList<>();
                LocalDate day = effective.test(kind) ? null : e.getValue().last();
                try {
                    db.with(c -> {
                        table.ids(c, kind, day, id -> hits.add(new EntityHit(EntityRef.of(kind, id), id, kind + " · " + sourceName)));
                        return null;
                    });
                } catch (Exception ex) {
                    problem = "ids of " + kind + " not read: " + ex.getMessage();
                    LOG.log(System.Logger.Level.WARNING, "{0}: {1}", sourceName, problem);
                    continue;
                }
                hitsByKind.put(kind, hits);
                if (from != null) {
                    indexedFrom.put(kind, from);
                }
                changed = true;
            }
        }
        if (changed) {
            hitsByKind.keySet().retainAll(ds.keySet());
            List<EntityHit> all = new ArrayList<>();
            hitsByKind.values().forEach(all::addAll);
            index.replaceAll(all);
        }
        warm();
        return true;
    }

    /** The newest day's columns of every laid-out kind, loaded in the background so the first search finds them. */
    private void warm() {
        dates.forEach((kind, ds) -> {
            Set<String> cols = columnar(kind);
            if (!ds.isEmpty() && !cols.isEmpty() && columnSets.getIfPresent(new DayKey(kind, ds.last())) == null) {
                Thread.ofVirtual().name("postgres-warm-" + kind).start(() -> {
                    try {
                        Objects.requireNonNull(columnSets.get(new DayKey(kind, ds.last()), k -> readDay(kind, ds.last(), List.copyOf(cols))));
                    } catch (RuntimeException e) {
                        // the first search loads it instead
                    }
                });
            }
        });
    }

    Set<String> kinds() {
        return dates.keySet();
    }

    boolean known(String kind) {
        return dates.containsKey(kind);
    }

    /** Why the last refresh failed, or null. */
    String problem() {
        return problem;
    }

    Instant loadedAt() {
        return loadedAt;
    }

    List<EntityHit> search(String kind, String text, int limit) {
        return index.search(kind, text, limit);
    }

    /** The kind's newest business date on or before {@code asked}, within the lookback (snapshot kinds). */
    Optional<LocalDate> snapshotDate(String kind, LocalDate asked) {
        NavigableSet<LocalDate> ds = dates.get(kind);
        if (ds == null || ds.isEmpty()) {
            return Optional.empty();
        }
        LocalDate d = asked == null ? ds.last() : ds.floor(asked);
        if (d == null || asked != null && d.isBefore(asked.minusDays(lookbackDays))) {
            return Optional.empty();
        }
        return Optional.of(d);
    }

    /** The paths the pack promotes for the kind that the table has as columns (none for effective kinds). */
    Set<String> columnar(String kind) {
        if (effective.test(kind)) {
            return Set.of();
        }
        Set<String> out = new java.util.LinkedHashSet<>();
        for (String p : promoted.getOrDefault(kind, List.of())) {
            if (present.containsKey(PostgresLayout.column(p))) {
                out.add(p);
            }
        }
        return out;
    }

    /** Kinds whose declared columns the table does not have, for Health ({@code trade (0 of 19 columns)}). */
    List<String> notLaidOut() {
        List<String> out = new ArrayList<>();
        promoted.forEach((kind, paths) -> {
            if (dates.containsKey(kind) && !effective.test(kind) && columnar(kind).size() < paths.size()) {
                out.add(kind + " (" + columnar(kind).size() + " of " + paths.size() + " columns)");
            }
        });
        return out;
    }

    /** A day of the kind's promoted columns: empty when the table does not hold the day (another store may). */
    Optional<ColumnSet> columns(String kind, Collection<String> paths, LocalDate asked) {
        Set<String> have = columnar(kind);
        if (!have.containsAll(paths)) {
            return Optional.empty();
        }
        Optional<LocalDate> day = snapshotDate(kind, asked);
        if (day.isEmpty()) {
            return Optional.empty();
        }
        ColumnSet all = columnSets.get(new DayKey(kind, day.get()), k -> readDay(kind, day.get(), List.copyOf(have)));
        if (all == null || all.size() == 0) {
            return Optional.empty();
        }
        Map<String, double[]> nums = new LinkedHashMap<>();
        Map<String, String[]> texts = new LinkedHashMap<>();
        for (String p : paths) {
            if (all.numbers().containsKey(p)) {
                nums.put(p, all.numbers().get(p));
            } else if (all.texts().containsKey(p)) {
                texts.put(p, all.texts().get(p));
            }
        }
        return Optional.of(new ColumnSet(all.ids(), nums, texts, all.businessDate()));
    }

    /** Entities of the kind whose promoted text columns hold {@code target} on the day; empty when the kind has none. */
    Optional<Set<String>> referrers(String kind, String target, LocalDate asked) {
        Set<String> cols = columnar(kind);
        if (cols.isEmpty() || snapshotDate(kind, asked).isEmpty()) {
            return Optional.empty();
        }
        Optional<ColumnSet> c = columns(kind, cols, asked);
        if (c.isEmpty()) {
            return Optional.empty();
        }
        Set<String> found = new TreeSet<>();
        c.get().texts().values().forEach(values -> {
            for (int i = 0; i < values.length; i++) {
                if (target.equals(values[i])) {
                    found.add(c.get().ids()[i]);
                }
            }
        });
        return Optional.of(found);
    }

    /** One id range's rows, column by column. */
    private static final class Chunk {
        final List<String> ids = new ArrayList<>();
        final double[][] numbers;
        final List<List<String>> texts = new ArrayList<>();
        int size;

        Chunk(int numberColumns, int textColumns) {
            numbers = new double[numberColumns][1024];
            for (int i = 0; i < textColumns; i++) {
                texts.add(new ArrayList<>());
            }
        }
    }

    /**
     * A day's ids and promoted columns: the day's ids are cut into {@code scan-threads} ranges at percentiles read from
     * the index, and the ranges are read at once on as many connections, at most two days at a time.
     */
    private ColumnSet readDay(String kind, LocalDate day, List<String> paths) {
        List<String> numberPaths = paths.stream().filter(p -> Boolean.TRUE.equals(present.get(PostgresLayout.column(p)))).toList();
        List<String> textPaths = paths.stream().filter(p -> !numberPaths.contains(p)).toList();
        List<String> columns = new ArrayList<>();
        numberPaths.forEach(p -> columns.add(PostgresLayout.column(p)));
        textPaths.forEach(p -> columns.add(PostgresLayout.column(p)));
        dayReads.acquireUninterruptibly();
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            List<String> cuts = db.with(c -> table.boundaries(c, kind, day, scanThreads));
            List<Future<Chunk>> parts = new ArrayList<>();
            for (int r = 0; r <= cuts.size(); r++) {
                String from = r == 0 ? null : cuts.get(r - 1);
                String to = r == cuts.size() ? null : cuts.get(r);
                parts.add(pool.submit(() -> db.with(c -> {
                    Chunk ch = new Chunk(numberPaths.size(), textPaths.size());
                    Map<String, String> shared = new HashMap<>();
                    table.columns(c, kind, day, from, to, columns, rs -> add(ch, rs, numberPaths.size(), textPaths.size(), shared));
                    return ch;
                })));
            }
            List<Chunk> chunks = new ArrayList<>();
            for (Future<Chunk> f : parts) {
                chunks.add(f.get());
            }
            return assemble(chunks, numberPaths, textPaths, day);
        } catch (Exception e) {
            throw new IllegalStateException("reading " + kind + " columns of " + day + ": " + e.getMessage(), e);
        } finally {
            dayReads.release();
        }
    }

    private static void add(Chunk ch, ResultSet rs, int numbers, int texts, Map<String, String> shared) throws SQLException {
        ch.ids.add(rs.getString(1));
        if (numbers > 0 && ch.size == ch.numbers[0].length) {
            for (int i = 0; i < numbers; i++) {
                ch.numbers[i] = Arrays.copyOf(ch.numbers[i], ch.numbers[i].length * 2);
            }
        }
        for (int i = 0; i < numbers; i++) {
            double v = rs.getDouble(2 + i);
            ch.numbers[i][ch.size] = rs.wasNull() ? Double.NaN : v;
        }
        for (int i = 0; i < texts; i++) {
            String v = rs.getString(2 + numbers + i);
            ch.texts.get(i).add(v == null || shared.size() > 200_000 ? v : shared.computeIfAbsent(v, k -> k));   // books, desks: one copy each
        }
        ch.size++;
    }

    private static ColumnSet assemble(List<Chunk> chunks, List<String> numberPaths, List<String> textPaths, LocalDate day) {
        int n = chunks.stream().mapToInt(c -> c.size).sum();
        String[] ids = new String[n];
        Map<String, double[]> nums = new LinkedHashMap<>();
        Map<String, String[]> texts = new LinkedHashMap<>();
        numberPaths.forEach(p -> nums.put(p, new double[n]));
        textPaths.forEach(p -> texts.put(p, new String[n]));
        int at = 0;
        for (Chunk c : chunks) {
            for (int i = 0; i < c.size; i++) {
                ids[at + i] = c.ids.get(i);
            }
            for (int j = 0; j < numberPaths.size(); j++) {
                System.arraycopy(c.numbers[j], 0, nums.get(numberPaths.get(j)), at, c.size);
            }
            for (int j = 0; j < textPaths.size(); j++) {
                String[] into = texts.get(textPaths.get(j));
                List<String> from = c.texts.get(j);
                for (int i = 0; i < c.size; i++) {
                    into[at + i] = from.get(i);
                }
            }
            at += c.size;
        }
        return new ColumnSet(ids, nums, texts, day);
    }

    private static long weight(ColumnSet c) {
        long w = 64L + 56L * c.size();
        for (String id : c.ids()) {
            w += 2L * id.length();
        }
        return w + 8L * c.size() * (c.numbers().size() + c.texts().size());   // texts share their values (books, desks)
    }

    Map<String, Object> stats() {
        return Map.of("kinds", dates.size(), "datesIndexed", dates.values().stream().mapToInt(Set::size).sum(), "ids", index.size(),
                "columnSets", columnSets.estimatedSize());
    }

    void clear() {
        columnSets.invalidateAll();
        indexedFrom.clear();
    }
}
