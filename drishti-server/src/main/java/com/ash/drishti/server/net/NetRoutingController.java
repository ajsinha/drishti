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
package com.ash.drishti.server.net;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin → Net → Routing (CLUSTER_DEPLOYMENT.md › Drishti*net: many servers, one Drishti, section 13): <em>explain a request</em> (a kind and id, or a search, and a role: where it would go
 * and why: answered here, forwarded whole and to which nodes, which peers a read falls back to, which a search asks, which nodes the role may
 * not reach) and the forwarded traffic (per peer and minute, by outcome; the slowest peers; the latest calls). Administrators only.
 */
@RestController
@RequestMapping("/api/v1/net")
public class NetRoutingController {

    private final NetRouting routing;
    private final ForwardLog log;
    private final Entitlements entitlements;
    private final com.ash.drishti.engine.command.Mnemonics mnemonics;

    public NetRoutingController(NetRouting routing, ForwardLog log, Entitlements entitlements, com.ash.drishti.engine.command.Mnemonics mnemonics) {
        this.routing = routing;
        this.log = log;
        this.entitlements = entitlements;
        this.mnemonics = mnemonics;
    }

    @GetMapping("/explain")
    public Map<String, Object> explain(@RequestParam(required = false) String kind, @RequestParam(required = false) String id,
            @RequestParam(required = false) String q, @RequestParam(required = false) String role,
            @RequestParam(defaultValue = "false") boolean dated, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        Principal as = role == null || role.isBlank() ? p : new Principal("explain", List.of(role.trim()));
        String k = kind == null ? "" : kind.trim();
        if (k.isEmpty() && q != null && !q.isBlank()) {
            String head = q.trim().split("\\s+", 2)[0];
            k = mnemonics.of(head.toUpperCase(Locale.ROOT)).map(x -> x.kind()).or(() -> routing.kindOfMnemonic(head, as)).orElse(head.toLowerCase(Locale.ROOT));
        }
        if (k.isEmpty()) {
            throw new DrishtiException(ErrorCode.NET_BAD_REQUEST, "name a kind (and an id), or a search");
        }
        NetRouting.Plan plan = routing.view(k, as, dated, id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", k);
        out.put("id", id);
        out.put("q", q);
        out.put("roles", as.roles());
        out.put("route", plan.route().name().toLowerCase(Locale.ROOT));
        out.put("reason", plan.reason());
        out.put("forwardTo", plan.targets());
        out.put("localPack", routing.localPack(k).map(x -> x.name() + " " + x.version()).orElse(null));
        out.put("dataPeers", plan.route() == NetRouting.Route.LOCAL ? routing.dataPeers(k, as, null, !dated, id) : List.of());
        out.put("scatter", routing.tableFor(as).scatterTargets(k));
        out.put("unreachable", plan.unreachable());
        out.put("down", routing.downRenderers(k));
        return out;
    }

    /** Forwarded traffic: per peer and minute for the last hour, the slowest peers over ten minutes, the latest calls. */
    @GetMapping("/traffic")
    public Map<String, Object> traffic(@RequestParam(defaultValue = "60") int minutes, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("minutes", log.minutes(Math.max(1, Math.min(60, minutes))));
        out.put("slowest", log.slowest());
        out.put("recent", log.recent().stream().limit(50).map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", c.at().toString());
            m.put("peer", c.peer());
            m.put("kind", c.kind());
            m.put("what", c.what());
            m.put("outcome", c.outcome());
            m.put("ms", c.ms());
            m.put("detail", c.detail());
            return m;
        }).toList());
        out.put("totals", log.totals(10));
        return out;
    }
}
