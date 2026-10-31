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

import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.engine.ViewPipeline;
import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.server.disk.DiskBudget;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Every cache on this server in one place (RESOURCES_AND_SCALE.md section 8.1, O1 and O2, 2.9.7): the engine's, history's, the connectors'
 * (as they report themselves), and every {@link CacheReport.Owner} (VaR, exposure, BI, pack access, the id maps); the memory and disk
 * totals above them; a purge of one cache, of every cache, or of one business date; the last purge of each, and by whom.
 */
public final class CacheBoard {

    public static final String ENGINE = "engine";
    public static final String HISTORY = "history";

    private final SourceRegistry sources;
    private final ViewPipeline pipeline;
    private final com.ash.drishti.engine.explain.ExplainService explain;
    private final ObjectProvider<com.ash.drishti.engine.history.HistoryService> history;
    private final ObjectProvider<CacheReport.Owner> owners;
    private final ObjectProvider<DiskBudget> disk;
    private final java.util.function.Supplier<Map<String, Object>> memory;
    private final Map<String, Map<String, Object>> lastPurge = new ConcurrentHashMap<>();

    public CacheBoard(SourceRegistry sources, ViewPipeline pipeline, com.ash.drishti.engine.explain.ExplainService explain,
            ObjectProvider<com.ash.drishti.engine.history.HistoryService> history, ObjectProvider<CacheReport.Owner> owners,
            ObjectProvider<DiskBudget> disk, java.util.function.Supplier<Map<String, Object>> memory) {
        this.sources = sources;
        this.pipeline = pipeline;
        this.explain = explain;
        this.history = history;
        this.owners = owners;
        this.disk = disk;
        this.memory = memory;
    }

    /** Every cache's report: the engine, history, the shared Delta budget, each connector, then each owner's. */
    public List<CacheReport> reports() {
        List<CacheReport> out = new ArrayList<>();
        out.add(new CacheReport(ENGINE, "engine", "Layouts and shape fingerprints", "heap", null, null, null, null, null, null, null, null, null,
                "data-version, signal", false, pipeline.cacheStats()));
        com.ash.drishti.engine.history.HistoryService h = history.getIfAvailable();
        if (h != null) {
            out.add(new CacheReport(HISTORY, "engine", "History queries' answers, by data version", "heap", null, null, null, null, null, null,
                    null, null, null, "data-version", false, h.cache().stats()));
        }
        out.add(new CacheReport("memory-budget", "connectors", "Shared by the Delta connectors", "heap", null, null, null, null, null, null,
                null, null, null, "data-version", false, com.ash.drishti.plugin.delta.DeltaSourcePlugin.sharedBudget()));
        for (SourcePlugin s : sources.plugins()) {
            Map<String, Object> stats = s.cacheStats();
            if (!stats.isEmpty()) {
                out.add(new CacheReport(s.manifest().name(), "connector", "Connector", "heap", null, null, null, null, null, null, null, null,
                        null, "data-version, ttl", s.manifest().capabilities().dated(), stats));
            }
        }
        owners.orderedStream().forEach(o -> {
            try {
                out.addAll(o.reports());
            } catch (RuntimeException e) {
                // an owner that cannot report now is left out; its purge still works
            }
        });
        return out;
    }

    /** {@code GET /api/v1/admin/caches}: memory and disk totals, then one row per cache. */
    public Map<String, Object> list() {
        List<CacheReport> rs = reports();
        Map<String, Object> mem = new LinkedHashMap<>(memory.get());
        long used = rs.stream().filter(r -> "heap".equals(r.where()) && r.sizeBytes() != null).mapToLong(CacheReport::sizeBytes).sum();
        mem.put("cachesMeasuredBytes", used);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("memory", mem);
        DiskBudget d = disk.getIfAvailable();
        out.put("disk", d == null ? Map.of() : d.describe());
        out.put("caches", rs.stream().map(r -> r.row(lastPurge.get(r.name()))).toList());
        return out;
    }

    /** The dates one cache holds, newest first (empty for a cache without dates); null when there is no such cache. */
    public List<Map<String, Object>> dates(String name) {
        for (CacheReport.Owner o : owners.orderedStream().toList()) {
            if (o.reports().stream().anyMatch(r -> r.name().equals(name))) {
                return o.dates(name);
            }
        }
        return reports().stream().anyMatch(r -> r.name().equals(name)) ? List.of() : null;
    }

    /**
     * Purges {@code name} ({@code all}: every cache) on this server, or one business date of it when {@code date} is set; returns what was
     * purged. A connector that cannot purge one date purges everything, and the answer says so.
     */
    public Map<String, Object> purge(String name, LocalDate date, String by) {
        List<String> purged = new ArrayList<>();
        List<String> whole = new ArrayList<>();
        boolean all = name.equals("all");
        if (name.equals(ENGINE) || all) {
            pipeline.purgeCaches();
            explain.purge();
            purged.add(ENGINE);
        }
        com.ash.drishti.engine.history.HistoryService h = history.getIfAvailable();
        if (h != null && (name.equals(HISTORY) || all)) {
            h.cache().clear();
            purged.add(HISTORY);
        }
        for (SourcePlugin s : sources.plugins()) {
            if ((all && !s.cacheStats().isEmpty()) || s.manifest().name().equals(name)) {
                if (date == null) {
                    s.purgeCaches();
                } else if (!s.purgeDate(date)) {
                    whole.add(s.manifest().name());
                }
                purged.add(s.manifest().name());
            }
        }
        for (CacheReport.Owner o : owners.orderedStream().toList()) {
            purged.addAll(o.purge(name, date));
        }
        Map<String, Object> at = new LinkedHashMap<>();
        at.put("at", Instant.now().toString());
        at.put("by", by);
        at.put("date", date == null ? null : date.toString());
        purged.forEach(n -> lastPurge.put(n, at));
        if (all) {
            lastPurge.put("all", at);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("purged", purged);
        if (date != null) {
            out.put("date", date.toString());
        }
        if (!whole.isEmpty()) {
            out.put("purgedWhole", whole);                              // connectors that cannot purge one date: everything went
        }
        return out;
    }
}
