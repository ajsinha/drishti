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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;

/**
 * The share rows of runs (docs/architecture/BI_RUNS.md section 9.3): the {@code drishti_bi_run_share} table of the identity database, or memory in tests.
 * A share row is a pointer (run, recipient, mode, the recipient's own run when recomputed); it never holds a value.
 */
public interface RunShares {

    /** One share. {@code mode}: {@code same}, {@code re-masked} or {@code recomputed}; {@code recipientRun}: the recipient's run when recomputed. */
    record Share(String runId, String recipient, String mode, String recipientRun, String sharedBy, Instant sharedAt) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("recipient", recipient);
            m.put("mode", mode);
            m.put("recipientRun", recipientRun);
            return m;
        }
    }

    /** Inserts or replaces the share of {@code runId} with {@code recipient}. */
    void save(Share s);

    /** Removes it; true when there was one. */
    boolean remove(String runId, String recipient);

    Optional<Share> get(String runId, String recipient);

    /** The shares of a run, by recipient. */
    List<Share> of(String runId);

    /** The shares made to a person, newest first. */
    List<Share> to(String recipient, int limit);

    /** In memory: tests and a server without an identity database. */
    final class Memory implements RunShares {
        private final Map<String, Share> rows = new ConcurrentHashMap<>();

        private static String k(String run, String who) {
            return run + "\u0000" + who;
        }

        @Override
        public void save(Share s) {
            rows.put(k(s.runId(), s.recipient()), s);
        }

        @Override
        public boolean remove(String runId, String recipient) {
            return rows.remove(k(runId, recipient)) != null;
        }

        @Override
        public Optional<Share> get(String runId, String recipient) {
            return Optional.ofNullable(rows.get(k(runId, recipient)));
        }

        @Override
        public List<Share> of(String runId) {
            return rows.values().stream().filter(s -> s.runId().equals(runId)).sorted(Comparator.comparing(Share::recipient)).toList();
        }

        @Override
        public List<Share> to(String recipient, int limit) {
            return rows.values().stream().filter(s -> s.recipient().equals(recipient)).sorted(Comparator.comparing(Share::sharedAt).reversed()).limit(limit)
                    .toList();
        }
    }

    /** The {@code drishti_bi_run_share} table; each call borrows a connection for one statement. */
    final class Jdbc implements RunShares {
        private static final String COLUMNS = "run_id, recipient, mode, recipient_run, shared_by, shared_at";
        private final DataSource ds;

        public Jdbc(DataSource ds) {
            this.ds = ds;
        }

        @Override
        public void save(Share s) {
            try (Connection c = ds.getConnection()) {
                try (PreparedStatement up = c.prepareStatement("UPDATE drishti_bi_run_share SET mode = ?, recipient_run = ?, shared_by = ?, shared_at = ?"
                        + " WHERE run_id = ? AND recipient = ?")) {
                    up.setString(1, s.mode());
                    up.setString(2, s.recipientRun());
                    up.setString(3, s.sharedBy());
                    up.setTimestamp(4, Timestamp.from(s.sharedAt()));
                    up.setString(5, s.runId());
                    up.setString(6, s.recipient());
                    if (up.executeUpdate() > 0) {
                        return;
                    }
                }
                try (PreparedStatement in = c.prepareStatement("INSERT INTO drishti_bi_run_share (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?)")) {
                    in.setString(1, s.runId());
                    in.setString(2, s.recipient());
                    in.setString(3, s.mode());
                    in.setString(4, s.recipientRun());
                    in.setString(5, s.sharedBy());
                    in.setTimestamp(6, Timestamp.from(s.sharedAt()));
                    in.executeUpdate();
                }
            } catch (SQLException e) {
                throw new IllegalStateException("could not save the share of BI run " + s.runId() + ": " + e.getMessage(), e);
            }
        }

        @Override
        public boolean remove(String runId, String recipient) {
            try (Connection c = ds.getConnection(); PreparedStatement st = c.prepareStatement("DELETE FROM drishti_bi_run_share WHERE run_id = ? AND recipient = ?")) {
                st.setString(1, runId);
                st.setString(2, recipient);
                return st.executeUpdate() > 0;
            } catch (SQLException e) {
                throw new IllegalStateException("could not remove the share of BI run " + runId + ": " + e.getMessage(), e);
            }
        }

        private List<Share> query(String where, int limit, String... args) {
            List<Share> out = new ArrayList<>();
            try (Connection c = ds.getConnection();
                    PreparedStatement st = c.prepareStatement("SELECT " + COLUMNS + " FROM drishti_bi_run_share WHERE " + where + " ORDER BY shared_at DESC")) {
                for (int i = 0; i < args.length; i++) {
                    st.setString(i + 1, args[i]);
                }
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next() && out.size() < limit) {
                        Timestamp t = rs.getTimestamp("shared_at");
                        out.add(new Share(rs.getString("run_id"), rs.getString("recipient"), rs.getString("mode"), rs.getString("recipient_run"),
                                rs.getString("shared_by"), t == null ? Instant.EPOCH : t.toInstant()));
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("could not read BI run shares: " + e.getMessage(), e);
            }
            return out;
        }

        @Override
        public Optional<Share> get(String runId, String recipient) {
            return query("run_id = ? AND recipient = ?", 1, runId, recipient).stream().findFirst();
        }

        @Override
        public List<Share> of(String runId) {
            return query("run_id = ?", 10_000, runId).stream().sorted(Comparator.comparing(Share::recipient)).toList();
        }

        @Override
        public List<Share> to(String recipient, int limit) {
            return query("recipient = ?", limit, recipient);
        }
    }
}
