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
package com.ash.drishti.server.lifecycle;

import com.ash.drishti.api.lifecycle.ComponentHealth;
import com.ash.drishti.api.lifecycle.ComponentStats;
import com.ash.drishti.api.lifecycle.Lifecycle;
import com.ash.drishti.api.lifecycle.Transition;
import com.ash.drishti.kernel.api.security.Principal;
import com.ash.drishti.server.net.DigestService;
import com.ash.drishti.server.security.Entitlements;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The lifecycle's REST API (docs/architecture/LIFECYCLE.md section 8), administrators only, every transition audited:
 *
 * <pre>
 *   GET  /api/v1/admin/lifecycle                                        every module, pack and connector here: state, health, stats; peers' parts not running
 *   GET  /api/v1/admin/lifecycle/graph                                  the dependency graph: nodes, edges, start order, cycle, missing
 *   GET  /api/v1/admin/{modules|packs|connectors}/{name}/lifecycle      one part: state, since, by, health, stats
 *   POST /api/v1/admin/{modules|packs|connectors}/{name}/lifecycle/{init|start|suspend|resume|shutdown}[?cascade=true]
 *   GET  /api/v1/admin/{modules|packs|connectors}/{name}/health         UP, DEGRADED, SUSPENDED or DOWN, the reasons, when checked
 *   GET  /api/v1/admin/{modules|packs|connectors}/{name}/stats          counters since start and over the window, latency, queue, last activity
 * </pre>
 * A transition the state does not allow is {@code 409 DRS-1036} and changes nothing; a request to a suspended part is {@code 503 DRS-1037}.
 * Engines keep their own paths ({@code /api/v1/engines/{id}/...}, the engine specification's).
 */
@RestController
public class LifecycleController {

    private final LifecycleService service;
    private final Entitlements entitlements;
    private final ObjectProvider<DigestService> digests;

    public LifecycleController(LifecycleService service, Entitlements entitlements, ObjectProvider<DigestService> digests) {
        this.service = service;
        this.entitlements = entitlements;
        this.digests = digests;
    }

    static Lifecycle.Type type(String plural) {
        return switch (plural) {
            case "modules" -> Lifecycle.Type.MODULE;
            case "packs" -> Lifecycle.Type.PACK;
            case "connectors" -> Lifecycle.Type.CONNECTOR;
            default -> throw new IllegalArgumentException("a part is a module, a pack or a connector, not '" + plural + "'");
        };
    }

    @GetMapping("/api/v1/admin/lifecycle")
    public Map<String, Object> all(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        service.names().forEach((type, names) -> names.forEach(n -> rows.add(service.row(type, n))));
        out.put("parts", rows);
        Map<String, Object> peers = new LinkedHashMap<>();
        DigestService d = digests.getIfAvailable();
        if (d != null) {
            d.peers().forEach((node, digest) -> peers.put(node, LifecycleService.parse(digest.services().get(LifecycleService.SERVICE))));
        }
        out.put("peers", peers);
        return out;
    }

    @GetMapping("/api/v1/admin/lifecycle/graph")
    public Map<String, Object> graph(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return service.graph().describe();
    }

    @GetMapping("/api/v1/admin/{type:modules|packs|connectors}/{name}/lifecycle")
    public Map<String, Object> one(@PathVariable String type, @PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return service.row(type(type), name);
    }

    /**
     * Runs one transition. {@code cascade} (packs): a shutdown also shuts down the running packs that depend on it, dependants first; without
     * it such a shutdown is refused naming them. A suspend always takes the packs that extend it along (they serve its kinds).
     */
    @PostMapping("/api/v1/admin/{type:modules|packs|connectors}/{name}/lifecycle/{transition}")
    public Map<String, Object> transition(@PathVariable String type, @PathVariable String name, @PathVariable String transition,
            @RequestParam(defaultValue = "false") boolean cascade, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        Lifecycle.Type t = type(type);
        Transition tr = Transition.of(transition);
        List<String> along = List.of();
        if (t == Lifecycle.Type.PACK && tr == Transition.SHUTDOWN) {
            along = service.packs().shutdown(name, p.user(), cascade);
        } else if (t == Lifecycle.Type.PACK && tr == Transition.SUSPEND) {
            along = service.packs().suspend(name, p.user());
        } else if (t == Lifecycle.Type.PACK && tr == Transition.RESUME) {
            along = service.packs().resume(name, p.user());
        } else {
            service.part(t, name).apply(tr, p.user());
        }
        Map<String, Object> out = service.row(t, name);
        out.put("along", along);
        return out;
    }

    @GetMapping("/api/v1/admin/{type:modules|packs|connectors}/{name}/health")
    public ComponentHealth health(@PathVariable String type, @PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return service.part(type(type), name).health();
    }

    @GetMapping("/api/v1/admin/{type:modules|packs|connectors}/{name}/stats")
    public ComponentStats stats(@PathVariable String type, @PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return service.part(type(type), name).stats();
    }
}
