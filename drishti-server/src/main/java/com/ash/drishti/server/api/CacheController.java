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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.UserService;
import com.ash.drishti.server.caches.CacheBoard;
import com.ash.drishti.server.net.NetCaller;
import com.ash.drishti.server.net.PeerCalls;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Caches, for admins (RESOURCES_AND_SCALE.md section 8.1, 2.9.7): every cache on this server with where it lives, its size against its
 * limit, its age, hit rate, the dates it holds and its last purge, with the memory and disk totals; a purge of one cache, of all, or of
 * one business date (O2), on this server by default or on every member ({@code scope=cluster}, PO-6). Every purge is audited, on each
 * member that runs it.
 */
@RestController
@RequestMapping("/api/v1/admin/caches")
public class CacheController {

    public static final String ENGINE = CacheBoard.ENGINE;
    public static final String HISTORY = CacheBoard.HISTORY;

    private final CacheBoard board;
    private final Entitlements entitlements;
    private final UserService users;
    private final PeerCalls peers;

    public CacheController(CacheBoard board, Entitlements entitlements, UserService users, PeerCalls peers) {
        this.board = board;
        this.entitlements = entitlements;
        this.users = users;
        this.peers = peers;
    }

    /** Memory and disk totals, then one row per cache ({@code caches}). */
    @GetMapping
    public Map<String, Object> list(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return board.list();
    }

    /** The business dates a cache holds: date, open or on disk, size, data versions, digest, when built and last used. */
    @GetMapping("/{name}/dates")
    public List<Map<String, Object>> dates(@PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        List<Map<String, Object>> d = board.dates(name);
        if (d == null) {
            throw new DrishtiException(ErrorCode.CACHE_NOT_FOUND, "no cache named '" + name + "'");
        }
        return d;
    }

    /**
     * Purges one cache by name, or every cache with {@code all}; with {@code date} only that business date (O2). {@code scope=cluster}
     * purges on every live member too (PO-6), each auditing it; the default is this server only.
     */
    @PostMapping("/{name}/purge")
    public Map<String, Object> purge(@PathVariable String name, @RequestParam(required = false) String date,
            @RequestParam(required = false, defaultValue = "server") String scope, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        LocalDate d;
        try {
            d = date == null || date.isBlank() ? null : LocalDate.parse(date.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "date is a business date (2026-09-30), not '" + date + "'");
        }
        long t0 = System.nanoTime();
        Map<String, Object> out = new LinkedHashMap<>(board.purge(name, d, p.user()));
        @SuppressWarnings("unchecked")
        List<String> purged = (List<String>) out.get("purged");
        if (purged.isEmpty()) {
            throw new DrishtiException(ErrorCode.CACHE_NOT_FOUND, "no cache named '" + name + "'");
        }
        users.recordAudit(p.user(), "cache-purged", name, String.join(", ", purged) + (d == null ? "" : " date=" + d)
                + (NetCaller.fromPeer() ? " (for the cluster)" : ""));
        out.put("scope", "cluster".equals(scope) ? "cluster" : "server");
        if ("cluster".equals(scope) && !NetCaller.fromPeer()) {
            String q = "/api/v1/admin/caches/" + java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20")
                    + "/purge" + (d == null ? "" : "?date=" + d);
            out.put("members", peers.all("POST", q, new byte[0], p, Duration.ofSeconds(30)).stream().map(PeerCalls.Answer::toMap).toList());
        }
        out.put("elapsedMs", Math.round((System.nanoTime() - t0) / 1e4) / 100.0);
        return out;
    }
}
