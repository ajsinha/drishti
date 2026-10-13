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

import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.api.SqlTarget;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.Rollups;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The query engine's front door (RUPAKA.md section 7): takes a visual query against a dataset, for a reader, and answers it from the smallest layer
 * that can. In order: admission (server and per-user concurrency), the result cache, the plan (names checked against the model, joins chosen, row
 * rules attached), the layer (a rollup that covers the query, else the columns layer, else a direct push down or a live snapshot), the compiled
 * parameterised SQL on the engine, the row and byte limits, and the cache. Every refusal carries a DRS-90xx code.
 */
public final class QueryService implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(QueryService.class);

    /** A dataset ready to query: the id it is stored under, its layer key, the parsed model and a fingerprint of its text. */
    public record Loaded(String id, String key, Dataset dataset, String hash) {}

    /** The reader: roles and attributes for the row rules, the masks, whether rules apply at all (security on), and the tables over kinds they may not open. */
    public record Caller(String user, Set<String> roles, boolean enforce, Map<String, List<Object>> attributes, SqlCompiler.Masks masks, Set<String> denied) {

        public Caller(String user, Set<String> roles, boolean enforce, Map<String, List<Object>> attributes, SqlCompiler.Masks masks) {
            this(user, roles, enforce, attributes, masks, Set.of());
        }
    }

    /** What was done, for the preview and the access log. */
    public record Meta(String layer, String rollup, int rows, double millis, boolean cached, List<String> dates, List<String> tables, List<String> masked,
            List<String> warnings, String sql, long generation) {}

    /** The answer: columns (typed) and what was done. */
    public record Result(List<ArrowIpcWriter.Column> columns, int rows, Meta meta) {}

    private final QueryProperties props;
    private final AnalyticEngine engine;
    private final Layers layers;
    private final LiveTables liveTables;
    private final SourceGateway sources;
    private final Cache<String, Result> cache;
    private final Semaphore slots;
    private final Map<String, AtomicInteger> perUser = new ConcurrentHashMap<>();
    private final Executor background = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicLong queries = new AtomicLong();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final Map<String, AtomicLong> byLayer = new ConcurrentHashMap<>();

    public QueryService(QueryProperties props, AnalyticEngine engine, SourceGateway sources, Clock clock) {
        this(props, BiLiveProperties.defaults(), engine, sources, clock);
    }

    public QueryService(QueryProperties props, BiLiveProperties liveProps, AnalyticEngine engine, SourceGateway sources, Clock clock) {
        this.props = props;
        this.engine = engine;
        this.sources = sources;
        this.liveTables = new LiveTables(engine, sources, liveProps, clock);
        this.layers = new Layers(engine, sources, props, clock);
        this.slots = new Semaphore(props.maxConcurrent(), true);
        // one budget in bytes; every entry weighs at least budget / entries, so the entry count is bounded too
        long budget = Math.max(1, props.cacheMaxBytes());
        long floor = props.cacheEntries() == 0 ? budget : Math.max(1, budget / props.cacheEntries());
        this.cache = props.cacheEntries() == 0 ? null : Caffeine.newBuilder().maximumWeight(budget)
                .weigher((String k, Result v) -> (int) Math.min(Integer.MAX_VALUE, Math.max(floor, 256L + (long) v.rows() * Math.max(1, v.columns().size()) * 24)))
                .expireAfterWrite(props.cacheTtl()).build();
    }

    public Layers layers() {
        return layers;
    }

    /** The live tables of the server (RUPAKA.md section 12). */
    public LiveTables live() {
        return liveTables;
    }

    /** Whether a dataset has a live table the engine keeps in memory (live tables are snapshots when {@code drishti.bi.live.enabled} is false). */
    public boolean keepsLive(Dataset d) {
        return liveTables.properties().enabled() && d.tables().values().stream().anyMatch(Dataset.Table::isLive);
    }

    /**
     * The state of a dataset's live tables for a browser, starting them when they are not running: {@code generation}, {@code lastUpdate}, {@code state} and per
     * table the counters. Empty ({@code state: none}) for a dataset without live tables.
     */
    public Map<String, Object> liveTick(Loaded ds) {
        if (keepsLive(ds.dataset())) {
            for (Dataset.Table t : ds.dataset().tables().values()) {
                if (t.isLive()) {
                    liveTables.ensure(ds.key(), ds.dataset(), t, sampler(ds));
                }
            }
            liveTables.touch(ds.key());
        }
        return liveTables.tick(ds.key());
    }

    public QueryProperties properties() {
        return props;
    }

    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("queries", queries.get());
        m.put("cacheHits", hits.get());
        m.put("rejectedBusy", rejected.get());
        m.put("cacheEntries", cache == null ? 0 : cache.estimatedSize());
        m.put("layerBuilds", layers.builds());
        Map<String, Long> l = new LinkedHashMap<>();
        byLayer.forEach((k, v) -> l.put(k, v.get()));
        m.put("byLayer", l);
        return m;
    }

    // ---- query -------------------------------------------------------------------------------------------------------

    public Result query(Loaded ds, QueryRequest q, Caller who, boolean explain) {
        return query(ds, q, who, explain, null, null);
    }

    /**
     * As {@link #query(Loaded, QueryRequest, Caller, boolean)} for a composed or derived dataset: {@code frames} supplies the rows of its one table (the sources
     * asked as the reader, or the Transform's result) when the result cache does not already hold the answer.
     */
    public Result query(Loaded ds, QueryRequest q, Caller who, boolean explain, Frames frames) {
        return query(ds, q, who, explain, frames, null);
    }

    /** As {@link #query(Loaded, QueryRequest, Caller, boolean)} for a model with tables whose rows are another query's answer ({@code staged}, asked as the reader). */
    public Result query(Loaded ds, QueryRequest q, Caller who, boolean explain, StagedTables staged) {
        return query(ds, q, who, explain, null, staged);
    }

    private Result query(Loaded ds, QueryRequest q, Caller who, boolean explain, Frames frames, StagedTables staged) {
        queries.incrementAndGet();
        Frames.Rows rows = null;
        if (frames != null && ds.dataset().framed() && (explain || cache == null || cache.getIfPresent(cacheKey(ds, q, who) + "|frames:" + frames.fingerprint()) == null)) {
            rows = frames.load();                   // the sources are asked before this query takes a place of its own: they are queries too, and must not wait for it
        }
        String stagedKey = staged == null ? "" : "|staged:" + staged.fingerprint();
        Map<String, StagedTables.Answer> inner = Map.of();
        if (staged != null && (explain || cache == null || cache.getIfPresent(cacheKey(ds, q, who) + stagedKey) == null)) {
            inner = innerAnswers(ds.dataset(), q, who, staged);               // likewise asked before this query takes its place
        }
        AtomicInteger mine = perUser.computeIfAbsent(who.user(), u -> new AtomicInteger());
        if (mine.incrementAndGet() > props.maxConcurrentPerUser()) {
            mine.decrementAndGet();
            rejected.incrementAndGet();
            throw new DrishtiException(ErrorCode.QUERY_BUSY, "you already have " + props.maxConcurrentPerUser() + " queries running (drishti.bi.query.max-concurrent-per-user)");
        }
        boolean got = false;
        try {
            String key = cache == null ? null : cacheKey(ds, q, who) + (frames == null ? "" : "|frames:" + frames.fingerprint()) + stagedKey;
            if (key != null && !explain) {
                Result hit = cache.getIfPresent(key);
                if (hit != null) {
                    hits.incrementAndGet();
                    Meta m = hit.meta();
                    return new Result(hit.columns(), hit.rows(), new Meta(m.layer(), m.rollup(), m.rows(), 0, true, m.dates(), m.tables(), m.masked(), m.warnings(), null, m.generation()));
                }
            }
            try {
                got = slots.tryAcquire(props.admission().toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (!got) {
                rejected.incrementAndGet();
                throw new DrishtiException(ErrorCode.QUERY_BUSY, "the server is running " + props.maxConcurrent() + " queries already (drishti.bi.query.max-concurrent); try again");
            }
            Result r = ds.dataset().framed() ? runFramed(ds, q, who, rows != null || frames == null ? rows : frames.load(), explain) : run(ds, q, who, explain, inner);
            if (key != null && !explain) {
                cache.put(key, r);
            }
            return r;
        } finally {
            if (got) {
                slots.release();
            }
            mine.decrementAndGet();
        }
    }

    /** What the history of a live table asks the engine with: the system, no row rules, no masks (the history holds raw values and readers' masks apply when they read it). */
    private LiveTables.Sampler sampler(Loaded ds) {
        Caller system = new Caller("history", Set.of(), false, Map.of(), SqlCompiler.Masks.NONE);
        return q -> query(ds, q, system, false);
    }

    private String cacheKey(Loaded ds, QueryRequest q, Caller who) {
        StringBuilder masked = new StringBuilder();
        for (Dataset.Table t : ds.dataset().tables().values()) {
            for (Dataset.Column c : t.columns().values()) {
                if (who.masks().masked(t.name(), c.name())) {
                    masked.append(t.name()).append('.').append(c.name()).append(',');
                }
            }
        }
        List<String> matched = ds.dataset().security().rows().stream().filter(r -> who.roles().contains(r.role())).map(r -> r.role() + "=" + r.where()).sorted().toList();
        return ds.key() + "|" + ds.hash() + "|g" + layers.generation(ds.key()) + "|L" + liveTables.generation(ds.key()) + "|" + q + "|rows:" + who.enforce() + matched + who.attributes() + "|masked:" + masked
                + "|denied:" + new java.util.TreeSet<>(who.denied()) + (who.enforce() ? TableRules.fingerprint(ds.dataset(), who.roles()) : "");
    }

    /** The rows of a parquet file (a Python Transform's result) as an answer, at most {@code maxRows} (more is DRS-9022). */
    public Result readParquet(java.nio.file.Path file, int maxRows) {
        AnalyticEngine.Answer a = engine.query("SELECT * FROM read_parquet('" + TableBuilder.literal(file) + "')", List.of(), maxRows + 1, props.timeout());
        if (a.rows() > maxRows) {
            throw new DrishtiException(ErrorCode.QUERY_ROWS, "the Transform's result has more than " + maxRows + " rows");
        }
        return new Result(a.columns(), a.rows(), new Meta("transform", null, a.rows(), a.millis(), false, List.of(), List.of(), List.of(), List.of(), null, 0));
    }

    /** The inner answers of the staged tables the query plans to read, asked as the reader. */
    private static Map<String, StagedTables.Answer> innerAnswers(Dataset d, QueryRequest q, Caller who, StagedTables staged) {
        SqlCompiler.Reader reader = new SqlCompiler.Reader(who.enforce(), who.roles(), who.attributes(), HistoryMasks.over(d, who.masks()));
        Map<String, StagedTables.Answer> out = new LinkedHashMap<>();
        for (String t : new SqlCompiler(d, Dialect.DUCKDB).plan(q, reader).tables()) {
            if (staged.has(t)) {
                out.put(t, staged.answer(t));
            }
        }
        return out;
    }

    /** Writes the inner answers as one partition each (files the caller deletes when the query is done). */
    private Map<String, Layers.Layer> stage(Dataset d, Map<String, StagedTables.Answer> inner) {
        Map<String, Layers.Layer> out = new LinkedHashMap<>();
        if (inner.isEmpty()) {
            return out;
        }
        java.nio.file.Path dir = java.nio.file.Path.of(props.storeDir(), "staged");
        try {
            java.nio.file.Files.createDirectories(dir);
        } catch (java.io.IOException e) {
            throw new DrishtiException(ErrorCode.QUERY_INVALID, "cannot write the staged answers under " + dir + ": " + e.getMessage());
        }
        for (Map.Entry<String, StagedTables.Answer> e : inner.entrySet()) {
            java.nio.file.Path file = dir.resolve("s-" + java.util.UUID.randomUUID() + ".parquet");
            long n = engine.with(conn -> StagedTables.write(conn, file, d.tables().get(e.getKey()), e.getValue()));
            out.put(e.getKey(), new Layers.Layer(List.of(new Layers.Partition(e.getValue().date(), file, n, "staged")), List.of(), 0));
        }
        return out;
    }

    private Result run(Loaded ds, QueryRequest q, Caller who, boolean explain, Map<String, StagedTables.Answer> inner) {
        Map<String, Layers.Layer> staged = stage(ds.dataset(), inner);
        try {
            return run(ds, q, who, explain, staged, inner);
        } finally {
            staged.values().forEach(l -> l.partitions().forEach(p -> {
                try {
                    java.nio.file.Files.deleteIfExists(p.file());
                } catch (java.io.IOException ignored) {
                    // scratch: the next start of the store removes what is left
                }
            }));
        }
    }

    private Result run(Loaded ds, QueryRequest q, Caller who, boolean explain, Map<String, Layers.Layer> staged, Map<String, StagedTables.Answer> inner) {
        Dataset d = ds.dataset();
        SqlCompiler.Reader reader = new SqlCompiler.Reader(who.enforce(), who.roles(), who.attributes(), HistoryMasks.over(d, who.masks()));
        Set<String> modes = new LinkedHashSet<>();
        SqlCompiler probe = new SqlCompiler(d, Dialect.DUCKDB);
        SqlCompiler.Plan plan = probe.plan(q, reader);
        plan.tables().forEach(t -> modes.add(d.tables().get(t).mode()));
        if (modes.contains("direct")) {
            for (String t : plan.tables()) {
                if (who.denied().contains(t)) {
                    throw new DrishtiException(ErrorCode.QUERY_MASKED, "you may not open kind '" + d.tables().get(t).kind() + "' (table " + t + ")");
                }
            }
            return direct(ds, q, plan, reader, who, explain);
        }
        List<String> warnings = new ArrayList<>();
        Map<String, Layers.Layer> cols = new LinkedHashMap<>();
        Map<String, LiveTable> inMemory = new LinkedHashMap<>();
        Map<String, LiveHistory> histories = new LinkedHashMap<>();
        for (String t : plan.tables()) {
            Dataset.Table table = d.tables().get(t);
            if (staged.containsKey(t)) {
                cols.put(t, staged.get(t));
                inner.get(t).result().meta().warnings().forEach(w -> warnings.add(t + ": " + w));
                continue;
            }
            if (who.denied().contains(t)) {
                cols.put(t, new Layers.Layer(List.of(), List.of(), 0));
                warnings.add("you may not open " + table.kind() + ": " + t + " has no rows for you");
                continue;
            }
            if (table.isHistory()) {
                if (!liveTables.properties().enabled()) {
                    throw new DrishtiException(ErrorCode.QUERY_INVALID, "table '" + t + "' is the history of the live table " + table.of() + ", and live tables are off (drishti.bi.live.enabled)");
                }
                LiveTable lt = liveTables.ensure(ds.key(), d, d.tables().get(table.of()), sampler(ds));
                histories.put(t, lt.history().orElseThrow(() -> new DrishtiException(ErrorCode.QUERY_INVALID, "the history of " + table.of() + " is not running")));
                cols.put(t, new Layers.Layer(List.of(), List.of(), 0));
                continue;
            }
            if (table.isLive() && liveTables.properties().enabled()) {
                LiveTable lt = liveTables.ensure(ds.key(), d, table, sampler(ds));
                inMemory.put(t, lt);
                cols.put(t, new Layers.Layer(List.of(), List.of(), 0));
                if (lt.state() == LiveTable.State.FAILED) {
                    warnings.add("the live feed of " + t + " is failing: " + lt.status().error());
                } else if (lt.status().error() != null) {
                    warnings.add("live " + t + ": " + lt.status().error());
                }
                continue;
            }
            Layers.Layer layer = "live".equals(table.mode()) ? layers.live(ds.key(), table) : layers.columns(ds.key(), table, q.from(), q.to());
            cols.put(t, layer);
            warnings.addAll(layer.warnings());
        }
        Dataset.Rollup rollup = "columns".equals(q.layer()) || !staged.isEmpty() || plan.tables().stream().anyMatch(who.denied()::contains) ? null : route(d, plan, q, inMemory);
        if ("rollup".equals(q.layer()) && rollup == null) {
            throw new DrishtiException(ErrorCode.QUERY_INVALID, "no rollup of this dataset can answer the query (layer: rollup was asked for)");
        }
        Map<String, SqlCompiler.Source> from = new LinkedHashMap<>();
        Map<String, String> components = null;
        String layerName = modes.contains("live") || modes.contains("history") ? "live" : "columns";
        if (rollup != null && inMemory.get(rollup.table()) != null) {
            LiveAggregate kept = inMemory.get(rollup.table()).aggregate(rollup.name()).orElseThrow();
            components = Layers.componentColumns(d, rollup);
            from.put(rollup.table(), new SqlCompiler.Source("(SELECT * FROM " + kept.table() + ") AS " + Dialect.quote(rollup.table()), List.of()));
        } else if (rollup != null) {
            Layers.Layer rl = layers.rollup(ds.key(), d, rollup, cols.get(rollup.table()));
            components = Layers.componentColumns(d, rollup);
            String rel = rl.files().isEmpty() ? emptyRollup(d, rollup, components) : "SELECT * FROM " + files(rl.files());
            from.put(rollup.table(), new SqlCompiler.Source("(" + rel + ") AS " + Dialect.quote(rollup.table()), List.of()));
            for (String t : plan.tables()) {                                       // a dimension joined for its row rules reads its columns layer
                if (!t.equals(rollup.table())) {
                    from.put(t, TableRelations.cached(d, d.tables().get(t), cols.get(t).files(), Dialect.DUCKDB));
                }
            }
            layerName = "rollup";
        } else {
            for (String t : plan.tables()) {
                LiveTable lt = inMemory.get(t);
                LiveHistory hh = histories.get(t);
                from.put(t, hh != null ? hh.source(d, Dialect.DUCKDB) : lt != null ? lt.source(d, Dialect.DUCKDB) : TableRelations.cached(d, d.tables().get(t), cols.get(t).files(), Dialect.DUCKDB));
            }
        }
        int cap = q.limit() != null ? Math.min(q.limit(), props.maxRows()) : props.maxRows() + 1;
        List<String> dates = new ArrayList<>();
        cols.values().forEach(l -> l.partitions().forEach(p -> {
            if (p.date() != null && p.rows() > 0 && !dates.contains(p.date().toString())) {
                dates.add(p.date().toString());
            }
        }));
        java.util.Collections.sort(dates);
        SqlCompiler.Compiled c = probe.compile(plan, reader, from, cap, components, timeContext(q, dates));
        AnalyticEngine.Answer a = engine.query(c.sql(), c.params(), cap, props.timeout());
        return finish(ds, q, c, a, layerName, rollup == null ? null : rollup.name(), dates, warnings, explain);
    }

    /** The compiled query and its answer, from a connection that holds the staging tables. */
    private record Framed(SqlCompiler.Compiled compiled, AnalyticEngine.Answer answer) {}

    /**
     * A composed or derived dataset's query (RUPAKA.md section 20.10): the one table is the join of the sources' answers (staged in the engine) or the
     * Transform's result file, and the dataset's own measures, row rules and masks run over it like over any table. The sources were asked as the reader, so what
     * the reader may not see never reaches this table.
     */
    private Result runFramed(Loaded ds, QueryRequest q, Caller who, Frames.Rows rows, boolean explain) {
        Dataset d = ds.dataset();
        if (rows == null) {
            throw new DrishtiException(ErrorCode.QUERY_INVALID, "dataset '" + d.name() + "' is " + (d.composed() ? "composed" : "derived") + ": it is queried through the dataset service, which asks its sources");
        }
        SqlCompiler.Masks own = who.masks();
        SqlCompiler.Reader reader = new SqlCompiler.Reader(who.enforce(), who.roles(), who.attributes(), (table, column) -> own.masked(table, column) || rows.masked().contains(column));
        SqlCompiler probe = new SqlCompiler(d, Dialect.DUCKDB);
        SqlCompiler.Plan plan = probe.plan(q, reader);
        int cap = q.limit() != null ? Math.min(q.limit(), props.maxRows()) : props.maxRows() + 1;
        TimeWindows.Context time = timeContext(q, rows.dates());
        Framed f = engine.with(conn -> {
            Map<String, String> staged = Map.of();
            try {
                SqlCompiler.Source src;
                if (d.composed()) {
                    staged = FrameSql.stageAll(conn, d.compose(), rows.answers());
                    src = new SqlCompiler.Source("(" + FrameSql.joined(d, staged) + ") AS " + Dialect.quote(Dataset.JOINED), List.of());
                } else {
                    Set<String> present = new LinkedHashSet<>();
                    try (java.sql.Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT column_name FROM (DESCRIBE SELECT * FROM read_parquet('"
                            + TableBuilder.literal(rows.parquet()) + "'))")) {
                        while (rs.next()) {
                            present.add(rs.getString(1));
                        }
                    }
                    src = FrameSql.parquet(d, rows.parquet(), present);
                }
                String table = d.composed() ? Dataset.JOINED : Dataset.RESULT;
                SqlCompiler.Compiled c = probe.compile(plan, reader, Map.of(table, src), cap, null, time);
                long t0 = System.nanoTime();
                try (PreparedStatement ps = conn.prepareStatement(c.sql())) {
                    ps.setQueryTimeout((int) Math.max(1, props.timeout().toSeconds()));
                    for (int i = 0; i < c.params().size(); i++) {
                        ps.setObject(i + 1, c.params().get(i));
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        List<ArrowIpcWriter.Column> cols = DuckDbEngine.read(rs, cap);
                        return new Framed(c, new AnalyticEngine.Answer(cols, cols.isEmpty() ? 0 : cols.get(0).values().length, (System.nanoTime() - t0) / 1e6));
                    }
                }
            } finally {
                FrameSql.drop(conn, staged.values());
            }
        });
        return finish(ds, q, f.compiled(), f.answer(), d.composed() ? "composed" : "derived", null, rows.dates(), new ArrayList<>(rows.warnings()), explain);
    }

    /**
     * The as-of date of the time-intelligence measures: the end of the query's dates, else its start, else the newest date the query read, else the current
     * business date.
     */
    private TimeWindows.Context timeContext(QueryRequest q, List<String> readDates) {
        LocalDate asOf = q.to() != null ? q.to() : q.from() != null ? q.from() : !readDates.isEmpty() ? LocalDate.parse(readDates.get(readDates.size() - 1)) : sources.currentDate();
        return new TimeWindows.Context(asOf, q.from(), sources.calendar());
    }

    private Result finish(Loaded ds, QueryRequest q, SqlCompiler.Compiled c, AnalyticEngine.Answer a, String layer, String rollup, List<String> dates,
            List<String> warnings, boolean explain) {
        if (q.limit() == null && a.rows() > props.maxRows()) {
            throw new DrishtiException(ErrorCode.QUERY_ROWS, "the answer has more than " + props.maxRows() + " rows (drishti.bi.query.max-rows): group by less, filter, or set a limit");
        }
        byLayer.computeIfAbsent(layer, k -> new AtomicLong()).incrementAndGet();
        List<String> masked = c.columns().stream().filter(SqlCompiler.Out::masked).map(SqlCompiler.Out::name).toList();
        List<String> w = new ArrayList<>(warnings);
        w.addAll(c.notes());
        Meta meta = new Meta(layer, rollup, a.rows(), Math.round(a.millis() * 100) / 100.0, false, dates, new ArrayList<>(c.tables()), masked, w.stream().distinct().toList(),
                explain ? c.sql() : null, layers.generation(ds.key()));
        return new Result(a.columns(), a.rows(), meta);
    }

    // ---- routing -----------------------------------------------------------------------------------------------------

    /**
     * The first rollup that can answer the plan, or null: one fact table, only its grouped columns, only measures made of its stored aggregates. Security-aware:
     * the columns of the reader's row rules on the fact must be grouped by the rollup, and a dimension joined only for its row rules (a dimension's rules reach
     * the facts that reference it) may stay when the fact joins it directly, many-to-one, on a column the rollup groups by: each rollup row then belongs to
     * exactly one row of the dimension, so the rule keeps or drops it whole, as it would each fact row.
     */
    Dataset.Rollup route(Dataset d, SqlCompiler.Plan plan, QueryRequest q, Map<String, LiveTable> inMemory) {
        if (d.rollups().isEmpty() || plan.measures().isEmpty() || plan.multiFact()) {
            return null;
        }
        String table = plan.tables().size() == 1 ? plan.tables().iterator().next() : plan.base();
        Set<String> joinedOn = ruleOnlyJoins(d, plan, table);
        if (table == null || joinedOn == null) {
            return null;
        }
        if (d.tables().get(table).shape() != null) {
            return null;                                                       // a de-duplicated or grouped table: its rollup would add the rows it drops
        }
        boolean live = "live".equals(d.tables().get(table).mode());
        if (live && inMemory.get(table) == null) {
            return null;                                                       // a live table kept as a snapshot has no aggregates to read
        }
        if (plan.filters().stream().anyMatch(f -> f.measure() != null)) {
            return null;
        }
        Set<String> used = new LinkedHashSet<>(plan.columnsOf(table));
        for (String rc : plan.ruleColumns(d)) {
            if (rc.startsWith(table + ".")) {                                     // the fact's own rules read its grouped columns; a dimension's are joined
                used.add(rc.substring(rc.indexOf('.') + 1));
            }
        }
        used.addAll(joinedOn);
        for (Dataset.Rollup r : d.rollups()) {
            if (!r.table().equals(table) || live && inMemory.get(table).aggregate(r.name()).isEmpty()) {
                continue;
            }
            Set<String> by = new LinkedHashSet<>(r.by());
            by.add(Dataset.DATE);
            if (!by.containsAll(used)) {
                continue;
            }
            Map<String, String> have = Layers.componentColumns(d, r);
            boolean ok = true;
            for (String m : plan.measureNames()) {
                Map<String, com.ash.drishti.server.bi.dataset.Node.Call> need = Rollups.components(d, d.measures().get(m), table);
                if (need == null || !have.keySet().containsAll(need.keySet())) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                return r;
            }
        }
        return null;
    }

    /**
     * The fact's columns that join the plan's other tables, when every other table is a dimension joined directly from the fact (many-to-one) and only for
     * its row rules (no field or filter reads it); an empty set for a one-table plan; null when a rollup cannot answer the plan.
     */
    static Set<String> ruleOnlyJoins(Dataset d, SqlCompiler.Plan plan, String fact) {
        Set<String> out = new LinkedHashSet<>();
        if (fact == null || plan.tables().size() == 1) {
            return out;
        }
        if (plan.fields().stream().anyMatch(f -> f.ref() == null || !fact.equals(f.ref().table()))
                || plan.filters().stream().anyMatch(f -> f.ref() != null && !fact.equals(f.ref().table()))) {
            return null;
        }
        Set<String> joined = new LinkedHashSet<>();
        for (SqlCompiler.Join j : plan.joins()) {
            Dataset.TableSecurity s = d.security().tables().get(j.toTable());
            if (!fact.equals(j.fromTable()) || s == null || !s.dimension() || !plan.tables().contains(j.toTable())) {
                return null;                                                       // a chain, a fact on the one side, or a table read for more than its rules
            }
            joined.add(j.toTable());
            out.add(j.fromColumn());
        }
        Set<String> others = new LinkedHashSet<>(plan.tables());
        others.remove(fact);
        return joined.equals(others) ? out : null;
    }

    /** No date to read: a rollup-shaped table with no rows, so the aggregates are null (counts 0) instead of an error. */
    private static String emptyRollup(Dataset d, Dataset.Rollup r, Map<String, String> components) {
        List<String> cols = new ArrayList<>();
        cols.add("CAST(NULL AS DATE) AS " + Dialect.quote(Dataset.DATE));
        for (String by : r.by()) {
            cols.add("CAST(NULL AS " + TableRelations.sqlType(d.tables().get(r.table()).columns().get(by).type(), Dialect.DUCKDB) + ") AS " + Dialect.quote(by));
        }
        components.values().forEach(c -> cols.add("CAST(NULL AS DOUBLE) AS " + Dialect.quote(c)));
        return "SELECT " + String.join(", ", cols) + " WHERE 1 = 0";
    }

    private static String files(List<java.nio.file.Path> files) {
        if (files.isEmpty()) {
            return "(SELECT 1 AS x WHERE FALSE)";
        }
        return "read_parquet([" + String.join(", ", files.stream().map(f -> "'" + TableBuilder.literal(f) + "'").toList()) + "], union_by_name = true)";
    }

    // ---- direct (push down) ------------------------------------------------------------------------------------------

    private Result direct(Loaded ds, QueryRequest q, SqlCompiler.Plan plan, SqlCompiler.Reader reader, Caller who, boolean explain) {
        Dataset d = ds.dataset();
        SqlTarget target = null;
        for (String t : plan.tables()) {
            Dataset.Table table = d.tables().get(t);
            if (!"direct".equals(table.mode())) {
                throw new DrishtiException(ErrorCode.QUERY_INVALID, "table '" + t + "' is " + table.mode() + " and another table of the query is direct: a direct query joins only direct tables of one store");
            }
            SourcePlugin p = sources.plugin(table.connector(), table.kind()).orElseThrow(() -> new DrishtiException(ErrorCode.QUERY_SOURCE,
                    "no running connector serves kind '" + table.kind() + "'"));
            SqlTarget st = p.sqlTarget().orElseThrow(() -> new DrishtiException(ErrorCode.QUERY_SOURCE,
                    "connector '" + p.manifest().name() + "' is not a SQL store, so table '" + t + "' cannot be direct: use mode: cached"));
            if (target != null && target != st && !sameStore(target, st)) {
                throw new DrishtiException(ErrorCode.QUERY_INVALID, "direct tables of one query must live in one store; '" + t + "' is in another connector");
            }
            target = st;
        }
        Dialect dialect = Dialect.of(target.dialect());
        SqlCompiler compiler = new SqlCompiler(d, dialect);
        SqlCompiler.Plan p = compiler.plan(q, reader);
        Map<String, SqlCompiler.Source> from = new LinkedHashMap<>();
        for (String t : p.tables()) {
            from.put(t, TableRelations.direct(d, d.tables().get(t), target, dialect, q.from(), q.to()));
        }
        int cap = q.limit() != null ? Math.min(q.limit(), props.maxRows()) : props.maxRows() + 1;
        SqlCompiler.Compiled c = compiler.compile(p, reader, from, cap, null, timeContext(q, List.of()));
        long t0 = System.nanoTime();
        AnalyticEngine.Answer a;
        try {
            a = target.with(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(c.sql())) {
                    ps.setQueryTimeout((int) Math.max(1, props.timeout().toSeconds()));
                    for (int i = 0; i < c.params().size(); i++) {
                        ps.setObject(i + 1, c.params().get(i));
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        List<ArrowIpcWriter.Column> cols = DuckDbEngine.read(rs, cap);
                        return new AnalyticEngine.Answer(cols, cols.isEmpty() ? 0 : cols.get(0).values().length, (System.nanoTime() - t0) / 1e6);
                    }
                }
            });
        } catch (DrishtiException e) {
            throw e;
        } catch (SQLException e) {
            throw new DrishtiException(e.getMessage() != null && e.getMessage().toLowerCase().contains("timeout") ? ErrorCode.QUERY_TIMEOUT : ErrorCode.QUERY_SOURCE,
                    "the store could not run the query: " + e.getMessage().lines().findFirst().orElse(""));
        } catch (Exception e) {
            throw new DrishtiException(ErrorCode.QUERY_SOURCE, "the store could not run the query: " + e.getMessage());
        }
        return finish(ds, q, c, a, "direct", null, List.of(), List.of(), explain);
    }

    private static boolean sameStore(SqlTarget a, SqlTarget b) {
        return a.equals(b);
    }

    // ---- data loads --------------------------------------------------------------------------------------------------

    /**
     * A data-load signal for a kind on a business date: clears the date's partitions of every table of the given datasets that read the kind,
     * drops their cached results, and (unless disabled) rebuilds the date in the background for tables that ask for it. Returns the datasets touched.
     */
    public List<String> onLoad(String kind, LocalDate date, List<Loaded> datasets) {
        List<String> touched = new ArrayList<>();
        for (Loaded ds : datasets) {
            List<String> tables = layers.invalidate(ds.key(), ds.dataset(), kind, date);
            if (tables.isEmpty()) {
                continue;
            }
            touched.add(ds.id());
            if (cache != null) {
                cache.asMap().keySet().removeIf(k -> k.startsWith(ds.key() + "|"));
            }
            if (props.eagerRebuild()) {
                for (Dataset.Table t : ds.dataset().tablesOfKind(kind)) {
                    if (t.onLoad() && "cached".equals(t.mode())) {
                        background.execute(() -> rebuild(ds, t, date));
                    }
                }
            }
        }
        return touched;
    }

    /** What a refresh did: the date rebuilt, the tables, the rows read and how long it took. */
    public record Refreshed(String date, List<String> tables, long rows, long millis, List<String> warnings) {}

    /**
     * Rebuilds the layers of a dataset for one business date, now and on this thread, so a failure comes back to the caller (the scheduler retries it with the
     * shared policy): the date's partitions of the cached tables are cleared, the cached results of the dataset dropped, and the columns and rollup layers read
     * again. Live and direct tables hold no layer and are left alone.
     *
     * @param only the tables to rebuild, or null for every cached table of the dataset
     */
    public Refreshed refresh(Loaded ds, java.util.Collection<String> only, LocalDate date) {
        long t0 = System.nanoTime();
        List<Dataset.Table> tables = ds.dataset().tables().values().stream()
                .filter(t -> "cached".equals(t.mode()) && (only == null || only.contains(t.name()))).toList();
        for (String kind : tables.stream().map(Dataset.Table::kind).distinct().toList()) {
            layers.invalidate(ds.key(), ds.dataset(), kind, date);
        }
        if (cache != null) {
            cache.asMap().keySet().removeIf(k -> k.startsWith(ds.key() + "|"));
        }
        long rows = 0;
        List<String> warnings = new ArrayList<>();
        String shown = date.toString();
        for (Dataset.Table t : tables) {
            Layers.Layer l = layers.columns(ds.key(), t, date, date);
            if (l.rows() == 0) {
                // The business date has no data in this source: do not keep an empty layer for it. Resolve the as-of as a query does (the latest the source
                // holds) and say so, so the dataset keeps answering from real rows.
                layers.invalidate(ds.key(), ds.dataset(), t.kind(), date);
                Layers.Layer latest = layers.columns(ds.key(), t, null, null);
                String found = latest.partitions().stream().filter(x -> x.rows() > 0).map(x -> String.valueOf(x.date())).findFirst().orElse(null);
                if (found == null) {
                    layers.invalidate(ds.key(), ds.dataset(), t.kind(), date);
                    warnings.add("warning: 0 rows for " + date + " in " + t.name() + "; the source holds no data");
                } else {
                    warnings.add("warning: 0 rows for " + date + "; latest data " + found);
                    shown = found;
                    l = latest;
                }
            }
            rows += l.rows();
            warnings.addAll(l.warnings());
            for (Dataset.Rollup r : ds.dataset().rollups()) {
                if (r.table().equals(t.name())) {
                    layers.rollup(ds.key(), ds.dataset(), r, l);
                }
            }
        }
        return new Refreshed(shown, tables.stream().map(Dataset.Table::name).toList(), rows, Math.round((System.nanoTime() - t0) / 1e6), warnings.stream().distinct().toList());
    }

    private void rebuild(Loaded ds, Dataset.Table t, LocalDate date) {
        try {
            Layers.Layer l = layers.columns(ds.key(), t, date, date);
            for (Dataset.Rollup r : ds.dataset().rollups()) {
                if (r.table().equals(t.name())) {
                    layers.rollup(ds.key(), ds.dataset(), r, l);
                }
            }
            LOG.info("BI layer rebuilt: {} {} {} ({} rows)", ds.id(), t.name(), date, l.rows());
        } catch (RuntimeException e) {
            LOG.warn("BI layer rebuild of {} {} {} failed: {}", ds.id(), t.name(), date, e.getMessage());
        }
    }

    @Override
    public void close() {
        liveTables.close();
        engine.close();
    }
}
