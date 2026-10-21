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
package com.ash.drishti.server.bi.engine;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.Node;
import com.ash.drishti.server.bi.dataset.Rollups;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The columns and rollup layers on disk (RUPAKA.md section 7.1): the BI store, a folder of typed Parquet files laid out
 * {@code columns/<dataset>/<table>/date=<yyyy-mm-dd>/part-<id>.parquet} (the {@code file} its metadata names; a build writes a new one, so a
 * query reading the old one goes on, and old ones go ten minutes later) and {@code rollups/<dataset>/<name>/date=.../part.parquet}, each with a
 * {@code meta.json} beside it (the table's signature, rows, a build id, how it was read, warnings). A partition is <em>valid</em> while its
 * signature matches the table's definition; a data-load signal deletes the date's partitions, so the next query (or the eager rebuild) reads the
 * source for that date only. A partition also records the version of the source data it was read from ({@link SourceGateway#dataVersion}: a Delta
 * date's files); when the source holds other data for that date (a lake rebuilt by {@code drishti.py lake demo}, a date restated without a
 * signal) the partition is stale and built again. A build is single-flight per partition: concurrent queries wait for one read of the source.
 */
public final class Layers {

    private static final Logger LOG = LoggerFactory.getLogger(Layers.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Days one query may build on demand; a longer range must be built by refresh or a data-load signal first. */
    static final int MAX_BUILDS_PER_QUERY = 31;
    /** The longest date range one query reads. */
    static final int MAX_RANGE_DAYS = 400;

    /** One date of a layer. */
    public record Partition(LocalDate date, Path file, long rows, String buildId) {}

    /** The partitions a query reads, and what happened getting them. */
    public record Layer(List<Partition> partitions, List<String> warnings, int built) {
        public List<Path> files() {
            return partitions.stream().filter(p -> p.rows() > 0).map(Partition::file).toList();
        }

        public long rows() {
            return partitions.stream().mapToLong(Partition::rows).sum();
        }
    }

    private record Latest(LocalDate date, long at) {}

    private final Path root;
    private final TableBuilder builder;
    private final AnalyticEngine engine;
    private final SourceGateway sources;
    private final QueryProperties props;
    private final Clock clock;
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final Map<String, Latest> latest = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> generations = new ConcurrentHashMap<>();
    private final AtomicLong builds = new AtomicLong();

    public Layers(AnalyticEngine engine, SourceGateway sources, QueryProperties props, Clock clock) {
        this.engine = engine;
        this.sources = sources;
        this.props = props;
        this.clock = clock;
        this.root = Path.of(props.storeDir()).toAbsolutePath().normalize();
        this.builder = new TableBuilder(engine, sources, props);
    }

    /** A directory-safe name for a dataset (its item id: {@code file:dataset:trades-basic}). */
    public static String key(String datasetId) {
        return datasetId.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    public long generation(String key) {
        return generations.computeIfAbsent(key, k -> new AtomicLong()).get();
    }

    /** Generations per business date (wave B, back-dated refresh): a load for one date moves that date's answers only. */
    private final Map<String, AtomicLong> dateGenerations = new ConcurrentHashMap<>();

    /** The generation of one business date of {@code key}: moved by a load, refresh or invalidation of that date (an as-of answer's cache key). */
    public long generation(String key, LocalDate date) {
        return date == null ? generation(key) : dateGenerations.computeIfAbsent(key + "|" + date, k -> new AtomicLong()).get();
    }

    /** Moves the data generation of {@code key} on without clearing a layer (rows that are not kept in a layer, such as a Transform's, were dropped). */
    public long bump(String key) {
        return generations.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
    }

    public long builds() {
        return builds.get();
    }

    public Path root() {
        return root;
    }

    // ---- the columns layer -------------------------------------------------------------------------------------------

    /** A table's definition as the layer sees it: a different source, kind, array or column set means different files. */
    static String signature(Dataset.Table t) {
        StringBuilder sb = new StringBuilder(t.connector()).append('|').append(t.kind()).append('|').append(t.each()).append('|');
        t.physical().forEach(c -> sb.append(c.name()).append(':').append(c.path()).append(':').append(c.type()).append(';'));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    Path tableDir(String key, String table) {
        return root.resolve("columns").resolve(key).resolve(table);
    }

    private Path partitionDir(Path dir, LocalDate date) {
        return dir.resolve("date=" + date);
    }

    /** The partitions of the table for the dates asked (null: the latest), building the ones that are missing. */
    public Layer columns(String key, Dataset.Table t, LocalDate from, LocalDate to) {
        Path dir = tableDir(key, t.name());
        String sig = signature(t);
        List<Partition> out = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int built = 0;
        if (from == null) {
            String lk = key + "|" + t.name();
            Latest l = latest.get(lk);
            long now = clock.millis();
            LocalDate current = sources.currentDate();
            Partition today = current == null ? null : valid(dir, current, sig, version(t, current));
            if (today != null && today.rows() > 0) {
                return new Layer(List.of(today), warnings, 0);              // the current business date is built (and has rows: an empty one is never served as 'the latest')
            }
            if (l != null && now - l.at() < props.latestTtl().toMillis()) {
                Partition p = valid(dir, l.date(), sig, version(t, l.date()));
                if (p != null) {
                    return new Layer(List.of(p), warnings, 0);
                }
            }
            java.util.concurrent.atomic.AtomicReference<Partition> made = new java.util.concurrent.atomic.AtomicReference<>();
            // the read under the latest's lock; the write of the date's partition under that partition's own lock (QA3B-07)
            TableBuilder.Built b = lock(dir.toString() + "|latest", () -> builder.build(t, null, d -> partitionDir(dir, d), this::partitionLock,
                    x -> made.set(finish(dir, x, sig, version(t, x.date()), -1))));
            Partition p = made.get();
            latest.put(lk, new Latest(b.date(), now));
            builds.incrementAndGet();
            warnings.addAll(b.warnings());
            return new Layer(List.of(p), warnings, 1);
        }
        LocalDate end = to == null ? from : to;
        int days = 0;
        for (LocalDate d = from; !d.isAfter(end); d = d.plusDays(1)) {
            if (++days > MAX_RANGE_DAYS) {
                warnings.add("the range is longer than " + MAX_RANGE_DAYS + " days: later days are not read");
                break;
            }
            final String ver = version(t, d);
            Partition p = valid(dir, d, sig, ver);
            if (p == null) {
                if (built >= MAX_BUILDS_PER_QUERY) {
                    warnings.add("more than " + MAX_BUILDS_PER_QUERY + " dates were missing from the layer: the rest are not read until a refresh builds them");
                    break;
                }
                final LocalDate day = d;
                java.util.concurrent.atomic.AtomicReference<Partition> made = new java.util.concurrent.atomic.AtomicReference<>();
                // one build of a partition at a time, its check, read, write and metadata under the partition's lock (QA3B-07)
                TableBuilder.Built b = lockPartition(partitionDir(dir, day), () -> {
                    Partition again = valid(dir, day, sig, ver);
                    if (again != null) {
                        made.set(again);
                        return null;
                    }
                    long epoch = epoch(partitionDir(dir, day));
                    return builder.build(t, day, x -> partitionDir(dir, x), this::partitionLock, x -> made.set(finish(dir, x, sig, ver, epoch)));
                });
                if (b == null) {
                    p = made.get();
                } else {
                    p = made.get();
                    builds.incrementAndGet();
                    built++;
                    warnings.addAll(b.warnings());
                }
            }
            if (p != null) {
                out.add(p);
            }
        }
        return new Layer(out, warnings.stream().distinct().toList(), built);
    }

    // ---- peek: the metadata of a range without building it (BI runs, section 4.1) -------------------------------------

    /**
     * One date of a table as the metadata knows it.
     *
     * @param rows    the rows; null when unknown
     * @param basis   {@code layers} (a valid built partition), the source's ({@code delta-stats}, {@code iceberg-manifests}, …), {@code history}
     *                or {@code unknown}
     * @param built   the partition is valid (nothing to build)
     * @param buildId the built partition's id (a rollup partition is valid only over it); null when not built
     * @param version the source's data version of the date; null when the source cannot tell
     */
    public record Peeked(LocalDate date, Long rows, String basis, boolean built, String buildId, String version) {}

    /** A table's learned rows per business day (BI runs' {@code RunHistory}); null when nothing is known. */
    @FunctionalInterface
    public interface RowHistory {
        Long rowsPerDay(String key, String table);

        default void saw(String key, String table, long rows) {}

        RowHistory NONE = (k, t) -> null;
    }

    /**
     * The dates of a table a query over {@code from..to} reads (null: the latest), each from metadata only: a valid partition's {@code meta.json},
     * else the source's {@link SourceGateway#rowEstimate}, else {@code history}, else unknown. Builds nothing and opens no data file; asks each date's
     * data version, which sources answer cheaply by contract. A built partition's rows teach {@code history}.
     */
    public List<Peeked> peek(String key, Dataset.Table t, LocalDate from, LocalDate to, RowHistory history) {
        Path dir = tableDir(key, t.name());
        String sig = signature(t);
        RowHistory h = history == null ? RowHistory.NONE : history;
        List<Peeked> out = new ArrayList<>();
        if (from == null) {
            LocalDate current = sources.currentDate();
            Latest l = latest.get(key + "|" + t.name());
            for (LocalDate d : new LocalDate[] {current, l == null ? null : l.date()}) {
                if (d != null) {
                    String ver = version(t, d);
                    Partition p = valid(dir, d, sig, ver);
                    if (p != null && p.rows() > 0) {
                        h.saw(key, t.name(), p.rows());
                        out.add(new Peeked(d, p.rows(), "layers", true, p.buildId(), ver));
                        return out;
                    }
                }
            }
            LocalDate held = latestHeld(t);
            LocalDate d = held != null ? held : current;
            out.add(unbuilt(key, t, d, d == null ? null : version(t, d), h));
            return out;
        }
        LocalDate end = to == null ? from : to;
        int days = 0;
        for (LocalDate d = from; !d.isAfter(end) && ++days <= MAX_RANGE_DAYS; d = d.plusDays(1)) {
            String ver = version(t, d);
            Partition p = valid(dir, d, sig, ver);
            if (p != null) {
                if (p.rows() > 0) {
                    h.saw(key, t.name(), p.rows());
                }
                out.add(new Peeked(d, p.rows(), "layers", true, p.buildId(), ver));
            } else {
                out.add(unbuilt(key, t, d, ver, h));
            }
        }
        return out;
    }

    private Peeked unbuilt(String key, Dataset.Table t, LocalDate d, String ver, RowHistory h) {
        if (d != null && t.kind() != null && !t.kind().isBlank()) {
            Optional<com.ash.drishti.api.RowEstimate> e = sources.rowEstimate(t.connector(), t.kind(), d);
            if (e.isPresent()) {
                return new Peeked(d, e.get().rows(), e.get().basis(), false, null, ver);
            }
        }
        Long perDay = h.rowsPerDay(key, t.name());
        return perDay != null ? new Peeked(d, perDay, "history", false, null, ver) : new Peeked(d, null, "unknown", false, null, ver);
    }

    /** The newest business date the table's source holds (metadata), or null when it cannot say. */
    private LocalDate latestHeld(Dataset.Table t) {
        try {
            for (com.ash.drishti.api.SourcePlugin p : sources.plugins(t.connector(), t.kind())) {
                Optional<LocalDate> d = p.latestDate(t.kind());
                if (d.isPresent()) {
                    return d.get();
                }
            }
        } catch (RuntimeException e) {
            // the source cannot say now: the current business date stands
        }
        return null;
    }

    /**
     * The rows of a rollup's valid partitions over the peeked dates of its table (a rollup partition is valid only over the columns partition it was
     * built from). Dates whose columns are not built, or whose rollup is not, are absent. Reads metadata only.
     */
    public Map<LocalDate, Long> peekRollup(String key, Dataset ds, Dataset.Rollup r, List<Peeked> columns) {
        Path dir = root.resolve("rollups").resolve(key).resolve(r.name());
        String sig = signature(ds.tables().get(r.table())) + "/" + r.by() + r.measures() + Rollups.class.getSimpleName();
        Map<LocalDate, Long> out = new java.util.TreeMap<>();
        for (Peeked c : columns) {
            if (!c.built() || c.date() == null) {
                continue;
            }
            Path pd = partitionDir(dir, c.date());
            try {
                Path meta = pd.resolve("meta.json");
                if (Files.isRegularFile(meta)) {
                    JsonNode m = JSON.readTree(Files.readString(meta));
                    if (Files.isRegularFile(dataFile(pd, m)) && sig.equals(m.path("signature").asText()) && c.buildId().equals(m.path("sourceBuild").asText())) {
                        out.put(c.date(), m.path("rows").asLong());
                    }
                }
            } catch (IOException e) {
                // not valid: absent
            }
        }
        return out;
    }

    /**
     * Writes the partition's metadata (called holding the partition's lock), which makes it valid. A partition whose source cannot say its
     * data version and that was invalidated while it was built ({@code epoch} moved: a data-load signal came during the read) is served to
     * the query that built it but gets no metadata, so the next read builds it again from the new data.
     */
    private Partition finish(Path dir, TableBuilder.Built b, String sig, String sourceVersion, long epoch) {
        Map<String, Object> meta = new LinkedHashMap<>();
        String id = Long.toHexString(System.nanoTime()) + "-" + b.rows();
        if (sourceVersion == null && epoch >= 0 && epoch != epoch(partitionDir(dir, b.date()))) {
            return new Partition(b.date(), b.file(), b.rows(), id);
        }
        meta.put("signature", sig);
        if (sourceVersion != null) {
            meta.put("sourceVersion", sourceVersion);
        }
        meta.put("rows", b.rows());
        meta.put("buildId", id);
        meta.put("how", b.how());
        meta.put("builtAt", Instant.now(clock).toString());
        meta.put("warnings", b.warnings());
        meta.put("file", b.file().getFileName().toString());
        Path pd = partitionDir(dir, b.date());
        writeMeta(pd, meta);
        FileSwap.sweep(pd, b.file().getFileName().toString());
        return new Partition(b.date(), b.file(), b.rows(), id);
    }

    /** The metadata written whole under a name of its own, then moved in place: a reader never sees half of it. */
    private static void writeMeta(Path pd, Map<String, Object> meta) {
        try {
            Path tmp = pd.resolve("meta." + java.util.UUID.randomUUID().toString().substring(0, 8) + ".json.tmp");
            Files.writeString(tmp, JSON.writeValueAsString(meta), StandardCharsets.UTF_8);
            Files.move(tmp, pd.resolve("meta.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new DrishtiException(ErrorCode.QUERY_ENGINE, "cannot write the layer's metadata: " + e.getMessage());
        }
    }

    /** The data file the metadata names (a store written before files had names of their own: part.parquet). */
    private static Path dataFile(Path pd, JsonNode meta) {
        String f = meta.path("file").asText("");
        return pd.resolve(f.isBlank() || f.contains("/") || f.contains("\\") ? "part.parquet" : f);
    }

    /**
     * The version of the data a table reads on a date (null: the source cannot tell, or the date is not known yet). Never fails a query: a
     * connector that cannot answer now leaves the stored partition in use.
     */
    String version(Dataset.Table t, LocalDate date) {
        if (date == null || t.kind() == null || t.kind().isBlank()) {
            return null;
        }
        try {
            return sources.dataVersion(t.connector(), t.kind(), date);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The partition when it exists, is complete, was built for this signature and (when the source can say) from the data the source holds now;
     * else null.
     */
    private Partition valid(Path dir, LocalDate date, String sig, String sourceVersion) {
        Path pd = partitionDir(dir, date);
        Path meta = pd.resolve("meta.json");
        if (!Files.isRegularFile(meta)) {
            return null;
        }
        try {
            JsonNode m = JSON.readTree(Files.readString(meta));
            Path file = dataFile(pd, m);
            if (!Files.isRegularFile(file)) {
                return null;
            }
            if (!sig.equals(m.path("signature").asText())) {
                return null;
            }
            if (sourceVersion != null && !sourceVersion.equals(m.path("sourceVersion").asText(null))) {
                LOG.info("{} {}: the source holds other data now (a rebuilt or restated table): built again", dir.getFileName(), date);
                return null;
            }
            return new Partition(date, file, m.path("rows").asLong(), m.path("buildId").asText());
        } catch (IOException e) {
            return null;
        }
    }

    // ---- rollups -----------------------------------------------------------------------------------------------------

    /** The stored aggregates of a rollup, in order, with the column each lives in. */
    public static Map<String, String> componentColumns(Dataset ds, Dataset.Rollup r) {
        Map<String, Node.Call> all = new LinkedHashMap<>();
        for (String m : r.measures()) {
            Map<String, Node.Call> c = Rollups.components(ds, ds.measures().get(m), r.table());
            if (c != null) {
                all.putAll(c);
            }
        }
        Map<String, String> cols = new LinkedHashMap<>();
        int i = 0;
        for (String k : all.keySet()) {
            cols.put(k, "m" + i++);
        }
        return cols;
    }

    private static Map<String, Node.Call> componentCalls(Dataset ds, Dataset.Rollup r) {
        Map<String, Node.Call> all = new LinkedHashMap<>();
        for (String m : r.measures()) {
            Map<String, Node.Call> c = Rollups.components(ds, ds.measures().get(m), r.table());
            if (c != null) {
                all.putAll(c);
            }
        }
        return all;
    }

    /** The rollup's partitions for the dates of a columns layer, building the missing ones from it. */
    public Layer rollup(String key, Dataset ds, Dataset.Rollup r, Layer columns) {
        Path dir = root.resolve("rollups").resolve(key).resolve(r.name());
        String sig = signature(ds.tables().get(r.table())) + "/" + r.by() + r.measures() + Rollups.class.getSimpleName();
        Map<String, Node.Call> calls = componentCalls(ds, r);
        Map<String, String> names = componentColumns(ds, r);
        List<Partition> out = new ArrayList<>();
        int built = 0;
        for (Partition src : columns.partitions()) {
            Path pd = partitionDir(dir, src.date());
            Path meta = pd.resolve("meta.json");
            Partition have = null;
            try {
                if (Files.isRegularFile(meta)) {
                    JsonNode m = JSON.readTree(Files.readString(meta));
                    Path file = dataFile(pd, m);
                    if (Files.isRegularFile(file) && sig.equals(m.path("signature").asText()) && src.buildId().equals(m.path("sourceBuild").asText())) {
                        have = new Partition(src.date(), file, m.path("rows").asLong(), src.buildId());
                    }
                }
            } catch (IOException e) {
                have = null;
            }
            if (have == null) {
                have = lock(pd + "|rollup", () -> buildRollup(ds, r, calls, names, src, pd, sig));
                built++;
            }
            out.add(have);
        }
        return new Layer(out, List.of(), built);
    }

    private Partition buildRollup(Dataset ds, Dataset.Rollup r, Map<String, Node.Call> calls, Map<String, String> names, Partition src, Path pd, String sig) {
        SqlEmitter e = new SqlEmitter(ds, Dialect.DUCKDB, r.table(), null, Map.of());
        List<String> select = new ArrayList<>();
        select.add(Dialect.quote(Dataset.DATE));
        List<String> group = new ArrayList<>(select);
        for (String by : r.by()) {
            select.add(Dialect.quote(r.table()) + "." + Dialect.quote(by) + " AS " + Dialect.quote(by));
            group.add(Dialect.quote(r.table()) + "." + Dialect.quote(by));
        }
        group.set(0, Dialect.quote(r.table()) + "." + Dialect.quote(Dataset.DATE));
        select.set(0, group.get(0) + " AS " + Dialect.quote(Dataset.DATE));
        for (Map.Entry<String, Node.Call> c : calls.entrySet()) {
            select.add(e.emit(c.getValue(), false) + " AS " + Dialect.quote(names.get(c.getKey())));
        }
        try {
            Files.createDirectories(pd);
            Path target = FileSwap.unique(pd);                  // a file of its own: a query reading the old one goes on (QA3B-07)
            Path tmp = FileSwap.temp(target);
            String sql = "COPY (SELECT " + String.join(", ", select) + " FROM read_parquet('" + TableBuilder.literal(src.file()) + "') AS " + Dialect.quote(r.table())
                    + " GROUP BY " + String.join(", ", group) + ") TO '" + TableBuilder.literal(tmp) + "' (FORMAT PARQUET, COMPRESSION ZSTD)";
            engine.execute(sql, e.params());
            FileSwap.replace(tmp, target);
            long rows = count(target);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("signature", sig);
            meta.put("rows", rows);
            meta.put("sourceBuild", src.buildId());
            meta.put("builtAt", Instant.now(clock).toString());
            meta.put("file", target.getFileName().toString());
            writeMeta(pd, meta);
            FileSwap.sweep(pd, target.getFileName().toString());
            builds.incrementAndGet();
            return new Partition(src.date(), target, rows, src.buildId());
        } catch (IOException ex) {
            throw new DrishtiException(ErrorCode.QUERY_ENGINE, "cannot write rollup " + r.name() + ": " + ex.getMessage());
        }
    }

    private long count(Path file) {
        Object[] v = engine.query("SELECT count(*) AS n FROM read_parquet('" + TableBuilder.literal(file) + "')", List.of(), 1, java.time.Duration.ofSeconds(30))
                .columns().get(0).values();
        return v.length == 0 ? 0 : ((Number) v[0]).longValue();
    }

    // ---- live tables -------------------------------------------------------------------------------------------------

    private final Map<String, Long> liveAt = new ConcurrentHashMap<>();

    /**
     * A live table: the current state of the kind, read through the connector at most every {@code live-ttl} and kept as one file. Minimal by
     * design (RUPAKA.md section 20.2): it is a short-lived snapshot, not an incrementally updated in-memory table.
     */
    public Layer live(String key, Dataset.Table t) {
        Path dir = root.resolve("live").resolve(key).resolve(t.name());
        String lk = dir.toString();
        long now = clock.millis();
        Long at = liveAt.get(lk);
        Path file = dir.resolve("date=live").resolve("part.parquet");
        if (at != null && now - at < props.liveTtl().toMillis() && Files.isRegularFile(file)) {
            return new Layer(List.of(new Partition(null, file, 1, "live")), List.of(), 0);
        }
        TableBuilder.Built b = lock(lk + "|live", () -> {
            Long again = liveAt.get(lk);
            if (again != null && clock.millis() - again < props.liveTtl().toMillis() && Files.isRegularFile(file)) {
                return null;
            }
            return builder.build(t, null, d -> dir.resolve("date=live"));
        });
        if (b != null) {
            liveAt.put(lk, clock.millis());
            return new Layer(List.of(new Partition(b.date(), b.file(), b.rows(), "live")), b.warnings(), 1);
        }
        return new Layer(List.of(new Partition(null, file, 1, "live")), List.of(), 0);
    }

    // ---- invalidation ------------------------------------------------------------------------------------------------

    /** The whole-table data version each cached table of a dataset was last seen at (key|table). */
    private final Map<String, String> seen = new ConcurrentHashMap<>();

    /**
     * Moves the dataset's generations on when a table's source holds a new version since the last query (a lake rebuilt or a date restated
     * without a data-load signal), so cached answers built from the old data are not served; the stale partitions themselves are rebuilt as
     * they are read. Cheap: the connectors answer from their own latest-version cache. Returns true when something moved.
     */
    public boolean sync(String key, Dataset ds) {
        boolean moved = false;
        for (Dataset.Table t : ds.tables().values()) {
            if (!"cached".equals(t.mode()) || t.kind() == null || t.kind().isBlank()) {
                continue;
            }
            String v;
            try {
                v = sources.dataVersion(t.connector(), t.kind(), null);
            } catch (RuntimeException e) {
                continue;
            }
            if (v == null) {
                continue;
            }
            String was = seen.put(key + "|" + t.name(), v);
            if (was != null && !was.equals(v)) {
                latest.remove(key + "|" + t.name());
                moved = true;
            }
        }
        if (moved) {
            generations.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
            dateGenerations.forEach((k, g) -> {
                if (k.startsWith(key + "|")) {
                    g.incrementAndGet();
                }
            });
        }
        return moved;
    }

    /** The file {@code drishti.py lake demo} writes into the BI store when no running server took its data-load signal. */
    public static final String REBUILT_STAMP = "lake-rebuilt.json";

    /**
     * Clears the store's columns, rollups and live files when the lake was rebuilt while the server was down ({@code lake demo} left
     * {@value #REBUILT_STAMP} in the store). Called at start; returns true when it cleared.
     */
    public boolean clearIfLakeRebuilt() {
        Path stamp = root.resolve(REBUILT_STAMP);
        if (!Files.isRegularFile(stamp)) {
            return false;
        }
        String what;
        try {
            what = Files.readString(stamp, StandardCharsets.UTF_8).replaceAll("\\s+", " ").strip();
        } catch (IOException e) {
            what = "";
        }
        for (String folder : List.of("columns", "rollups", "live")) {
            try {
                TableBuilder.delete(root.resolve(folder));
            } catch (IOException e) {
                LOG.warn("cannot clear the BI store's {} after a lake rebuild: {}", folder, e.getMessage());
                return false;
            }
        }
        latest.clear();
        liveAt.clear();
        try {
            Files.deleteIfExists(stamp);
        } catch (IOException e) {
            LOG.warn("cannot remove {}: {}", stamp, e.getMessage());
        }
        LOG.info("The lake was rebuilt while the server was stopped ({}): the BI store's columns and rollups were cleared and are built again from it",
                what.length() > 200 ? what.substring(0, 200) + "..." : what);
        return true;
    }

    /** Invalidates the date's partitions of every table (and rollup) of the dataset that reads the kind; returns the tables touched. */
    public List<String> invalidate(String key, Dataset ds, String kind, LocalDate date) {
        return invalidate(key, ds, ds.tablesOfKind(kind), date);
    }

    /**
     * Invalidates the date's partitions of these tables (and their rollups): their metadata is removed, so the next read builds them again.
     * The Parquet files stay until a build replaces them, so a query reading one now finishes, and a build running now keeps its temporary
     * file: before, the folders were deleted, under a build writing into them (QA3B-07: {@code part.parquet.….tmp is gone}, {@code cannot
     * write the layer's metadata}).
     */
    public List<String> invalidate(String key, Dataset ds, List<Dataset.Table> tables, LocalDate date) {
        List<String> touched = new ArrayList<>();
        for (Dataset.Table t : tables) {
            try {
                Path pd = partitionDir(tableDir(key, t.name()), date);
                epochs.computeIfAbsent(pd.toString(), k -> new AtomicLong()).incrementAndGet();
                Files.deleteIfExists(pd.resolve("meta.json"));
                for (Dataset.Rollup r : ds.rollups()) {
                    if (r.table().equals(t.name())) {
                        Files.deleteIfExists(partitionDir(root.resolve("rollups").resolve(key).resolve(r.name()), date).resolve("meta.json"));
                    }
                }
            } catch (IOException e) {
                LOG.warn("cannot clear {} {} of {}: {}", t.name(), date, key, e.getMessage());
            }
            latest.remove(key + "|" + t.name());
            liveAt.remove(root.resolve("live").resolve(key).resolve(t.name()).toString());
            touched.add(t.name());
        }
        if (!touched.isEmpty() && date != null) {
            dateGenerations.computeIfAbsent(key + "|" + date, k -> new AtomicLong()).incrementAndGet();
        }
        if (!touched.isEmpty()) {
            generations.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
        }
        return touched;
    }

    /** What the store holds for a dataset, for the dataset page: per table, the dates and rows. */
    public List<Map<String, Object>> inventory(String key, Dataset ds) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Dataset.Table t : ds.tables().values()) {
            Path dir = tableDir(key, t.name());
            List<Map<String, Object>> dates = new ArrayList<>();
            if (Files.isDirectory(dir)) {
                try (Stream<Path> s = Files.list(dir)) {
                    for (Path p : s.filter(x -> x.getFileName().toString().startsWith("date=")).sorted().toList()) {
                        try {
                            JsonNode m = JSON.readTree(Files.readString(p.resolve("meta.json")));
                            dates.add(Map.of("date", p.getFileName().toString().substring(5), "rows", m.path("rows").asLong(), "how", m.path("how").asText(),
                                    "builtAt", m.path("builtAt").asText()));
                        } catch (IOException ignored) {
                            // a partition being written
                        }
                    }
                } catch (IOException ignored) {
                    // folder vanished
                }
            }
            out.add(Map.of("table", t.name(), "mode", t.mode(), "partitions", dates));
        }
        return out;
    }

    /**
     * Whether the table's partition for the date is built from the data the source holds now: it exists, is complete, has this definition and
     * the source can say its data version, which matches the one it was built from. A data-load signal for such a date changes nothing
     * (QA3B-07: a burst re-signalling the same dates rebuilt each one 25 times).
     */
    public boolean fresh(String key, Dataset.Table t, LocalDate date) {
        if (date == null || !"cached".equals(t.mode())) {
            return false;
        }
        String ver = version(t, date);
        return ver != null && valid(tableDir(key, t.name()), date, signature(t), ver) != null;
    }

    /**
     * Whether the table holds a stored partition for the date (valid or stale): a page has read that date. A data-load signal rebuilds such a
     * date in the background; a date nobody has read is built when a page asks for it, not by every signal of a backfill (QA3B-07: sixty
     * dates of twelve tables, each read from the documents, kept the server busy long after the burst).
     */
    public boolean holds(String key, Dataset.Table t, LocalDate date) {
        if (date == null) {
            return false;
        }
        return FileSwap.holdsData(partitionDir(tableDir(key, t.name()), date));
    }

    // ---- single flight -----------------------------------------------------------------------------------------------

    /** Invalidations per partition folder: a build started before one does not make its partition valid (sources without a data version). */
    private final Map<String, AtomicLong> epochs = new ConcurrentHashMap<>();

    private long epoch(Path partition) {
        AtomicLong e = epochs.get(partition.toString());
        return e == null ? 0 : e.get();
    }

    /** The one lock of a partition folder: its builds, on demand and in the background, the latest's write and its metadata. */
    private java.util.concurrent.locks.Lock partitionLock(Path partition) {
        return locks.computeIfAbsent(partition + "|build", k -> new ReentrantLock());
    }

    private <T> T lockPartition(Path partition, java.util.function.Supplier<T> work) {
        java.util.concurrent.locks.Lock l = partitionLock(partition);
        l.lock();
        try {
            return work.get();
        } finally {
            l.unlock();
        }
    }

    private <T> T lock(String name, java.util.function.Supplier<T> work) {
        ReentrantLock l = locks.computeIfAbsent(name, k -> new ReentrantLock());
        l.lock();
        try {
            return work.get();
        } finally {
            l.unlock();
        }
    }
}
