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
package com.ash.drishti.plugin.cassandra;

import com.ash.drishti.api.LoadGuard;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.BatchStatement;
import com.datastax.oss.driver.api.core.cql.BatchType;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.BoundStatementBuilder;
import com.datastax.oss.driver.api.core.cql.Statement;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Loads JSON lines ({@code {"domain", "kind", "id", "date", "doc", "columns": {path: value}}}, the format of
 * {@code make_data.py --jsonl} and {@code bulk_trades.py --jsonl}) into Cassandra in the connector's layout
 * ({@link CassandraLayout}). A day appears whole or not at all: its rows go to a new <b>generation</b>, then its
 * {@code entity_days} rows, and only when every write has succeeded the {@code kind_days} row naming the new generation;
 * the old generation's partitions are then deleted, a few whole-partition deletes. A load that stops part way leaves a
 * generation no reader follows; the next load of the day takes a newer one.
 *
 * <pre>
 * CassandraLoader FILE|- [contact-points] [local-datacenter] [--keyspace K] [--create] [--create-keyspace RF] [--ttl-days N]
 *     [--keep-days N] [--force-drop] [--buckets N | --buckets KIND=N] [--in-flight N] [--batch-rows N] [--consistency C]
 *     [--username U] [--password P] [--setting key=value] [--as-of D] [--future-days N] [--zone Z]
 * </pre>
 *
 * <p>Writes are prepared statements, at most {@code --in-flight} (256) at once, grouped per partition into unlogged batches
 * of at most {@code --batch-rows} (32) rows (never across partitions, as Cassandra recommends). {@code --create} creates the
 * tables and adds promoted columns ({@code ALTER TABLE … ADD}); without it the loader prints the CQL for an administrator and
 * refuses to load into tables that lack them. The keyspace is the administrator's; {@code --create-keyspace RF} makes one
 * with SimpleStrategy for a development node. {@code --ttl-days N} writes with a TTL (and {@code --create} gives new tables
 * one-day time windows); {@code --keep-days N} then drops each loaded kind's days older than its newest N on or before
 * {@code --as-of}, refusing to drop more than half of them without {@code --force-drop} ({@link LoadGuard}).
 */
public final class CassandraLoader implements AutoCloseable {

    /** Partitions above this are warned about, with a bucket count that would keep them under it. */
    static final long PARTITION_WARN_BYTES = 100L * 1024 * 1024;
    private static final long BATCH_BYTES = 256L * 1024;
    private static final long BUFFER_BYTES = 64L * 1024 * 1024;
    /** Generations are seconds since 2020 (or the current one plus one), so a stopped load's partitions are never reused. */
    private static final long GENERATION_EPOCH = Instant.parse("2020-01-01T00:00:00Z").getEpochSecond();

    /** What to do; {@code settings} are connector settings (contact points, log-in, {@code tls.*}). */
    public record Options(Map<String, String> settings, String keyspace, boolean create, int createKeyspace, int ttlDays, int keepDays,
            int buckets, Map<String, Integer> bucketsByKind, int inFlight, int batchRows) {

        int bucketsFor(String kind) {
            return bucketsByKind.getOrDefault(kind, buckets);
        }
    }

    /** One day loaded: its kind, the generation written and the one it replaced (0: none), its rows. */
    public record DayLoaded(String keyspace, String kind, LocalDate date, int generation, int replaced, long rows) {}

    /** What a load did. */
    public record Result(long rows, List<DayLoaded> days, long daysDropped, List<String> warnings) {}

    private record Row(String domain, String kind, String id, LocalDate date, String doc, Map<String, Object> columns) {}

    private record PartKey(String keyspace, String kind, LocalDate date, int generation, int bucket) {}

    /** A kind's table as loaded: its promoted columns and the insert over all of them (columns a row lacks stay unset). */
    private static final class TableState {
        final String keyspace;
        final String kind;
        final Map<String, CassandraLayout.ColumnType> columns = new LinkedHashMap<>();
        List<String> order = List.of();
        String insert;

        TableState(String keyspace, String kind) {
            this.keyspace = keyspace;
            this.kind = kind;
        }
    }

    /** A day being written: its new generation, the one it replaces, its rows and bytes per bucket. */
    private static final class DayLoad {
        final String keyspace;
        final String kind;
        final LocalDate date;
        final int generation;
        final CassandraLayout.Day replaced;
        final int buckets;
        final AtomicLong rows = new AtomicLong();
        final long[] bytes;

        DayLoad(String keyspace, String kind, LocalDate date, int generation, CassandraLayout.Day replaced, int buckets) {
            this.keyspace = keyspace;
            this.kind = kind;
            this.date = date;
            this.generation = generation;
            this.replaced = replaced;
            this.buckets = buckets;
            this.bytes = new long[buckets];
        }
    }

    private final CassandraStore store;
    private final Options options;
    private final LoadGuard guard;
    private final Semaphore inFlight;
    private final int permits;
    private final AtomicReference<Throwable> failed = new AtomicReference<>();
    private final Set<String> keyspacesReady = ConcurrentHashMap.newKeySet();
    private final Map<String, TableState> tables = new HashMap<>();
    private final Map<String, NavigableMap<LocalDate, CassandraLayout.Day>> existing = new HashMap<>();
    private final Map<String, DayLoad> days = new LinkedHashMap<>();
    private final Map<PartKey, List<Statement<?>>> buffers = new HashMap<>();
    private final Map<PartKey, Long> bufferBytes = new HashMap<>();
    private final List<String> warnings = new ArrayList<>();
    private long buffered;

    /** A loader over a session it owns (closed by {@link #close()}). */
    public CassandraLoader(Options options, LoadGuard guard) {
        this.options = options;
        this.guard = guard;
        CassandraSessions.Plan plan = CassandraSessions.plan(options.settings(), System::getenv);
        this.store = new CassandraStore(CassandraSessions.open(options.settings(), plan, System::getenv), 1,
                Duration.ofSeconds(Math.max(5, Long.parseLong(options.settings().getOrDefault("connect-timeout-ms", "30000")) / 1000)));
        this.permits = Math.max(1, options.inFlight());
        this.inFlight = new Semaphore(permits);
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: CassandraLoader FILE|- [contact-points] [local-datacenter] [--keyspace K] [--create] [--create-keyspace RF] "
                    + "[--ttl-days N] [--keep-days N] [--force-drop] [--buckets N|KIND=N] [--in-flight N] [--batch-rows N] [--consistency C] "
                    + "[--username U] [--password P] [--setting key=value] [--as-of D] [--future-days N] [--zone Z]");
            System.exit(2);
        }
        Options o = parse(args);
        LoadGuard guard = LoadGuard.fromArgs(args);
        long t0 = System.nanoTime();
        Result r;
        try (CassandraLoader loader = new CassandraLoader(o, guard);
             BufferedReader in = args[0].equals("-") ? new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8), 1 << 20)
                     : Files.newBufferedReader(Path.of(args[0]), StandardCharsets.UTF_8)) {
            r = loader.load(in);
        }
        double s = (System.nanoTime() - t0) / 1e9;
        r.warnings().forEach(w -> System.err.println("cassandra: warning: " + w));
        System.out.printf("cassandra: loaded %,d rows (%d days) in %,.1f s (%,.0f rows/s)%s%n", r.rows(), r.days().size(), s, r.rows() / Math.max(s, 1e-3),
                r.daysDropped() > 0 ? String.format(", dropped %d days (--keep-days)", r.daysDropped()) : "");
        guard.finish();
    }

    /** The command line as options (connector settings for the session, the loader's own for the rest). */
    static Options parse(String[] args) {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("contact-points", args.length > 1 && !args[1].startsWith("--") ? args[1] : "localhost:9042");
        settings.put("local-datacenter", args.length > 2 && !args[2].startsWith("--") ? args[2] : "datacenter1");
        settings.put("request-timeout-ms", "30000");
        settings.put("max-concurrent-reads", "100000");                   // the loader bounds its own writes (--in-flight)
        String user = option(args, "--username", System.getenv().getOrDefault("DRISHTI_CASSANDRA_USERNAME", ""));
        if (!user.isBlank()) {
            settings.put("username", user);
            settings.put("password", option(args, "--password", System.getenv().getOrDefault("DRISHTI_CASSANDRA_PASSWORD", "")));
        }
        settings.put("consistency", option(args, "--consistency", "LOCAL_QUORUM"));
        int buckets = CassandraLayout.DEFAULT_BUCKETS;
        Map<String, Integer> byKind = new HashMap<>();
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--setting")) {
                String[] kv = args[i + 1].split("=", 2);
                settings.put(kv[0].strip(), kv.length > 1 ? kv[1].strip() : "");
            } else if (args[i].equals("--buckets")) {
                String v = args[i + 1];
                if (v.contains("=")) {
                    byKind.put(v.substring(0, v.indexOf('=')), Integer.parseInt(v.substring(v.indexOf('=') + 1)));
                } else {
                    buckets = Integer.parseInt(v);
                }
            }
        }
        List<String> a = List.of(args);
        String ks = option(args, "--keyspace", "");
        return new Options(settings, ks.isBlank() ? null : ks, a.contains("--create"), Integer.parseInt(option(args, "--create-keyspace", "0")),
                Integer.parseInt(option(args, "--ttl-days", "0")), Integer.parseInt(option(args, "--keep-days", "0")), buckets, byKind,
                Integer.parseInt(option(args, "--in-flight", "256")), Integer.parseInt(option(args, "--batch-rows", "32")));
    }

    private static String option(String[] args, String name, String fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        return fallback;
    }

    // ---- loading -------------------------------------------------------------------------------------------------------------

    /** Loads every line, then makes each day's new generation current and drops the old ones (and, with {@code --keep-days}, old days). */
    public Result load(BufferedReader in) throws IOException {
        JsonFactory json = new JsonFactory();
        CqlSession session = store.session();
        long n = 0;
        long t0 = System.nanoTime();
        String line;
        while ((line = in.readLine()) != null && failed.get() == null) {
            if (line.isBlank()) {
                continue;
            }
            Row row = parse(json, line);
            if (!guard.accept(row.date(), row.domain() + " " + row.kind() + " " + row.id())) {
                continue;
            }
            write(session, row);
            if (++n % 100_000 == 0) {
                System.err.printf("cassandra: %,d rows (%,.0f s)%n", n, (System.nanoTime() - t0) / 1e9);
            }
        }
        new ArrayList<>(buffers.keySet()).forEach(k -> flush(session, k));
        inFlight.acquireUninterruptibly(permits);                            // every write has finished
        inFlight.release(permits);
        if (failed.get() != null) {
            throw new IllegalStateException("cassandra: a write failed, so no day was switched to its new generation (the old ones are still read): "
                    + failed.get().getMessage(), failed.get());
        }
        List<DayLoaded> loaded = new ArrayList<>();
        for (DayLoad d : days.values()) {
            publish(d);
            loaded.add(new DayLoaded(d.keyspace, d.kind, d.date, d.generation, d.replaced == null ? 0 : d.replaced.generation(), d.rows.get()));
            warnLargePartitions(d);
        }
        long dropped = options.keepDays() > 0 ? keepDays() : 0;
        return new Result(n, loaded, dropped, List.copyOf(warnings));
    }

    private void write(CqlSession session, Row row) {
        String ks = options.keyspace() != null ? CassandraLayout.keyspace(options.keyspace()) : CassandraLayout.keyspace(row.domain());
        readyKeyspace(ks);
        TableState t = table(ks, row.kind(), row.columns());
        DayLoad day = days.computeIfAbsent(ks + "\u001f" + row.kind() + "\u001f" + row.date(), k -> newDay(ks, row.kind(), row.date()));
        int bucket = CassandraLayout.bucket(row.id(), day.buckets);
        BoundStatementBuilder b = store.prepare(t.insert).boundStatementBuilder()
                .setLocalDate(0, row.date()).setInt(1, day.generation).setShort(2, (short) bucket).setString(3, row.id()).setString(4, row.doc());
        for (int i = 0; i < t.order.size(); i++) {
            String path = t.order.get(i);
            Object v = row.columns().get(path);
            if (v == null) {
                continue;                                      // left unset: no cell, no tombstone
            }
            String col = CassandraLayout.column(path);
            if (t.columns.get(col) == CassandraLayout.ColumnType.DOUBLE) {
                Double d = v instanceof Number num ? num.doubleValue() : parseDouble(String.valueOf(v));
                if (d != null) {
                    b = b.setDouble(5 + i, d);
                }
            } else {
                b = b.setString(5 + i, v instanceof Double d && d == Math.rint(d) && !Double.isInfinite(d) ? String.valueOf(d.longValue()) : String.valueOf(v));
            }
        }
        if (options.ttlDays() > 0) {
            b = b.setInt(5 + t.order.size(), options.ttlDays() * 86_400);
        }
        PartKey key = new PartKey(ks, row.kind(), row.date(), day.generation, bucket);
        List<Statement<?>> buf = buffers.computeIfAbsent(key, k -> new ArrayList<>());
        buf.add(b.build());
        long size = row.doc().length() + 64L;
        bufferBytes.merge(key, size, Long::sum);
        buffered += size;
        day.rows.incrementAndGet();
        day.bytes[bucket] += size;
        if (buf.size() >= options.batchRows() || bufferBytes.get(key) >= BATCH_BYTES) {
            flush(session, key);
        }
        if (buffered > BUFFER_BYTES) {
            new ArrayList<>(buffers.keySet()).forEach(k -> flush(session, k));
        }
        BoundStatement ed = options.ttlDays() > 0
                ? store.prepare(CassandraLayout.insertEntityDay(ks, true)).bind(row.kind(), row.id(), row.date(), options.ttlDays() * 86_400)
                : store.prepare(CassandraLayout.insertEntityDay(ks, false)).bind(row.kind(), row.id(), row.date());
        submit(session, ed);
    }

    private static Double parseDouble(String s) {
        try {
            return Double.valueOf(s);
        } catch (NumberFormatException e) {
            return null;                                       // a text in a number column: left unset
        }
    }

    /** One partition's buffered rows: a single insert, or an unlogged batch (all rows of one partition, so one mutation). */
    private void flush(CqlSession session, PartKey key) {
        List<Statement<?>> buf = buffers.remove(key);
        Long size = bufferBytes.remove(key);
        if (buf == null || buf.isEmpty()) {
            return;
        }
        buffered -= size == null ? 0 : size;
        if (buf.size() == 1) {
            submit(session, buf.get(0));
        } else {
            List<com.datastax.oss.driver.api.core.cql.BatchableStatement<?>> rows = new ArrayList<>(buf.size());
            buf.forEach(s -> rows.add((com.datastax.oss.driver.api.core.cql.BatchableStatement<?>) s));
            submit(session, BatchStatement.newInstance(BatchType.UNLOGGED, rows).setIdempotent(true));
        }
    }

    /** Sends a write, waiting while {@code --in-flight} are outstanding. */
    private void submit(CqlSession session, Statement<?> st) {
        inFlight.acquireUninterruptibly();
        session.executeAsync(st).whenComplete((rs, e) -> {
            if (e != null) {
                failed.compareAndSet(null, e);
            }
            inFlight.release();
        });
    }

    private DayLoad newDay(String ks, String kind, LocalDate date) {
        NavigableMap<LocalDate, CassandraLayout.Day> have = existing.computeIfAbsent(ks + "\u001f" + kind, k -> kindDays(ks, kind));
        CassandraLayout.Day old = have.get(date);
        int now = (int) (Instant.now().getEpochSecond() - GENERATION_EPOCH);
        int gen = Math.max(old == null ? 1 : old.generation() + 1, now);
        for (DayLoad d : days.values()) {
            gen = Math.max(gen, d.generation);                  // one load's days share no generation with a stopped load's
        }
        return new DayLoad(ks, kind, date, gen, old, Math.max(1, options.bucketsFor(kind)));
    }

    private NavigableMap<LocalDate, CassandraLayout.Day> kindDays(String ks, String kind) {
        NavigableMap<LocalDate, CassandraLayout.Day> out = new TreeMap<>();
        store.forEach(CassandraLayout.selectKindDays(ks), r -> out.put(r.getLocalDate(0),
                new CassandraLayout.Day(r.getLocalDate(0), r.getInt(1), Math.max(1, r.getInt(2)), r.getLong(3), r.getInstant(4))), kind);
        return out;
    }

    /** Names the day's new generation in {@code kind_days} (readers switch to it now), then deletes the old generation's partitions. */
    private void publish(DayLoad d) {
        if (options.ttlDays() > 0) {
            store.execute(CassandraLayout.insertKindDay(d.keyspace, true), d.kind, d.date, d.generation, d.buckets, d.rows.get(), Instant.now(),
                    options.ttlDays() * 86_400);
        } else {
            store.execute(CassandraLayout.insertKindDay(d.keyspace, false), d.kind, d.date, d.generation, d.buckets, d.rows.get(), Instant.now());
        }
        if (d.replaced != null && d.replaced.generation() != d.generation) {
            dropPartitions(d.keyspace, d.kind, d.replaced);
        }
    }

    private void dropPartitions(String ks, String kind, CassandraLayout.Day day) {
        String cql = CassandraLayout.deletePartition(ks, kind);
        for (int b = 0; b < day.buckets(); b++) {
            store.execute(cql, day.date(), day.generation(), (short) b);
        }
    }

    private void warnLargePartitions(DayLoad d) {
        long max = 0;
        long total = 0;
        for (long b : d.bytes) {
            max = Math.max(max, b);
            total += b;
        }
        if (max > PARTITION_WARN_BYTES) {
            long suggest = Math.max(d.buckets * 2L, Math.ceilDiv(total, PARTITION_WARN_BYTES / 2));
            warnings.add(String.format("%s.%s on %s: a partition holds about %,d MB (above 100 MB); load with --buckets %s=%d", d.keyspace, d.kind, d.date,
                    max >> 20, d.kind, Integer.highestOneBit((int) Math.min(Integer.MAX_VALUE, suggest - 1)) << 1));
        }
    }

    /** Drops each loaded kind's days older than its newest {@code --keep-days} (partitions, then the catalogue row). */
    private long keepDays() {
        long dropped = 0;
        Set<String> kinds = new java.util.LinkedHashSet<>();
        days.values().forEach(d -> kinds.add(d.keyspace + "\u001f" + d.kind));
        for (String k : kinds) {
            String[] kk = k.split("\u001f", 2);
            NavigableMap<LocalDate, CassandraLayout.Day> have = kindDays(kk[0], kk[1]);
            var from = guard.keepFromNewest(have.keySet(), options.keepDays());
            if (from.isEmpty()) {
                continue;
            }
            NavigableMap<LocalDate, CassandraLayout.Day> drop = have.headMap(from.get(), false);
            guard.checkDrop(kk[0] + "." + kk[1], drop.size(), have.size(), "business days");
            for (CassandraLayout.Day d : drop.values()) {
                dropPartitions(kk[0], kk[1], d);
                store.execute(CassandraLayout.deleteKindDay(kk[0]), kk[1], d.date());
                dropped++;
            }
        }
        return dropped;
    }

    // ---- schema --------------------------------------------------------------------------------------------------------------

    private void readyKeyspace(String ks) {
        if (keyspacesReady.contains(ks)) {
            return;
        }
        if (store.keyspace(ks).isEmpty()) {
            if (options.createKeyspace() > 0) {
                store.session().execute(CassandraLayout.createKeyspace(ks, options.createKeyspace()));
            } else {
                throw new IllegalStateException("keyspace " + ks + " does not exist; an administrator creates it (its replication is theirs), e.g.\n  "
                        + CassandraLayout.createKeyspace(ks, 3).replace("SimpleStrategy', 'replication_factor'", "NetworkTopologyStrategy', 'datacenter1'")
                        + ";\nor pass --create-keyspace 1 for a development node");
            }
        }
        for (String ddl : List.of(CassandraLayout.createEntityDays(ks), CassandraLayout.createKindDays(ks))) {
            String table = ddl.contains("\"" + CassandraLayout.ENTITY_DAYS + "\"") ? CassandraLayout.ENTITY_DAYS : CassandraLayout.KIND_DAYS;
            if (store.table(ks, table).isEmpty()) {
                schema(ddl, ks + "." + table + " does not exist");
            }
        }
        keyspacesReady.add(ks);
    }

    /** The kind's table with a column for each promoted value this row carries (created or added with --create). */
    private TableState table(String ks, String kind, Map<String, Object> values) {
        TableState t = tables.get(ks + "\u001f" + kind);
        if (t == null) {
            TableState fresh = new TableState(ks, kind);
            var meta = store.table(ks, CassandraLayout.table(kind));
            if (meta.isEmpty()) {
                Map<String, CassandraLayout.ColumnType> cols = new LinkedHashMap<>();
                values.forEach((p, v) -> {
                    if (v != null) {
                        cols.put(CassandraLayout.column(p), CassandraLayout.ColumnType.of(v));
                    }
                });
                String missing = ks + "." + CassandraLayout.table(kind) + " does not exist";
                try {
                    schema(CassandraLayout.createTable(ks, kind, cols, options.ttlDays() > 0), missing);
                } catch (com.datastax.oss.driver.api.core.servererrors.InvalidConfigurationInQueryException e) {
                    if (!String.valueOf(e.getMessage()).contains("sstable_compression")) {
                        throw e;
                    }
                    // ScyllaDB names the compressor with the older option
                    schema(CassandraLayout.createTable(ks, kind, cols, options.ttlDays() > 0, true), missing);
                }
                fresh.columns.putAll(cols);
            } else {
                // the table's columns by name; which path each holds is learnt as rows carry them
                CassandraStore.promotedColumns(meta.get()).forEach((c, dbl) -> fresh.columns.put(c, dbl ? CassandraLayout.ColumnType.DOUBLE : CassandraLayout.ColumnType.TEXT));
            }
            tables.put(ks + "\u001f" + kind, fresh);
            t = fresh;
        }
        boolean changed = t.insert == null;
        Map<String, String> paths = new LinkedHashMap<>();
        for (String p : t.order) {
            paths.put(p, CassandraLayout.column(p));
        }
        for (var e : values.entrySet()) {
            if (e.getValue() == null || paths.containsKey(e.getKey())) {
                continue;
            }
            String col = CassandraLayout.column(e.getKey());
            if (!t.columns.containsKey(col)) {
                CassandraLayout.ColumnType type = CassandraLayout.ColumnType.of(e.getValue());
                schema(CassandraLayout.addColumn(ks, kind, col, type), ks + "." + CassandraLayout.table(kind) + " has no column " + col + " for " + e.getKey());
                t.columns.put(col, type);
            }
            paths.put(e.getKey(), col);
            changed = true;
        }
        if (changed) {
            t.order = List.copyOf(paths.keySet());
            t.insert = CassandraLayout.insertRow(ks, kind, t.order.stream().map(CassandraLayout::column).toList(), options.ttlDays() > 0);
        }
        return t;
    }

    /** Runs a schema statement with --create; without it prints it for an administrator and stops. */
    private void schema(String ddl, String missing) {
        if (!options.create()) {
            System.out.println(ddl + ";");
            throw new IllegalStateException(missing + ": run the CQL above (printed for a database administrator), or load with --create");
        }
        store.session().execute(com.datastax.oss.driver.api.core.cql.SimpleStatement.newInstance(ddl).setTimeout(Duration.ofSeconds(60)));
    }

    // ---- input -----------------------------------------------------------------------------------------------------------------

    private static Row parse(JsonFactory json, String line) throws IOException {
        String domain = null;
        String kind = null;
        String id = null;
        String date = null;
        String doc = null;
        Map<String, Object> columns = new LinkedHashMap<>();
        try (JsonParser p = json.createParser(line)) {
            p.nextToken();
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String name = p.currentName();
                JsonToken t = p.nextToken();
                switch (name) {
                    case "domain" -> domain = p.getText();
                    case "kind" -> kind = p.getText();
                    case "id" -> id = p.getText();
                    case "date" -> date = p.getText();
                    case "doc" -> doc = p.getText();
                    case "columns" -> {
                        if (t == JsonToken.START_OBJECT) {
                            while (p.nextToken() == JsonToken.FIELD_NAME) {
                                String path = p.currentName();
                                JsonToken v = p.nextToken();
                                columns.put(path, v == JsonToken.VALUE_NUMBER_INT || v == JsonToken.VALUE_NUMBER_FLOAT ? (Object) p.getDoubleValue()
                                        : v == JsonToken.VALUE_NULL ? null : p.getText());
                            }
                        }
                    }
                    default -> p.skipChildren();
                }
            }
        }
        if (kind == null || id == null || date == null || doc == null) {
            throw new IOException("a line without kind, id, date or doc: " + (line.length() > 200 ? line.substring(0, 200) + "…" : line));
        }
        return new Row(domain == null ? "drishti" : domain, kind, id, LocalDate.parse(date), doc, columns);
    }

    /** Closes the session. */
    @Override
    public void close() {
        store.close();
    }
}
