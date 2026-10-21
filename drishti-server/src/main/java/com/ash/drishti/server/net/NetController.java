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
import com.ash.drishti.net.member.Member;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The membership endpoints of Drishti*net (DRISHTI_NET.md section 15): the push-pull exchange and the probes, which only another
 * node may call (the call filter checks the cluster credential first), and the member list, which a node, an administrator
 * (everything) or any signed-in caller such as the console's discovery (the alive members' addresses, tags and packs) may read.
 *
 * <p>Since 2.6.7 the exchange, the probes and the digest are answered before any of this, on either port, by the priority path
 * {@link GossipPath} (the same answers, without the filter chain or the dispatcher); the mappings here are the API of record and answer
 * only if that filter is not registered.
 */
@RestController
@RequestMapping("/api/v1/net")
public class NetController {

    private final NetService net;
    private final NetViews views;
    private final DigestService digests;
    private final Entitlements entitlements;
    private final ObjectMapper json = new ObjectMapper();

    public NetController(NetService net, NetViews views, DigestService digests, Entitlements entitlements) {
        this.net = net;
        this.views = views;
        this.digests = digests;
        this.entitlements = entitlements;
    }

    /** Push-pull: the caller's table is merged and this node's is the answer. A node that left serves nothing. */
    @PostMapping("/gossip")
    public Map<String, Object> gossip(@RequestBody JsonNode body) throws Exception {
        NetService.Node n = net.require();
        List<Member> theirs = List.of(json.treeToValue(body.path("members"), Member[].class));
        n.gossiper().accept(theirs);                       // merged, and joins or departures told as for an exchange of this node
        return Map.of("members", n.table().entries());
    }

    /** This node's capability digest (section 6), for a peer whose gossip carried a new hash. */
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

    /** A direct probe, or ({@code ?target=}) an indirect one: this node probes a member for the caller. */
    @GetMapping("/ping")
    public Map<String, Object> ping(@RequestParam(required = false) String target) {
        NetService.Node n = net.require();
        if (target == null || target.isBlank()) {
            return Map.of("nodeId", n.id(), "state", n.table().self().state().wire());
        }
        // only a member's address is probed: a probe is never a way to make this node call anywhere else
        String t = target.replaceAll("/+$", "");
        boolean member = n.table().entries().stream().anyMatch(m -> m.url().equals(t));
        if (!member) {
            throw new DrishtiException(ErrorCode.NET_BAD_REQUEST, "not a member's address: " + t);
        }
        return Map.of("nodeId", n.id(), "target", t, "answered", net.http().ping(t));
    }

    /**
     * The member table. An administrator gets every member with its local bookkeeping and the capability table; a node gets the
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
