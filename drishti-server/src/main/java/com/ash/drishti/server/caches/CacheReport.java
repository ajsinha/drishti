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
package com.ash.drishti.server.caches;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One cache as Admin, Caches shows it (RESOURCES_AND_SCALE.md section 8.1, O1, 2.9.7): where it lives, its size against its limit, its age,
 * its hit rate, whether it holds business dates, how it learns of new data, and the raw statistics its owner reports. A value it cannot say
 * is null (an unknown size, no TTL).
 *
 * @param name the cache's name, unique on the server ({@code var.hot-files})
 * @param owner what owns it ({@code var}, {@code exposure}, {@code bi}, {@code engine}, a connector's name)
 * @param type a short description of what it holds
 * @param where {@code heap}, {@code mapped} (off-heap, memory-mapped), {@code native} or {@code disk}
 * @param path the folder on disk, or null
 * @param freshness how it learns of new data: {@code data-version} (keyed by it: never stale, only cold), {@code ttl}, {@code signal}
 * @param dates true when it holds business dates (a date can be purged; {@code /dates} lists them)
 */
public record CacheReport(String name, String owner, String type, String where, String path, Long sizeBytes, Long limitBytes, Long entries,
        Long limitEntries, Long ttlSeconds, Long oldestAgeSeconds, Long hits, Long misses, String freshness, boolean dates,
        Map<String, Object> stats) {

    public CacheReport {
        stats = stats == null ? Map.of() : stats;
    }

    /** A Caffeine cache's report: entries, hits, misses (when it records statistics) and its weighed size when it has a weigher. */
    public static <K, V> CacheReport of(String name, String owner, String type, String where, com.github.benmanes.caffeine.cache.Cache<K, V> c,
            Long limitBytes, Long limitEntries, Long ttlSeconds, String freshness, boolean dates) {
        var st = c.stats();
        var policy = c.policy();
        Long size = null;
        var ev = policy.eviction();
        if (ev.isPresent() && ev.get().isWeighted() && ev.get().weightedSize().isPresent()) {
            size = ev.get().weightedSize().getAsLong();
        }
        Long oldest = null;
        var exp = policy.expireAfterAccess().or(policy::expireAfterWrite);
        if (exp.isPresent()) {
            var e = exp.get();
            oldest = e.oldest(1).keySet().stream().findFirst().flatMap(e::ageOf).map(java.time.Duration::toSeconds).orElse(null);
        }
        return new CacheReport(name, owner, type, where, null, size, limitBytes, c.estimatedSize(), limitEntries, ttlSeconds, oldest,
                policy.isRecordingStats() ? st.hitCount() : null, policy.isRecordingStats() ? st.missCount() : null, freshness, dates, Map.of());
    }

    /** The same report with its size (when the owner measures it itself) and its folder. */
    public CacheReport sized(Long bytes, String folder) {
        return new CacheReport(name, owner, type, where, folder, bytes, limitBytes, entries, limitEntries, ttlSeconds, oldestAgeSeconds, hits,
                misses, freshness, dates, stats);
    }

    /** As a row of {@code GET /api/v1/admin/caches}: the report, the last purge, and {@code type} and {@code stats} as before 2.9.7. */
    public Map<String, Object> row(Map<String, Object> lastPurge) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("owner", owner);
        m.put("type", type);
        m.put("where", where);
        m.put("path", path);
        m.put("sizeBytes", sizeBytes);
        m.put("limitBytes", limitBytes);
        m.put("entries", entries);
        m.put("limitEntries", limitEntries);
        m.put("ttlSeconds", ttlSeconds);
        m.put("oldestAgeSeconds", oldestAgeSeconds);
        m.put("hits", hits);
        m.put("misses", misses);
        m.put("freshness", freshness);
        m.put("dates", dates);
        m.put("lastPurge", lastPurge);
        m.put("stats", stats);
        return m;
    }

    /** Something that owns caches and reports them (VaR, exposure, BI, pack access). */
    public interface Owner {
        List<CacheReport> reports();

        /**
         * Purges the cache {@code name} (a date of it when {@code date} is set; every cache of this owner for {@code all}); returns what was
         * purged (empty when the name is not this owner's).
         */
        List<String> purge(String name, LocalDate date);

        /** The dates a cache holds, newest first: date, state (open or disk), size, versions, digest, built and last used. */
        default List<Map<String, Object>> dates(String name) {
            return List.of();
        }
    }
}
