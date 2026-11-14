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
package com.ash.drishti.memlake;

import com.ash.drishti.deltalake.write.TableUpkeep;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * A memory table's row retention (docs/guides/DATA_LAKE_GUIDE.md, the in-memory lake's table options): {@code retention: {ttl: 8h, on:
 * UPDATED_AT, max-rows: 100000}}. Rows older than {@code ttl} by the field {@code on} (the time the row arrived when {@code on} is absent:
 * the promoted column {@code arrived_at}) are dropped, and at most {@code max-rows}, the newest, are kept. Applied by the compaction's
 * rewrite, the one upkeep: no second mechanism. Absent: no limit, nothing changes.
 *
 * @param ttl the age limit ({@code 30s}, {@code 15m}, {@code 8h}, {@code 1d}), or null
 * @param on the document field the age is read from (a time, a date, epoch seconds or milliseconds), or null for the arrival time
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

    /** Whether the age is the arrival time (the table then carries {@link #ARRIVED}). */
    public boolean byArrival() {
        return on == null || on.isBlank();
    }

    /** The age limit, or null. */
    public Duration limit() {
        return ttl == null || ttl.isBlank() ? null : MemoryLake.duration(ttl.trim());
    }

    /** The rows kept at {@code now}: within the age limit, then the newest {@code max-rows}. */
    public List<TableUpkeep.Staged> keep(List<TableUpkeep.Staged> rows, Instant now) {
        Duration d = limit();
        Instant cut = d == null ? null : now.minus(d);
        List<Object[]> aged = new ArrayList<>();
        for (TableUpkeep.Staged s : rows) {
            Instant at = age(s.line());
            if (cut == null || at == null || !at.isBefore(cut)) {
                aged.add(new Object[] {at == null ? Instant.MAX : at, s});
            }
        }
        if (maxRows != null && aged.size() > maxRows) {
            aged.sort(Comparator.comparing(a -> (Instant) a[0]));
            aged = new ArrayList<>(aged.subList(aged.size() - maxRows, aged.size()));
        }
        List<TableUpkeep.Staged> out = new ArrayList<>(aged.size());
        aged.forEach(a -> out.add((TableUpkeep.Staged) a[1]));
        return out;
    }

    /** How many of {@code rows} {@link #keep} would drop. */
    public int expired(List<TableUpkeep.Staged> rows, Instant now) {
        return rows.size() - keep(rows, now).size();
    }

    /** A staged line's age: its field {@code on} in the document, else its {@code arrived_at} column; null when it has none. */
    Instant age(String line) {
        try {
            JsonNode n = JSON.readTree(line);
            if (byArrival()) {
                return instant(n.path("columns").path(ARRIVED));
            }
            return instant(JSON.readTree(n.path("doc").asText("{}")).path(on));
        } catch (java.io.IOException e) {
            return null;
        }
    }

    static Instant instant(JsonNode v) {
        if (v == null || v.isMissingNode() || v.isNull()) {
            return null;
        }
        if (v.isNumber()) {
            long x = v.asLong();
            return x > 100_000_000_000L ? Instant.ofEpochMilli(x) : Instant.ofEpochSecond(x);
        }
        String s = v.asText().trim();
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
