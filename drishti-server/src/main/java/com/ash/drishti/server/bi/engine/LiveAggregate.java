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

import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.Node;
import com.ash.drishti.server.bi.dataset.Resolver;
import com.ash.drishti.server.bi.dataset.Rollups;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The aggregates of a rollup kept by group <em>incrementally</em> over a live table (RUPAKA.md section 12): for each group of {@code by} columns and business
 * date, the sums, counts, minimums and maximums the rollup's measures are made of. A row that arrives adds to its group; a row that is replaced, removed or
 * evicted first takes its old values out of the group, so sums and counts move by the difference and nothing is recomputed. A minimum or maximum is
 * incremental while values only join the group; when the row that held it leaves, <em>that group alone</em> is recomputed from the live table (inside the
 * same transaction). The groups are written to a small table of the engine in the transaction that changed the live table, so a query sees both or neither,
 * and the rollup routing reads it exactly as it reads a stored rollup (the reader's row rules and masks are applied by the compiler on top).
 *
 * <p>Only rollups whose components are plain: a sum, count, minimum or maximum of a stored column (or {@code count(table)}). A measure over an expression, or a
 * rollup grouped by a calculated column, is not incremental: the table answers it by recomputing over the live rows, as before.
 */
final class LiveAggregate {

    /** One stored component: its function, the index of the live column it reads ({@code -1} counts rows), and the name of its column in the aggregate table. */
    private record Component(String fn, int column, String name, String type) {}

    /** The mutable figures of one group. */
    private static final class Acc {
        final long gid;
        final List<Object> key;
        long rows;
        final double[] sum;
        final long[] nonNull;
        final Object[] ext;
        boolean dirty;

        Acc(long gid, List<Object> key, int n) {
            this.gid = gid;
            this.key = key;
            sum = new double[n];
            nonNull = new long[n];
            ext = new Object[n];
        }

        Acc copy() {
            Acc a = new Acc(gid, key, sum.length);
            a.rows = rows;
            System.arraycopy(sum, 0, a.sum, 0, sum.length);
            System.arraycopy(nonNull, 0, a.nonNull, 0, sum.length);
            System.arraycopy(ext, 0, a.ext, 0, sum.length);
            return a;
        }
    }

    /** A prepared change: the groups as they will be after the batch, to write now and to keep once the transaction commits. */
    static final class Delta {
        private final Map<List<Object>, Acc> touched = new LinkedHashMap<>();
        private long recomputed;
        private long moved;
    }

    private final Dataset.Rollup rollup;
    private final String tableSql;
    private final String liveSql;
    private final List<Dataset.Column> cols;
    private final List<Integer> byIdx = new ArrayList<>();
    private final List<Component> comps = new ArrayList<>();
    private final Map<List<Object>, Acc> groups = new HashMap<>();
    private final AtomicLong gids = new AtomicLong();
    private final AtomicLong incremental = new AtomicLong();
    private final AtomicLong recomputedGroups = new AtomicLong();
    private volatile long groupCount;

    private LiveAggregate(Dataset.Rollup rollup, String tableSql, String liveSql, List<Dataset.Column> cols) {
        this.rollup = rollup;
        this.tableSql = tableSql;
        this.liveSql = liveSql;
        this.cols = cols;
    }

    /** The incremental aggregate of a rollup over a live table, or null when the rollup holds something that is not a plain stored aggregate. */
    static LiveAggregate of(Dataset ds, Dataset.Rollup r, List<Dataset.Column> cols, String liveSql, String tableSql) {
        LiveAggregate a = new LiveAggregate(r, tableSql, liveSql, cols);
        Resolver resolver = new Resolver(ds);
        for (String by : r.by()) {
            int i = index(cols, by);
            if (i < 0) {
                return null;
            }
            a.byIdx.add(i);
        }
        Map<String, String> names = Layers.componentColumns(ds, r);
        Map<String, Node.Call> calls = new LinkedHashMap<>();
        for (String m : r.measures()) {
            Map<String, Node.Call> c = Rollups.components(ds, ds.measures().get(m), r.table());
            if (c == null) {
                return null;
            }
            calls.putAll(c);
        }
        for (Map.Entry<String, Node.Call> e : calls.entrySet()) {
            Node.Call c = e.getValue();
            if (c.args().size() != 1 || !(c.args().get(0) instanceof Node.Ref ref)) {
                return null;
            }
            int column;
            String type = "BIGINT";
            if (ref.parts().size() == 1 && ds.tables().containsKey(ref.parts().get(0))) {
                if (!"count".equals(c.function())) {
                    return null;
                }
                column = -1;
            } else {
                Resolver.ColRef col;
                try {
                    col = resolver.resolve(ref.parts(), r.table());
                } catch (IllegalArgumentException ex) {
                    return null;
                }
                if (col.part() != null || col.column() == null || !col.table().equals(r.table())) {
                    return null;
                }
                column = index(cols, col.column());
                if (column < 0) {
                    return null;
                }
                type = switch (c.function()) {
                    case "sum" -> "DOUBLE";
                    case "count" -> "BIGINT";
                    default -> TableRelations.sqlType(cols.get(column).type(), Dialect.DUCKDB);
                };
            }
            a.comps.add(new Component(c.function(), column, names.get(e.getKey()), type));
        }
        return a;
    }

    private static int index(List<Dataset.Column> cols, String name) {
        for (int i = 0; i < cols.size(); i++) {
            if (cols.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    String name() {
        return rollup.name();
    }

    Dataset.Rollup rollup() {
        return rollup;
    }

    /** The SQL table that holds the groups. */
    String table() {
        return tableSql;
    }

    long groups() {
        return groupCount;
    }

    /** Rows taken out of or put into a group by a difference (no recomputation). */
    long incremental() {
        return incremental.get();
    }

    /** Groups whose minimum or maximum was read again from the live table because the row that held it left. */
    long recomputed() {
        return recomputedGroups.get();
    }

    void create(AnalyticEngine engine) {
        StringBuilder sql = new StringBuilder("CREATE TABLE " + tableSql + " (\"gid\" BIGINT PRIMARY KEY, " + Dialect.quote(Dataset.DATE) + " DATE");
        for (int i = 0; i < byIdx.size(); i++) {
            Dataset.Column c = cols.get(byIdx.get(i));
            sql.append(", ").append(Dialect.quote(c.name())).append(' ').append(TableRelations.sqlType(c.type(), Dialect.DUCKDB));
        }
        for (Component c : comps) {
            sql.append(", ").append(Dialect.quote(c.name())).append(' ').append(c.type());
        }
        engine.execute(sql.append(")").toString(), List.of());
    }

    // ---- maintaining -------------------------------------------------------------------------------------------------

    /**
     * Works out the groups after a batch. {@code removed} are the rows (as the live table stores them: the business date, then one value per column) that left or
     * were replaced, {@code added} those that arrived. Call it inside the transaction, after the live table was written, so a group whose minimum or maximum
     * must be read again sees the new state.
     */
    Delta prepare(Connection c, List<Object[]> removed, List<Object[]> added) throws SQLException {
        Delta d = new Delta();
        for (Object[] row : removed) {
            Acc a = acc(d, row, false);
            if (a != null) {
                take(a, row);
                d.moved++;
            }
        }
        for (Object[] row : added) {
            give(acc(d, row, true), row);
            d.moved++;
        }
        for (Acc a : d.touched.values()) {
            if (a.dirty && a.rows > 0) {
                recompute(c, a);
                d.recomputed++;
            }
        }
        return d;
    }

    /** Writes the changed groups (and deletes the emptied ones) in the caller's transaction. */
    void write(Connection c, Delta d) throws SQLException {
        if (d.touched.isEmpty()) {
            return;
        }
        StringBuilder q = new StringBuilder("INSERT OR REPLACE INTO " + tableSql + " VALUES (?, ?");
        byIdx.forEach(x -> q.append(", ?"));
        comps.forEach(x -> q.append(", ?"));
        q.append(")");
        try (PreparedStatement put = c.prepareStatement(q.toString()); PreparedStatement del = c.prepareStatement("DELETE FROM " + tableSql + " WHERE \"gid\" = ?")) {
            for (Acc a : d.touched.values()) {
                if (a.rows <= 0) {
                    del.setLong(1, a.gid);
                    del.addBatch();
                    continue;
                }
                int i = 1;
                put.setLong(i++, a.gid);
                for (Object k : a.key) {
                    put.setObject(i++, k);
                }
                for (int k = 0; k < comps.size(); k++) {
                    put.setObject(i++, value(a, k));
                }
                put.addBatch();
            }
            put.executeBatch();
            del.executeBatch();
        }
    }

    /** The batch committed: the groups become the live ones. */
    void commit(Delta d) {
        d.touched.forEach((k, a) -> {
            if (a.rows <= 0) {
                groups.remove(k);
            } else {
                groups.put(k, a);
            }
        });
        groupCount = groups.size();
        incremental.addAndGet(d.moved);
        recomputedGroups.addAndGet(d.recomputed);
    }

    private Acc acc(Delta d, Object[] row, boolean create) {
        List<Object> key = new ArrayList<>(byIdx.size() + 1);
        key.add(row[0]);
        byIdx.forEach(i -> key.add(row[i + 1]));
        Acc a = d.touched.get(key);
        if (a == null) {
            Acc live = groups.get(key);
            if (live != null) {
                a = live.copy();
            } else if (create) {
                a = new Acc(gids.incrementAndGet(), key, comps.size());
            } else {
                return null;
            }
            d.touched.put(key, a);
        }
        return a;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void give(Acc a, Object[] row) {
        a.rows++;
        for (int i = 0; i < comps.size(); i++) {
            Component c = comps.get(i);
            Object v = c.column() < 0 ? Boolean.TRUE : row[c.column() + 1];
            if (v == null) {
                continue;
            }
            a.nonNull[i]++;
            switch (c.fn()) {
                case "sum" -> a.sum[i] += ((Number) v).doubleValue();
                case "min" -> a.ext[i] = a.ext[i] == null || ((Comparable) v).compareTo(a.ext[i]) < 0 ? v : a.ext[i];
                case "max" -> a.ext[i] = a.ext[i] == null || ((Comparable) v).compareTo(a.ext[i]) > 0 ? v : a.ext[i];
                default -> { }
            }
        }
    }

    private void take(Acc a, Object[] row) {
        a.rows--;
        for (int i = 0; i < comps.size(); i++) {
            Component c = comps.get(i);
            Object v = c.column() < 0 ? Boolean.TRUE : row[c.column() + 1];
            if (v == null) {
                continue;
            }
            a.nonNull[i]--;
            switch (c.fn()) {
                case "sum" -> a.sum[i] -= ((Number) v).doubleValue();
                case "min", "max" -> {
                    if (v.equals(a.ext[i])) {
                        a.dirty = true;                                    // the row that held the extreme left: read the group again
                    }
                }
                default -> { }
            }
        }
    }

    private void recompute(Connection c, Acc a) throws SQLException {
        List<String> picks = new ArrayList<>();
        List<Integer> which = new ArrayList<>();
        for (int i = 0; i < comps.size(); i++) {
            Component k = comps.get(i);
            if ("min".equals(k.fn()) || "max".equals(k.fn())) {
                picks.add(k.fn() + "(" + Dialect.quote(cols.get(k.column()).name()) + ")");
                which.add(i);
            }
        }
        if (picks.isEmpty()) {
            return;
        }
        StringBuilder sql = new StringBuilder("SELECT " + String.join(", ", picks) + " FROM " + liveSql + " WHERE \"businessDate\" IS NOT DISTINCT FROM ?");
        for (int i : byIdx) {
            sql.append(" AND ").append(Dialect.quote(cols.get(i).name())).append(" IS NOT DISTINCT FROM ?");
        }
        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < a.key.size(); i++) {
                ps.setObject(i + 1, a.key.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    for (int j = 0; j < which.size(); j++) {
                        a.ext[which.get(j)] = typed(rs, j + 1, cols.get(comps.get(which.get(j)).column()).type());
                    }
                }
            }
        }
        a.dirty = false;
    }

    /** An extreme read back as the Java type a row of the live table holds, so it compares equal to the values that leave. */
    private static Object typed(ResultSet rs, int i, String type) throws SQLException {
        return switch (type) {
            case "integer" -> rs.getObject(i) == null ? null : (Object) rs.getLong(i);
            case "decimal" -> rs.getObject(i) == null ? null : (Object) rs.getDouble(i);
            case "boolean" -> rs.getObject(i) == null ? null : (Object) rs.getBoolean(i);
            case "date" -> rs.getObject(i, java.time.LocalDate.class);
            case "timestamp" -> rs.getObject(i, java.time.LocalDateTime.class);
            default -> rs.getString(i);
        };
    }

    private Object value(Acc a, int i) {
        Component c = comps.get(i);
        return switch (c.fn()) {
            case "sum" -> a.nonNull[i] == 0 ? null : a.sum[i];
            case "count" -> a.nonNull[i];
            default -> a.nonNull[i] == 0 ? null : a.ext[i];
        };
    }
}
