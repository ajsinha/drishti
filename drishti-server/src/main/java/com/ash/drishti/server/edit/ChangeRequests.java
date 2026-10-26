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
package com.ash.drishti.server.edit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Changes awaiting approval (WRITE_BACK.md section 9): a submitted change that needs one or two approvers waits here, not in a lane;
 * it takes its place in a lane only once approved. A decision is a compare-and-set on the row's state, so two approvers deciding at
 * once never enqueue it twice.
 */
public final class ChangeRequests {

    public static final String AWAITING = "awaiting";
    public static final String APPROVED = "approved";
    public static final String REJECTED = "rejected";
    public static final String WITHDRAWN = "withdrawn";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String COLS = "change_id, change_key, kind, entity, target, body, state, needed, approvals, by_user, submitted_at, "
            + "decided_by, decided_at, reason";

    /** An approval given. */
    public record Approval(String by, String at, String note) {}

    /** A change awaiting (or past) approval. */
    public record Request(Change change, String state, int needed, List<Approval> approvals, String by, Instant submittedAt, String decidedBy,
            Instant decidedAt, String reason) {}

    private final DataSource ds;

    public ChangeRequests(DataSource ds) {
        this.ds = ds;
    }

    public void add(Change c, int needed) {
        try (Connection cn = ds.getConnection();
                PreparedStatement s = cn.prepareStatement("INSERT INTO drishti_edit_request (" + COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            s.setString(1, c.id());
            s.setString(2, c.key());
            s.setString(3, c.kind());
            s.setString(4, c.entity());
            s.setString(5, c.target());
            s.setString(6, c.json());
            s.setString(7, AWAITING);
            s.setInt(8, needed);
            s.setString(9, "[]");
            s.setString(10, c.by());
            s.setTimestamp(11, Timestamp.from(c.at()));
            s.setString(12, null);
            s.setTimestamp(13, null);
            s.setString(14, null);
            s.executeUpdate();
        } catch (SQLException e) {
            throw EditLog.failed("keep a change for approval", e);
        }
    }

    public Optional<Request> get(String id) {
        List<Request> r = query("SELECT " + COLS + " FROM drishti_edit_request WHERE change_id = ?", List.of(id), 1);
        return r.isEmpty() ? Optional.empty() : Optional.of(r.get(0));
    }

    /** Requests in a state, newest first; {@code state} null: every state. */
    public List<Request> list(String state, String kind, String entity, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + COLS + " FROM drishti_edit_request WHERE 1 = 1");
        List<String> args = new ArrayList<>();
        for (String[] f : new String[][] {{"state", state}, {"kind", kind}, {"entity", entity}}) {
            if (f[1] != null && !f[1].isBlank()) {
                sql.append(" AND ").append(f[0]).append(" = ?");
                args.add(f[1]);
            }
        }
        sql.append(" ORDER BY submitted_at DESC");
        return query(sql.toString(), args, limit);
    }

    /**
     * Records one approval; returns the request as it is now. When it reaches the approvals it needs, its state moves to
     * {@code approved} in the same statement (compare-and-set on the approvals seen), so exactly one approver enqueues it.
     */
    public Optional<Request> approve(String id, String by, String note, Instant at) {
        for (int attempt = 0; attempt < 5; attempt++) {
            Request r = get(id).orElse(null);
            if (r == null || !AWAITING.equals(r.state())) {
                return Optional.ofNullable(r);
            }
            List<Approval> given = new ArrayList<>(r.approvals());
            given.add(new Approval(by, at.toString(), note == null ? "" : note));
            boolean done = given.size() >= r.needed();
            try (Connection c = ds.getConnection();
                    PreparedStatement s = c.prepareStatement("UPDATE drishti_edit_request SET approvals = ?, state = ?, decided_by = ?, decided_at = ? "
                            + "WHERE change_id = ? AND state = ? AND approvals = ?")) {
                s.setString(1, JSON.writeValueAsString(given));
                s.setString(2, done ? APPROVED : AWAITING);
                s.setString(3, done ? by : null);
                s.setTimestamp(4, done ? Timestamp.from(at) : null);
                s.setString(5, id);
                s.setString(6, AWAITING);
                s.setString(7, JSON.writeValueAsString(r.approvals()));
                if (s.executeUpdate() == 1) {
                    return get(id);
                }
            } catch (SQLException | IOException e) {
                throw new IllegalStateException("edit requests: could not record an approval: " + e.getMessage(), e);
            }
        }
        throw new IllegalStateException("the change " + id + " is being decided by someone else: try again");
    }

    /** Rejects or withdraws an awaiting request (compare-and-set); false when it is no longer awaiting. */
    public boolean decide(String id, String state, String by, String reason, Instant at) {
        try (Connection c = ds.getConnection();
                PreparedStatement s = c.prepareStatement("UPDATE drishti_edit_request SET state = ?, decided_by = ?, decided_at = ?, reason = ? "
                        + "WHERE change_id = ? AND state = ?")) {
            s.setString(1, state);
            s.setString(2, by);
            s.setTimestamp(3, Timestamp.from(at));
            s.setString(4, reason);
            s.setString(5, id);
            s.setString(6, AWAITING);
            return s.executeUpdate() == 1;
        } catch (SQLException e) {
            throw EditLog.failed("decide a change", e);
        }
    }

    private List<Request> query(String sql, List<String> args, int limit) {
        List<Request> out = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setMaxRows(Math.max(1, Math.min(limit, 1_000)));
            for (int i = 0; i < args.size(); i++) {
                s.setString(i + 1, args.get(i));
            }
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    List<Approval> approvals = JSON.readValue(r.getString("approvals"), new TypeReference<List<Approval>>() {});
                    out.add(new Request(Change.parse(r.getString("body")), r.getString("state"), r.getInt("needed"), approvals, r.getString("by_user"),
                            EditLog.instant(r.getTimestamp("submitted_at")), r.getString("decided_by"), EditLog.instant(r.getTimestamp("decided_at")),
                            r.getString("reason")));
                }
            }
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("edit requests: could not read: " + e.getMessage(), e);
        }
        return out;
    }

    static Map<String, Object> words(Request r) {
        return Map.of("state", r.state(), "needed", r.needed(), "approvals", r.approvals());
    }
}
