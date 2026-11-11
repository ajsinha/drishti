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
package com.ash.drishti.server.flight;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.flight.FlightActivity;
import com.ash.drishti.flight.FlightBackend;
import com.ash.drishti.flight.FlightLimits;
import com.ash.drishti.flight.FlightService;
import com.ash.drishti.flight.FlightSettings;
import com.ash.drishti.identity.ApiTokenStore;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.kernel.api.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin → Data out (docs/guides/FLIGHT_GUIDE.md, "Admin → Data out"; mock-ups A and B, docs/architecture/mockups/data-out/): one API for
 * both views of the screen, the page (A) and the map of clients and nodes (B).
 * <ul>
 *   <li>{@code GET /api/v1/admin/data-out}: {@code {service: {enabled, running, port, tls, mutualTls, memory: {used, cap}, problem, forms},
 *       nodes: [{id, host, port, self}], live: [{id, person, onBehalfOf, token, form, request, part, stream, started, rows, bytes, sequence,
 *       memory}], recent: [{at, person, onBehalfOf, token, form, request, part, stream, rows, bytes, millis, node, refusal}], limits:
 *       {default, roles}, tokens: [{id, user, name, scopes, expiresAt, lastUsedAt}]}}; the live and recent reads are this node's (each node
 *       answers for itself; the screen asks every node of the list);</li>
 *   <li>{@code POST /api/v1/admin/data-out/streams/{id}/stop}: the read ends after its current batch with {@code DRS-5610}; audited.</li>
 * </ul>
 * Administrators only. Token revocation stays on Admin → Users (one place); the screen links to it.
 */
@RestController
public class DataOutController {

    private final FlightService service;
    private final ServerFlightBackend backend;
    private final Entitlements entitlements;
    private final AuditLog audit;
    private final ApiTokenStore tokens;

    DataOutController(FlightService service, ServerFlightBackend backend, Entitlements entitlements, AuditLog audit, ApiTokenStore tokens) {
        this.service = service;
        this.backend = backend;
        this.entitlements = entitlements;
        this.audit = audit;
        this.tokens = tokens;
    }

    @GetMapping("/api/v1/admin/data-out")
    public Map<String, Object> describe(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        FlightSettings s = service.settings();
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> svc = new LinkedHashMap<>();
        svc.put("enabled", s.enabled());
        svc.put("running", service.running());
        svc.put("port", service.port());
        svc.put("tls", true);
        svc.put("mutualTls", s.clientCa() != null);
        long[] mem = service.memory();
        svc.put("memory", Map.of("used", mem[0], "cap", mem[1]));
        svc.put("problem", FlightConfiguration.startProblem());
        svc.put("forms", backend.forms());
        svc.put("batchRows", s.batchRows());
        out.put("service", svc);
        List<Map<String, Object>> nodes = new ArrayList<>();
        FlightBackend.Node self = backend.nodes().self();
        nodes.add(node(self, true));
        backend.nodes().peers().forEach(n -> nodes.add(node(n, false)));
        out.put("nodes", nodes);
        List<Map<String, Object>> live = new ArrayList<>();
        for (FlightActivity.Live l : service.activity().live()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", l.id());
            m.put("person", l.caller().person());
            m.put("onBehalfOf", l.caller().onBehalfOf());
            m.put("token", l.caller().tokenId());
            m.put("form", l.request().form().name().toLowerCase(java.util.Locale.ROOT));
            m.put("request", l.request().text());
            m.put("part", l.part());
            m.put("stream", l.request().stream());
            m.put("started", l.started().toString());
            m.put("rows", l.rows());
            m.put("bytes", l.bytes());
            m.put("sequence", l.sequence());
            m.put("memory", l.memory());
            m.put("node", self.id());
            live.add(m);
        }
        out.put("live", live);
        out.put("recent", service.activity().recent());
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("default", limits(s.defaultLimits()));
        Map<String, Object> roles = new LinkedHashMap<>();
        s.roleLimits().forEach((r, l) -> roles.put(r, limits(l)));
        limits.put("roles", roles);
        out.put("limits", limits);
        List<Map<String, Object>> withScope = new ArrayList<>();
        if (tokens != null) {
            for (ApiTokenStore.TokenView t : tokens.all()) {
                if (!t.active() || t.scopes() == null || t.scopes().stream().noneMatch(x -> x.startsWith(ServerFlightBackend.SCOPE))) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", t.id());
                m.put("user", t.user());
                m.put("name", t.name());
                m.put("scopes", t.scopes());
                m.put("expiresAt", t.expiresAt() == null ? null : t.expiresAt().toString());
                m.put("lastUsedAt", t.lastUsedAt() == null ? null : t.lastUsedAt().toString());
                withScope.add(m);
            }
        }
        out.put("tokens", withScope);
        return out;
    }

    @PostMapping("/api/v1/admin/data-out/streams/{id}/stop")
    public Map<String, Object> stop(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @PathVariable String id) {
        entitlements.requireAdmin(p);
        if (!service.activity().stop(id, p.user())) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no live Flight read " + id + " on this node (it may have ended)");
        }
        if (audit != null) {
            audit.record(p.user(), "data.flight-stopped", id, "node=" + backend.nodeId());
        }
        return Map.of("stopped", id, "node", backend.nodeId());
    }

    private static Map<String, Object> node(FlightBackend.Node n, boolean self) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", n.id());
        m.put("host", n.host());
        m.put("port", n.port());
        m.put("self", self);
        return m;
    }

    private static Map<String, Object> limits(FlightLimits l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rows", l.rows());
        m.put("bytes", l.bytes());
        m.put("seconds", l.time().toSeconds());
        m.put("memory", l.memory());
        return m;
    }
}
