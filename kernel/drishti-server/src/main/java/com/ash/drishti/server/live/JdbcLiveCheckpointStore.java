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
package com.ash.drishti.server.live;

import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.live.LiveCheckpointStore;
import com.ash.drishti.live.LiveCheckpoints;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The live intake's checkpoints in the Drishti database (docs/architecture/LIVE_CONNECTORS_DESIGN.md section 6): {@code drishti_live_checkpoint}
 * (per connector and kind, the cut-off a by-cutoff reconcile absorbed up to) and {@code drishti_live_position} (per connector, kind, stream and
 * partition, the last offset a reset applied). The same statements run on SQLite and PostgreSQL. A checkpoint only moves forward: each write
 * is an update that names the older value, then an insert where the row is not there yet, in one transaction, so a node that took a feed over, a
 * repeated reconcile or a stale write never brings back what a load absorbed. A feed reads its connector's rows when it starts, which is how the
 * node that takes a feed over continues where the last one stopped.
 */
@Component
public final class JdbcLiveCheckpointStore implements LiveCheckpointStore {

    private final DataSource ds;

    /** The server's: told to the registry when the Drishti database is there (a server without one keeps checkpoints in memory). */
    @Autowired
    public JdbcLiveCheckpointStore(SourceRegistry registry, ObjectProvider<HikariDataSource> identity) {
        this.ds = identity.getIfAvailable();
        if (ds != null) {
            registry.liveCheckpoints(this);
        }
    }

    /** Over this database (the tests'). */
    public JdbcLiveCheckpointStore(DataSource ds) {
        this.ds = ds;
    }

    @Override
    public Map<String, LiveCheckpoints.Kind> load(String connector) {
        Map<String, Instant> cutoffs = new LinkedHashMap<>();
        Map<String, Map<String, Long>> positions = new HashMap<>();
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement s = c.prepareStatement("SELECT kind, cutoff_at FROM drishti_live_checkpoint WHERE connector = ?")) {
                s.setString(1, connector);
                try (ResultSet r = s.executeQuery()) {
                    while (r.next()) {
                        Timestamp t = r.getTimestamp(2);
                        cutoffs.put(r.getString(1), t == null ? null : t.toInstant());
                    }
                }
            }
            try (PreparedStatement s = c.prepareStatement(
                    "SELECT kind, stream, stream_partition, last_offset FROM drishti_live_position WHERE connector = ?")) {
                s.setString(1, connector);
                try (ResultSet r = s.executeQuery()) {
                    while (r.next()) {
                        positions.computeIfAbsent(r.getString(1), k -> new LinkedHashMap<>()).put(r.getString(2) + "/" + r.getInt(3), r.getLong(4));
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read the live checkpoints of " + connector + ": " + e.getMessage(), e);
        }
        Map<String, LiveCheckpoints.Kind> out = new LinkedHashMap<>();
        cutoffs.forEach((kind, cutoff) -> out.put(kind, new LiveCheckpoints.Kind(cutoff, positions.getOrDefault(kind, Map.of()))));
        positions.forEach((kind, p) -> out.computeIfAbsent(kind, k -> new LiveCheckpoints.Kind(null, p)));
        return out;
    }

    @Override
    public void save(String connector, String kind, LiveCheckpoints.Kind checkpoint) {
        Timestamp now = Timestamp.from(Instant.now());
        Timestamp cutoff = checkpoint.cutoff() == null ? null : Timestamp.from(checkpoint.cutoff());
        try (Connection c = ds.getConnection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                if (cutoff != null) {
                    try (PreparedStatement s = c.prepareStatement("UPDATE drishti_live_checkpoint SET cutoff_at = ?, updated_at = ? "
                            + "WHERE connector = ? AND kind = ? AND (cutoff_at IS NULL OR cutoff_at < ?)")) {
                        s.setTimestamp(1, cutoff);
                        s.setTimestamp(2, now);
                        s.setString(3, connector);
                        s.setString(4, kind);
                        s.setTimestamp(5, cutoff);
                        s.executeUpdate();
                    }
                }
                try (PreparedStatement s = c.prepareStatement("INSERT INTO drishti_live_checkpoint (connector, kind, cutoff_at, updated_at) "
                        + "SELECT ?, ?, ?, ? WHERE NOT EXISTS (SELECT 1 FROM drishti_live_checkpoint WHERE connector = ? AND kind = ?)")) {
                    s.setString(1, connector);
                    s.setString(2, kind);
                    if (cutoff == null) {
                        s.setNull(3, Types.TIMESTAMP);
                    } else {
                        s.setTimestamp(3, cutoff);
                    }
                    s.setTimestamp(4, now);
                    s.setString(5, connector);
                    s.setString(6, kind);
                    s.executeUpdate();
                }
                for (Map.Entry<String, Long> p : checkpoint.positions().entrySet()) {
                    int slash = p.getKey().lastIndexOf('/');
                    String stream = p.getKey().substring(0, slash);
                    int partition = Integer.parseInt(p.getKey().substring(slash + 1));
                    try (PreparedStatement s = c.prepareStatement("UPDATE drishti_live_position SET last_offset = ?, updated_at = ? "
                            + "WHERE connector = ? AND kind = ? AND stream = ? AND stream_partition = ? AND last_offset < ?")) {
                        s.setLong(1, p.getValue());
                        s.setTimestamp(2, now);
                        s.setString(3, connector);
                        s.setString(4, kind);
                        s.setString(5, stream);
                        s.setInt(6, partition);
                        s.setLong(7, p.getValue());
                        s.executeUpdate();
                    }
                    try (PreparedStatement s = c.prepareStatement("INSERT INTO drishti_live_position "
                            + "(connector, kind, stream, stream_partition, last_offset, updated_at) SELECT ?, ?, ?, ?, ?, ? WHERE NOT EXISTS "
                            + "(SELECT 1 FROM drishti_live_position WHERE connector = ? AND kind = ? AND stream = ? AND stream_partition = ?)")) {
                        s.setString(1, connector);
                        s.setString(2, kind);
                        s.setString(3, stream);
                        s.setInt(4, partition);
                        s.setLong(5, p.getValue());
                        s.setTimestamp(6, now);
                        s.setString(7, connector);
                        s.setString(8, kind);
                        s.setString(9, stream);
                        s.setInt(10, partition);
                        s.executeUpdate();
                    }
                }
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot write the live checkpoint of " + connector + "/" + kind + ": " + e.getMessage(), e);
        }
    }
}
