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

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.NodeType;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.bi.dataset.Dataset;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A live table (RUPAKA.md section 12): the current state of a kind, held in an in-memory table of the analytic engine and maintained <em>incrementally</em>.
 * Rows arrive as documents; each is turned into a typed row and applied by key (the entity id: a new id is inserted, a known id replaces its row), in
 * batches, in one transaction with the eviction, so a query never sees half a batch. A row whose declared columns did not change is skipped and does not move
 * the table's generation, so a source that republishes the same values costs nothing downstream. Retention keeps the table bounded: at most {@code rows}
 * rows (the oldest changed go first) and, when set, none that has not changed for {@code age}.
 *
 * <p>Every table also has two columns the author never declares: {@code updateSeq} (a counter that grows with each change, so "what changed since
 * <i>n</i>" is a filter) and {@code updatedAt}. The table itself holds raw values; <b>nothing here knows a reader</b>: row rules and masks are applied by the
 * query compiler on every query, so each browser's every update is answered as that reader (the stream only says "the table changed").
 */
public final class LiveTable implements AutoCloseable {

    /** How the table is doing, for the indicator on a visual. */
    public enum State { STARTING, LIVE, STALE, FAILED, CLOSED }

    /** A snapshot of the counters, for the API and the indicator. */
    public record Status(String table, String kind, String state, long rows, long entities, int subscribed, boolean pushes, long seq, long epoch, long generation,
            Instant lastUpdate, Instant lastSeen, Instant startedAt, long applied, long skipped, long evicted, long deleted, String error) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("table", table);
            m.put("kind", kind);
            m.put("state", state);
            m.put("rows", rows);
            m.put("entities", entities);
            m.put("subscribed", subscribed);
            m.put("pushes", pushes);
            m.put("seq", seq);
            m.put("epoch", epoch);
            m.put("generation", generation);
            m.put("lastUpdate", lastUpdate == null ? null : lastUpdate.toString());
            m.put("lastSeen", lastSeen == null ? null : lastSeen.toString());
            m.put("startedAt", startedAt.toString());
            m.put("applied", applied);
            m.put("skipped", skipped);
            m.put("evicted", evicted);
            m.put("deleted", deleted);
            m.put("error", error);
            return m;
        }
    }

    private final String sqlName;
    private final Dataset.Table def;
    private final List<Dataset.Column> cols;
    private final AnalyticEngine engine;
    private final BiLiveProperties props;
    private final Clock clock;
    private final int maxRows;
    private final java.time.Duration maxAge;
    private final ReentrantLock write = new ReentrantLock();
    private final Map<String, Object[]> last = new HashMap<>();
    private final AtomicLong seq = new AtomicLong();
    private final AtomicLong epoch = new AtomicLong();
    private final AtomicLong generation = new AtomicLong();
    private final AtomicLong applied = new AtomicLong();
    private final AtomicLong skipped = new AtomicLong();
    private final AtomicLong evicted = new AtomicLong();
    private final AtomicLong deleted = new AtomicLong();
    private final Instant startedAt;
    private volatile Instant lastUpdate;
    private volatile Instant lastSeen;
    private volatile long rows;
    private volatile boolean closed;
    private volatile boolean started;
    private volatile boolean pushes;
    private volatile int subscribed;
    private volatile long entities;
    private volatile String error;
    private volatile LocalDate fallbackDate = LocalDate.now(ZoneOffset.UTC);

    LiveTable(String sqlName, Dataset.Table def, AnalyticEngine engine, BiLiveProperties props, Clock clock) {
        this.sqlName = sqlName;
        this.def = def;
        this.cols = def.physical();
        this.engine = engine;
        this.props = props;
        this.clock = clock;
        Dataset.Live live = def.live();
        this.maxRows = live != null && live.rows() != null ? Math.min(live.rows(), props.maxRows()) : props.maxRows();
        this.maxAge = live == null ? null : live.age();
        this.startedAt = clock.instant();
        create();
    }

    public String name() {
        return def.name();
    }

    public Dataset.Table definition() {
        return def;
    }

    String sqlName() {
        return sqlName;
    }

    private void create() {
        StringBuilder sql = new StringBuilder("CREATE TABLE " + sqlName + " (\"id\" VARCHAR PRIMARY KEY, \"businessDate\" DATE");
        for (Dataset.Column c : cols) {
            sql.append(", ").append(Dialect.quote(c.name())).append(' ').append(TableRelations.sqlType(c.type(), Dialect.DUCKDB));
        }
        sql.append(", \"updateSeq\" BIGINT, \"updatedAt\" TIMESTAMP)");
        engine.execute(sql.toString(), List.of());
    }

    // ---- the counters ------------------------------------------------------------------------------------------------

    /** Grows whenever the table's contents change (a row inserted, replaced with other values, evicted or removed). */
    public long generation() {
        return generation.get();
    }

    /** Grows when rows disappear (evicted or removed): a client that keeps rows must start again from a full read. */
    public long epoch() {
        return epoch.get();
    }

    public long seq() {
        return seq.get();
    }

    boolean isStarted() {
        return started;
    }

    void markStarted(boolean pushing, int subscribedCount, long entityCount, String problem) {
        this.started = true;
        this.pushes = pushing;
        this.subscribed = subscribedCount;
        this.entities = entityCount;
        this.error = problem;
        if (lastSeen == null) {
            lastSeen = clock.instant();
        }
        if (lastUpdate == null) {
            lastUpdate = clock.instant();
        }
    }

    void setFeedState(boolean pushing, int subscribedCount, long entityCount, String problem) {
        this.pushes = pushing;
        this.subscribed = subscribedCount;
        this.entities = entityCount;
        this.error = problem;
    }

    void setFallbackDate(LocalDate date) {
        this.fallbackDate = date;
    }

    /** A document arrived (even one that changed nothing): the source is alive. */
    void seen() {
        lastSeen = clock.instant();
    }

    public State state() {
        if (closed) {
            return State.CLOSED;
        }
        if (!started) {
            return State.STARTING;
        }
        if (error != null && rows == 0) {
            return State.FAILED;
        }
        if (pushes && subscribed > 0 && lastSeen != null && clock.instant().isAfter(lastSeen.plus(props.staleAfter()))) {
            return State.STALE;
        }
        return State.LIVE;
    }

    public Status status() {
        return new Status(def.name(), def.kind(), state().name().toLowerCase(java.util.Locale.ROOT), rows, entities, subscribed, pushes, seq.get(), epoch.get(), generation.get(),
                lastUpdate, lastSeen, startedAt, applied.get(), skipped.get(), evicted.get(), deleted.get(), error);
    }

    // ---- applying changes --------------------------------------------------------------------------------------------

    /**
     * Applies documents by key, in one transaction with the eviction. Deletions remove the row. Returns how many rows changed (inserted, replaced or removed).
     * The last document of an id wins; a document whose declared columns equal the row's skip it.
     */
    int apply(Collection<EntityDocument> docs) {
        if (docs.isEmpty() || closed) {
            return 0;
        }
        write.lock();
        try {
            Instant now = clock.instant();
            Map<String, Object[]> rowsToWrite = new LinkedHashMap<>();
            List<String> removals = new ArrayList<>();
            for (EntityDocument d : docs) {
                String id = d.ref().id();
                if (d.deleted()) {
                    rowsToWrite.remove(id);
                    removals.add(id);
                    continue;
                }
                removals.remove(id);
                Object[] row = row(d);
                Object[] before = last.get(id);
                if (before != null && Arrays.equals(before, row)) {
                    rowsToWrite.remove(id);
                    skipped.incrementAndGet();
                    continue;
                }
                rowsToWrite.put(id, row);
            }
            if (rowsToWrite.isEmpty() && removals.isEmpty()) {
                return 0;
            }
            long[] removedBy = new long[1];
            List<String> gone = new ArrayList<>();
            long firstSeq = seq.get();
            long[] after = new long[1];
            LocalDateTime stamp = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
            engine.with(c -> {
                c.setAutoCommit(false);
                try {
                    if (!rowsToWrite.isEmpty()) {
                        StringBuilder q = new StringBuilder("INSERT OR REPLACE INTO " + sqlName + " VALUES (?, ?");
                        cols.forEach(x -> q.append(", ?"));
                        q.append(", ?, ?)");
                        try (PreparedStatement ps = c.prepareStatement(q.toString())) {
                            long n = firstSeq;
                            for (Map.Entry<String, Object[]> e : rowsToWrite.entrySet()) {
                                int i = 1;
                                ps.setString(i++, e.getKey());
                                Object[] v = e.getValue();
                                ps.setObject(i++, v[0]);                                 // the business date
                                for (int k = 0; k < cols.size(); k++) {
                                    ps.setObject(i++, v[k + 1]);
                                }
                                ps.setLong(i++, ++n);
                                ps.setObject(i, stamp);
                                ps.addBatch();
                            }
                            ps.executeBatch();
                            after[0] = n;
                        }
                    }
                    if (!removals.isEmpty()) {
                        try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + sqlName + " WHERE \"id\" = ?")) {
                            for (String id : removals) {
                                ps.setString(1, id);
                                ps.addBatch();
                            }
                            removedBy[0] = Arrays.stream(ps.executeBatch()).filter(x -> x > 0 || x == Statement.SUCCESS_NO_INFO).count();
                        }
                    }
                    gone.addAll(evict(c, now));
                    c.commit();
                } catch (Exception e) {
                    c.rollback();
                    throw e;
                }
                return null;
            });
            if (after[0] > 0) {
                seq.set(after[0]);
            }
            rowsToWrite.forEach(last::put);
            removals.forEach(last::remove);
            applied.addAndGet(rowsToWrite.size());
            deleted.addAndGet(removedBy[0]);
            evicted.addAndGet(gone.size());
            gone.forEach(last::remove);
            if (removedBy[0] > 0 || !gone.isEmpty()) {
                epoch.incrementAndGet();
            }
            rows = count();
            lastUpdate = now;
            lastSeen = now;
            generation.incrementAndGet();
            return rowsToWrite.size() + removals.size();
        } finally {
            write.unlock();
        }
    }

    /** Retention, inside the batch's transaction: rows older than the age, then the oldest changed past the row limit. Returns the ids that went. */
    private List<String> evict(java.sql.Connection c, Instant now) throws java.sql.SQLException {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        if (maxAge != null) {
            try (PreparedStatement ps = c.prepareStatement("SELECT \"id\" FROM " + sqlName + " WHERE \"updatedAt\" < ?")) {
                ps.setObject(1, LocalDateTime.ofInstant(now.minus(maxAge), ZoneOffset.UTC));
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getString(1));
                    }
                }
            }
        }
        try (Statement s = c.createStatement(); var rs = s.executeQuery("SELECT count(*) FROM " + sqlName)) {
            rs.next();
            long over = rs.getLong(1) - ids.size() - maxRows;
            if (over > 0) {
                try (PreparedStatement ps = c.prepareStatement("SELECT \"id\" FROM " + sqlName + " ORDER BY \"updateSeq\" ASC LIMIT ?")) {
                    ps.setLong(1, over + ids.size());
                    try (var more = ps.executeQuery()) {
                        while (more.next()) {
                            ids.add(more.getString(1));
                        }
                    }
                }
            }
        }
        if (!ids.isEmpty()) {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + sqlName + " WHERE \"id\" = ?")) {
                for (String id : ids) {
                    ps.setString(1, id);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
        return new ArrayList<>(ids);
    }

    private long count() {
        Object[] v = engine.query("SELECT count(*) FROM " + sqlName, List.of(), 1, java.time.Duration.ofSeconds(5)).columns().get(0).values();
        return v.length == 0 ? 0 : ((Number) v[0]).longValue();
    }

    /** Applies age retention now (a table nobody changes still forgets); returns how many rows went. */
    int sweep() {
        if (closed || maxAge == null) {
            return 0;
        }
        write.lock();
        try {
            Instant now = clock.instant();
            List<String> gone = new ArrayList<>();
            engine.with(c -> {
                c.setAutoCommit(false);
                gone.addAll(evict(c, now));
                c.commit();
                return null;
            });
            if (!gone.isEmpty()) {
                evicted.addAndGet(gone.size());
                gone.forEach(last::remove);
                epoch.incrementAndGet();
                generation.incrementAndGet();
                rows = count();
                lastUpdate = now;
            }
            return gone.size();
        } finally {
            write.unlock();
        }
    }

    // ---- turning a document into a row ------------------------------------------------------------------------------

    /** The business date, then one value per declared column, typed for the SQL column. */
    private Object[] row(EntityDocument d) {
        Object[] out = new Object[cols.size() + 1];
        out[0] = d.provenance() != null && d.provenance().businessDate() != null ? d.provenance().businessDate() : fallbackDate;
        for (int i = 0; i < cols.size(); i++) {
            out[i + 1] = value(cols.get(i), d.data().at(cols.get(i).path()));
        }
        return out;
    }

    static Object value(Dataset.Column c, DataNode v) {
        if (v == null || v.isMissing() || v.isNull() || v.isMasked() || v.type() == NodeType.OBJECT || v.type() == NodeType.ARRAY) {
            return null;
        }
        try {
            return switch (c.type()) {
                case "integer" -> {
                    double x = v.asDouble();
                    yield Double.isNaN(x) || Double.isInfinite(x) ? null : (Object) Math.round(x);
                }
                case "decimal" -> {
                    double x = v.asDouble();
                    yield Double.isNaN(x) || Double.isInfinite(x) ? null : (Object) x;
                }
                case "boolean" -> v.asBoolean();
                case "date" -> {
                    String t = v.asText();
                    yield t == null || t.length() < 10 ? null : LocalDate.parse(t.substring(0, 10));
                }
                case "timestamp" -> timestamp(v.asText());
                default -> v.asText();
            };
        } catch (RuntimeException e) {
            return null;                                              // a value that does not fit its column is a missing value, as TRY_CAST does for layers
        }
    }

    private static Object timestamp(String t) {
        if (t == null || t.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.ofInstant(Instant.parse(t), ZoneOffset.UTC);
        } catch (RuntimeException e) {
            try {
                return LocalDateTime.ofInstant(java.time.OffsetDateTime.parse(t).toInstant(), ZoneOffset.UTC);
            } catch (RuntimeException e2) {
                try {
                    return LocalDateTime.parse(t.replace(' ', 'T'));
                } catch (RuntimeException e3) {
                    return null;
                }
            }
        }
    }

    // ---- as a FROM item ----------------------------------------------------------------------------------------------

    /** The table as the FROM item of a query: every column of the model by its name, calculated columns added, so measures and rules see it like any table. */
    public SqlCompiler.Source source(Dataset ds, Dialect d) {
        return TableRelations.live(ds, def, sqlName, d);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        write.lock();
        try {
            closed = true;
            try {
                engine.execute("DROP TABLE IF EXISTS " + sqlName, List.of());
            } catch (DrishtiException e) {
                // the engine is shutting down
            }
            last.clear();
        } finally {
            write.unlock();
        }
    }

    /** Refuses what a live table cannot do, with its code. */
    static void check(Dataset.Table t) {
        if (t.each() != null) {
            throw new DrishtiException(ErrorCode.LIVE_TABLE, "table '" + t.name() + "' is over the array '" + t.each() + "' and cannot be live: a live table follows whole entities");
        }
    }
}
