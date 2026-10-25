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
package com.ash.drishti.compute.approval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;

/**
 * Where approval requests and the approved items are kept: the Drishti database's {@code drishti_compute_request} and
 * {@code drishti_compute_approval} tables (docs/architecture/DATABASE.md; both schema files), so every node of a cluster sees the same
 * approvals, or memory in tests and on a server without a database.
 */
public interface ApprovalStore {

    void save(ApprovalRequest r);

    Optional<ApprovalRequest> get(String id);

    /** Newest first, at most {@code limit}. */
    List<ApprovalRequest> recent(int limit);

    /** The requests of a subject in a state. */
    List<ApprovalRequest> of(String subject, String state);

    /** Records the request's items as approved. */
    void approve(ApprovalRequest r, String by, Instant at);

    /** Marks the items approved by the request as revoked. */
    void revoke(String requestId, Instant at);

    /** The keys of every item approved and not revoked. */
    Set<String> approvedKeys();

    /** In memory. */
    final class Memory implements ApprovalStore {
        private final Map<String, ApprovalRequest> rows = new ConcurrentHashMap<>();
        private final Map<String, Set<String>> approved = new ConcurrentHashMap<>();

        @Override
        public void save(ApprovalRequest r) {
            rows.put(r.id(), r);
        }

        @Override
        public Optional<ApprovalRequest> get(String id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public List<ApprovalRequest> recent(int limit) {
            return rows.values().stream().sorted(Comparator.comparing(ApprovalRequest::requestedAt).reversed()).limit(limit).toList();
        }

        @Override
        public List<ApprovalRequest> of(String subject, String state) {
            return rows.values().stream().filter(r -> r.subject().equals(subject) && r.state().equals(state))
                    .sorted(Comparator.comparing(ApprovalRequest::requestedAt).reversed()).toList();
        }

        @Override
        public void approve(ApprovalRequest r, String by, Instant at) {
            Set<String> keys = new HashSet<>();
            r.items().forEach(i -> keys.add(i.key()));
            approved.put(r.id(), keys);
        }

        @Override
        public void revoke(String requestId, Instant at) {
            approved.remove(requestId);
        }

        @Override
        public Set<String> approvedKeys() {
            Set<String> out = new HashSet<>();
            approved.values().forEach(out::addAll);
            return out;
        }
    }

    /** The Drishti database (SQLite or PostgreSQL, the same statements). */
    final class Jdbc implements ApprovalStore {
        private static final ObjectMapper JSON = new ObjectMapper();
        private static final String COLS = "id, subject, fingerprint, items, state, requested_by, requested_at, decided_by, decided_at, reason, "
                + "revoked_by, revoked_at, revoke_reason";
        private final DataSource ds;

        public Jdbc(DataSource ds) {
            this.ds = ds;
        }

        @Override
        public void save(ApprovalRequest r) {
            try (Connection c = ds.getConnection()) {
                c.setAutoCommit(false);
                try (PreparedStatement d = c.prepareStatement("DELETE FROM drishti_compute_request WHERE id = ?")) {
                    d.setString(1, r.id());
                    d.executeUpdate();
                }
                try (PreparedStatement p = c.prepareStatement("INSERT INTO drishti_compute_request (" + COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                    p.setString(1, r.id());
                    p.setString(2, r.subject());
                    p.setString(3, r.fingerprint());
                    p.setString(4, JSON.writeValueAsString(r.items()));
                    p.setString(5, r.state());
                    p.setString(6, r.requestedBy());
                    p.setTimestamp(7, ts(r.requestedAt()));
                    p.setString(8, r.decidedBy());
                    p.setTimestamp(9, ts(r.decidedAt()));
                    p.setString(10, r.reason());
                    p.setString(11, r.revokedBy());
                    p.setTimestamp(12, ts(r.revokedAt()));
                    p.setString(13, r.revokeReason());
                    p.executeUpdate();
                }
                c.commit();
            } catch (SQLException | com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException("could not save compute approval request " + r.id() + ": " + e.getMessage(), e);
            }
        }

        @Override
        public Optional<ApprovalRequest> get(String id) {
            List<ApprovalRequest> l = query("SELECT " + COLS + " FROM drishti_compute_request WHERE id = ?", id);
            return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
        }

        @Override
        public List<ApprovalRequest> recent(int limit) {
            List<ApprovalRequest> l = query("SELECT " + COLS + " FROM drishti_compute_request ORDER BY requested_at DESC");
            return l.size() > limit ? l.subList(0, limit) : l;
        }

        @Override
        public List<ApprovalRequest> of(String subject, String state) {
            return query("SELECT " + COLS + " FROM drishti_compute_request WHERE subject = ? AND state = ? ORDER BY requested_at DESC", subject, state);
        }

        @Override
        public void approve(ApprovalRequest r, String by, Instant at) {
            try (Connection c = ds.getConnection()) {
                c.setAutoCommit(false);
                try (PreparedStatement p = c.prepareStatement("INSERT INTO drishti_compute_approval (item_key, request_id, kind, name, version, hash, source, "
                        + "native_code, approved_by, approved_at, revoked_at) VALUES (?,?,?,?,?,?,?,?,?,?,NULL)")) {
                    for (ApprovalItem i : r.items()) {
                        p.setString(1, i.key());
                        p.setString(2, r.id());
                        p.setString(3, i.kind());
                        p.setString(4, i.name());
                        p.setString(5, i.version());
                        p.setString(6, i.hash());
                        p.setString(7, i.source());
                        p.setInt(8, i.nativeCode() ? 1 : 0);
                        p.setString(9, by);
                        p.setTimestamp(10, ts(at));
                        p.addBatch();
                    }
                    p.executeBatch();
                }
                c.commit();
            } catch (SQLException e) {
                throw new IllegalStateException("could not record the approval of " + r.id() + ": " + e.getMessage(), e);
            }
        }

        @Override
        public void revoke(String requestId, Instant at) {
            try (Connection c = ds.getConnection();
                    PreparedStatement p = c.prepareStatement("UPDATE drishti_compute_approval SET revoked_at = ? WHERE request_id = ? AND revoked_at IS NULL")) {
                p.setTimestamp(1, ts(at));
                p.setString(2, requestId);
                p.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("could not revoke " + requestId + ": " + e.getMessage(), e);
            }
        }

        @Override
        public Set<String> approvedKeys() {
            Set<String> out = new HashSet<>();
            try (Connection c = ds.getConnection();
                    PreparedStatement p = c.prepareStatement("SELECT item_key FROM drishti_compute_approval WHERE revoked_at IS NULL");
                    ResultSet rs = p.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            } catch (SQLException e) {
                throw new IllegalStateException("could not read the compute approvals: " + e.getMessage(), e);
            }
            return out;
        }

        private List<ApprovalRequest> query(String sql, String... args) {
            List<ApprovalRequest> out = new ArrayList<>();
            try (Connection c = ds.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    p.setString(i + 1, args[i]);
                }
                try (ResultSet rs = p.executeQuery()) {
                    while (rs.next()) {
                        out.add(new ApprovalRequest(rs.getString(1), rs.getString(2), rs.getString(3),
                                JSON.readValue(rs.getString(4), new TypeReference<List<ApprovalItem>>() {}), rs.getString(5), rs.getString(6),
                                instant(rs.getTimestamp(7)), rs.getString(8), instant(rs.getTimestamp(9)), rs.getString(10), rs.getString(11),
                                instant(rs.getTimestamp(12)), rs.getString(13)));
                    }
                }
            } catch (SQLException | com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException("could not read the compute approval requests: " + e.getMessage(), e);
            }
            return out;
        }

        private static Timestamp ts(Instant i) {
            return i == null ? null : Timestamp.from(i);
        }

        private static Instant instant(Timestamp t) {
            return t == null ? null : t.toInstant();
        }
    }
}
