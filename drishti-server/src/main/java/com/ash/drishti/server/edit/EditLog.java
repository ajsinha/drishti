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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;

/**
 * The edit log in the Drishti database (WRITE_BACK.md sections 7 and 11): {@code drishti_edit_lane} (one row per lane: its owner, the
 * fencing epoch, the lease, the next seq and the last applied seq) and {@code drishti_edit_log} (one row per change, ordered by
 * {@code (lane, seq)}). The same statements run on SQLite and PostgreSQL; only "now" differs ({@link Dialect}), and it is always the
 * database's clock, never a node's.
 *
 * <p>Every write that matters is <b>fenced</b>: an append or an apply first moves the lane's row with
 * {@code WHERE lane = ? AND owner_node = ? AND epoch = ?} in the same transaction, so a node whose epoch is stale (a zombie owner cut
 * off by a split, or paused by a long GC) matches no row and its whole transaction rolls back ({@link Fenced}). An append is
 * acknowledged only after its commit. Connections are borrowed per transaction and returned at once; no lock is held across it.
 */
public final class EditLog {

    public static final String QUEUED = "queued";
    public static final String APPLYING = "applying";
    public static final String APPLIED = "applied";
    public static final String REFUSED = "refused";
    public static final String CONFLICTED = "conflicted";
    public static final String WITHDRAWN = "withdrawn";

    public static final String CMD_PENDING = "pending";
    public static final String CMD_SENT = "sent";
    public static final String CMD_APPLIED = "applied";
    public static final String CMD_REFUSED = "refused";

    private static final String ROW = "lane, seq, change_id, epoch, state, change_key, body, command, command_attempts, command_sent_at, "
            + "command_reason, state_reason, submitted_at, applied_at, signature";

    /** This node's epoch for a lane has moved: another node owns it now. The transaction was rolled back. */
    public static final class Fenced extends RuntimeException {
        public Fenced(int lane) {
            super("lane " + lane + " is not owned by this node at this epoch any more");
        }
    }

    /** The lane is deeper than {@code edit.max-lane-depth}: "busy, try again". Nothing was appended. */
    public static final class Busy extends RuntimeException {
        public Busy(int lane, long depth) {
            super("lane " + lane + " has " + depth + " changes waiting: busy, try again in a moment");
        }
    }

    /** One lane's row. {@code now} is the database's clock when it was read (epoch ms). */
    public record Lane(int lane, String owner, long epoch, Long leaseUntil, long nextSeq, long appliedSeq, long now) {
        public long depth() {
            return Math.max(0, nextSeq - 1 - appliedSeq);
        }

        public boolean leased() {
            return owner != null && leaseUntil != null && leaseUntil >= now;
        }
    }

    /** One change in the log. */
    public record Row(int lane, long seq, String changeId, long epoch, String state, String key, Change change, String command, int attempts,
            Instant commandSentAt, String commandReason, String stateReason, Instant submittedAt, Instant appliedAt, String signature, String body) {}

    /** Work done inside an apply's transaction (overlay or owned rows), on its connection. */
    @FunctionalInterface
    public interface InTx {
        void run(Connection c) throws SQLException;
    }

    /** "Now" in the database's clock, as epoch milliseconds. */
    enum Dialect {
        SQLITE("CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)"),
        POSTGRES("CAST(EXTRACT(EPOCH FROM clock_timestamp()) * 1000 AS BIGINT)");

        final String now;

        Dialect(String now) {
            this.now = now;
        }
    }

    private final DataSource ds;
    private final Dialect dialect;

    public EditLog(DataSource ds) {
        this.ds = ds;
        try (Connection c = ds.getConnection()) {
            this.dialect = c.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT).contains("postgres") ? Dialect.POSTGRES : Dialect.SQLITE;
        } catch (SQLException e) {
            throw new IllegalStateException("the database is not reachable for the edit log: " + e.getMessage(), e);
        }
    }

    public boolean postgres() {
        return dialect == Dialect.POSTGRES;
    }

    DataSource dataSource() {
        return ds;
    }

    // ---------------------------------------------------------------------------------------------------------------- lanes

    /** Makes sure lanes 0..p-1 have their rows (another node may insert one at the same moment: that is fine). */
    public void ensureLanes(int p) {
        Set<Integer> have = new HashSet<>();
        try (Connection c = ds.getConnection(); PreparedStatement s = c.prepareStatement("SELECT lane FROM drishti_edit_lane"); ResultSet r = s.executeQuery()) {
            while (r.next()) {
                have.add(r.getInt(1));
            }
        } catch (SQLException e) {
            throw failed("read the lanes", e);
        }
        for (int lane = 0; lane < p; lane++) {
            if (have.contains(lane)) {
                continue;
            }
            try (Connection c = ds.getConnection();
                    PreparedStatement s = c.prepareStatement("INSERT INTO drishti_edit_lane (lane, epoch, next_seq, applied_seq) "
                            + "SELECT ?, 0, 1, 0 WHERE NOT EXISTS (SELECT 1 FROM drishti_edit_lane WHERE lane = ?)")) {
                s.setInt(1, lane);
                s.setInt(2, lane);
                s.executeUpdate();
            } catch (SQLException e) {
                if (!lane(lane).isPresent()) {                       // a duplicate from another node is fine; anything else is not
                    throw failed("create lane " + lane, e);
                }
            }
        }
    }

    public Optional<Lane> lane(int lane) {
        try (Connection c = ds.getConnection();
                PreparedStatement s = c.prepareStatement("SELECT lane, owner_node, epoch, lease_until, next_seq, applied_seq, " + dialect.now
                        + " FROM drishti_edit_lane WHERE lane = ?")) {
            s.setInt(1, lane);
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? Optional.of(lane(r)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw failed("read lane " + lane, e);
        }
    }

    public List<Lane> lanes() {
        List<Lane> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
                PreparedStatement s = c.prepareStatement("SELECT lane, owner_node, epoch, lease_until, next_seq, applied_seq, " + dialect.now
                        + " FROM drishti_edit_lane ORDER BY lane");
                ResultSet r = s.executeQuery()) {
            while (r.next()) {
                out.add(lane(r));
            }
        } catch (SQLException e) {
            throw failed("read the lanes", e);
        }
        return out;
    }

    private static Lane lane(ResultSet r) throws SQLException {
        long lease = r.getLong(4);
        Long leaseUntil = r.wasNull() ? null : lease;
        return new Lane(r.getInt(1), r.getString(2), r.getLong(3), leaseUntil, r.getLong(5), r.getLong(6), r.getLong(7));
    }

    /**
     * Takes a lane whose lease has run out, or that this node held before a restart (section 11): the epoch moves up by one, so
     * anything an earlier owner (or an earlier run of this node) tries after this is fenced. Returns the new epoch, or empty when
     * another node holds a live lease.
     */
    public Optional<Long> claim(int lane, String node, long leaseMs) {
        return tx(c -> {
            try (PreparedStatement s = c.prepareStatement("UPDATE drishti_edit_lane SET owner_node = ?, epoch = epoch + 1, lease_until = "
                    + dialect.now + " + ? WHERE lane = ? AND (owner_node IS NULL OR owner_node = ? OR lease_until IS NULL OR lease_until < " + dialect.now + ")")) {
                s.setString(1, node);
                s.setLong(2, leaseMs);
                s.setInt(3, lane);
                s.setString(4, node);
                if (s.executeUpdate() != 1) {
                    return Optional.<Long>empty();
                }
            }
            try (PreparedStatement s = c.prepareStatement("SELECT epoch FROM drishti_edit_lane WHERE lane = ? AND owner_node = ?")) {
                s.setInt(1, lane);
                s.setString(2, node);
                try (ResultSet r = s.executeQuery()) {
                    return r.next() ? Optional.of(r.getLong(1)) : Optional.<Long>empty();
                }
            }
        }, "claim lane " + lane);
    }

    /** Renews this node's lease; false when the lane is no longer this node's at this epoch (stop the lane at once). */
    public boolean renew(int lane, String node, long epoch, long leaseMs) {
        return tx(c -> {
            try (PreparedStatement s = c.prepareStatement("UPDATE drishti_edit_lane SET lease_until = " + dialect.now + " + ? "
                    + "WHERE lane = ? AND owner_node = ? AND epoch = ?")) {
                s.setLong(1, leaseMs);
                s.setInt(2, lane);
                s.setString(3, node);
                s.setLong(4, epoch);
                return s.executeUpdate() == 1;
            }
        }, "renew lane " + lane);
    }

    /** A node leaving cleanly lets its lanes go at once. */
    public void release(int lane, String node, long epoch) {
        tx(c -> {
            try (PreparedStatement s = c.prepareStatement("UPDATE drishti_edit_lane SET lease_until = " + dialect.now + " - 1 "
                    + "WHERE lane = ? AND owner_node = ? AND epoch = ?")) {
                s.setInt(1, lane);
                s.setString(2, node);
                s.setLong(3, epoch);
                return s.executeUpdate();
            }
        }, "release lane " + lane);
    }

    /** The fence: moves nothing but matches only while this node owns the lane at this epoch; else rolls the transaction back. */
    private static void fence(Connection c, int lane, String node, long epoch) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("UPDATE drishti_edit_lane SET epoch = epoch WHERE lane = ? AND owner_node = ? AND epoch = ?")) {
            s.setInt(1, lane);
            s.setString(2, node);
            s.setLong(3, epoch);
            if (s.executeUpdate() != 1) {
                throw new Fenced(lane);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------- append

    /**
     * Appends a change to its lane (one transaction, fenced): takes the lane's next seq and inserts the row as {@code queued}; with
     * a source command the row's command starts empty and becomes {@code pending} only when the change is applied. A change id
     * already in the log is not appended twice: its seq is returned (a retry carries the same change id). Returns the seq once committed.
     */
    public long append(Change ch, int lane, String node, long epoch, int maxDepth) {
        Optional<Row> already = get(ch.id());
        if (already.isPresent()) {
            return already.get().seq();
        }
        return tx(c -> {
            try (PreparedStatement s = c.prepareStatement("UPDATE drishti_edit_lane SET next_seq = next_seq + 1 WHERE lane = ? AND owner_node = ? AND epoch = ?")) {
                s.setInt(1, lane);
                s.setString(2, node);
                s.setLong(3, epoch);
                if (s.executeUpdate() != 1) {
                    throw new Fenced(lane);
                }
            }
            long seq;
            long applied;
            try (PreparedStatement s = c.prepareStatement("SELECT next_seq - 1, applied_seq FROM drishti_edit_lane WHERE lane = ?")) {
                s.setInt(1, lane);
                try (ResultSet r = s.executeQuery()) {
                    r.next();
                    seq = r.getLong(1);
                    applied = r.getLong(2);
                }
            }
            if (seq - applied > maxDepth) {
                throw new Busy(lane, seq - 1 - applied);
            }
            String body = ch.json();
            try (PreparedStatement s = c.prepareStatement("INSERT INTO drishti_edit_log (lane, seq, change_id, epoch, state, change_key, kind, entity, "
                    + "business_date, target, connector, body, command_attempts, by_user, submitted_at, signature) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,0,?,?,?)")) {
                s.setInt(1, lane);
                s.setLong(2, seq);
                s.setString(3, ch.id());
                s.setLong(4, epoch);
                s.setString(5, QUEUED);
                s.setString(6, ch.key());
                s.setString(7, ch.kind());
                s.setString(8, ch.entity());
                s.setString(9, ch.businessDate().toString());
                s.setString(10, ch.target());
                s.setString(11, ch.connector());
                s.setString(12, body);
                s.setString(13, ch.by());
                s.setTimestamp(14, Timestamp.from(ch.at()));
                s.setString(15, signature(lane, seq, ch.id(), epoch, body));
                s.executeUpdate();
            }
            return seq;
        }, "append to lane " + lane);
    }

    /** The entry's signature: SHA-256 over its place, id, epoch and body, so an edited row no longer matches. */
    static String signature(int lane, long seq, String changeId, long epoch, String body) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            d.update((lane + "|" + seq + "|" + changeId + "|" + epoch + "|").getBytes(StandardCharsets.UTF_8));
            d.update(body.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(d.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** True when a row's signature still matches its content. */
    public static boolean intact(Row r) {
        return r.signature().equals(signature(r.lane(), r.seq(), r.changeId(), r.epoch(), r.body()));
    }

    // ---------------------------------------------------------------------------------------------------------------- apply

    /** The lane's rows after its applied seq, in seq order (the next ones to apply). */
    public List<Row> unapplied(int lane, int limit) {
        return rows("SELECT " + ROW + " FROM drishti_edit_log WHERE lane = ? AND seq > (SELECT applied_seq FROM drishti_edit_lane WHERE lane = ?) "
                + "ORDER BY seq LIMIT " + Math.max(1, limit), s -> {
                    s.setInt(1, lane);
                    s.setInt(2, lane);
                });
    }

    /** Marks a row {@code applying} (a new owner that finds it so applies it again, safely: the writes check the change id). */
    public void applying(int lane, long seq) {
        tx(c -> {
            try (PreparedStatement s = c.prepareStatement("UPDATE drishti_edit_log SET state = ? WHERE lane = ? AND seq = ? AND state = ?")) {
                s.setString(1, APPLYING);
                s.setInt(2, lane);
                s.setLong(3, seq);
                s.setString(4, QUEUED);
                return s.executeUpdate();
            }
        }, "mark applying");
    }

    /**
     * Finishes a change in one transaction: the fence, the writes ({@code work}: overlay or owned rows), the row's final state (and
     * {@code command: pending} for a source target), and the lane's applied seq. Rolls everything back on {@link Fenced}.
     */
    public void finish(Row row, String node, long epoch, String state, String reason, String command, InTx work) {
        tx(c -> {
            fence(c, row.lane(), node, epoch);
            if (work != null) {
                work.run(c);
            }
            try (PreparedStatement s = c.prepareStatement("UPDATE drishti_edit_log SET state = ?, state_reason = ?, command = ?, applied_at = ? "
                    + "WHERE lane = ? AND seq = ?")) {
                s.setString(1, state);
                s.setString(2, reason);
                s.setString(3, command);
                s.setTimestamp(4, Timestamp.from(Instant.now()));
                s.setInt(5, row.lane());
                s.setLong(6, row.seq());
                s.executeUpdate();
            }
            try (PreparedStatement s = c.prepareStatement("UPDATE drishti_edit_lane SET applied_seq = ? WHERE lane = ? AND owner_node = ? AND epoch = ? AND applied_seq < ?")) {
                s.setLong(1, row.seq());
                s.setInt(2, row.lane());
                s.setString(3, node);
                s.setLong(4, epoch);
                s.setLong(5, row.seq());
                s.executeUpdate();
            }
            return null;
        }, "apply " + row.changeId());
    }

    // ---------------------------------------------------------------------------------------------------------------- the outbox

    /** A lane's source commands not yet confirmed ({@code pending} or {@code sent}), in seq order. */
    public List<Row> unconfirmed(int lane) {
        return rows("SELECT " + ROW + " FROM drishti_edit_log WHERE lane = ? AND command IN (?, ?) ORDER BY seq", s -> {
            s.setInt(1, lane);
            s.setString(2, CMD_PENDING);
            s.setString(3, CMD_SENT);
        });
    }

    /** Every unconfirmed source command (Admin → Edits). */
    public List<Row> unconfirmed() {
        return rows("SELECT " + ROW + " FROM drishti_edit_log WHERE command IN (?, ?) ORDER BY lane, seq", s -> {
            s.setString(1, CMD_PENDING);
            s.setString(2, CMD_SENT);
        });
    }

    /** Records a command's progress (fenced): {@code sent} (one more attempt, the time), {@code applied} or {@code refused} with why. */
    public void command(Row row, String node, long epoch, String command, String reason, boolean attempt, InTx work) {
        tx(c -> {
            fence(c, row.lane(), node, epoch);
            if (work != null) {
                work.run(c);
            }
            try (PreparedStatement s = c.prepareStatement("UPDATE drishti_edit_log SET command = ?, command_reason = ?, command_attempts = command_attempts + ?, "
                    + "command_sent_at = CASE WHEN ? = 1 THEN ? ELSE command_sent_at END WHERE lane = ? AND seq = ?")) {
                s.setString(1, command);
                s.setString(2, reason);
                s.setInt(3, attempt ? 1 : 0);
                s.setInt(4, attempt ? 1 : 0);
                s.setTimestamp(5, Timestamp.from(Instant.now()));
                s.setInt(6, row.lane());
                s.setLong(7, row.seq());
                s.executeUpdate();
            }
            return null;
        }, "record the command of " + row.changeId());
    }

    // ---------------------------------------------------------------------------------------------------------------- reads

    public Optional<Row> get(String changeId) {
        List<Row> r = rows("SELECT " + ROW + " FROM drishti_edit_log WHERE change_id = ?", s -> s.setString(1, changeId));
        return r.isEmpty() ? Optional.empty() : Optional.of(r.get(0));
    }

    /** A record's changes, newest first. */
    public List<Row> history(String key, int limit) {
        return rows("SELECT " + ROW + " FROM drishti_edit_log WHERE change_key = ? ORDER BY submitted_at DESC, seq DESC LIMIT " + Math.max(1, limit),
                s -> s.setString(1, key));
    }

    /** Changes, newest first, filtered by state, kind, entity and submitter (null: any). */
    public List<Row> list(String state, String kind, String entity, String by, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + ROW + " FROM drishti_edit_log WHERE 1 = 1");
        List<String> args = new ArrayList<>();
        for (String[] f : new String[][] {{"state", state}, {"kind", kind}, {"entity", entity}, {"by_user", by}}) {
            if (f[1] != null && !f[1].isBlank()) {
                sql.append(" AND ").append(f[0]).append(" = ?");
                args.add(f[1]);
            }
        }
        sql.append(" ORDER BY submitted_at DESC, seq DESC LIMIT ").append(Math.max(1, Math.min(limit, 1_000)));
        return rows(sql.toString(), s -> {
            for (int i = 0; i < args.size(); i++) {
                s.setString(i + 1, args.get(i));
            }
        });
    }

    /** The applied changes of a lane after {@code seq}, in order (the reactive triggers and the followers read the log this way). */
    public List<Row> appliedAfter(int lane, long seq, int limit) {
        return rows("SELECT " + ROW + " FROM drishti_edit_log WHERE lane = ? AND seq > ? AND state = ? ORDER BY seq LIMIT " + Math.max(1, limit), s -> {
            s.setInt(1, lane);
            s.setLong(2, seq);
            s.setString(3, APPLIED);
        });
    }

    /** How far a consumer of applied changes has read a lane (0: from the start). */
    public long cursor(String consumer, int lane) {
        try (Connection c = ds.getConnection(); PreparedStatement s = c.prepareStatement("SELECT seq FROM drishti_edit_cursor WHERE consumer = ? AND lane = ?")) {
            s.setString(1, consumer);
            s.setInt(2, lane);
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? r.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw failed("read a cursor", e);
        }
    }

    public void cursor(String consumer, int lane, long seq) {
        tx(c -> {
            try (PreparedStatement s = c.prepareStatement("UPDATE drishti_edit_cursor SET seq = ? WHERE consumer = ? AND lane = ? AND seq < ?")) {
                s.setLong(1, seq);
                s.setString(2, consumer);
                s.setInt(3, lane);
                s.setLong(4, seq);
                if (s.executeUpdate() == 1) {
                    return null;
                }
            }
            try (PreparedStatement s = c.prepareStatement("INSERT INTO drishti_edit_cursor (consumer, lane, seq) SELECT ?, ?, ? "
                    + "WHERE NOT EXISTS (SELECT 1 FROM drishti_edit_cursor WHERE consumer = ? AND lane = ?)")) {
                s.setString(1, consumer);
                s.setInt(2, lane);
                s.setLong(3, seq);
                s.setString(4, consumer);
                s.setInt(5, lane);
                s.executeUpdate();
            }
            return null;
        }, "move a cursor");
    }

    /** Deletes entries submitted before {@code before} that are finished (retention: decision 16.3). Returns how many. */
    public int purge(Instant before) {
        return tx(c -> {
            try (PreparedStatement s = c.prepareStatement("DELETE FROM drishti_edit_log WHERE submitted_at < ? AND state IN (?, ?, ?, ?) "
                    + "AND (command IS NULL OR command IN (?, ?))")) {
                s.setTimestamp(1, Timestamp.from(before));
                s.setString(2, APPLIED);
                s.setString(3, REFUSED);
                s.setString(4, CONFLICTED);
                s.setString(5, WITHDRAWN);
                s.setString(6, CMD_APPLIED);
                s.setString(7, CMD_REFUSED);
                return s.executeUpdate();
            }
        }, "purge the edit log");
    }

    // ---------------------------------------------------------------------------------------------------------------- plumbing

    @FunctionalInterface
    interface Binder {
        void bind(PreparedStatement s) throws SQLException;
    }

    @FunctionalInterface
    interface TxBody<T> {
        T run(Connection c) throws SQLException;
    }

    private List<Row> rows(String sql, Binder b) {
        List<Row> out = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            b.bind(s);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    out.add(row(r));
                }
            }
        } catch (SQLException e) {
            throw failed("read the edit log", e);
        }
        return out;
    }

    private static Row row(ResultSet r) throws SQLException {
        return new Row(r.getInt("lane"), r.getLong("seq"), r.getString("change_id"), r.getLong("epoch"), r.getString("state"),
                r.getString("change_key"), Change.parse(r.getString("body")), r.getString("command"), r.getInt("command_attempts"),
                instant(r.getTimestamp("command_sent_at")), r.getString("command_reason"), r.getString("state_reason"),
                instant(r.getTimestamp("submitted_at")), instant(r.getTimestamp("applied_at")), r.getString("signature"), r.getString("body"));
    }

    static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    /** Runs {@code body} in one transaction on a borrowed connection; rolls back on any failure. */
    <T> T tx(TxBody<T> body, String what) {
        try (Connection c = ds.getConnection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                T out = body.run(c);
                c.commit();
                return out;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            throw failed(what, e);
        }
    }

    static IllegalStateException failed(String what, SQLException e) {
        return new IllegalStateException("edit log: could not " + what + ": " + e.getMessage(), e);
    }
}
