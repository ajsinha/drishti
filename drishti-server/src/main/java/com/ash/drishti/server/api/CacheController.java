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
package com.ash.drishti.server.api;

import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.ViewPipeline;
import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.identity.UserService;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Caches, for admins: what each connector and the engine hold, and a purge for any of them (or all) at any time.
 * Every purge is written to the audit log.
 */
@RestController
@RequestMapping("/api/v1/admin/caches")
public class CacheController {

    public static final String ENGINE = "engine";

    /** One cache: its owner (a connector, or the engine) and what it holds. */
    public record CacheInfo(String name, String type, Map<String, Object> stats) {}

    private final SourceRegistry sources;
    private final ViewPipeline pipeline;
    private final com.ash.drishti.engine.explain.ExplainService explain;
    private final Entitlements entitlements;
    private final UserService users;

    public CacheController(SourceRegistry sources, ViewPipeline pipeline, com.ash.drishti.engine.explain.ExplainService explain, Entitlements entitlements, UserService users) {
        this.sources = sources;
        this.pipeline = pipeline;
        this.explain = explain;
        this.entitlements = entitlements;
        this.users = users;
    }

    @GetMapping
    public List<CacheInfo> list(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        List<CacheInfo> out = new ArrayList<>();
        out.add(new CacheInfo(ENGINE, "Layouts and shape fingerprints", pipeline.cacheStats()));
        for (SourcePlugin s : sources.plugins()) {
            Map<String, Object> stats = s.cacheStats();
            if (!stats.isEmpty()) {
                out.add(new CacheInfo(s.manifest().name(), "Connector", stats));
            }
        }
        return out;
    }

    /** Purges one cache by name, or every cache with {@code all}. */
    @PostMapping("/{name}/purge")
    public Map<String, Object> purge(@PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        List<String> purged = new ArrayList<>();
        long t0 = System.nanoTime();
        if (name.equals(ENGINE) || name.equals("all")) {
            pipeline.purgeCaches();
            explain.purge();
            purged.add(ENGINE);
        }
        for (SourcePlugin s : sources.plugins()) {
            if ((name.equals("all") && !s.cacheStats().isEmpty()) || s.manifest().name().equals(name)) {
                s.purgeCaches();
                purged.add(s.manifest().name());
            }
        }
        if (purged.isEmpty()) {
            throw new DrishtiException(ErrorCode.CACHE_NOT_FOUND, "no cache named '" + name + "'");
        }
        users.recordAudit(p.user(), "cache-purged", name, String.join(", ", purged));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("purged", purged);
        out.put("elapsedMs", Math.round((System.nanoTime() - t0) / 1e4) / 100.0);
        return out;
    }
}
