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

import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.kernel.api.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The membership endpoints of Drishti*net (CLUSTER_GUIDE.md section 1): the probe and the digest, which only another node may call (the
 * call filter checks the cluster credential first), and the member list, which a node, an administrator (everything) or any signed-in
 * caller such as the console's discovery (the alive members' addresses, tags and packs) may read. Membership itself is the membership
 * channel's (JGroups on cluster-port, CLUSTER_I5_DESIGN.md section 4.1), not HTTP.
 *
 * <p>The probe and the digest are answered before any of this by the priority path {@link ControlPath} (the same answers, without the
 * filter chain or the dispatcher); the mappings here are the API of record and answer only if that filter is not registered.
 */
@RestController
@RequestMapping("/api/v1/net")
public class NetController {

    private final NetService net;
    private final NetViews views;
    private final DigestService digests;
    private final Entitlements entitlements;

    public NetController(NetService net, NetViews views, DigestService digests, Entitlements entitlements) {
        this.net = net;
        this.views = views;
        this.digests = digests;
        this.entitlements = entitlements;
    }

    /** This node's capability digest (section 6), for a peer whose vitals carried a new hash. */
    @GetMapping("/digest")
    public com.ash.drishti.net.digest.Digest digest() {
        net.require();
        return digests.self();
    }

    /** Admin → Net → Capability map: kind to the nodes that render it and hold its data, with the warnings (an administrator). */
    @GetMapping("/capabilities")
    public Map<String, Object> capabilities(@RequestAttribute(Principal.ATTRIBUTE) Principal p,
            @RequestAttribute(value = NetCallFilter.PEER, required = false) String peer) {
        if (peer == null) {
            entitlements.requireAdmin(p);
        }
        return views.capabilities();
    }

    /** A probe: this node's id, state and view (the HTTP/2 priming call of a peer, a health check between nodes). */
    @GetMapping("/ping")
    public Map<String, Object> ping() {
        NetService.Node n = net.require();
        return Map.of("nodeId", n.id(), "state", n.members().self().state().wire(), "view", n.members().viewId());
    }

    /**
     * The members. An administrator gets every member with its local bookkeeping and the capability table; a node gets the
     * same; any other caller (the console's discovery) the alive members' advertised addresses, tags and packs.
     */
    @GetMapping("/members")
    public Map<String, Object> members(@RequestAttribute(Principal.ATTRIBUTE) Principal p,
            @RequestAttribute(value = NetCallFilter.PEER, required = false) String peer) {
        boolean full = peer != null || entitlements.isAdmin(p);
        if (!full) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("enabled", net.running());
            out.put("self", net.nodeId());
            out.put("members", views.discovery());
            return out;
        }
        return views.members();
    }
}
