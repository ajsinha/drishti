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
package com.ash.drishti.plugin.redis;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * Where a data domain's entities live in Redis: built for a million entities a day kept in memory for the last few days
 * (history beyond that lives in Delta Lake or Iceberg behind this store). For domain {@code trading}, kind {@code trade}:
 *
 * <pre>
 * trading:kinds                              SET     the kinds the domain holds
 * trading:trade:days                         ZSET    the kind's business days (member and score yyyyMMdd)
 * trading:trade:{MX-20000017}                ZSET    the entity's business days
 * trading:trade:{MX-20000017}:20260930       STRING  the entity's document that day, compressed ({@link DocCodec})
 * {trading:trade:20260930}:cols              HASH    the day's ids and promoted fields, column-wise in chunks ({@link ColumnCodec})
 * trading:trade:dict                         STRING  the id of the kind's current zstd dictionary
 * trading:dict:&lt;id&gt;                           STRING  a zstd dictionary (never expires: documents written with it name it)
 * trading:updated                            STRING  when a loader last wrote (epoch milliseconds)
 * trading:changes                            channel what a loader wrote: "kind TAB id TAB yyyyMMdd" (id * for a day's columns)
 * {trading:trade:20260930}:cols:loading:&lt;load&gt;  SET  ids a running load wrote documents for on the day ({@link RedisLoadJournal})
 * trading:loading                            SET     the days loads started and have not finished: "load TAB kind TAB yyyyMMdd"
 * trading:loader:&lt;load&gt;                       STRING  present while that load runs (a short expiry it renews)
 * </pre>
 *
 * <p>The braces are Redis Cluster hash tags: an entity's days and its documents share a slot (so one script reads both
 * in one round trip), and a day's column hash and its staging copy share a slot (so the staging copy is renamed into
 * place atomically). Documents of a day spread over every node; a day's columns sit on one.
 */
public final class RedisLayout {

    /** The column hash's description: rows, chunk size, version and the columns with their types. */
    public static final String META = "meta";
    /** Chunk fields of the ids: {@code #id:0}, {@code #id:1} … (a document path never starts with {@code #}). */
    public static final String IDS = "#id";

    private RedisLayout() {
    }

    public static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    public static String kinds(String domain) {
        return domain + ":kinds";
    }

    public static String days(String domain, String kind) {
        return domain + ":" + kind + ":days";
    }

    /** The entity's days (a sorted set); its documents are this key followed by {@code :yyyyMMdd}. */
    public static String entity(String domain, String kind, String id) {
        return domain + ":" + kind + ":{" + id + "}";
    }

    public static String doc(String domain, String kind, String id, LocalDate date) {
        return entity(domain, kind, id) + ":" + day(date);
    }

    public static String columns(String domain, String kind, LocalDate date) {
        return "{" + domain + ":" + kind + ":" + day(date) + "}:cols";
    }

    /** The staging copy a loader fills before renaming it over {@link #columns} (same hash tag, same slot). */
    public static String columnsStaging(String domain, String kind, LocalDate date) {
        return columns(domain, kind, date) + ":staging";
    }

    /**
     * The ids a running load has written documents for on a day, before the day's columns list them (a SET; same hash
     * tag as the day's columns): what the next load deletes when the load that wrote them died.
     */
    public static String journal(String domain, String kind, LocalDate date, String load) {
        return columns(domain, kind, date) + ":loading:" + load;
    }

    /** The days loads have started and not finished: members {@code load TAB kind TAB yyyyMMdd} (a SET). */
    public static String loads(String domain) {
        return domain + ":loading";
    }

    /** Present while a load runs (it renews the key's short expiry): {@code <domain>:loader:<load>}. */
    public static String loader(String domain, String load) {
        return domain + ":loader:" + load;
    }

    public static String dictionaryOf(String domain, String kind) {
        return domain + ":" + kind + ":dict";
    }

    public static String dictionary(String domain, int id) {
        return domain + ":dict:" + Integer.toUnsignedString(id, 16);
    }

    public static String updated(String domain) {
        return domain + ":updated";
    }

    public static String changes(String domain) {
        return domain + ":changes";
    }

    /** A chunk's field in the column hash: {@code <path>:<chunk>}, the ids under {@link #IDS}. */
    public static String field(String path, int chunk) {
        return path + ":" + chunk;
    }

    public static long day(LocalDate date) {
        return date.getYear() * 10_000L + date.getMonthValue() * 100L + date.getDayOfMonth();
    }

    public static LocalDate date(long day) {
        return LocalDate.of((int) (day / 10_000), (int) (day / 100 % 100), (int) (day % 100));
    }

    /** A change message on {@link #changes}: kind, id ({@code *} when a day's columns were written) and business day. */
    public static String change(String kind, String id, LocalDate date) {
        return kind + "\t" + id + "\t" + day(date);
    }
}
