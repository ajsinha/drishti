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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The columns and rollup layers on disk (RUPAKA.md section 7.1): the BI store, a folder of typed Parquet files laid out
 * {@code columns/<dataset>/<table>/date=<yyyy-mm-dd>/part.parquet} and {@code rollups/<dataset>/<name>/date=.../part.parquet}, each with a
 * {@code meta.json} beside it (the table's signature, rows, a build id, how it was read, warnings). A partition is <em>valid</em> while its
 * signature matches the table's definition; a data-load signal deletes the date's partitions, so the next query (or the eager rebuild) reads the
 * source for that date only. A build is single-flight per partition: concurrent queries wait for one read of the source.
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
            Partition today = current == null ? null : valid(dir, current, sig);
            if (today != null && today.rows() > 0) {
                return new Layer(List.of(today), warnings, 0);              // the current business date is built (and has rows: an empty one is never served as 'the latest')
            }
            if (l != null && now - l.at() < props.latestTtl().toMillis()) {
                Partition p = valid(dir, l.date(), sig);
                if (p != null) {
                    return new Layer(List.of(p), warnings, 0);
                }
            }
            TableBuilder.Built b = lock(dir.toString() + "|latest", () -> builder.build(t, null, d -> partitionDir(dir, d)));
            Partition p = finish(dir, b, sig);
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
            Partition p = valid(dir, d, sig);
            if (p == null) {
                if (built >= MAX_BUILDS_PER_QUERY) {
                    warnings.add("more than " + MAX_BUILDS_PER_QUERY + " dates were missing from the layer: the rest are not read until a refresh builds them");
                    break;
                }
                final LocalDate day = d;
                TableBuilder.Built b = lock(partitionDir(dir, day) + "|build", () -> {
                    Partition again = valid(dir, day, sig);
                    return again != null ? null : builder.build(t, day, x -> partitionDir(dir, x));
                });
                if (b == null) {
                    p = valid(dir, d, sig);
                } else {
                    p = finish(dir, b, sig);
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

    private Partition finish(Path dir, TableBuilder.Built b, String sig) {
        Map<String, Object> meta = new LinkedHashMap<>();
        String id = Long.toHexString(System.nanoTime()) + "-" + b.rows();
        meta.put("signature", sig);
        meta.put("rows", b.rows());
        meta.put("buildId", id);
        meta.put("how", b.how());
        meta.put("builtAt", Instant.now(clock).toString());
        meta.put("warnings", b.warnings());
        try {
            Files.writeString(partitionDir(dir, b.date()).resolve("meta.json"), JSON.writeValueAsString(meta), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DrishtiException(ErrorCode.QUERY_ENGINE, "cannot write the layer's metadata: " + e.getMessage());
        }
        return new Partition(b.date(), b.file(), b.rows(), id);
    }

    /** The partition when it exists, is complete and was built for this signature; else null. */
    private Partition valid(Path dir, LocalDate date, String sig) {
        Path pd = partitionDir(dir, date);
        Path meta = pd.resolve("meta.json");
        if (!Files.isRegularFile(meta) || !Files.isRegularFile(pd.resolve("part.parquet"))) {
            return null;
        }
        try {
            JsonNode m = JSON.readTree(Files.readString(meta));
            if (!sig.equals(m.path("signature").asText())) {
                return null;
            }
            return new Partition(date, pd.resolve("part.parquet"), m.path("rows").asLong(), m.path("buildId").asText());
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
                if (Files.isRegularFile(meta) && Files.isRegularFile(pd.resolve("part.parquet"))) {
                    JsonNode m = JSON.readTree(Files.readString(meta));
                    if (sig.equals(m.path("signature").asText()) && src.buildId().equals(m.path("sourceBuild").asText())) {
                        have = new Partition(src.date(), pd.resolve("part.parquet"), m.path("rows").asLong(), src.buildId());
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
            Path tmp = pd.resolve("part.parquet.tmp");
            String sql = "COPY (SELECT " + String.join(", ", select) + " FROM read_parquet('" + TableBuilder.literal(src.file()) + "') AS " + Dialect.quote(r.table())
                    + " GROUP BY " + String.join(", ", group) + ") TO '" + TableBuilder.literal(tmp) + "' (FORMAT PARQUET, COMPRESSION ZSTD)";
            engine.execute(sql, e.params());
            Files.move(tmp, pd.resolve("part.parquet"), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            long rows = count(pd.resolve("part.parquet"));
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("signature", sig);
            meta.put("rows", rows);
            meta.put("sourceBuild", src.buildId());
            meta.put("builtAt", Instant.now(clock).toString());
            Files.writeString(pd.resolve("meta.json"), JSON.writeValueAsString(meta), StandardCharsets.UTF_8);
            builds.incrementAndGet();
            return new Partition(src.date(), pd.resolve("part.parquet"), rows, src.buildId());
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

    /** Deletes the date's partitions of every table (and rollup) of the dataset that reads the kind; returns the tables touched. */
    public List<String> invalidate(String key, Dataset ds, String kind, LocalDate date) {
        List<String> touched = new ArrayList<>();
        for (Dataset.Table t : ds.tablesOfKind(kind)) {
            try {
                TableBuilder.delete(partitionDir(tableDir(key, t.name()), date));
                for (Dataset.Rollup r : ds.rollups()) {
                    if (r.table().equals(t.name())) {
                        TableBuilder.delete(partitionDir(root.resolve("rollups").resolve(key).resolve(r.name()), date));
                    }
                }
            } catch (IOException e) {
                LOG.warn("cannot clear {} {} of {}: {}", t.name(), date, key, e.getMessage());
            }
            latest.remove(key + "|" + t.name());
            liveAt.remove(root.resolve("live").resolve(key).resolve(t.name()).toString());
            touched.add(t.name());
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

    // ---- single flight -----------------------------------------------------------------------------------------------

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
