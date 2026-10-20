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
package com.ash.drishti.plugin.cassandra;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The table layout the loader writes and the connector reads (docs/architecture/CASSANDRA_CONNECTOR_DESIGN.md section 4): a
 * data domain is a keyspace; each kind has one data table with a partition per business day, generation and bucket, keyed
 * by id; the domain shares {@code entity_days} (the days each entity has) and {@code kind_days} (each kind's days and the
 * generation of each that readers follow). Every statement either names a whole partition key or creates a table: none
 * scans (no {@code ALLOW FILTERING}, no token range), which {@code CassandraPartitionKeyTest} enforces.
 *
 * <p>Names: CQL folds unquoted names to lower case, so a kind, a domain or a promoted path becomes a lower-case identifier
 * (a dot becomes {@code __}, as in Delta Lake; any other character outside {@code [a-z0-9_]} becomes {@code _}); a name
 * longer than 48 characters is cut and given four hex digits of its SHA-1. Every identifier is written quoted, so a name
 * that happens to be a CQL keyword ({@code order}, {@code limit}) is still a name.
 */
public final class CassandraLayout {

    public static final String DATE = "business_date";
    public static final String GENERATION = "generation";
    public static final String BUCKET = "bucket";
    public static final String ID = "id";
    public static final String DOC = "doc";
    public static final String KIND = "kind";
    public static final String ENTITY_DAYS = "entity_days";
    public static final String KIND_DAYS = "kind_days";
    /** The data table's partition key, in order. */
    public static final List<String> PARTITION_KEY = List.of(DATE, GENERATION, BUCKET);
    /** The table comment that names the kind a data table holds (a kind's name may not survive the identifier rule). */
    public static final String COMMENT_PREFIX = "drishti kind: ";
    /** Buckets per day for a new load when none is given. */
    public static final int DEFAULT_BUCKETS = 128;

    private static final Set<String> RESERVED_COLUMNS = Set.of(DATE, GENERATION, BUCKET, ID, DOC);
    private static final Set<String> RESERVED_TABLES = Set.of(ENTITY_DAYS, KIND_DAYS);
    private static final int MAX_NAME = 48;

    /** One business day of a kind as {@code kind_days} lists it: the generation readers follow and how it was written. */
    public record Day(LocalDate date, int generation, int buckets, long rows, Instant loadedAt) {}

    /** A promoted column's CQL type: numbers are doubles, everything else text. */
    public enum ColumnType {
        DOUBLE("double"), TEXT("text");

        private final String cql;

        ColumnType(String cql) {
            this.cql = cql;
        }

        public String cql() {
            return cql;
        }

        static ColumnType of(Object value) {
            return value instanceof Number ? DOUBLE : TEXT;
        }
    }

    private CassandraLayout() {
    }

    /** A lower-case CQL identifier for a kind, domain or path (see the class comment). */
    public static String name(String text) {
        String n = text.toLowerCase(Locale.ROOT).replace(".", "__").replaceAll("[^a-z0-9_]", "_");
        if (n.isEmpty() || !Character.isLetter(n.charAt(0))) {
            n = "c_" + n;
        }
        if (n.length() <= MAX_NAME) {
            return n;
        }
        return n.substring(0, MAX_NAME - 5) + "_" + sha1(text).substring(0, 4);
    }

    /** The keyspace of a data domain ({@code trading}). */
    public static String keyspace(String domain) {
        return name(domain);
    }

    /** The data table of a kind ({@code netting-set} becomes {@code netting_set}); never one of the shared tables. */
    public static String table(String kind) {
        String t = name(kind);
        return RESERVED_TABLES.contains(t) ? t + "_k" : t;
    }

    /** The column a promoted path is kept in ({@code LEGS.CURRENCY} becomes {@code legs__currency}); never a key or the document. */
    public static String column(String path) {
        String c = name(path);
        return RESERVED_COLUMNS.contains(c) ? "f_" + c : c;
    }

    /** The bucket of an id among {@code buckets}: Murmur3 (x86, 32 bit, seed 0) of its UTF-8 bytes, so every client agrees. */
    public static int bucket(String id, int buckets) {
        return Math.floorMod(murmur3(id.getBytes(StandardCharsets.UTF_8)), Math.max(1, buckets));
    }

    static int murmur3(byte[] data) {
        final int c1 = 0xcc9e2d51;
        final int c2 = 0x1b873593;
        int h = 0;
        int blocks = data.length / 4;
        for (int i = 0; i < blocks; i++) {
            int k = (data[4 * i] & 0xff) | (data[4 * i + 1] & 0xff) << 8 | (data[4 * i + 2] & 0xff) << 16 | (data[4 * i + 3] & 0xff) << 24;
            k *= c1;
            k = Integer.rotateLeft(k, 15);
            k *= c2;
            h ^= k;
            h = Integer.rotateLeft(h, 13);
            h = h * 5 + 0xe6546b64;
        }
        int k = 0;
        int tail = blocks * 4;
        switch (data.length & 3) {
            case 3:
                k ^= (data[tail + 2] & 0xff) << 16;
                // fall through
            case 2:
                k ^= (data[tail + 1] & 0xff) << 8;
                // fall through
            case 1:
                k ^= data[tail] & 0xff;
                k *= c1;
                k = Integer.rotateLeft(k, 15);
                k *= c2;
                h ^= k;
                break;
            default:
                break;
        }
        h ^= data.length;
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return h;
    }

    private static String sha1(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A quoted identifier. */
    static String q(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    private static String t(String keyspace, String table) {
        return q(keyspace) + "." + q(table);
    }

    private static final String WHERE_PARTITION = " WHERE " + q(DATE) + " = ? AND " + q(GENERATION) + " = ? AND " + q(BUCKET) + " = ?";

    // ---- schema (the loader with --create; printed for an administrator without it) ----------------------------------------

    /** The keyspace for a development node (SimpleStrategy); a production keyspace's replication is the administrator's. */
    public static String createKeyspace(String keyspace, int replication) {
        return "CREATE KEYSPACE IF NOT EXISTS " + q(keyspace) + " WITH replication = {'class': 'SimpleStrategy', 'replication_factor': "
                + Math.max(1, replication) + "}";
    }

    /**
     * A kind's data table, with its promoted columns; with a TTL load ({@code timeWindows}) on one-day time windows, so whole
     * SSTables expire together.
     */
    public static String createTable(String keyspace, String kind, Map<String, ColumnType> columns, boolean timeWindows) {
        return createTable(keyspace, kind, columns, timeWindows, false);
    }

    /**
     * The same, with the older compression option ({@code sstable_compression}) that ScyllaDB still requires where Cassandra 4
     * and 5 take {@code class}.
     */
    public static String createTable(String keyspace, String kind, Map<String, ColumnType> columns, boolean timeWindows, boolean legacyCompression) {
        StringBuilder b = new StringBuilder("CREATE TABLE IF NOT EXISTS ").append(t(keyspace, table(kind))).append(" (")
                .append(q(DATE)).append(" date, ").append(q(GENERATION)).append(" int, ").append(q(BUCKET)).append(" smallint, ")
                .append(q(ID)).append(" text, ").append(q(DOC)).append(" text");
        columns.forEach((c, type) -> b.append(", ").append(q(c)).append(' ').append(type.cql()));
        b.append(", PRIMARY KEY ((").append(q(DATE)).append(", ").append(q(GENERATION)).append(", ").append(q(BUCKET)).append("), ")
                .append(q(ID)).append(")) WITH compression = {'").append(legacyCompression ? "sstable_compression" : "class")
                .append("': 'ZstdCompressor'} AND comment = '")
                .append((COMMENT_PREFIX + kind).replace("'", "''")).append('\'');
        if (timeWindows) {
            b.append(" AND compaction = {'class': 'TimeWindowCompactionStrategy', 'compaction_window_unit': 'DAYS', 'compaction_window_size': 1}");
        }
        return b.toString();
    }

    /** A promoted column added to an existing table. */
    public static String addColumn(String keyspace, String kind, String column, ColumnType type) {
        return "ALTER TABLE " + t(keyspace, table(kind)) + " ADD " + q(column) + " " + type.cql();
    }

    public static String createEntityDays(String keyspace) {
        return "CREATE TABLE IF NOT EXISTS " + t(keyspace, ENTITY_DAYS) + " (" + q(KIND) + " text, " + q(ID) + " text, " + q(DATE) + " date, "
                + "PRIMARY KEY ((" + q(KIND) + ", " + q(ID) + "), " + q(DATE) + ")) WITH CLUSTERING ORDER BY (" + q(DATE) + " DESC)";
    }

    public static String createKindDays(String keyspace) {
        return "CREATE TABLE IF NOT EXISTS " + t(keyspace, KIND_DAYS) + " (" + q(KIND) + " text, " + q(DATE) + " date, " + q(GENERATION) + " int, "
                + "\"buckets\" int, \"rows\" bigint, \"loaded_at\" timestamp, PRIMARY KEY (" + q(KIND) + ", " + q(DATE) + ")) "
                + "WITH CLUSTERING ORDER BY (" + q(DATE) + " DESC)";
    }

    // ---- writes --------------------------------------------------------------------------------------------------------------

    /** One entity's row for a day: the keys, the document and the given promoted columns (values left unbound are not written). */
    public static String insertRow(String keyspace, String kind, List<String> columns, boolean ttl) {
        String extra = columns.stream().map(c -> ", " + q(c)).collect(Collectors.joining());
        return "INSERT INTO " + t(keyspace, table(kind)) + " (" + q(DATE) + ", " + q(GENERATION) + ", " + q(BUCKET) + ", " + q(ID) + ", " + q(DOC)
                + extra + ") VALUES (?, ?, ?, ?, ?" + ", ?".repeat(columns.size()) + ")" + (ttl ? " USING TTL ?" : "");
    }

    public static String insertEntityDay(String keyspace, boolean ttl) {
        return "INSERT INTO " + t(keyspace, ENTITY_DAYS) + " (" + q(KIND) + ", " + q(ID) + ", " + q(DATE) + ") VALUES (?, ?, ?)" + (ttl ? " USING TTL ?" : "");
    }

    /** The row that makes a day's generation the one readers follow: written last, after every row of the day. */
    public static String insertKindDay(String keyspace, boolean ttl) {
        return "INSERT INTO " + t(keyspace, KIND_DAYS) + " (" + q(KIND) + ", " + q(DATE) + ", " + q(GENERATION) + ", \"buckets\", \"rows\", \"loaded_at\") "
                + "VALUES (?, ?, ?, ?, ?, ?)" + (ttl ? " USING TTL ?" : "");
    }

    /** One whole partition (a day's generation's bucket): a single partition tombstone, never row by row. */
    public static String deletePartition(String keyspace, String kind) {
        return "DELETE FROM " + t(keyspace, table(kind)) + WHERE_PARTITION;
    }

    /** A dropped day's catalogue row (in the kind's small {@code kind_days} partition). */
    public static String deleteKindDay(String keyspace) {
        return "DELETE FROM " + t(keyspace, KIND_DAYS) + " WHERE " + q(KIND) + " = ? AND " + q(DATE) + " = ?";
    }

    // ---- reads ---------------------------------------------------------------------------------------------------------------

    /** A kind's days, newest first: one small partition. */
    public static String selectKindDays(String keyspace) {
        return "SELECT " + q(DATE) + ", " + q(GENERATION) + ", \"buckets\", \"rows\", \"loaded_at\" FROM " + t(keyspace, KIND_DAYS) + " WHERE " + q(KIND) + " = ?";
    }

    /** An entity's days on or before a date, newest first. */
    public static String selectEntityDaysUpTo(String keyspace) {
        return "SELECT " + q(DATE) + " FROM " + t(keyspace, ENTITY_DAYS) + " WHERE " + q(KIND) + " = ? AND " + q(ID) + " = ? AND " + q(DATE) + " <= ? LIMIT ?";
    }

    /** An entity's newest days. */
    public static String selectEntityDays(String keyspace) {
        return "SELECT " + q(DATE) + " FROM " + t(keyspace, ENTITY_DAYS) + " WHERE " + q(KIND) + " = ? AND " + q(ID) + " = ? LIMIT ?";
    }

    /** One entity's document on a day: one row of one partition. */
    public static String selectDoc(String keyspace, String kind) {
        return "SELECT " + q(DOC) + " FROM " + t(keyspace, table(kind)) + WHERE_PARTITION + " AND " + q(ID) + " = ?";
    }

    /** A partition's ids, sorted (type-ahead). */
    public static String selectIds(String keyspace, String kind) {
        return "SELECT " + q(ID) + " FROM " + t(keyspace, table(kind)) + WHERE_PARTITION;
    }

    /** A partition's ids and promoted columns, never the document (searches, pick lists, derived kinds, impact). */
    public static String selectColumns(String keyspace, String kind, List<String> columns) {
        String cols = columns.stream().map(c -> ", " + q(c)).collect(Collectors.joining());
        return "SELECT " + q(ID) + cols + " FROM " + t(keyspace, table(kind)) + WHERE_PARTITION;
    }

    /** A partition's ids and documents (documents of a day, reverse lookups without promoted columns). */
    public static String selectDocs(String keyspace, String kind) {
        return "SELECT " + q(ID) + ", " + q(DOC) + " FROM " + t(keyspace, table(kind)) + WHERE_PARTITION;
    }
}
