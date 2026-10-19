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
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.net.config.AccessSettings;
import com.ash.drishti.net.config.NetFolder;
import com.ash.drishti.net.config.NetSchema;
import com.ash.drishti.net.member.Member;
import com.ash.drishti.server.connectors.ConnectorManager;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.ash.drishti.server.security.RoleCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin → Net, Access and Security (DRISHTI_NET.md sections 9 and 13). Access: the nodes each role may reach, this node's tags and its
 * peer-only packs, written to {@code access.yaml} and {@code node.yaml} (checked first, history kept, audited) and applied without a
 * restart. Security: the cluster key's state (set, the previous key accepted, the last time each peer signed with the previous key, so an
 * administrator knows when a rollover is complete), the mutual TLS settings with their certificates' expiry, and the refused calls.
 * The key itself is never shown or set here. Administrators only.
 */
@RestController
@RequestMapping("/api/v1/net")
public class NetSecurityController {

    private final NetService net;
    private final Entitlements entitlements;
    private final RoleCatalog roles;
    private final AuditLog audit;

    public NetSecurityController(NetService net, Entitlements entitlements, RoleCatalog roles, AuditLog audit) {
        this.net = net;
        this.entitlements = entitlements;
        this.roles = roles;
        this.audit = audit;
    }

    /** Nodes per role (and where each role reaches now), this node's tags and peer-only packs, the roles and tags there are. */
    @GetMapping("/access")
    public Map<String, Object> access(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        AccessSettings a = net.config().access();
        List<Member> members = net.node().map(n -> n.table().entries()).orElse(List.of());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("roles", a.roles());
        TreeSet<String> names = new TreeSet<>(roles.get());
        names.addAll(a.roles().keySet());
        List<Map<String, Object>> reach = new ArrayList<>();
        for (String role : names) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("role", role);
            r.put("nodes", a.roles().getOrDefault(role, List.of()));
            r.put("limited", !a.roles().getOrDefault(role, List.of()).isEmpty());
            r.put("reaches", members.stream().filter(m -> a.reaches(List.of(role), m.nodeId(), m.tags())).map(Member::nodeId).toList());
            reach.add(r);
        }
        out.put("byRole", reach);
        out.put("tags", net.config().node().tags());
        out.put("peerOnlyPacks", net.config().node().peerOnlyPacks());
        TreeSet<String> known = new TreeSet<>();
        members.forEach(m -> {
            known.add(m.nodeId());
            known.addAll(m.tags());
        });
        out.put("choices", List.copyOf(known));
        out.put("etags", Map.of("access.yaml", net.folder().text("access.yaml").map(NetFolder::etag).orElse(""),
                "node.yaml", net.folder().text("node.yaml").map(NetFolder::etag).orElse("")));
        return out;
    }

    /**
     * Body {@code {roles: {role: [node id or tag, ...]}, tags: [...], peerOnlyPacks: [...]}}, each part optional: what is sent is written
     * (access.yaml whole; node.yaml's two settings, the rest of it kept), checked first; a refused file leaves both as they were.
     */
    @PutMapping("/access")
    public Map<String, Object> saveAccess(@RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        NetFolder f = net.folder();
        Map<String, String> writes = new LinkedHashMap<>();
        List<String> changed = new ArrayList<>();
        if (body.has("roles")) {
            Map<String, Object> roleMap = new LinkedHashMap<>();
            body.path("roles").fields().forEachRemaining(e -> {
                List<String> nodes = new ArrayList<>();
                e.getValue().forEach(x -> {
                    if (!x.asText().isBlank()) {
                        nodes.add(x.asText().trim());
                    }
                });
                roleMap.put(e.getKey(), Map.of("nodes", nodes));
            });
            writes.put("access.yaml", f.render("access.yaml", roleMap.isEmpty() ? Map.of() : Map.of("roles", roleMap)));
            changed.add("roles " + roleMap.keySet());
        }
        String node = null;
        for (String[] key : new String[][] {{"tags", "tags"}, {"peerOnlyPacks", "peer-only-packs"}}) {
            if (body.has(key[0])) {
                List<String> values = new ArrayList<>();
                body.path(key[0]).forEach(x -> values.add(x.asText().trim()));
                node = withList(node == null ? f.text("node.yaml").orElse("") : node, key[1], values);
                changed.add(key[1] + " " + values);
            }
        }
        if (node != null) {
            writes.put("node.yaml", node);
        }
        for (var w : writes.entrySet()) {
            List<NetSchema.Problem> problems = f.check(w.getKey(), w.getValue());
            if (problems.stream().anyMatch(NetSchema.Problem::error)) {
                throw new DrishtiException(ErrorCode.NET_FILE_REFUSED, w.getKey() + " was not written: "
                        + problems.stream().filter(NetSchema.Problem::error).findFirst().orElseThrow());
            }
        }
        writes.forEach((id, text) -> f.write(id, text, null));
        net.reload();
        audit.record(p.user(), "net-access-saved", String.join(", ", writes.keySet()), String.join("; ", changed)
                + " (on node " + (net.nodeId().isEmpty() ? "with the cluster off" : net.nodeId()) + ")");
        return access(p);
    }

    /** node.yaml's text with one list setting replaced (an empty list removes it), the rest kept. */
    private String withList(String text, String key, List<String> values) {
        return net.folder().withSetting(text, "node.yaml", key, String.join(",", values));
    }

    /** The key's state, the previous key's last uses per peer, mutual TLS and its certificates, the refused calls. */
    @GetMapping("/security")
    public Map<String, Object> security(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("keySet", net.key().set());
        out.put("previousKeySet", net.key().previousSet());
        out.put("previousUses", net.key().previousUses().entrySet().stream()
                .map(e -> Map.of("node", e.getKey(), "at", e.getValue().toString())).toList());
        out.put("rollover", !net.key().previousSet() ? "none: one key" : net.key().previousUses().isEmpty()
                ? "a previous key is accepted and no peer has signed with it since this node started: remove previous-secret once every node signs with the new key"
                : "a previous key is accepted and still used by " + net.key().previousUses().keySet());
        Map<String, String> tls = new LinkedHashMap<>();
        net.config().node().tls().forEach((k, v) -> tls.put("tls." + k, v));
        Map<String, Object> summary = tls.isEmpty() ? null : ConnectorManager.tlsSummary(tls);
        out.put("tls", summary);
        out.put("refusals", net.refusals().stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", r.at().toString());
            m.put("address", r.address());
            m.put("node", r.node());
            m.put("reason", r.reason());
            return m;
        }).toList());
        return out;
    }
}
