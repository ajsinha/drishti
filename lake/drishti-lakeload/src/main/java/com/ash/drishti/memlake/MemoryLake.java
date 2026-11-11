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
package com.ash.drishti.memlake;

import com.ash.drishti.deltalake.write.LakeManifest;
import com.ash.drishti.deltalake.write.LakeWriter;
import com.ash.drishti.deltalake.write.TableUpkeep;
import com.ash.drishti.memlake.store.MemoryStorage;
import com.ash.drishti.memlake.store.MemoryStoreFullException;
import com.ash.drishti.memlake.store.MemoryStores;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.delta.kernel.engine.Engine;
import io.delta.kernel.exceptions.TableNotFoundException;
import java.io.StringReader;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The in-memory lake of this node (docs/architecture/DATA_ARCHITECTURE.md): its memory stores, the tables their replenish jobs fill (on a
 * schedule, on the source's signal, at start in warm-up order, and at once after an eviction), the eviction ladder each store runs when a
 * write needs room (section 6), and what Admin, Lakes, Health and {@code drishti.py lake memory} show. Logs carry ids and counts only, never
 * a value of the data.
 */
public final class MemoryLake implements AutoCloseable {

    /** A cron line's next firing after {@code after}; null when it never fires again. Throws when the line is not a cron line. */
    @FunctionalInterface
    public interface Cron {
        ZonedDateTime next(String line, ZonedDateTime after);
    }

    private static final Logger LOG = LoggerFactory.getLogger(MemoryLake.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final MemoryLakeSettings props;
    private final ReplenishSource source;
    private final Map<String, MemoryTable> tables = new LinkedHashMap<>();
    private final Map<String, MemoryLakeSettings.Store> stores = new LinkedHashMap<>();
    private final Set<String> owned = new HashSet<>();
    private final ScheduledExecutorService clock;
    private final ExecutorService jobs;
    private final MemoryCompactor compactor;
    private final Cron cron;
    private volatile boolean warm;
    private volatile Runnable changed = () -> { };
    private volatile Instant lastActivity = Instant.now();

    /**
     * @param props the stores and tables
     * @param source what the jobs read
     * @param ownedHere the stores this node owns (node.yaml {@code memory-stores}); null: every store whose {@code owner} is true
     * @param quiet whether no request is being answered now (compaction's {@code idle-only})
     * @param cron the next time a table's {@code cron} line fires (the server's cron parser: this module has none of its own); null when
     *     no table has a cron line
     */
    public MemoryLake(MemoryLakeSettings props, ReplenishSource source, Set<String> ownedHere, java.util.function.BooleanSupplier quiet,
            Cron cron) {
        this.props = props;
        this.source = source;
        this.cron = cron;
        props.stores().forEach((name, s) -> {
            stores.put(name, s);
            boolean mine = ownedHere == null ? s.owner() : ownedHere.contains(name);
            if (mine) {
                owned.add(name);
                MemoryStorage st = MemoryStores.define(name, (long) s.maxMb() << 20, s.chunkKb() * 1024, s.contract());
                st.roomMaker(this::makeRoom);
            }
        });
        for (MemoryLakeSettings.TableSpec t : props.tables()) {
            if (t.name() == null || t.name().isBlank() || t.store() == null || !stores.containsKey(t.store())) {
                throw new IllegalArgumentException("drishti.lake.memory.tables: table " + t.name() + " names no store of drishti.lake.memory.stores ("
                        + stores.keySet() + ")");
            }
            if (!List.of("replace", "append", "merge").contains(t.mode())) {
                throw new IllegalArgumentException("drishti.lake.memory.tables: table " + t.name() + ": mode " + t.mode() + " is not replace, append or merge");
            }
            if (tables.containsKey(t.name())) {
                throw new IllegalArgumentException("drishti.lake.memory.tables: two tables are named " + t.name());
            }
            MemoryTable mt = new MemoryTable(t, props.historyKeep());
            if (!owned.contains(t.store())) {
                mt.state(MemoryTable.State.REMOTE);
            }
            tables.put(t.name(), mt);
        }
        this.clock = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("memory-lake-clock").daemon(true).factory());
        this.jobs = Executors.newFixedThreadPool(props.jobThreads(), Thread.ofPlatform().name("memory-lake-job-", 0).daemon(true).factory());
        this.compactor = new MemoryCompactor(this, props.compaction(), quiet);
        source.idsOf(this::idsOf);
    }

    /** {@code <table>.<FIELD>}: the field's values over the memory table's rows (the ids a connector-by-ids source fetches). */
    private List<String> idsOf(String ref) {
        int dot = ref.indexOf('.');
        MemoryTable t = dot < 0 ? null : tables.get(ref.substring(0, dot));
        if (t == null) {
            throw new IllegalArgumentException("ids-from " + ref + ": no memory table " + (dot < 0 ? ref : ref.substring(0, dot)));
        }
        String field = ref.substring(dot + 1);
        List<String> out = new ArrayList<>();
        try {
            List<TableUpkeep.Staged> rows = TableUpkeep.rows(LakeWriter.engineFor(t.spec.path(), Map.of()), t.spec.path(), pv -> true);
            for (TableUpkeep.Staged s : rows == null ? List.<TableUpkeep.Staged>of() : rows) {
                JsonNode doc = JSON.readTree(JSON.readTree(s.line()).path("doc").asText("{}"));
                JsonNode v = doc.get(field);
                if (v != null && v.isTextual() && !v.asText().isBlank()) {
                    out.add(v.asText());
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("ids-from " + ref + ": the table " + t.name() + " cannot be read now (" + valuesFree(e) + ")", e);
        }
        return out;
    }

    /** What runs after a table changed (a fill, a compaction, an eviction): the memory-only connectors look for the new version at once. */
    public void onChange(Runnable r) {
        this.changed = r == null ? () -> { } : r;
    }

    void changed() {
        try {
            changed.run();
        } catch (RuntimeException e) {
            LOG.debug("memory lake: telling the connectors failed: {}", e.toString());
        }
    }

    /** Whether anything is configured. */
    public boolean configured() {
        return !stores.isEmpty();
    }

    // ------------------------------------------------------------------------------------------------------------ schedule

    /** Starts the schedules and the warm-up (in warm-up order, one table after another). */
    public void start() {
        if (tables.isEmpty()) {
            warm = true;
            return;
        }
        for (MemoryTable t : tables.values()) {
            if (t.state() == MemoryTable.State.REMOTE) {
                continue;
            }
            Duration every = duration(t.spec.every());
            if (every != null) {
                clock.scheduleWithFixedDelay(() -> refill(t.name(), "schedule"), every.toMillis(), every.toMillis(), TimeUnit.MILLISECONDS);
                t.nextRefill = Instant.now().plus(every);
            } else if (t.spec.cron() != null && !t.spec.cron().isBlank()) {
                if (cron == null) {
                    throw new IllegalStateException("drishti.lake.memory.tables: table " + t.name() + " has a cron line, and no cron parser was given");
                }
                scheduleCron(t, t.spec.cron().trim());
            }
        }
        clock.scheduleWithFixedDelay(this::signals, 10, 10, TimeUnit.SECONDS);
        clock.scheduleWithFixedDelay(this::trimIdle, 1, 1, TimeUnit.MINUTES);
        clock.scheduleWithFixedDelay(compactor::tick, props.compaction().everySeconds(), props.compaction().everySeconds(), TimeUnit.SECONDS);
        jobs.execute(this::warmUp);
    }

    private void scheduleCron(MemoryTable t, String line) {
        ZonedDateTime next = cron.next(line, ZonedDateTime.now(ZoneId.systemDefault()));
        if (next == null) {
            return;
        }
        t.nextRefill = next.toInstant();
        long delay = Math.max(1, Duration.between(Instant.now(), next.toInstant()).toMillis());
        clock.schedule(() -> {
            refill(t.name(), "schedule");
            scheduleCron(t, line);
        }, delay, TimeUnit.MILLISECONDS);
    }

    /** At start: the tables in warm-up order (numbered first), each filled before the next. */
    void warmUp() {
        List<MemoryTable> order = new ArrayList<>(tables.values());
        order.sort(Comparator.comparing((MemoryTable t) -> t.spec.warmUp() == null ? Integer.MAX_VALUE : t.spec.warmUp()));
        for (MemoryTable t : order) {
            if (t.state() != MemoryTable.State.REMOTE) {
                run(t, "warm-up");
            }
        }
        warm = true;
        LOG.info("memory lake: warm-up done ({} tables)", order.size());
    }

    /** Whether the warm-up has finished. */
    public boolean warm() {
        return warm;
    }

    private void signals() {
        for (MemoryTable t : tables.values()) {
            if (!t.spec.signal() || t.state() == MemoryTable.State.REMOTE || t.spec.source() == null) {
                continue;
            }
            String mark = source.mark(t.spec.source());
            if (mark != null && t.sourceMark != null && !mark.equals(t.sourceMark)) {
                refill(t.name(), "signal");
            }
        }
    }

    private void trimIdle() {
        Instant idleSince = lastActivity;
        for (String name : owned) {
            MemoryLakeSettings.Store s = stores.get(name);
            if (Duration.between(idleSince, Instant.now()).toMinutes() >= s.idleTrimMinutes()) {
                MemoryStores.store(name).pool().trim(0);                     // idle: free chunks go back to the operating system
            }
        }
    }

    /** Queues a run of the table's job; false when no such table here (or held by another node). */
    public boolean refill(String name, String trigger) {
        MemoryTable t = tables.get(name);
        if (t == null || t.state() == MemoryTable.State.REMOTE) {
            return false;
        }
        CompletableFuture.runAsync(() -> run(t, trigger), jobs);
        return true;
    }

    /** Runs the table's job now, on this thread (tests and the warm-up); returns the run recorded. */
    MemoryTable.Run runNow(String name, String trigger) {
        MemoryTable t = tables.get(name);
        if (t == null) {
            throw new IllegalArgumentException("no memory table " + name);
        }
        run(t, trigger);
        return t.history().isEmpty() ? null : t.history().get(0);
    }

    // ------------------------------------------------------------------------------------------------------------ a run

    private void run(MemoryTable t, String trigger) {
        long start = System.nanoTime();
        lastActivity = Instant.now();
        t.lock.lock();
        try {
            t.refillQueued = false;
            if (t.state() != MemoryTable.State.READY) {
                t.state(MemoryTable.State.REFILLING);
            }
            String mark = source.mark(t.spec.source());
            List<ObjectNode> docs = source.read(t.spec.source());
            Written w = write(t, docs);
            t.sourceMark = mark;
            t.version = w.version;
            t.lastRefill = Instant.now();
            Duration every = duration(t.spec.every());
            if (every != null) {
                t.nextRefill = t.lastRefill.plus(every);
            }
            if (t.spec.mode().equals("append")) {
                t.appendsSinceSort.incrementAndGet();
            }
            t.state(MemoryTable.State.READY);
            t.record(new MemoryTable.Run(Instant.now(), trigger, "ok", docs.size(), w.bytes, w.version, (System.nanoTime() - start) / 1_000_000, ""));
            LOG.info("memory lake: {} filled ({}): {} rows, version {}", t.name(), trigger, docs.size(), w.version);
            changed();
            compactor.afterCommit(t);
        } catch (Exception e) {
            MemoryStoreFullException full = full(e);
            if (full != null) {
                t.refusals.incrementAndGet();
                t.state(MemoryTable.State.REFUSED);
                t.record(new MemoryTable.Run(Instant.now(), trigger, "refused", 0, 0, t.version, (System.nanoTime() - start) / 1_000_000,
                        "refused: the store is full; the table stays at version " + t.version));
                LOG.warn("memory lake: {} was not filled ({}): memory store {} is full; the table stays at version {}", t.name(), trigger, t.spec.store(), t.version);
            } else {
                t.state(t.version >= 0 && t.state() == MemoryTable.State.READY ? MemoryTable.State.READY : MemoryTable.State.FAILED);
                String why = valuesFree(e);
                t.record(new MemoryTable.Run(Instant.now(), trigger, "failed", 0, 0, t.version, (System.nanoTime() - start) / 1_000_000, why));
                LOG.warn("memory lake: {} was not filled ({}): {}", t.name(), trigger, why);
            }
        } finally {
            t.lock.unlock();
        }
    }

    /** What a write did. */
    private record Written(long version, long bytes) {}

    private Written write(MemoryTable t, List<ObjectNode> docs) throws Exception {
        MemoryLakeSettings.TableSpec spec = t.spec;
        String path = spec.path();
        MemoryStorage store = MemoryStores.store(spec.store());
        long before = store.bytesWritten();
        Map<String, String> settings = Map.of("codec", spec.codec());
        Engine engine = LakeWriter.engineFor(path, settings);
        // id → row; the run's rows grouped by business date, sorted by sort-by (runs of equal values: dictionaries and RLE shrink)
        Map<String, Row> byId = new LinkedHashMap<>();
        for (ObjectNode d : docs) {
            Row r = row(spec, d);
            byId.put(r.date + "\u001f" + r.id, r);
        }
        String mode = switch (spec.mode()) {
            case "append" -> "append";
            case "merge" -> "overwrite-dates";
            default -> "replace";
        };
        if (spec.mode().equals("merge") && exists(engine, path)) {
            Set<String> dates = new HashSet<>();
            byId.values().forEach(r -> dates.add(r.date));
            List<TableUpkeep.Staged> old = TableUpkeep.rows(engine, path, pv -> dates.contains(pv.get("business_date")));
            int replaced = 0;
            if (old != null) {
                for (TableUpkeep.Staged s : old) {
                    String k = s.partitionValues().get("business_date") + "\u001f" + s.id();
                    if (byId.containsKey(k)) {
                        replaced++;
                    } else {
                        byId.put(k, Row.staged(s));
                    }
                }
            }
            t.rowsReplaced.addAndGet(replaced);
        }
        TreeMap<String, List<Row>> byDate = new TreeMap<>();
        byId.values().forEach(r -> byDate.computeIfAbsent(r.date, x -> new ArrayList<>()).add(r));
        List<LakeManifest.Part> parts = new ArrayList<>();
        long decoded = 0;
        for (Map.Entry<String, List<Row>> e : byDate.entrySet()) {
            List<Row> rows = e.getValue();
            rows.sort(Comparator.comparing(r -> r.sort));
            StringBuilder b = new StringBuilder();
            rows.forEach(r -> b.append(r.line).append('\n'));
            decoded += b.length();
            String text = b.toString();
            parts.add(LakeManifest.Part.inMemory(() -> new StringReader(text), rows.size(), Map.of("business_date", e.getKey())));
        }
        if (parts.isEmpty() && mode.equals("replace")) {
            // an empty run of a replace: an empty table (one commit removing every file), so screens say "no rows", not stale ones
            parts.add(LakeManifest.Part.inMemory(() -> new StringReader(""), 0, Map.of("business_date", LocalDate.now(ZoneId.systemDefault()).toString())));
        }
        List<LakeManifest.Column> columns = new ArrayList<>(List.of(new LakeManifest.Column("id", "string"), new LakeManifest.Column("doc", "string"),
                new LakeManifest.Column("business_date", "date")));
        spec.columns().forEach(c -> columns.add(new LakeManifest.Column(c, "string")));
        List<String> stats = new ArrayList<>(List.of("id", "business_date"));
        stats.addAll(spec.columns());
        LakeManifest m = new LakeManifest(path, List.of("business_date"), columns, spec.columns(), mode, 5_000, stats, parts);
        LakeWriter.Result r = LakeWriter.forTable(path, settings).write(m, n -> { });
        MemoryUpkeep.expire(engine, store, path, spec.keepVersions());
        t.rows = spec.mode().equals("append") ? t.rows + r.rows() : byId.size();
        t.decodedBytes = spec.mode().equals("append") ? t.decodedBytes + decoded : decoded;
        return new Written(r.version(), store.bytesWritten() - before);
    }

    private static boolean exists(Engine engine, String path) {
        try {
            io.delta.kernel.Table.forPath(engine, path).getLatestSnapshot(engine);
            return true;
        } catch (TableNotFoundException e) {
            return false;
        }
    }

    /** A row to write: its id, its business date, its sort key and its staged line. */
    private record Row(String id, String date, String sort, String line) {

        static Row staged(TableUpkeep.Staged s) {
            return new Row(s.id(), s.partitionValues().get("business_date"), s.id() == null ? "" : s.id(), s.line());
        }
    }

    private static Row row(MemoryLakeSettings.TableSpec spec, ObjectNode d) {
        String id;
        if (spec.key().isEmpty()) {
            id = Integer.toHexString(d.toString().hashCode());
        } else {
            StringBuilder b = new StringBuilder();
            for (String k : spec.key()) {
                b.append(b.length() == 0 ? "" : "|").append(text(d.get(k)));
            }
            id = b.toString();
        }
        String date = date(spec.businessDate(), d);
        StringBuilder sort = new StringBuilder();
        for (String k : spec.sortBy()) {
            sort.append(text(d.get(k))).append('\u001f');
        }
        ObjectNode line = JSON.createObjectNode();
        line.put("id", id);
        line.put("doc", d.toString());
        ObjectNode cols = line.putObject("columns");
        for (String c : spec.columns()) {
            JsonNode v = d.get(c);
            if (v != null && !v.isNull()) {
                cols.put(c, text(v));
            }
        }
        return new Row(id, date, sort.toString(), line.toString());
    }

    private static String text(JsonNode n) {
        return n == null || n.isNull() ? "" : n.isTextual() ? n.asText() : n.toString();
    }

    static String date(String rule, JsonNode d) {
        if (rule.equalsIgnoreCase("today")) {
            return LocalDate.now(ZoneId.systemDefault()).toString();
        }
        if (rule.startsWith("field:")) {
            String v = text(d.get(rule.substring(6).trim()));
            if (v.length() >= 10) {
                try {
                    return LocalDate.parse(v.substring(0, 10)).toString();
                } catch (java.time.format.DateTimeParseException e) {
                    // not a date: today's
                }
            }
            return LocalDate.now(ZoneId.systemDefault()).toString();
        }
        return LocalDate.parse(rule).toString();
    }

    private static MemoryStoreFullException full(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof MemoryStoreFullException f) {
                return f;
            }
        }
        return null;
    }

    /** An error in words without any quoted value (a parse error quotes the text it met; logs carry no values). */
    static String valuesFree(Throwable e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        m = m.replaceAll("\"[^\"]*\"", "\"…\"").replaceAll("'[^']*'", "'…'").replaceAll("(?s)\\[Source:.*", "").replaceAll("\\s+", " ").trim();
        return e.getClass().getSimpleName() + (m.isEmpty() ? "" : ": " + (m.length() > 200 ? m.substring(0, 200) + "…" : m));
    }

    // ------------------------------------------------------------------------------------------------------------ the eviction ladder

    /**
     * Section 6: frees memory in a fixed order and stops as soon as there is enough: (1) versions past keep-versions, (2) the decoded-column
     * cache (none is kept: off by default), (3) whole unpinned tables, least recently read first, never the table being written; (4) false,
     * and the write is refused.
     */
    boolean makeRoom(MemoryStorage store, long bytes, String writing) {
        long freed = 0;
        List<MemoryTable> mine = tables.values().stream().filter(t -> t.spec.store().equals(store.name()) && !writing.startsWith(t.spec.path() + "/")).toList();
        for (MemoryTable t : mine) {
            if (t.lock.tryLock()) {
                try {
                    freed += MemoryUpkeep.expire(LakeWriter.engineFor(t.spec.path(), Map.of()), store, t.spec.path(), t.spec.keepVersions());
                } catch (Exception e) {
                    // not a table yet, or being replaced: nothing to expire
                } finally {
                    t.lock.unlock();
                }
            }
            if (freed >= bytes) {
                return true;
            }
        }
        List<MemoryTable> lru = new ArrayList<>(mine.stream().filter(t -> !t.spec.pin() && t.state() == MemoryTable.State.READY).toList());
        lru.sort(Comparator.comparingLong(t -> lastRead(store, t)));
        for (MemoryTable t : lru) {
            if (!t.lock.tryLock()) {
                continue;
            }
            try {
                long b = store.deleteUnder(t.spec.path());
                freed += b;
                t.evictions.incrementAndGet();
                t.state(MemoryTable.State.EVICTED);
                t.version = -1;
                t.rows = 0;
                t.record(new MemoryTable.Run(Instant.now(), "eviction", "evicted", 0, -b, -1, 0, "evicted whole to make room for " + writing));
                LOG.warn("memory lake: {} evicted whole from memory store {} ({} MB) to make room; a refill is queued", t.name(), store.name(), b >> 20);
                queueRefillAfterEviction(t);
                changed();
            } catch (Exception e) {
                // the table stays
            } finally {
                t.lock.unlock();
            }
            if (freed >= bytes) {
                return true;
            }
        }
        return freed > 0;
    }

    private static long lastRead(MemoryStorage store, MemoryTable t) {
        try {
            return store.lastReadUnder(t.spec.path());
        } catch (java.io.IOException e) {
            return 0;
        }
    }

    private void queueRefillAfterEviction(MemoryTable t) {
        if (t.refillQueued) {
            return;
        }
        t.refillQueued = true;
        clock.schedule(() -> refill(t.name(), "after eviction"), 30, TimeUnit.SECONDS);   // not at once: the write that needed room goes first
    }

    // ------------------------------------------------------------------------------------------------------------ what is shown

    /** The tables here. */
    List<MemoryTable> tables() {
        return List.copyOf(tables.values());
    }

    MemoryTable table(String name) {
        return tables.get(name);
    }

    /** Whether a table of this name is configured (held here or by its owner node). */
    public boolean hasTable(String name) {
        return tables.containsKey(name);
    }

    /** The stores configured, by name. */
    public Map<String, MemoryLakeSettings.Store> stores() {
        return stores;
    }

    /** Whether this node owns the store (holds its memory, runs its jobs). */
    public boolean owns(String store) {
        return owned.contains(store);
    }

    MemoryCompactor compactor() {
        return compactor;
    }

    /** What a screen of a memory-only table says while it is not ready, or null: by the connector's root and kind. */
    public String words(String root, String kind) {
        if (root == null || !MemoryStores.isMemory(root)) {
            return null;
        }
        String path = root.replaceAll("/+$", "") + "/" + kind;
        for (MemoryTable t : tables.values()) {
            if (t.spec.path().equals(path)) {
                return t.words(stores.get(t.spec.store()).contract());
            }
        }
        return null;
    }

    /** Admin, Lakes and {@code drishti.py lake memory status}: every store and table. */
    public Map<String, Object> describe() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> ss = new ArrayList<>();
        for (Map.Entry<String, MemoryLakeSettings.Store> e : stores.entrySet()) {
            String name = e.getKey();
            boolean mine = owned.contains(name);
            MemoryStorage st = mine ? MemoryStores.store(name) : null;
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("name", name);
            s.put("owner", mine);
            s.put("contract", e.getValue().contract());
            s.put("format", e.getValue().format());
            s.put("capBytes", (long) e.getValue().maxMb() << 20);
            s.put("usedBytes", st == null ? 0 : st.pool().inUseBytes());
            s.put("allocatedBytes", st == null ? 0 : st.pool().allocatedBytes());
            s.put("releasedBytes", st == null ? 0 : st.pool().releasedBytes());
            s.put("files", st == null ? 0 : st.fileCount());
            s.put("refusals", st == null ? 0 : st.refusals());
            s.put("lastRefusal", st == null ? null : st.lastRefusal());
            s.put("directLimitProblem", st == null ? null : st.pool().directLimitProblem());
            ss.add(s);
        }
        out.put("stores", ss);
        List<Map<String, Object>> ts = new ArrayList<>();
        for (MemoryTable t : tables.values()) {
            ts.add(table(t));
        }
        out.put("tables", ts);
        out.put("warm", warm);
        out.put("compaction", compactor.describe());
        out.put("health", health());
        return out;
    }

    private Map<String, Object> table(MemoryTable t) {
        Map<String, Object> m = new LinkedHashMap<>();
        MemoryLakeSettings.TableSpec s = t.spec;
        m.put("name", t.name());
        m.put("store", s.store());
        m.put("path", s.path());
        m.put("source", s.source() == null ? "" : s.source().describe());
        m.put("mode", s.mode());
        m.put("state", t.state().name().toLowerCase(Locale.ROOT));
        m.put("stateWords", t.state().words);
        m.put("since", t.since().toString());
        m.put("words", t.words(stores.get(s.store()).contract()));
        long bytes = 0;
        long length = 0;
        int files = 0;
        if (owned.contains(s.store())) {
            MemoryStorage st = MemoryStores.store(s.store());
            try {
                bytes = st.bytesUnder(s.path());
                length = st.lengthUnder(s.path());
                files = st.filesUnder(s.path());
            } catch (java.io.IOException e) {
                // a path the store cannot name: shown as empty
            }
        }
        m.put("bytes", bytes);
        m.put("fileBytes", length);
        m.put("files", files);
        m.put("decodedBytes", t.decodedBytes);
        m.put("ratio", length > 0 && t.decodedBytes > 0 ? Math.round(t.decodedBytes * 10.0 / length) / 10.0 : null);
        m.put("rows", t.rows);
        m.put("version", t.version);
        m.put("lastRefill", t.lastRefill == null ? null : t.lastRefill.toString());
        m.put("nextRefill", t.nextRefill == null ? null : t.nextRefill.toString());
        m.put("pinned", s.pin());
        m.put("evictions", t.evictions.get());
        m.put("refusals", t.refusals.get());
        m.put("compactions", t.compactions.get());
        m.put("savedTodayBytes", t.savedToday());
        m.put("savedTotalBytes", t.savedTotal.get());
        m.put("contract", stores.get(s.store()).contract());
        return m;
    }

    /** Every table's runs, newest first ({@code drishti.py lake memory jobs}). */
    public List<Map<String, Object>> jobs(String table, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (MemoryTable t : tables.values()) {
            if (table == null || table.isBlank() || t.name().equals(table)) {
                t.history().forEach(r -> out.add(r.toMap(t.name())));
            }
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("at"))).reversed());
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    /**
     * Admin, Health: amber when a store is above 85% of its cap or a write was refused; red when a pinned table is empty (after the
     * warm-up) or the JVM's direct-memory limit is below the stores' caps; the host checks of section 8.
     */
    public Map<String, Object> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (stores.isEmpty()) {
            m.put("state", "green");
            m.put("problems", List.of());
            return m;
        }
        List<String> amber = new ArrayList<>();
        List<String> red = new ArrayList<>();
        for (String name : owned) {
            MemoryStorage st = MemoryStores.store(name);
            long cap = st.pool().capBytes();
            if (cap > 0 && st.pool().inUseBytes() > cap * 0.85) {
                amber.add("memory store " + name + " is above 85% of its cap (" + (st.pool().inUseBytes() >> 20) + " of " + (cap >> 20) + " MB)");
            }
            if (st.refusals() > 0) {
                amber.add("memory store " + name + " refused " + st.refusals() + " write(s) since the start: " + st.lastRefusal());
            }
        }
        if (warm) {
            for (MemoryTable t : tables.values()) {
                if (t.spec.pin() && t.state() != MemoryTable.State.READY && t.state() != MemoryTable.State.REMOTE) {
                    red.add("pinned table " + t.name() + " is " + t.state().words);
                }
            }
        }
        long caps = 0;
        for (String name : owned) {
            caps += (long) stores.get(name).maxMb() << 20;
        }
        List<Map<String, Object>> host = new ArrayList<>();
        for (HostChecks.Check c : HostChecks.run(caps)) {
            host.add(c.toMap());
            if (c.state().equals("red")) {
                red.add(c.words());
            } else if (c.state().equals("amber")) {
                amber.add(c.words());
            }
        }
        m.put("state", !red.isEmpty() ? "red" : !amber.isEmpty() ? "amber" : "green");
        List<String> problems = new ArrayList<>(red);
        problems.addAll(amber);
        m.put("problems", problems);
        m.put("host", host);
        m.put("directLimitBytes", HostChecks.directLimitBytes());
        m.put("directNeededBytes", HostChecks.BASE_DIRECT_BYTES + caps);
        return m;
    }

    /** The stores' caps and use here, for the memory budget's off-heap pool and busy protection. */
    public long capBytes() {
        long n = 0;
        for (String name : owned) {
            n += MemoryStores.store(name).pool().capBytes();
        }
        return n;
    }

    public long usedBytes() {
        long n = 0;
        for (String name : owned) {
            n += MemoryStores.store(name).pool().allocatedBytes();
        }
        return n;
    }

    static Duration duration(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.trim().toLowerCase(Locale.ROOT);
        if (t.startsWith("pt")) {
            return Duration.parse(t.toUpperCase(Locale.ROOT));
        }
        long n = Long.parseLong(t.replaceAll("[^0-9]", ""));
        return switch (t.replaceAll("[0-9]", "")) {
            case "s" -> Duration.ofSeconds(n);
            case "m", "" -> Duration.ofMinutes(n);
            case "h" -> Duration.ofHours(n);
            case "d" -> Duration.ofDays(n);
            default -> throw new IllegalArgumentException("every: '" + text + "' is not a duration (30s, 15m, 1h)");
        };
    }

    @Override
    public void close() {
        clock.shutdownNow();
        jobs.shutdownNow();
        for (String name : owned) {
            MemoryStores.remove(name);                       // the memory goes back now; a restart empties the stores by design
        }
    }
}
