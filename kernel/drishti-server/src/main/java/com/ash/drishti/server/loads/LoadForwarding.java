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
package com.ash.drishti.server.loads;

import com.ash.drishti.server.net.NetCallFilter;
import com.ash.drishti.server.net.NetCaller;
import com.ash.drishti.server.net.PeerCalls;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.kernel.api.security.Principal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A ready data load passed on once to every other live member of the cluster (PO-5, 2.9.7), so each refreshes its own caches and engines:
 * the one way a data-load signal reaches the other members, whoever made it. A person's announcement ({@code POST /packs/{pack}/loads})
 * goes on as that person; a signal the server makes itself (the data inbox) goes on as this node ({@link #node}: the service role, carried
 * on behalf of the node and signed with the cluster key, as the members' other system calls). A member that received a load from a peer
 * never passes it on again (one hop); a failed load is not passed on. The calls are made in the background, so the loader is never held up.
 */
public final class LoadForwarding {

    private static final Logger LOG = LoggerFactory.getLogger(LoadForwarding.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** The prefix of a node's own identity ({@code node:<id>}), as the token filter names an unsigned member's call. */
    public static final String NODE = "node:";

    /** The members, as {@link PeerCalls} sees them (a test gives its own). */
    public interface Members {
        boolean clustered();

        List<String> peers();

        List<PeerCalls.Answer> all(String method, String pathAndQuery, byte[] body, Principal p, Duration timeout);
    }

    private final Members members;
    private final Executor background;

    /** The forwarding over the cluster's peer calls ({@code null}: no cluster, nothing is passed on). */
    public LoadForwarding(PeerCalls peers) {
        this(peers == null ? null : new Members() {
            @Override
            public boolean clustered() {
                return peers.clustered();
            }

            @Override
            public List<String> peers() {
                return peers.peers();
            }

            @Override
            public List<PeerCalls.Answer> all(String method, String pathAndQuery, byte[] body, Principal p, Duration timeout) {
                return peers.all(method, pathAndQuery, body, p, timeout);
            }
        }, r -> Thread.ofVirtual().name("drishti-load-forward").start(r));
    }

    LoadForwarding(Members members, Executor background) {
        this.members = members;
        this.background = background;
    }

    /** This node's own identity for a signal it makes itself: {@code node:<id>} with the service role (never a person's roles). */
    public static Principal node(String nodeId) {
        return new Principal(NODE + (nodeId == null || nodeId.isBlank() ? "this-server" : nodeId), List.of(Entitlements.SERVICE));
    }

    /** Whether a request is another member's own signal: signed with the cluster key, made as a node with the service role. */
    public static boolean fromNode(HttpServletRequest req, Principal p) {
        return req.getAttribute(NetCallFilter.PEER) != null && p != null && p.user().startsWith(NODE) && p.roles().contains(Entitlements.SERVICE);
    }

    /**
     * Passes a load on to every other live member as {@code p}; returns the members it goes to (empty: not clustered, no other member,
     * a failed load, or a load received from a peer).
     */
    public List<String> forward(String pack, LoadService.Announcement body, Principal p) {
        if (members == null || NetCaller.fromPeer() || !members.clustered() || "failed".equalsIgnoreCase(String.valueOf(body.status()))) {
            return List.of();
        }
        List<String> to = members.peers();
        if (to.isEmpty()) {
            return List.of();
        }
        byte[] json;
        try {
            json = JSON.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            json = new byte[0];
        }
        byte[] payload = json;
        String path = "/api/v1/packs/" + URLEncoder.encode(pack, StandardCharsets.UTF_8) + "/loads";
        background.execute(() -> members.all("POST", path, payload, p, Duration.ofSeconds(30)).stream()
                .filter(a -> a.status() >= 300 || a.status() == 0)
                .forEach(a -> LOG.warn("data load {} {} {}: member {} did not take it ({}): announce on it by hand", pack, body.kind(),
                        body.businessDate(), a.node(), a.status() == 0 ? a.body() : a.status() + " " + a.body())));
        return to;
    }
}
