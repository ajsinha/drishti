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
package com.ash.drishti.server.bi.runs;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import javax.sql.DataSource;

/**
 * Where visible runs are kept: the {@code drishti_bi_run} table of the identity database (docs/architecture/BI_RUNS.md section 3.4), or memory
 * in tests. Only visible runs are written; a run that finished inside the inline wait writes no row. Names, states and counts only.
 */
public interface RunRecords {

    /** Inserts or replaces the row. */
    void save(Run r);

    Optional<Run> get(String id);

    /** The runs {@code owner} owns (or ran), newest first, at most {@code limit}, submitted before {@code before} when given. */
    List<Run> owned(String owner, Instant before, int limit);

    /** This node's rows in the given states (restart recovery, section 12.1). */
    List<Run> onNode(String node, List<RunState> states);

    /** Every row in the given states (Admin). */
    List<Run> inStates(List<RunState> states, int limit);

    /** The newest {@code done} run with this result key, if any. */
    Optional<Run> doneWithKey(String resultKey);

    /** Unfinished rows whose node is {@code node}: queued, running or storing, the rows a lease covers (section 13.3). */
    List<RunState> LEASED = List.of(RunState.QUEUED, RunState.RUNNING, RunState.STORING);

    /** Renews the lease of a row this node still holds; false when the row is gone or another node holds it now (this node then abandons its copy). */
    boolean renew(String id, String node, Instant until);

    /**
     * Adopts a dead node's run (section 13.3): one conditional update ({@code node = to, attempt + 1, state queued} where the row is still {@code from}'s, its
     * lease lapsed and it is unfinished), so exactly one node wins; true for the winner.
     */
    boolean adopt(String id, String from, String to, Instant now, String reason);

    /** Runs in memory: tests and a server without an identity database. */
    final class Memory implements RunRecords {
        private final Map<String, Run> rows = new ConcurrentHashMap<>();

        @Override
        public void save(Run r) {
            rows.put(r.id(), r);
        }

        @Override
        public Optional<Run> get(String id) {
            return Optional.ofNullable(rows.get(id));
        }

        private List<Run> where(Predicate<Run> p, int limit) {
            return rows.values().stream().filter(p).sorted(Comparator.comparing(Run::submittedAt).reversed().thenComparing(Run::id, Comparator.reverseOrder()))
                    .limit(limit).toList();
        }

        @Override
        public List<Run> owned(String owner, Instant before, int limit) {
            return where(r -> (owner.equals(r.owner()) || owner.equals(r.runner())) && (before == null || r.submittedAt().isBefore(before)), limit);
        }

        @Override
        public List<Run> onNode(String node, List<RunState> states) {
            return where(r -> node.equals(r.node()) && states.contains(r.state()), Integer.MAX_VALUE);
        }

        @Override
        public List<Run> inStates(List<RunState> states, int limit) {
            return where(r -> states.contains(r.state()), limit);
        }

        @Override
        public Optional<Run> doneWithKey(String resultKey) {
            return where(r -> resultKey.equals(r.resultKey()) && r.state() == RunState.DONE, 1).stream().findFirst();
        }

        @Override
        public synchronized boolean renew(String id, String node, Instant until) {
            Run r = rows.get(id);
            if (r == null || !node.equals(r.node())) {
                return false;
            }
            Run.Builder b = r.toBuilder();
            b.leaseUntil = until;
            rows.put(id, b.build());
            return true;
        }

        @Override
        public synchronized boolean adopt(String id, String from, String to, Instant now, String reason) {
            Run r = rows.get(id);
            if (r == null || !from.equals(r.node()) || !LEASED.contains(r.state()) || r.leaseUntil() == null || !r.leaseUntil().isBefore(now)) {
                return false;
            }
            Run moved = r.state() == RunState.QUEUED ? r : r.moveTo(RunState.QUEUED, null, reason, now);     // running or storing: attempt + 1
            Run.Builder b = moved.toBuilder();
            b.node = to;
            b.attempt = r.attempt() + 1;
            b.reason = reason;
            b.leaseUntil = null;
            rows.put(id, b.build());
            return true;
        }
    }

    /** The {@code drishti_bi_run} table. Each call borrows a connection for one statement and returns it. */
    final class Jdbc implements RunRecords {
        private static final String COLUMNS = "id, runner, owner, workspace, source_type, source_id, source_ref, title, question, params, as_of, data_version,"
                + " fingerprint, signature, result_key, tier, engine, state, progress, phase, estimate, rows_read, rows_out, bytes_out, code, reason, node,"
                + " attempt, lease_until, submitted_at, started_at, finished_at, expires_at, rerun_of, shared_from, reuse_of, group_id, promoted_to";
        private static final int N = COLUMNS.split(",").length;
        private final DataSource ds;

        public Jdbc(DataSource ds) {
            this.ds = ds;
        }

        @Override
        public void save(Run r) {
            String marks = String.join(", ", java.util.Collections.nCopies(N, "?"));
            String update = "UPDATE drishti_bi_run SET " + String.join(", ", java.util.Arrays.stream(COLUMNS.split(",")).skip(1).map(c -> c.strip() + " = ?").toList())
                    + " WHERE id = ?";
            try (Connection c = ds.getConnection(); PreparedStatement up = c.prepareStatement(update)) {
                bind(up, r, true);
                if (up.executeUpdate() == 0) {
                    try (PreparedStatement in = c.prepareStatement("INSERT INTO drishti_bi_run (" + COLUMNS + ") VALUES (" + marks + ")")) {
                        bind(in, r, false);
                        in.executeUpdate();
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("could not save the BI run " + r.id() + ": " + e.getMessage(), e);
            }
        }

        private static void bind(PreparedStatement st, Run r, boolean idLast) throws SQLException {
            Object[] v = {r.id(), r.runner(), r.owner(), r.workspace(), r.sourceType(), r.sourceId(), r.sourceRef(), r.title(), r.question(), r.params(),
                    r.asOf(), r.dataVersion(), r.fingerprint(), r.signature(), r.resultKey(), r.tier().wire(), r.engine(), r.state().wire(), r.progress(),
                    r.phase(), r.estimate(), r.rowsRead(), r.rowsOut(), r.bytesOut(), r.code(), r.reason(), r.node(), r.attempt(), ts(r.leaseUntil()),
                    ts(r.submittedAt()), ts(r.startedAt()), ts(r.finishedAt()), ts(r.expiresAt()), r.rerunOf(), r.sharedFrom(), r.reuseOf(), r.groupId(),
                    r.promotedTo()};
            int i = 1;
            for (int k = idLast ? 1 : 0; k < v.length; k++) {
                set(st, i++, v[k]);
            }
            if (idLast) {
                set(st, i, v[0]);
            }
        }

        private static void set(PreparedStatement st, int i, Object v) throws SQLException {
            if (v == null) {
                st.setNull(i, Types.NULL);
            } else if (v instanceof Timestamp t) {
                st.setTimestamp(i, t);
            } else if (v instanceof Double d) {
                st.setDouble(i, d);
            } else if (v instanceof Long l) {
                st.setLong(i, l);
            } else if (v instanceof Integer n) {
                st.setInt(i, n);
            } else {
                st.setString(i, String.valueOf(v));
            }
        }

        private static Timestamp ts(Instant i) {
            return i == null ? null : Timestamp.from(i);
        }

        private static Instant instant(ResultSet rs, String col) throws SQLException {
            Timestamp t = rs.getTimestamp(col);
            return t == null ? null : t.toInstant();
        }

        private static Long lng(ResultSet rs, String col) throws SQLException {
            long v = rs.getLong(col);
            return rs.wasNull() ? null : v;
        }

        private static Run read(ResultSet rs) throws SQLException {
            double prog = rs.getDouble("progress");
            Double progress = rs.wasNull() ? null : prog;
            return new Run(rs.getString("id"), rs.getString("runner"), rs.getString("owner"), rs.getString("workspace"), rs.getString("source_type"),
                    rs.getString("source_id"), rs.getString("source_ref"), rs.getString("question"), rs.getString("params"), rs.getString("as_of"),
                    rs.getString("data_version"), rs.getString("fingerprint"), rs.getString("signature"), rs.getString("result_key"),
                    Tier.of(rs.getString("tier")), rs.getString("engine"), RunState.of(rs.getString("state")), progress, rs.getString("phase"),
                    rs.getString("estimate"), lng(rs, "rows_out"), lng(rs, "bytes_out"), rs.getString("code"), rs.getString("reason"), rs.getString("node"),
                    rs.getInt("attempt"), instant(rs, "lease_until"), instant(rs, "submitted_at"), instant(rs, "started_at"), instant(rs, "finished_at"),
                    instant(rs, "expires_at"), rs.getString("rerun_of"), rs.getString("shared_from"), rs.getString("group_id"), rs.getString("promoted_to"),
                    rs.getString("title"), rs.getString("reuse_of"), lng(rs, "rows_read"));
        }

        private List<Run> query(String where, int limit, Object... args) {
            String sql = "SELECT " + COLUMNS + " FROM drishti_bi_run WHERE " + where + " ORDER BY submitted_at DESC, id DESC";
            List<Run> out = new ArrayList<>();
            try (Connection c = ds.getConnection(); PreparedStatement st = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    set(st, i + 1, args[i]);
                }
                st.setMaxRows(Math.max(0, limit == Integer.MAX_VALUE ? 0 : limit));
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next() && out.size() < limit) {
                        out.add(read(rs));
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("could not read BI runs: " + e.getMessage(), e);
            }
            return out;
        }

        private static String in(List<RunState> states) {
            return String.join(", ", states.stream().map(s -> "'" + s.wire() + "'").toList());
        }

        @Override
        public Optional<Run> get(String id) {
            return query("id = ?", 1, id).stream().findFirst();
        }

        @Override
        public List<Run> owned(String owner, Instant before, int limit) {
            return before == null ? query("(owner = ? OR runner = ?)", limit, owner, owner)
                    : query("(owner = ? OR runner = ?) AND submitted_at < ?", limit, owner, owner, ts(before));
        }

        @Override
        public List<Run> onNode(String node, List<RunState> states) {
            return query("node = ? AND state IN (" + in(states) + ")", Integer.MAX_VALUE, node);
        }

        @Override
        public List<Run> inStates(List<RunState> states, int limit) {
            return query("state IN (" + in(states) + ")", limit);
        }

        @Override
        public Optional<Run> doneWithKey(String resultKey) {
            return query("result_key = ? AND state = 'done'", 1, resultKey).stream().findFirst();
        }

        private int update(String sql, Object... args) {
            try (Connection c = ds.getConnection(); PreparedStatement st = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    set(st, i + 1, args[i]);
                }
                return st.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("could not change a BI run: " + e.getMessage(), e);
            }
        }

        @Override
        public boolean renew(String id, String node, Instant until) {
            return update("UPDATE drishti_bi_run SET lease_until = ? WHERE id = ? AND node = ?", ts(until), id, node) > 0;
        }

        @Override
        public boolean adopt(String id, String from, String to, Instant now, String reason) {
            return update("UPDATE drishti_bi_run SET node = ?, attempt = attempt + 1, state = 'queued', reason = ?, lease_until = NULL WHERE id = ? AND node = ?"
                    + " AND lease_until < ? AND state IN (" + in(LEASED) + ")", to, reason, id, from, ts(now)) == 1;
        }
    }
}
