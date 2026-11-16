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
package com.ash.drishti.server.node;

import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.server.net.NetService;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.kernel.api.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * This node's readiness and its drain before a stop (CD1 and CD2, CLUSTER_GUIDE.md sections 4.6 and 9.1). Administrators only; a
 * personal API token needs the {@code node:drain} scope for the {@code POST} (the stop hooks and the rolling upgrade runner use one).
 *
 * <pre>
 *   GET  /api/v1/admin/node              readiness (status, what it waits for) and the drain (drained, safeToStop, each part)
 *   POST /api/v1/admin/node/drain {on}   drains this node (true, the default) or takes it back (false); the same answer
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/admin/node")
public class NodeController {

    private final NodeDrain drain;
    private final NodeReadiness readiness;
    private final Entitlements entitlements;
    private final ObjectProvider<AuditLog> audit;
    private final ObjectProvider<NetService> net;

    public NodeController(NodeDrain drain, NodeReadiness readiness, Entitlements entitlements, ObjectProvider<AuditLog> audit,
            ObjectProvider<NetService> net) {
        this.drain = drain;
        this.readiness = readiness;
        this.entitlements = entitlements;
        this.audit = audit;
        this.net = net;
    }

    @GetMapping
    public Map<String, Object> status(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return answer();
    }

    /** Body {@code {"on": true|false}} (no body: on). Audited as {@code node-drain} or {@code node-undrain}. */
    @PostMapping("/drain")
    public Map<String, Object> drain(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @RequestBody(required = false) Map<String, Object> body) {
        entitlements.requireAdmin(p);
        boolean on = body == null || !Boolean.FALSE.equals(body.get("on")) && !"false".equals(String.valueOf(body.get("on")));
        drain.drain(on);
        AuditLog a = audit.getIfAvailable();
        if (a != null) {
            a.record(p.user(), on ? "node-drain" : "node-undrain", nodeId(), on ? "forwarded work and shards handed over before a stop; readiness out of service"
                    : "takes work again");
        }
        return answer();
    }

    private String nodeId() {
        NetService n = net.getIfAvailable();
        return n == null || n.nodeId().isEmpty() ? "this server" : n.nodeId();
    }

    private Map<String, Object> answer() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("node", nodeId());
        out.putAll(drain.status());
        out.put("readiness", readiness.describe());
        return out;
    }
}
