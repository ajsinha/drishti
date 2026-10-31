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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * What an applied change leaves in the Drishti database besides its log row (WRITE_BACK.md section 5.2): <b>overlays</b>
 * ({@code drishti_overlay}: a dated value laid over the source's at read time, with the source's value beside it), <b>owned</b> values
 * ({@code drishti_owned}: the kind is Drishti's own), and each record's <b>version</b> ({@code drishti_edit_version}: +1 per applied
 * change, the {@code basedOn} a form opens on). The writes run on the apply's own connection and transaction; an overlay or owned
 * row already written for a change id is not written again (a replayed apply is safe). Reads are cached per record and dropped when a
 * change to it is applied (or a follower learns one).
 */
public final class EditRecords {

    public static final String ACTIVE = "active";
    public static final String WITHDRAWN = "withdrawn";

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * One overlay value.
     *
     * @param path the record's field ({@code $.NOTIONAL}, or a row of a page: {@code $.TRADES[TRADE_ID=MX-1].NOTIONAL})
     * @param value the value laid over the source's
     * @param sourceValue what the source said when it was applied
     * @param businessDate from when it holds
     * @param until the last date it holds, or null
     */
    public record Overlay(String changeId, String path, String kind, String entity, Object value, Object sourceValue, LocalDate businessDate,
            LocalDate until, String state, String by, Instant appliedAt) {

        public boolean validOn(LocalDate d) {
            return d == null || !businessDate.isAfter(d) && (until == null || !until.isBefore(d));
        }
    }

    /** One owned value. */
    public record Owned(String kind, String entity, String path, Object value, String changeId, String by, Instant at) {}

    private final DataSource ds;
    // per entity read once any edit exists (soak audit H11): bounded since 2.9.7, 100,000 entities each and an idle hour; a miss reads the table
    private final Map<String, List<Overlay>> overlayCache = com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(100_000)
            .expireAfterAccess(java.time.Duration.ofHours(1)).<String, List<Overlay>>build().asMap();
    private final Map<String, List<Owned>> ownedCache = com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(100_000)
            .expireAfterAccess(java.time.Duration.ofHours(1)).<String, List<Owned>>build().asMap();
    private final java.util.concurrent.atomic.AtomicLong forgotten = new java.util.concurrent.atomic.AtomicLong();
    private volatile boolean any = true;

    public EditRecords(DataSource ds) {
        this.ds = ds;
        try (Connection c = ds.getConnection(); PreparedStatement s = c.prepareStatement("SELECT 1 FROM drishti_overlay WHERE state = ? LIMIT 1")) {
            s.setString(1, ACTIVE);
            try (ResultSet r = s.executeQuery(); PreparedStatement o = c.prepareStatement("SELECT 1 FROM drishti_owned LIMIT 1"); ResultSet r2 = o.executeQuery()) {
                any = r.next() || r2.next();
            }
        } catch (SQLException e) {
            any = true;
        }
    }

    /** False while nothing was ever written: reads skip the database entirely (a site that never edits pays nothing). */
    public boolean any() {
        return any;
    }

    // ------------------------------------------------------------------------------------------------------- writes (in the apply's tx)

    /** Writes an overlay row unless this change already wrote this path. */
    public void overlay(Connection c, Overlay o) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("INSERT INTO drishti_overlay (change_id, path, kind, entity, value, source_value, business_date, "
                + "valid_until, state, by_user, applied_at) SELECT ?,?,?,?,?,?,?,?,?,?,? WHERE NOT EXISTS (SELECT 1 FROM drishti_overlay WHERE change_id = ? AND path = ?)")) {
            s.setString(1, o.changeId());
            s.setString(2, o.path());
            s.setString(3, o.kind());
            s.setString(4, o.entity());
            s.setString(5, json(o.value()));
            s.setString(6, json(o.sourceValue()));
            s.setString(7, o.businessDate().toString());
            s.setString(8, o.until() == null ? null : o.until().toString());
            s.setString(9, ACTIVE);
            s.setString(10, o.by());
            s.setTimestamp(11, Timestamp.from(o.appliedAt()));
            s.setString(12, o.changeId());
            s.setString(13, o.path());
            s.executeUpdate();
        }
        any = true;
    }

    /** Sets an owned value (the newest change holds it). */
    public void owned(Connection c, Owned o) throws SQLException {
        try (PreparedStatement d = c.prepareStatement("DELETE FROM drishti_owned WHERE kind = ? AND entity = ? AND path = ?")) {
            d.setString(1, o.kind());
            d.setString(2, o.entity());
            d.setString(3, o.path());
            d.executeUpdate();
        }
        try (PreparedStatement s = c.prepareStatement("INSERT INTO drishti_owned (kind, entity, path, value, change_id, updated_by, updated_at) VALUES (?,?,?,?,?,?,?)")) {
            s.setString(1, o.kind());
            s.setString(2, o.entity());
            s.setString(3, o.path());
            s.setString(4, json(o.value()));
            s.setString(5, o.changeId());
            s.setString(6, o.by());
            s.setTimestamp(7, Timestamp.from(o.at()));
            s.executeUpdate();
        }
        any = true;
    }

    /** Moves a record's version up by one, unless {@code changeId} already moved it; returns the version now. */
    public long bump(Connection c, String key, String changeId, Instant at) throws SQLException {
        long now = 0;
        String last = null;
        try (PreparedStatement s = c.prepareStatement("SELECT version, change_id FROM drishti_edit_version WHERE change_key = ?")) {
            s.setString(1, key);
            try (ResultSet r = s.executeQuery()) {
                if (r.next()) {
                    now = r.getLong(1);
                    last = r.getString(2);
                }
            }
        }
        if (changeId.equals(last)) {
            return now;
        }
        if (last == null) {
            try (PreparedStatement s = c.prepareStatement("INSERT INTO drishti_edit_version (change_key, version, change_id, updated_at) VALUES (?,?,?,?)")) {
                s.setString(1, key);
                s.setLong(2, 1);
                s.setString(3, changeId);
                s.setTimestamp(4, Timestamp.from(at));
                s.executeUpdate();
            }
            return 1;
        }
        try (PreparedStatement s = c.prepareStatement("UPDATE drishti_edit_version SET version = version + 1, change_id = ?, updated_at = ? WHERE change_key = ?")) {
            s.setString(1, changeId);
            s.setTimestamp(2, Timestamp.from(at));
            s.setString(3, key);
            s.executeUpdate();
        }
        return now + 1;
    }

    /** Withdraws a change's overlays (a source that refused the command later, or an operator). */
    public void withdraw(Connection c, String changeId) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("UPDATE drishti_overlay SET state = ? WHERE change_id = ?")) {
            s.setString(1, WITHDRAWN);
            s.setString(2, changeId);
            s.executeUpdate();
        }
    }

    /** Drops what is cached for a record (after an apply commits, or a follower learns one). */
    public void forget(String kind, String entity) {
        forgotten.incrementAndGet();
        overlayCache.remove(Change.keyOf(kind, entity));
        ownedCache.remove(Change.keyOf(kind, entity));
    }

    public void forgetAll() {
        forgotten.incrementAndGet();
        overlayCache.clear();
        ownedCache.clear();
    }

    // ------------------------------------------------------------------------------------------------------- reads

    /** A record's active overlays, oldest first (a later one on the same path wins). */
    public List<Overlay> overlays(String kind, String entity) {
        if (!any) {
            return List.of();
        }
        String key = Change.keyOf(kind, entity);
        List<Overlay> got = overlayCache.get(key);
        if (got == null) {                                   // read outside the map's lock; a concurrent reader may read it too
            long before = forgotten.get();
            got = read(kind, entity);
            if (forgotten.get() == before) {                 // nothing was applied meanwhile: safe to keep
                overlayCache.putIfAbsent(key, got);
            }
        }
        return got;
    }

    private List<Overlay> read(String kind, String entity) {
        List<Overlay> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
                PreparedStatement s = c.prepareStatement("SELECT change_id, path, kind, entity, value, source_value, business_date, valid_until, state, by_user, "
                        + "applied_at FROM drishti_overlay WHERE kind = ? AND entity = ? AND state = ?")) {
            s.setString(1, kind);
            s.setString(2, entity);
            s.setString(3, ACTIVE);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    String until = r.getString(8);
                    out.add(new Overlay(r.getString(1), r.getString(2), r.getString(3), r.getString(4), value(r.getString(5)), value(r.getString(6)),
                            LocalDate.parse(r.getString(7)), until == null ? null : LocalDate.parse(until), r.getString(9), r.getString(10),
                            EditLog.instant(r.getTimestamp(11))));
                }
            }
        } catch (SQLException e) {
            throw EditLog.failed("read the overlays of " + kind + " " + entity, e);
        }
        out.sort(Comparator.comparing(Overlay::appliedAt).thenComparing(Overlay::changeId));
        return List.copyOf(out);
    }

    /** The overlays of a record that hold on {@code date}, one per path (the newest wins). */
    public Map<String, Overlay> on(String kind, String entity, LocalDate date) {
        Map<String, Overlay> out = new LinkedHashMap<>();
        for (Overlay o : overlays(kind, entity)) {
            if (o.validOn(date)) {
                out.put(o.path(), o);
            }
        }
        return out;
    }

    /** A record's owned values. */
    public List<Owned> owned(String kind, String entity) {
        if (!any) {
            return List.of();
        }
        String key = Change.keyOf(kind, entity);
        List<Owned> cached = ownedCache.get(key);
        if (cached != null) {
            return cached;
        }
        long before = forgotten.get();
        {
            List<Owned> out = new ArrayList<>();
            try (Connection c = ds.getConnection();
                    PreparedStatement s = c.prepareStatement("SELECT kind, entity, path, value, change_id, updated_by, updated_at FROM drishti_owned WHERE kind = ? AND entity = ?")) {
                s.setString(1, kind);
                s.setString(2, entity);
                try (ResultSet r = s.executeQuery()) {
                    while (r.next()) {
                        out.add(new Owned(r.getString(1), r.getString(2), r.getString(3), value(r.getString(4)), r.getString(5), r.getString(6),
                                EditLog.instant(r.getTimestamp(7))));
                    }
                }
            } catch (SQLException e) {
                throw EditLog.failed("read the owned values of " + kind + " " + entity, e);
            }
            List<Owned> got = List.copyOf(out);
            if (forgotten.get() == before) {
                ownedCache.putIfAbsent(key, got);
            }
            return got;
        }
    }

    /** A record's version (0: never changed through Drishti). */
    public long version(String key) {
        try (Connection c = ds.getConnection(); PreparedStatement s = c.prepareStatement("SELECT version FROM drishti_edit_version WHERE change_key = ?")) {
            s.setString(1, key);
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? r.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw EditLog.failed("read a version", e);
        }
    }

    /** The overlays of every record that still differ from the source (the Open overlays report, decision 16.1). */
    public List<Overlay> open(int limit) {
        List<Overlay> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
                PreparedStatement s = c.prepareStatement("SELECT change_id, path, kind, entity, value, source_value, business_date, valid_until, state, by_user, "
                        + "applied_at FROM drishti_overlay WHERE state = ? ORDER BY applied_at DESC LIMIT " + Math.max(1, Math.min(limit, 5_000)))) {
            s.setString(1, ACTIVE);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    String until = r.getString(8);
                    out.add(new Overlay(r.getString(1), r.getString(2), r.getString(3), r.getString(4), value(r.getString(5)), value(r.getString(6)),
                            LocalDate.parse(r.getString(7)), until == null ? null : LocalDate.parse(until), r.getString(9), r.getString(10),
                            EditLog.instant(r.getTimestamp(11))));
                }
            }
        } catch (SQLException e) {
            throw EditLog.failed("read the open overlays", e);
        }
        return out;
    }

    /** Every overlay a change wrote, in any state (a follower copies them; a withdrawal names their records). */
    public List<Overlay> ofChange(String changeId) {
        List<Overlay> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
                PreparedStatement s = c.prepareStatement("SELECT change_id, path, kind, entity, value, source_value, business_date, valid_until, state, by_user, "
                        + "applied_at FROM drishti_overlay WHERE change_id = ?")) {
            s.setString(1, changeId);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    String until = r.getString(8);
                    out.add(new Overlay(r.getString(1), r.getString(2), r.getString(3), r.getString(4), value(r.getString(5)), value(r.getString(6)),
                            LocalDate.parse(r.getString(7)), until == null ? null : LocalDate.parse(until), r.getString(9), r.getString(10),
                            EditLog.instant(r.getTimestamp(11))));
                }
            }
        } catch (SQLException e) {
            throw EditLog.failed("read the overlays of " + changeId, e);
        }
        return out;
    }

    /** The owned values a change holds now. */
    public List<Owned> ownedOfChange(String changeId) {
        List<Owned> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
                PreparedStatement s = c.prepareStatement("SELECT kind, entity, path, value, change_id, updated_by, updated_at FROM drishti_owned WHERE change_id = ?")) {
            s.setString(1, changeId);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    out.add(new Owned(r.getString(1), r.getString(2), r.getString(3), value(r.getString(4)), r.getString(5), r.getString(6),
                            EditLog.instant(r.getTimestamp(7))));
                }
            }
        } catch (SQLException e) {
            throw EditLog.failed("read the owned values of " + changeId, e);
        }
        return out;
    }

    /** Sets an overlay's state as a follower learned it (the primary withdrew it). */
    public void state(Connection c, String changeId, String path, String state) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("UPDATE drishti_overlay SET state = ? WHERE change_id = ? AND path = ?")) {
            s.setString(1, state);
            s.setString(2, changeId);
            s.setString(3, path);
            s.executeUpdate();
        }
    }

    public Optional<Overlay> current(String kind, String entity, String path, LocalDate date) {
        return Optional.ofNullable(on(kind, entity, date).get(path));
    }

    static String json(Object v) {
        try {
            return v == null ? null : JSON.writeValueAsString(v);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("a value that cannot be written: " + e.getMessage(), e);
        }
    }

    static Object value(String json) {
        try {
            return json == null ? null : JSON.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            return json;
        }
    }
}
