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
package com.ash.drishti.server;

import com.ash.drishti.ai.LlmRegistry;
import com.ash.drishti.ai.McpRegistry;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.common.RetryBoard;
import com.ash.drishti.common.RetryTracker;
import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.identity.AuditLog;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * One place that knows every area that retries (packs, connectors, AI providers, MCP servers): what is failing and when it is
 * tried next, and "Retry now". Admin &rarr; Packs, Connectors, AI and Health read it; the audit log gets every retry-now and,
 * through each area's listener, every (rate-limited) failure and every recovery.
 */
public final class RetryService {

    public static final Set<String> AREAS = Set.of("packs", "connectors", "ai", "mcp");

    private final PackReloader packs;
    private final SourceRegistry sources;
    private final LlmRegistry llm;
    private final McpRegistry mcp;
    private final AuditLog audit;

    public RetryService(PackReloader packs, SourceRegistry sources, LlmRegistry llm, McpRegistry mcp, AuditLog audit) {
        this.packs = packs;
        this.sources = sources;
        this.llm = llm;
        this.mcp = mcp;
        this.audit = audit;
        sources.retryListener(listener("connector"));
        llm.retry().listener(listener("ai"));
        mcp.retry().listener(listener("mcp"));
    }

    private RetryBoard.Listener listener(String area) {
        return new RetryBoard.Listener() {
            @Override
            public void failed(String a, String key, RetryTracker.Status s, boolean logged) {
                if (logged) {
                    audit.record("system", area + "-retry-failed", key, "attempt " + s.attempt() + (s.permanent() ? " (looks permanent)" : "") + ": " + s.lastError());
                }
            }

            @Override
            public void recovered(String a, String key, int failures, String lastError) {
                audit.record("system", area + "-recovered", key, "working after " + failures + " failed attempt(s); it was: " + lastError);
            }
        };
    }

    private static Map<String, Map<String, Object>> describe(Map<String, RetryTracker.Status> statuses) {
        Map<String, Map<String, Object>> out = new java.util.TreeMap<>();
        Instant now = Instant.now();
        statuses.forEach((k, v) -> out.put(k, RetryBoard.describe(v, now)));
        return out;
    }

    public Map<String, Map<String, Object>> packs() {
        return packs.retryStatus();
    }

    public Map<String, Map<String, Object>> connectors() {
        Map<String, RetryTracker.Status> m = new java.util.TreeMap<>();
        sources.connectorStatuses().keySet().forEach(n -> sources.retryStatus(n).ifPresent(s -> m.put(n, s)));
        return describe(m);
    }

    public Map<String, Map<String, Object>> ai() {
        return describe(llm.retry().statuses());
    }

    public Map<String, Map<String, Object>> mcp() {
        return describe(mcp.retry().statuses());
    }

    /** Everything that is failing and will be retried, by area; an area with nothing failing is an empty map. */
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("packs", packs());
        out.put("connectors", connectors());
        out.put("ai", ai());
        out.put("mcp", mcp());
        out.put("policy", Map.of("enabled", sources.retryPolicy().enabled(), "initial", sources.retryPolicy().initial().toString(),
                "factor", sources.retryPolicy().factor(), "max", sources.retryPolicy().max().toString()));
        return out;
    }

    /** "Retry now" for one area ({@code all} for every area), optionally one named item. Audited. Returns how many items were tried. */
    public Map<String, Object> retryNow(String area, String name, String actor) {
        String a = area == null ? "all" : area.toLowerCase(java.util.Locale.ROOT);
        if (!a.equals("all") && !AREAS.contains(a)) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "unknown area '" + area + "': packs, connectors, ai, mcp or all");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (a.equals("all") || a.equals("packs")) {
            PackReloader.Result r = packs.retryNow(actor);
            out.put("packs", Map.of("loaded", r.loaded(), "problems", r.problems()));
        }
        if (a.equals("all") || a.equals("connectors")) {
            int n = sources.retryNow(name);
            audit.record(actor, "connector-retry-now", name == null ? "all" : name, n + " tried");
            out.put("connectors", n);
        }
        if (a.equals("all") || a.equals("ai")) {
            int n = llm.retryNow();
            audit.record(actor, "ai-retry-now", "all", n + " tried");
            out.put("ai", n);
        }
        if (a.equals("all") || a.equals("mcp")) {
            int n = mcp.retryNow();
            audit.record(actor, "mcp-retry-now", "all", n + " tried");
            out.put("mcp", n);
        }
        out.put("retry", snapshot());
        return out;
    }
}
