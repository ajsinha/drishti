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

import com.ash.drishti.server.bi.engine.QueryService;
import com.ash.drishti.server.security.PackAccess;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The server's other caches as Admin, Caches shows them (2.9.7, O1): BI's query results and its layers on disk, who sees which packs, and
 * the connectors' id maps mapped from disk.
 */
public final class ServerCaches implements CacheReport.Owner {

    static final String BI_RESULTS = "bi.results";
    static final String BI_LAYERS = "bi.layers";
    static final String PACK_ACCESS = "security.pack-access";
    static final String ID_MAPS = "connectors.id-maps";

    private final ObjectProvider<QueryService> bi;
    private final ObjectProvider<PackAccess> packs;

    public ServerCaches(ObjectProvider<QueryService> bi, ObjectProvider<PackAccess> packs) {
        this.bi = bi;
        this.packs = packs;
    }

    @Override
    public List<CacheReport> reports() {
        List<CacheReport> out = new ArrayList<>();
        QueryService q = bi.getIfAvailable();
        if (q != null) {
            if (q.resultCache() != null) {
                out.add(CacheReport.of(BI_RESULTS, "bi", "BI query results", "heap", q.resultCache(), q.props().cacheMaxBytes(),
                        (long) q.props().cacheEntries(), q.props().cacheTtl().toSeconds(), "data-version, ttl, signal", false));
            }
            out.add(new CacheReport(BI_LAYERS, "bi", "BI layers (columns, rollups) on disk", "disk", q.layers().root().toString(),
                    q.layers().bytesOnDisk(), (long) (q.props().storeMaxGb() * (1L << 30)), null, null, null, null, null, null,
                    "data-version per partition", true, Map.of("maxDates", q.props().storeMaxDates())));
        }
        PackAccess p = packs.getIfAvailable();
        if (p != null) {
            out.add(CacheReport.of(PACK_ACCESS, "security", "Which packs each user sees", "heap", p.effectiveCache(), null, 100_000L, 5L, "ttl",
                    false));
        }
        long[] mapped = com.ash.drishti.api.MappedIdFile.mapped();
        out.add(new CacheReport(ID_MAPS, "connectors", "Id maps memory-mapped from disk (Delta, Iceberg)", "mapped", null, mapped[1], null,
                mapped[0], null, null, null, null, null, "data-version", true, idMapStats()));
        return out;
    }

    @Override
    public List<String> purge(String name, LocalDate date) {
        List<String> done = new ArrayList<>();
        boolean all = "all".equals(name);
        QueryService q = bi.getIfAvailable();
        if (q != null && (all || BI_RESULTS.equals(name)) && q.resultCache() != null) {
            q.resultCache().invalidateAll();                          // keyed by dataset and query, not date: all of it
            done.add(BI_RESULTS);
        }
        if (q != null && (all || BI_LAYERS.equals(name))) {
            if (date != null) {
                q.layers().purgeDate(date);
            } else {
                q.layers().trim(1, 0);                                  // everything but each table's newest date
            }
            done.add(BI_LAYERS);
        }
        PackAccess p = packs.getIfAvailable();
        if (p != null && (all || PACK_ACCESS.equals(name))) {
            p.effectiveCache().invalidateAll();
            done.add(PACK_ACCESS);
        }
        return done;
    }

    @Override
    public List<Map<String, Object>> dates(String name) {
        if (ID_MAPS.equals(name)) {
            return idMapDates();
        }
        QueryService q = bi.getIfAvailable();
        return q != null && BI_LAYERS.equals(name) ? q.layers().dates() : List.of();
    }

    /**
     * Every connector's id maps on disk (mapped or not, a warning when one alone is over {@code id-map-disk.max-gb}), then the last drops
     * with their reasons: {@code expired (ttl)}, {@code evicted (max-gb)}, {@code replaced (new version)} (2.9.7).
     */
    static List<Map<String, Object>> idMapDates() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (com.ash.drishti.api.IdMapFolder f : com.ash.drishti.api.IdMapFolder.all()) {
            out.addAll(f.dates());
        }
        return out;
    }

    /** The id-map folders' figures: per reason the maps dropped, and the warnings (a map alone over its connector's cap). */
    static Map<String, Object> idMapStats() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        long expired = 0;
        long evicted = 0;
        long replaced = 0;
        List<String> warnings = new ArrayList<>();
        for (com.ash.drishti.api.IdMapFolder f : com.ash.drishti.api.IdMapFolder.all()) {
            Map<String, Object> s = f.stats();
            expired += ((Number) s.get("idMapsExpired")).longValue();
            evicted += ((Number) s.get("idMapsEvicted")).longValue();
            replaced += ((Number) s.get("idMapsReplaced")).longValue();
            f.warnings().values().forEach(w -> warnings.add(f.connector() + ": " + w));
        }
        m.put("expired (ttl)", expired);
        m.put("evicted (max-gb)", evicted);
        m.put("replaced (new version)", replaced);
        if (!warnings.isEmpty()) {
            m.put("warning", String.join("; ", warnings));
        }
        return m;
    }
}
