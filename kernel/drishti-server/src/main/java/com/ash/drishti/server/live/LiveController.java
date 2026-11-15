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
package com.ash.drishti.server.live;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.kernel.api.security.Principal;
import com.ash.drishti.server.security.Entitlements;
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
 * The live feeds for Admin and the CLI (docs/architecture/LIVE_CONNECTORS_DESIGN.md sections 7.9 and 9.1), administrators only:
 * {@code GET /api/v1/admin/live} every feed with its state, rate, counts and last reconcile; {@code GET /{connector}} one with its kinds'
 * rules in words, its newest rejects and how it flows; {@code POST /{connector}/pause}, {@code /resume} and {@code /apply-now}, audited.
 * Switching a kind's live feed on or off is a binding change (Admin → Data bindings, four eyes), never here.
 */
@RestController
@RequestMapping("/api/v1/admin/live")
public class LiveController {

    private final LiveFeeds feeds;
    private final Entitlements entitlements;

    public LiveController(LiveFeeds feeds, Entitlements entitlements) {
        this.feeds = feeds;
        this.entitlements = entitlements;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String kind, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        List<Map<String, Object>> rows = kind == null || kind.isBlank() ? feeds.list() : feeds.ofKind(kind.strip());
        return Map.of("feeds", rows);
    }

    @GetMapping("/{connector}")
    public Map<String, Object> detail(@PathVariable String connector, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return feeds.detail(connector).orElseThrow(() -> notFound(connector));
    }

    /** The newest rejects of a feed (all kinds, or {@code kind}'s); the full log is {@code <data folder>/live/rejects/<connector>/<date>.jsonl}. */
    @GetMapping("/{connector}/rejects")
    public Map<String, Object> rejects(@PathVariable String connector, @RequestParam(required = false) String kind,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        Map<String, Object> d = feeds.detail(connector).orElseThrow(() -> notFound(connector));
        List<?> all = d.get("rejects") instanceof List<?> l ? l : List.of();
        List<?> mine = kind == null || kind.isBlank() ? all : all.stream().filter(r -> String.valueOf(r).contains("\"kind\":\"" + kind.strip() + "\"")).toList();
        return Map.of("connector", connector, "rejects", mine);
    }

    @PostMapping("/{connector}/pause")
    public Map<String, Object> pause(@PathVariable String connector, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        if (!feeds.pause(connector, p.user())) {
            throw notRunning(connector);
        }
        return feeds.detail(connector).orElseThrow(() -> notFound(connector));
    }

    @PostMapping("/{connector}/resume")
    public Map<String, Object> resume(@PathVariable String connector, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        if (!feeds.resume(connector, p.user())) {
            throw notRunning(connector);
        }
        return feeds.detail(connector).orElseThrow(() -> notFound(connector));
    }

    @PostMapping("/{connector}/apply-now")
    public Map<String, Object> applyNow(@PathVariable String connector, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return feeds.applyNow(connector, p.user()).orElseThrow(() -> new DrishtiException(ErrorCode.LIVE_FEED_NOT_FOUND,
                connector + " lands nothing: it is not running, or its kinds are not landed (apply: land or both)"));
    }

    private static DrishtiException notFound(String connector) {
        return new DrishtiException(ErrorCode.LIVE_FEED_NOT_FOUND, "no live connector named " + connector + " (a kafka, activemq or rabbitmq connector)");
    }

    private static DrishtiException notRunning(String connector) {
        return new DrishtiException(ErrorCode.LIVE_FEED_NOT_FOUND, connector + " is not running: nothing to pause or resume");
    }
}
