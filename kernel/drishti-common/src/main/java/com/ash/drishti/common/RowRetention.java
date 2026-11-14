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
package com.ash.drishti.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * A table's row retention, the one rule for every store (docs/guides/DATA_LAKE_GUIDE.md, "Row retention"): {@code retention: {ttl: 8h, on:
 * UPDATED_AT, max-rows: 100000}}. Rows older than {@code ttl} by the field {@code on} (without {@code on}: the time the row arrived, the
 * promoted column {@code arrived_at}) are dropped, and of the rest at most {@code max-rows}, the newest by the same age, are kept (rows tied at
 * the cut are all kept). A row with no readable age is never dropped. The memory lake applies it in its compaction's rewrite, a Delta table by
 * rewriting the business dates that hold dropped rows, an Iceberg table by replacing those dates' files, and a files store by rewriting
 * those day files; every one asks this class which rows stay, so the stores leave the same rows. Absent: no limit, nothing changes.
 *
 * @param ttl the age limit ({@code 500ms}, {@code 30s}, {@code 15m}, {@code 8h}, {@code 1d}), or null
 * @param on the document field (or promoted column) the age is read from (a time, a date, epoch seconds or milliseconds), or null for the
 *     arrival time
 * @param maxRows the newest rows kept, or null
 */
public record RowRetention(String ttl, String on, Integer maxRows) {

    /** The promoted column holding when a row arrived (written only for a table whose retention ages by arrival). */
    public static final String ARRIVED = "arrived_at";
    private static final ObjectMapper JSON = new ObjectMapper();

    public RowRetention {
        if (ttl != null && !ttl.isBlank() && !ttl.trim().toLowerCase(Locale.ROOT).matches("\\d+(ms|s|m|h|d)")) {
            throw new IllegalArgumentException("retention.ttl is a time such as 30s, 15m, 8h or 1d, not '" + ttl + "'");
        }
        if (maxRows != null && maxRows < 1) {
            throw new IllegalArgumentException("retention.max-rows is at least 1");
        }
    }

    /** Whether any limit is set. */
    public boolean active() {
        return (ttl != null && !ttl.isBlank()) || maxRows != null;
    }

    /** Whether the age is the arrival time (the table then carries {@link #ARRIVED}). */
    public boolean byArrival() {
        return on == null || on.isBlank();
    }

    /** The age limit, or null. */
    public Duration limit() {
        if (ttl == null || ttl.isBlank()) {
            return null;
        }
        String t = ttl.trim().toLowerCase(Locale.ROOT);
        long n = Long.parseLong(t.replaceAll("[^0-9]", ""));
        return switch (t.replaceAll("[0-9]", "")) {
            case "ms" -> Duration.ofMillis(n);
            case "s" -> Duration.ofSeconds(n);
            case "m" -> Duration.ofMinutes(n);
            case "h" -> Duration.ofHours(n);
            default -> Duration.ofDays(n);
        };
    }

    /** The rows kept at {@code now}: within the age limit, then the newest {@code max-rows} ({@code age} gives each row's age, null: unknown). */
    public <T> List<T> keep(List<T> rows, Function<T, Instant> age, Instant now) {
        Judge j = judge(rows.stream().map(age).toList(), now);
        List<T> out = new ArrayList<>(rows.size());
        for (T r : rows) {
            if (j.keeps(age.apply(r))) {
                out.add(r);
            }
        }
        return out;
    }

    /** How many of {@code rows} {@link #keep} would drop. */
    public <T> int expired(List<T> rows, Function<T, Instant> age, Instant now) {
        return rows.size() - keep(rows, age, now).size();
    }

    /** The verdict over a whole table's row ages at {@code now}: ask {@link Judge#keeps} for each row (a store that streams its rows asks twice: ages, then rows). */
    public Judge judge(Collection<Instant> ages, Instant now) {
        Duration d = limit();
        Instant ttlCut = d == null ? null : now.minus(d);
        Instant maxCut = null;
        if (maxRows != null) {
            List<Instant> alive = new ArrayList<>();
            for (Instant at : ages) {
                if (ttlCut == null || at == null || !at.isBefore(ttlCut)) {
                    alive.add(at == null ? Instant.MAX : at);
                }
            }
            if (alive.size() > maxRows) {
                alive.sort(null);
                maxCut = alive.get(alive.size() - maxRows);
            }
        }
        return new Judge(ttlCut, maxCut);
    }

    /** Which ages stay: not before the ttl cut, not before the max-rows cut; no age: stays. */
    public record Judge(Instant ttlCut, Instant maxCut) {
        public boolean keeps(Instant at) {
            return at == null || ((ttlCut == null || !at.isBefore(ttlCut)) && (maxCut == null || !at.isBefore(maxCut)));
        }
    }

    /**
     * A staged line's age ({@code {"id", "doc", "columns"}}): the field {@code on} (a promoted column of that name, else the document's field),
     * or without {@code on} the {@code arrived_at} column, else {@code fallback} (a durable table's business date when it records no arrival);
     * null when none.
     */
    public Instant ageOfLine(String line, Instant fallback) {
        try {
            JsonNode n = JSON.readTree(line);
            if (byArrival()) {
                Instant at = instant(n.path("columns").path(ARRIVED));
                return at != null ? at : fallback;
            }
            Instant at = instant(n.path("columns").path(on));
            if (at != null) {
                return at;
            }
            JsonNode doc = n.path("doc");
            return instant(path(doc.isTextual() ? JSON.readTree(doc.asText("{}")) : doc, on));
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /** A row's age from its document text and its promoted values (a lookup by column name, null when absent), as {@link #ageOfLine}. */
    public Instant ageOfRow(String doc, Function<String, Object> column, Instant fallback) {
        try {
            if (byArrival()) {
                Instant at = instant(column.apply(ARRIVED));
                return at != null ? at : fallback;
            }
            Instant at = instant(column.apply(on));
            if (at != null) {
                return at;
            }
            return doc == null ? null : instant(path(JSON.readTree(doc), on));
        } catch (java.io.IOException e) {
            return null;
        }
    }

    private static JsonNode path(JsonNode doc, String path) {
        JsonNode n = doc;
        for (String p : path.split("\\.")) {
            n = n.path(p);
        }
        return n;
    }

    /** The midnight (UTC) that starts a business date, the age a durable row has when it records no arrival. */
    public static Instant dayStart(String businessDate) {
        try {
            return LocalDate.parse(businessDate).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Instant instant(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof JsonNode j) {
            if (j.isMissingNode() || j.isNull()) {
                return null;
            }
            return j.isNumber() ? instant((Object) j.asLong()) : instant((Object) j.asText());
        }
        if (v instanceof Number num) {
            long x = num.longValue();
            return x > 100_000_000_000L ? Instant.ofEpochMilli(x) : Instant.ofEpochSecond(x);
        }
        String s = v.toString().trim();
        for (java.util.function.Function<String, Instant> f : List.<java.util.function.Function<String, Instant>>of(Instant::parse,
                t -> OffsetDateTime.parse(t).toInstant(), t -> LocalDateTime.parse(t).toInstant(ZoneOffset.UTC),
                t -> LocalDate.parse(t.length() >= 10 ? t.substring(0, 10) : t).atStartOfDay().toInstant(ZoneOffset.UTC))) {
            try {
                return f.apply(s);
            } catch (RuntimeException e) {
                // the next shape
            }
        }
        return null;
    }
}
