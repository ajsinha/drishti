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
package com.ash.drishti.server.memlake;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin, Lakes for the in-memory lake (IN_MEMORY_LAKE.md section 12) and {@code drishti.py lake memory}: {@code GET
 * /api/v1/admin/lakes/memory} every store and table (cap, use, bytes, rows, version, refills, evictions, refusals, compaction, the
 * compression ratio, health); {@code GET …/memory/jobs} the runs, newest first; {@code POST …/memory/refill/{table}} a run now (audited).
 */
@RestController
public class MemoryLakeController {

    private final MemoryLake lake;
    private final Retention retention;
    private final Entitlements entitlements;
    private final AuditLog audit;

    MemoryLakeController(MemoryLake lake, Retention retention, Entitlements entitlements, AuditLog audit) {
        this.lake = lake;
        this.retention = retention;
        this.entitlements = entitlements;
        this.audit = audit;
    }

    @GetMapping("/api/v1/admin/lakes/memory")
    public Map<String, Object> status(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        Map<String, Object> out = new LinkedHashMap<>(lake.describe());
        Map<String, Object> guard = new LinkedHashMap<>();
        guard.put("refusals", retention.refusals());
        guard.put("downloads", retention.downloads());
        out.put("guard", guard);
        return out;
    }

    @GetMapping("/api/v1/admin/lakes/memory/jobs")
    public Map<String, Object> jobs(@RequestParam(name = "table", required = false) String table,
            @RequestParam(name = "limit", required = false, defaultValue = "100") int limit, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        List<Map<String, Object>> runs = lake.jobs(table, Math.max(1, Math.min(limit, 1000)));
        return Map.of("runs", runs);
    }

    @PostMapping("/api/v1/admin/lakes/memory/refill/{table}")
    public Map<String, Object> refill(@PathVariable("table") String table, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        if (lake.table(table) == null) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no memory table '" + table + "' (drishti.lake.memory.tables)");
        }
        if (!lake.refill(table, "by " + p.user())) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "memory table '" + table + "' is held by its owner node: refill it there");
        }
        if (audit != null) {
            audit.record(p.user(), "lake.memory.refill", "memory-table/" + table, "");
        }
        return Map.of("table", table, "queued", true);
    }
}
