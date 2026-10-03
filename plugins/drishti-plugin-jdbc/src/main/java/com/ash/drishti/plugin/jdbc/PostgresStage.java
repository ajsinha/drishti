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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Where one load of {@link PostgresLoader} writes a data domain's rows before any reader sees them, and how it then
 * publishes them, so that a reader sees each (kind, business date) either as it was or as loaded, never empty or half
 * loaded, and a killed or failed load leaves the domain as it was:
 *
 * <ul>
 *   <li>A load that adds to the domain COPYs its rows into a stage table, {@code <schema>.entities_load_<token>}
 *       (unlogged, no index), on many connections at once. Each (kind, business date) is then published in one
 *       transaction: the day's rows deleted, the staged rows inserted, the day recorded in {@code entity_dates}. A
 *       transaction-scoped advisory lock on the (schema, kind, date) serialises two loads of the same day: the later
 *       one replaces the earlier one whole.</li>
 *   <li>A load with {@code --recreate} builds the whole domain in a shadow schema, {@code <schema>__load_<token>}
 *       (the layout's tables, partitions and indexes), and swaps it in in one transaction: the old tables dropped, the
 *       new ones moved into the schema. Until then readers see the old domain.</li>
 * </ul>
 *
 * <p>A stage is marked alive by a session advisory lock on its name; a stage left by a killed load (its lock is gone
 * with its connection) is dropped by the next load of the domain.
 */
final class PostgresStage {

    private static final String STAGE_PREFIX = PostgresLayout.TABLE + "_load_";
    private static final String SHADOW_INFIX = "__load_";
    private static final String RECREATE_STAGE = PostgresLayout.TABLE + "_stage";
    /** The stage's extra column: the line of the input a row came from, so the last of a duplicated id wins. */
    static final String LINE = "_line";

    private final String schema;
    private final boolean recreate;
    private final String name;                                      // the stage table, or the shadow schema
    private final Map<String, Boolean> columns = new LinkedHashMap<>();
    private final Set<LocalDate> months = new HashSet<>();
    private final Map<String, Map<LocalDate, AtomicLong>> days = new TreeMap<>();   // kind -> date -> rows staged

    private PostgresStage(String schema, boolean recreate, String name) {
        this.schema = schema;
        this.recreate = recreate;
        this.name = name;
    }

    /**
     * Opens a load's stage for a data domain: drops the stages killed loads left, creates the domain's tables when
     * missing (and, with {@code recreate}, the shadow schema), and the stage.
     */
    static PostgresStage open(Connection admin, String schema, boolean recreate) throws SQLException {
        String token = Long.toHexString(ThreadLocalRandom.current().nextLong() >>> 32 | 0x1_0000_0000L).substring(1);
        String name = recreate ? schema + SHADOW_INFIX + token : STAGE_PREFIX + token;
        if (name.length() > 63) {
            throw new IllegalArgumentException("schema name too long for a load's stage: " + schema);
        }
        dropLeftovers(admin, schema);
        PostgresStage s = new PostgresStage(schema, recreate, name);
        try (PreparedStatement ps = admin.prepareStatement("SELECT pg_advisory_lock(hashtext(?))")) {
            ps.setString(1, s.lockName());
            ps.execute();
        }
        if (recreate) {
            PostgresLayout.create(admin, name);                         // the new domain, invisible until swapped in
            try (Statement st = admin.createStatement()) {
                st.execute("CREATE UNLOGGED TABLE " + name + "." + RECREATE_STAGE + " (LIKE " + name + "." + PostgresLayout.TABLE + " INCLUDING COMPRESSION)");
                st.execute("ALTER TABLE " + name + "." + RECREATE_STAGE + " ADD COLUMN " + LINE + " bigint");
            }
        } else {
            Boolean partitioned = PostgresLayout.partitioned(admin, schema);
            if (Boolean.FALSE.equals(partitioned)) {
                throw new IllegalStateException(schema + "." + PostgresLayout.TABLE + " is a plain table of the old layout: load with --recreate");
            }
            PostgresLayout.create(admin, schema);
            try (Statement st = admin.createStatement()) {
                st.execute("CREATE UNLOGGED TABLE " + schema + "." + name + " (LIKE " + schema + "." + PostgresLayout.TABLE + " INCLUDING COMPRESSION)");
                st.execute("ALTER TABLE " + schema + "." + name + " ADD COLUMN " + LINE + " bigint");
            }
        }
        s.columns.putAll(PostgresLayout.columns(admin, recreate ? name : schema, PostgresLayout.TABLE));
        return s;
    }

    /** The name a stage's advisory lock is taken on (the qualified stage table, or the shadow schema). */
    private String lockName() {
        return recreate ? name : schema + "." + name;
    }

    /** Drops the stage tables and shadow schemas of the domain whose loads are gone (their advisory lock is free). */
    static void dropLeftovers(Connection admin, String schema) throws SQLException {
        List<String> found = new ArrayList<>();
        try (PreparedStatement ps = admin.prepareStatement("SELECT n.nspname || '.' || c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE n.nspname = ? AND c.relname LIKE ? UNION ALL SELECT nspname FROM pg_namespace WHERE nspname LIKE ?")) {
            ps.setString(1, schema);
            ps.setString(2, STAGE_PREFIX.replace("_", "\\_") + "%");
            ps.setString(3, (schema + SHADOW_INFIX).replace("_", "\\_") + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    found.add(rs.getString(1));
                }
            }
        }
        for (String f : found) {
            try (PreparedStatement lock = admin.prepareStatement("SELECT pg_try_advisory_lock(hashtext(?))")) {
                lock.setString(1, f);
                try (ResultSet rs = lock.executeQuery()) {
                    if (!rs.next() || !rs.getBoolean(1)) {
                        continue;                                       // a load still running
                    }
                }
            }
            try (Statement st = admin.createStatement()) {
                st.execute(f.contains(".") ? "DROP TABLE IF EXISTS " + f : "DROP SCHEMA IF EXISTS " + f + " CASCADE");
                System.err.println("postgres: dropped " + f + ", left by a load that did not finish");
            } finally {
                unlock(admin, f);
            }
        }
    }

    private static void unlock(Connection admin, String lockName) throws SQLException {
        try (PreparedStatement ps = admin.prepareStatement("SELECT pg_advisory_unlock(hashtext(?))")) {
            ps.setString(1, lockName);
            ps.execute();
        }
    }

    /** The table COPY writes this domain's rows into. */
    String into() {
        return recreate ? name + "." + RECREATE_STAGE : schema + "." + name;
    }

    /** The domain's promoted columns: {column: number}. */
    Map<String, Boolean> columns() {
        return columns;
    }

    /** Before a row is staged: its month's partition (where it will be published) and the day's count. */
    void add(Connection admin, String kind, LocalDate day) throws SQLException {
        if (months.add(day.withDayOfMonth(1))) {
            PostgresLayout.ensurePartition(admin, recreate ? name : schema, day);
        }
        days.computeIfAbsent(kind, k -> new TreeMap<>()).computeIfAbsent(day, d -> new AtomicLong()).incrementAndGet();
    }

    /** A promoted path seen with a value for the first time: a column of the stage and of the table it is published into. */
    void addColumn(Connection admin, String path, boolean number) throws SQLException {
        if (recreate) {
            PostgresLayout.addColumn(admin, name, path, number);
            try (Statement st = admin.createStatement()) {
                st.execute("ALTER TABLE " + into() + " ADD COLUMN IF NOT EXISTS " + PostgresLayout.quoted(path) + (number ? " double precision" : " text"));
            }
        } else {
            PostgresLayout.addColumn(admin, schema, path, number);    // nullable, no default: no row is rewritten
            try (Statement st = admin.createStatement()) {
                st.execute("ALTER TABLE " + schema + "." + name + " ADD COLUMN IF NOT EXISTS " + PostgresLayout.quoted(path) + (number ? " double precision" : " text"));
            }
        }
        columns.put(PostgresLayout.column(path), number);
    }

    String schema() {
        return schema;
    }

    boolean recreate() {
        return recreate;
    }

    /** Each kind's staged days: {kind: {date: rows}}. */
    Map<String, Map<LocalDate, AtomicLong>> days() {
        return days;
    }

    /** The monthly partitions this load wrote, for VACUUM. */
    List<String> partitions() {
        List<String> out = new ArrayList<>();
        months.stream().sorted().forEach(m -> out.add(schema + "." + PostgresLayout.partition(m)));
        return out;
    }

    /** Lets the publishing of many days find a day's staged rows without reading the whole stage each time. */
    void indexStage(Connection admin) throws SQLException {
        int pairs = days.values().stream().mapToInt(Map::size).sum();
        if (!recreate && pairs > 4) {
            try (Statement st = admin.createStatement()) {
                st.execute("CREATE INDEX ON " + into() + " (kind, business_date)");
                st.execute("ANALYZE " + into());
            }
        }
    }

    /**
     * Before anything is published: the ids staged more than once in a day, with the input lines they came from. The
     * last line of each wins (as in the JSON-lines connector). Returns the number of such ids; the first
     * {@code show} are named on stderr.
     */
    long reportDuplicates(Connection admin, int show) throws SQLException {
        long total = 0;
        try (Statement st = admin.createStatement();
             ResultSet rs = st.executeQuery("SELECT kind, business_date, id, count(*), array_to_string((array_agg(" + LINE + " ORDER BY " + LINE
                     + "))[1:5], ',') FROM " + into() + " GROUP BY kind, business_date, id HAVING count(*) > 1 ORDER BY min(" + LINE + ")")) {
            while (rs.next()) {
                if (total++ < show) {
                    System.err.printf("postgres: %s %s %s is on %d lines (%s): the last one is kept%n", rs.getString(1), rs.getString(2),
                            rs.getString(3), rs.getLong(4), rs.getString(5));
                }
            }
        }
        if (total > show) {
            System.err.printf("postgres: ... and %,d more ids loaded more than once%n", total - show);
        }
        return total;
    }

    /**
     * Publishes one staged (kind, business date) in one transaction: the day's rows replaced by the staged ones and
     * the day recorded. Returns the rows published.
     */
    long publishDay(Connection c, String kind, LocalDate day) throws SQLException {
        StringBuilder cols = new StringBuilder("kind, id, business_date, doc");
        columns.keySet().forEach(n -> cols.append(", \"").append(n).append('"'));
        String table = schema + "." + PostgresLayout.TABLE;
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            try (PreparedStatement lock = c.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))")) {
                lock.setString(1, schema + "/" + kind + "/" + day);       // two loads of one day: one after the other
                lock.execute();
            }
            try (PreparedStatement del = c.prepareStatement("DELETE FROM " + table + " WHERE kind = ? AND business_date = ?")) {
                del.setString(1, kind);
                del.setDate(2, java.sql.Date.valueOf(day));
                del.executeUpdate();
            }
            long n;
            try (PreparedStatement ins = c.prepareStatement("INSERT INTO " + table + " (" + cols + ") SELECT DISTINCT ON (id) " + cols + " FROM " + into()
                    + " WHERE kind = ? AND business_date = ? ORDER BY id, " + LINE + " DESC")) {
                ins.setString(1, kind);
                ins.setDate(2, java.sql.Date.valueOf(day));
                n = ins.executeUpdate();
            }
            PostgresLayout.recordDate(c, schema, kind, day, n);
            c.commit();
            return n;
        } catch (SQLException | RuntimeException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(auto);
        }
    }

    /**
     * Publishes a recreated domain: its days recorded in the shadow schema, then in one transaction the old tables
     * dropped and the shadow's tables moved into the schema.
     */
    void publishRecreated(Connection admin) throws SQLException {
        StringBuilder cols = new StringBuilder("kind, id, business_date, doc");
        columns.keySet().forEach(n -> cols.append(", \"").append(n).append('"'));
        for (var k : days.entrySet()) {
            for (var d : k.getValue().entrySet()) {
                long n;
                try (PreparedStatement ins = admin.prepareStatement("INSERT INTO " + name + "." + PostgresLayout.TABLE + " (" + cols
                        + ") SELECT DISTINCT ON (id) " + cols + " FROM " + into() + " WHERE kind = ? AND business_date = ? ORDER BY id, " + LINE + " DESC")) {
                    ins.setString(1, k.getKey());
                    ins.setDate(2, java.sql.Date.valueOf(d.getKey()));
                    n = ins.executeUpdate();
                }
                PostgresLayout.recordDate(admin, name, k.getKey(), d.getKey(), n);
            }
        }
        try (Statement st = admin.createStatement()) {
            st.execute("DROP TABLE " + into());
        }
        List<String> tables = new ArrayList<>();
        try (PreparedStatement ps = admin.prepareStatement("SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE n.nspname = ? AND c.relkind IN ('r', 'p') ORDER BY c.relkind DESC, c.relname")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
        }
        boolean auto = admin.getAutoCommit();
        admin.setAutoCommit(false);
        try (Statement st = admin.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
            st.execute("DROP TABLE IF EXISTS " + schema + "." + PostgresLayout.TABLE + " CASCADE");
            st.execute("DROP TABLE IF EXISTS " + schema + "." + PostgresLayout.DATES);
            for (String t : tables) {
                st.execute("ALTER TABLE " + name + "." + t + " SET SCHEMA " + schema);
            }
            st.execute("DROP SCHEMA " + name);
            admin.commit();
        } catch (SQLException | RuntimeException e) {
            admin.rollback();
            throw e;
        } finally {
            admin.setAutoCommit(auto);
        }
    }

    /** Drops what is left of the stage (all of it after a failure; the stage table after a publish) and frees its lock. */
    void close(Connection admin) throws SQLException {
        try (Statement st = admin.createStatement()) {
            st.execute(recreate ? "DROP SCHEMA IF EXISTS " + name + " CASCADE" : "DROP TABLE IF EXISTS " + schema + "." + name);
        } finally {
            unlock(admin, lockName());
        }
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%s (%s %s)", schema, recreate ? "shadow schema" : "stage", name);
    }
}
