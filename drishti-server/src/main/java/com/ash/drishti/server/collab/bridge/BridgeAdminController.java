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
package com.ash.drishti.server.collab.bridge;

import com.ash.drishti.identity.collab.CollabProperties;
import com.ash.drishti.identity.collab.OutboxItem;
import com.ash.drishti.identity.collab.OutboxStore;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin: the configured bridges (name, format, whether it can post and, if not, why, the host it posts to, its routes, and what is
 * waiting or dead in the outbox for it) and a test post. Never the URL (its path is a secret) and never the signing secret.
 * {@code admin} only.
 */
@RestController
@RequestMapping("/api/v1/admin/collab/bridges")
public class BridgeAdminController {

    private final BridgeRegistry registry;
    private final BridgeSender sender;
    private final OutboxStore outbox;
    private final Entitlements entitlements;
    private final CollabProperties props;

    public BridgeAdminController(BridgeRegistry registry, BridgeSender sender, OutboxStore outbox, Entitlements entitlements, CollabProperties props) {
        this.registry = registry;
        this.sender = sender;
        this.outbox = outbox;
        this.entitlements = entitlements;
        this.props = props;
    }

    @GetMapping
    public Map<String, Object> list(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        Map<String, Map<String, Long>> byBridge = new LinkedHashMap<>();
        for (OutboxItem i : outbox.list(null, 500)) {
            if (BridgeSender.CHANNEL.equals(i.channel())) {
                byBridge.computeIfAbsent(i.recipient(), k -> new LinkedHashMap<>()).merge(i.state(), 1L, Long::sum);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", props.bridges().enabled());
        out.put("renderAs", props.bridges().renderAs());
        out.put("perMinute", props.bridges().perMinute());
        out.put("bridges", registry.all().stream().map(b -> row(b, byBridge.getOrDefault(b.name(), Map.of()))).toList());
        return out;
    }

    /** Posts a test message to the bridge now and returns the endpoint's HTTP status; {@code 503 DRS-7013} says why it failed. */
    @PostMapping("/{name}/test")
    public Map<String, Object> test(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @PathVariable String name) {
        entitlements.requireAdmin(p);
        int status = sender.test(name, p.user(), props.consoleUrl());
        return Map.of("sent", true, "bridge", name, "status", status);
    }

    private static Map<String, Object> row(BridgeRegistry.Bridge b, Map<String, Long> queue) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", b.name());
        m.put("format", b.format());
        m.put("usable", b.usable());
        m.put("status", b.status());
        m.put("host", b.host());
        m.put("routes", b.routes().stream().map(r -> Map.of("packs", r.packs(), "kinds", r.kinds(), "events", r.events())).toList());
        m.put("outbox", queue);
        return m;
    }
}
