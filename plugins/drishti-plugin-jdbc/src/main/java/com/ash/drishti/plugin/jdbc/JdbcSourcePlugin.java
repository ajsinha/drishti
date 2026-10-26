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

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.api.SourceCapabilities;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.api.pool.PoolSettings;
import com.ash.drishti.api.pool.PoolStats;
import com.ash.drishti.api.pool.ResourcePool;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Reads entities with one parameterised query per kind. The JDBC driver is supplied by the site (on the
 * class path or in the plugin directory).
 *
 * <p>Settings: {@code url}, {@code user}, {@code password} (from the environment), {@code source-name}
 * (default {@code jdbc}), {@code pool-size} (default 20, or the site's {@code drishti.db.pool.size}; with {@code pool-min-idle}, {@code pool-idle-timeout}, {@code pool-max-lifetime}, {@code pool-connection-timeout}, {@code pool-leak-threshold} and {@code statement-timeout}: see {@link JdbcPool}), and {@code query.<kind>}: SQL with one {@code ?}
 * for the id, or named parameters {@code :id} and {@code :asOf} (the business date, a SQL {@code DATE}). A query
 * that uses {@code :asOf} makes the source dated, for example
 * {@code ... WHERE trade_id = :id AND business_date = (SELECT MAX(business_date) FROM trades
 * WHERE trade_id = :id AND business_date <= :asOf)}. The first row becomes the document: each column is a field,
 * except a column named {@code json}, whose JSON text is used as the whole document. A column named
 * {@code generation}, if present, is the version; {@code business_date}, if present, is the date the row is for.
 * More queries per kind ({@code query.<kind>.<part>} for nested parts, {@code ids.<kind>} for type-ahead,
 * {@code columns.<kind>} for searches over a day's promoted fields, {@code reverse.<kind>} for reverse lookups) are
 * described in {@link QueryMode} and {@code docs/connectors/JDBC_QUERIES.md}.
 * Connections are pooled ({@link JdbcPool}), each opened on first use and replaced whenever it is found broken, so the
 * connector starts while the database is down and recovers by itself when it comes back; every borrow is for one unit of work.
 *
 * <p><b>Table mode</b> ({@code table: reference.entities}): every kind of a data domain in one PostgreSQL table of
 * {@code (kind, id, business_date, doc jsonb)} rows, dated, with search and reverse lookups; {@code mode.<kind>}
 * is {@code snapshot} (default) or {@code effective}, partitioned by month for scale. See {@link PostgresLayout},
 * {@link TableCatalog} and {@code docs/connectors/POSTGRES_CONNECTOR.md}.
 */
public final class JdbcSourcePlugin implements SourcePlugin {

    private QueryMode queryMode;
    private EntityTable table;
    private TableCatalog catalog;
    private final Map<String, String> modes = new LinkedHashMap<>();
    private int maxLoadRows = 200_000;
    private boolean reverseIndex = true;
    private volatile java.util.List<String> tableKinds = java.util.List.of();   // discovered later when the database starts after us
    private volatile ResourcePool<Connection> pool;
    /** The pool, for the table catalogue and query mode. */
    private final TableCatalog.Db db = new TableCatalog.Db() {
        @Override
        public <T> T with(TableCatalog.Work<T> work) throws Exception {
            return withConnection(work::run);
        }
    };
    private volatile String lastError;
    private SourceContext context;
    private String url;
    private JdbcTls.Plan tlsPlan = JdbcTls.Plan.NONE;
    private String user;
    private String password;
    private String sourceName;

    @Override
    public PluginManifest manifest() {
        if (table != null) {
            return new PluginManifest("jdbc", "1.0", new java.util.HashSet<>(tableKinds), new SourceCapabilities(false, true, true, true));
        }
        if (queryMode == null) {
            return new PluginManifest("jdbc", "1.0", java.util.Set.of(), new SourceCapabilities(false, false, false, false));
        }
        return new PluginManifest("jdbc", "1.0", queryMode.kinds(),
                new SourceCapabilities(false, queryMode.reverses(), queryMode.searches(), queryMode.dated()));
    }

    @Override
    public Optional<String> writeKey() {
        return Optional.of(JdbcWrites.KEY);
    }

    @Override
    public java.util.List<String> checkWrite(String command, Map<String, Object> block, java.util.Set<String> known) {
        return JdbcWrites.check(command, block, known);
    }

    /** One {@code writes:} command (WRITE_BACK.md section 5.4) on a pooled connection, in its own transaction ({@link JdbcWrites}). */
    @Override
    public com.ash.drishti.api.write.WriteResult write(com.ash.drishti.api.write.WriteCommand command) throws Exception {
        return withConnection(c -> JdbcWrites.run(c, command));
    }

    /**
     * Runs {@code work} on a pooled connection: borrowed for this call only and returned at once, whatever happens. A failure to
     * connect fails this call only; the next call tries again.
     */
    private <T> T withConnection(SqlWork<T> work) throws Exception {
        try (var lease = borrow()) {
            Connection c = lease.get();
            try {
                T out = work.run(c);
                lastError = null;
                return out;
            } catch (SQLException e) {
                // the connection died mid-call, or its cached plans predate a table that was dropped and recreated (a reload,
                // SQL state 0A000 "cached plan must not change result type"): the pool closes it instead of reusing it
                boolean broken = !c.isValid(1);
                if ("0A000".equals(e.getSQLState()) || broken) {
                    lease.invalidate();
                }
                if (broken) {
                    lastError = e.getMessage();   // the database went away: DOWN until a call succeeds
                }
                // else a statement failed on a live connection (bad SQL, a missing table): the caller reports it; the source
                // is not down
                throw e;
            }
        }
    }

    private ResourcePool<Connection>.Lease borrow() throws Exception {
        try {
            return pool.borrow();
        } catch (com.ash.drishti.api.pool.PoolExhaustedException e) {
            throw new SQLException(e.getMessage(), e);
        } catch (SQLException e) {
            lastError = e.getMessage();
            throw e;
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection c) throws Exception;
    }

    @Override
    public java.util.List<com.ash.drishti.api.EntityRef> reverse(EntityRef target, String kind) {
        return reverse(target, kind, com.ash.drishti.api.AsOf.LATEST);
    }

    @Override
    public java.util.List<com.ash.drishti.api.EntityRef> reverse(EntityRef target, String kind, com.ash.drishti.api.AsOf asOf) {
        if (!reverseIndex) {
            return java.util.List.of();
        }
        if (table == null) {
            if (queryMode == null) {
                return java.util.List.of();
            }
            java.util.List<EntityRef> out = new java.util.ArrayList<>();
            for (String k : kind == null ? queryMode.reverseKinds() : java.util.Set.of(kind)) {
                try {
                    queryMode.reverse(k, target.id(), asOf.businessDate()).forEach(i -> out.add(EntityRef.of(k, i)));
                } catch (Exception e) {
                    // no referrers from here, not an error page
                }
            }
            return out;
        }
        java.util.List<EntityRef> out = new java.util.ArrayList<>();
        for (String k : kind == null ? tableKinds : java.util.List.of(kind)) {
            if (!tableKinds.contains(k)) {
                continue;                                      // a kind this table does not hold: no query
            }
            try {
                // promoted link columns (nettingSet, book, counterparty.id …) of the day: no document is read
                Optional<java.util.Set<String>> fromColumns = catalog.referrers(k, target.id(), asOf.businessDate());
                java.util.Collection<String> ids = fromColumns.isPresent() ? fromColumns.get()
                        : withConnection(c -> table.reverse(c, k, target.id(), asOf.businessDate(), maxLoadRows));
                ids.forEach(i -> out.add(EntityRef.of(k, i)));
            } catch (Exception e) {
                // no referrers from here, not an error page
            }
        }
        return out;
    }

    @Override
    public java.util.List<com.ash.drishti.api.EntityHit> search(String kind, String text, int limit) {
        if (table == null) {
            return queryMode == null ? java.util.List.of() : queryMode.search(kind, text, limit);
        }
        return catalog.search(kind, text, limit);               // in memory: the newest day's ids, never a query per keystroke
    }

    @Override
    public java.util.List<com.ash.drishti.api.EntityHit> search(String kind, String text, int limit, com.ash.drishti.api.AsOf asOf) {
        if (table == null) {
            return search(kind, text, limit);
        }
        return catalog.search(kind, text, limit, asOf);          // the date's own ids when the kept days hold them
    }

    @Override
    public void start(SourceContext ctx) throws SQLException {
        this.context = ctx;
        this.url = ctx.setting("url", "");
        if (url.isEmpty()) {
            throw new com.ash.drishti.api.PluginNotConfigured("jdbc needs settings.url");
        }
        this.tlsPlan = JdbcTls.plan(ctx.settings(), url, System::getenv);     // fails the start, naming the setting and the file
        this.user = ctx.setting("user", "");
        this.password = ctx.setting("password", "");
        this.sourceName = ctx.setting("source-name", "jdbc");
        java.util.Properties props = new java.util.Properties();
        props.putAll(tlsPlan.properties());
        props.setProperty("user", user);
        props.setProperty("password", password);
        // pool-size (default 20, or the site's drishti.db.pool.size) and the other pool-* keys; connections open on first use,
        // because the database may still be starting
        this.pool = JdbcPool.create(sourceName, PoolSettings.resolve(ctx.settings()), url, props);
        String t = ctx.setting("table", "");
        if (t.isBlank()) {
            // query mode: your own SQL, several queries per kind; type-ahead ids now and every refresh-seconds
            queryMode = new QueryMode(ctx, db, sourceName);
            if (queryMode.searches()) {
                Thread.ofVirtual().name("jdbc-ids-" + sourceName).start(queryMode::refreshIds);
                long every = Long.parseLong(ctx.setting("refresh-seconds", "60"));
                if (ctx.scheduler() != null) {
                    ctx.scheduler().scheduleWithFixedDelay(queryMode::refreshIds, every, every, TimeUnit.SECONDS);
                }
            }
        } else {
            ctx.settings().forEach((k, v) -> {
                if (k.startsWith("mode.")) {
                    modes.put(k.substring(5), v);
                }
            });
            table = new EntityTable(t, ctx.setting("kind-column", "kind"), ctx.setting("id-column", "id"),
                    ctx.setting("date-column", "business_date"), ctx.setting("doc-column", "doc"), modes,
                    Integer.parseInt(ctx.setting("lookback-days", "10")));
            Map<String, java.util.List<String>> promoted = new LinkedHashMap<>();
            ctx.settings().forEach((k, v) -> {
                if (k.startsWith("layout.") && k.endsWith(".columns")) {
                    promoted.put(k.substring(7, k.length() - 8), java.util.Arrays.stream(v.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList());
                }
            });
            this.maxLoadRows = Integer.parseInt(ctx.setting("max-load-rows", "200000"));
            this.reverseIndex = Boolean.parseBoolean(ctx.setting("reverse-index", "true"));
            catalog = new TableCatalog(table, db, sourceName, k -> "effective".equals(modes.get(k)), Integer.parseInt(ctx.setting("lookback-days", "10")), promoted,
                    Integer.parseInt(ctx.setting("scan-threads", "4")), Long.parseLong(ctx.setting("columns-cache-mb", "1024")),
                    java.time.Duration.ofSeconds(Long.parseLong(ctx.setting("columns-seconds", "300"))), ctx.settings());
            String configured = ctx.setting("kinds", "");
            java.util.List<String> fixed = configured.isBlank() ? java.util.List.of()
                    : java.util.Arrays.stream(configured.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
            if (!fixed.isEmpty()) {
                tableKinds = fixed;
            }
            // the dates, the newest day's ids and columns, now and every refresh-seconds; the database may start after us
            Runnable refresh = () -> {
                if (catalog.refresh(fixed) && fixed.isEmpty()) {
                    tableKinds = java.util.List.copyOf(catalog.kinds());
                }
            };
            refresh.run();
            long every = Long.parseLong(ctx.setting("refresh-seconds", "60"));
            if (ctx.scheduler() != null) {
                ctx.scheduler().scheduleWithFixedDelay(refresh, Math.min(10, every), every, TimeUnit.SECONDS);
            }
        }
    }

    private String modeOf(String kind) {
        return modes.getOrDefault(kind, "snapshot");
    }

    @Override
    public java.util.Set<String> columnar(String kind) {
        return catalog != null ? catalog.columnar(kind) : queryMode != null ? queryMode.columnar(kind) : java.util.Set.of();
    }

    @Override
    public Optional<com.ash.drishti.api.ColumnSet> columns(String kind, java.util.Collection<String> paths, com.ash.drishti.api.AsOf asOf) {
        return catalog != null ? catalog.columns(kind, paths, asOf.businessDate())
                : queryMode != null ? queryMode.columns(kind, paths, asOf.businessDate()) : Optional.empty();
    }

    @Override
    public Optional<com.ash.drishti.api.ArrowScan> scan(com.ash.drishti.api.ScanRequest request) {
        return com.ash.drishti.api.ColumnScans.over(this, request);
    }

    @Override
    public Optional<com.ash.drishti.api.SqlTarget> sqlTarget() {
        if (table == null || pool == null || !tableKinds.stream().findAny().isPresent()) {
            return Optional.empty();
        }
        EntityTable t = table;
        return Optional.of(new com.ash.drishti.api.SqlTarget() {
            @Override
            public String dialect() {
                return "postgresql";
            }

            @Override
            public Relation relation(String kind) {
                return tableKinds.contains(kind) ? t.relation(columnar(kind)) : null;
            }

            @Override
            public <T> T with(Work<T> work) throws Exception {
                return withConnection(work::run);
            }
        });
    }

    @Override
    public Instant lastUpdate() {
        return catalog == null ? null : catalog.loadedAt();
    }

    @Override
    public java.util.Optional<com.ash.drishti.api.DatedHitIndex> typeAheadIndex() {
        return catalog != null && catalog.dated().enabled() ? java.util.Optional.of(catalog.dated()) : java.util.Optional.empty();
    }

    @Override
    public Map<String, Object> cacheStats() {
        return catalog != null ? catalog.stats() : queryMode != null ? queryMode.stats() : Map.of();
    }

    @Override
    public void purgeCaches() {
        if (catalog != null) {
            catalog.clear();
        }
        if (queryMode != null) {
            queryMode.clear();
        }
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref) throws Exception {
        return fetch(ref, com.ash.drishti.api.AsOf.LATEST);
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref, com.ash.drishti.api.AsOf asOf) throws Exception {
        if (table != null) {
            if (!tableKinds.contains(ref.kind())) {
                if (!catalog.catalogued()) {
                    // the database has not answered yet: whether it holds the kind is unknown, so this is a failure
                    // (DRS-1003), not "not held" (which would let another store answer with other data)
                    throw new java.sql.SQLException(sourceName + " has not read its table yet: " + catalog.problem());
                }
                return Optional.empty();
            }
            Optional<EntityTable.Hit> hit;
            if (catalog.known(ref.kind()) && !"effective".equals(modeOf(ref.kind()))) {
                // the snapshot date from memory, then a primary-key lookup in that date's partition
                Optional<java.time.LocalDate> day = catalog.snapshotDate(ref.kind(), asOf.businessDate());
                if (day.isEmpty()) {
                    return Optional.empty();
                }
                hit = withConnection(c -> table.fetchOn(c, ref.kind(), ref.id(), day.get()));
            } else {
                hit = withConnection(c -> table.fetch(c, ref.kind(), ref.id(), asOf.businessDate()));
            }
            if (hit.isEmpty()) {
                return Optional.empty();
            }
            DataNode d = context.parseJson(new ByteArrayInputStream(hit.get().json().getBytes(StandardCharsets.UTF_8)));
            return Optional.of(new EntityDocument(ref, d, new Provenance(sourceName, hit.get().date().toEpochDay(), Instant.now(), false,
                    hit.get().date())));
        }
        return queryMode == null ? Optional.empty() : queryMode.fetch(ref, asOf.businessDate());
    }

    /**
     * History queries' fast path in table mode (HISTORY_QUERIES.md section 10): the entity's rows from..to in one query; query mode reads
     * day by day through the site's query (the default).
     */
    @Override
    public com.ash.drishti.api.HistoryRead history(com.ash.drishti.api.HistoryRequest r) throws Exception {
        if (table == null) {
            return com.ash.drishti.api.HistoryReads.perDay(this, r);
        }
        if (!tableKinds.contains(r.ref().kind())) {
            if (!catalog.catalogued()) {
                throw new java.sql.SQLException(sourceName + " has not read its table yet: " + catalog.problem());
            }
            return com.ash.drishti.api.HistoryRead.NONE;
        }
        java.util.List<EntityTable.Hit> hits = withConnection(c -> table.range(c, r.ref().kind(), r.ref().id(), r.from(), r.to()));
        java.util.Set<java.time.LocalDate> wanted = r.dates().isEmpty() ? null : new java.util.HashSet<>(r.candidateDates());
        java.util.List<com.ash.drishti.api.HistoryDay> days = new java.util.ArrayList<>();
        for (int i = hits.size() - 1; i >= 0; i--) {                          // newest first, for the last N days
            EntityTable.Hit h = hits.get(i);
            if (wanted != null && !wanted.contains(h.date())) {
                continue;
            }
            if (r.lastDays() > 0 && days.size() >= r.lastDays()) {
                break;
            }
            DataNode d = context.parseJson(new ByteArrayInputStream(h.json().getBytes(StandardCharsets.UTF_8)));
            EntityDocument doc = new EntityDocument(r.ref(), d, new Provenance(sourceName, h.date().toEpochDay(), Instant.now(), false, h.date()));
            days.add(new com.ash.drishti.api.HistoryDay(h.date(), com.ash.drishti.api.HistoryReads.project(doc, r.fields()), null));
        }
        java.util.Set<java.time.LocalDate> covered = new java.util.HashSet<>();
        for (java.time.LocalDate d : r.candidateDates()) {
            if (coverage(r.ref().kind(), com.ash.drishti.api.AsOf.of(d)) == com.ash.drishti.api.DateCoverage.HELD) {
                covered.add(d);
            }
        }
        return new com.ash.drishti.api.HistoryRead(days, covered, java.util.Map.of());
    }

    @Override
    public String health() {
        String e = lastError;
        if (pool == null) {
            return "DOWN: not started";
        }
        if (e != null) {
            return "DOWN: " + e + " (reconnecting)";
        }
        return tlsPlan.tls() == null ? healthDetail() : tlsPlan.tls().annotate(healthDetail(), Instant.now());
    }

    private String healthDetail() {
        if (catalog != null && catalog.problem() != null) {
            return "UP (" + catalog.problem() + ")";
        }
        String queries = queryMode == null ? null : queryMode.problem();
        if (queries != null) {
            // a failing query fails every read of its kind: DEGRADED; ids repeated by a columns query are a warning
            return queryMode.degraded() ? "DEGRADED: " + queries : "UP (" + queries + ")";
        }
        java.util.List<String> notLaidOut = catalog == null ? java.util.List.of() : catalog.notLaidOut();
        String index = catalog != null && catalog.dated().enabled() ? catalog.dated().summary() : null;
        if (notLaidOut.isEmpty()) {
            return index == null ? "UP" : "UP (" + index + ")";
        }
        return "UP (not laid out as the pack declares: " + String.join(", ", notLaidOut) + "; searches read documents)";
    }

    @Override
    public void close() {
        PgSslFactory.unregister(tlsPlan.factoryId());
        if (pool != null) {
            pool.close();
        }
    }

    @Override
    public Optional<PoolStats> poolStats() {
        ResourcePool<Connection> p = pool;
        return p == null ? Optional.empty() : Optional.of(p.stats());
    }
}
