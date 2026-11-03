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

import com.ash.drishti.flight.FlightBackend;
import com.ash.drishti.net.config.HostAddress;
import com.ash.drishti.net.member.Member;
import com.ash.drishti.server.net.NetService;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The nodes a Flight endpoint names (Q5): this node first, then the live members of Drishti*net that may answer the same part, each with
 * its real address (the host of its advertised URL, never a loopback or wildcard address) and the Flight port. Every member opens Flight
 * on the same port (config/engines/flight.yaml is the cluster's); a member whose address cannot be read is left out.
 */
public final class FlightNodes {

    private final NetService net;
    private final String advertise;
    private final int port;

    public FlightNodes(NetService net, String advertise, int port) {
        this.net = net;
        this.advertise = advertise == null ? "" : advertise.trim();
        this.port = port;
    }

    /** This node's id: its cluster id, else the host's name. */
    public String selfId() {
        String id = net == null ? null : net.nodeId();
        return id == null || id.isBlank() ? self().host() : id;
    }

    /** This node with the address clients reach it at. */
    public FlightBackend.Node self() {
        String host = advertise;
        if (host.isEmpty() && net != null) {
            host = hostOf(net.advertiseUrl()).orElse("");
        }
        if (host.isEmpty() || HostAddress.loopbackOrWildcard(host)) {
            host = HostAddress.primary().orElse(host.isEmpty() ? "localhost" : host);
        }
        String id = net == null || net.nodeId() == null || net.nodeId().isBlank() ? host : net.nodeId();
        return new FlightBackend.Node(id, host, port);
    }

    /** The live members of the cluster other than this node, by their real addresses. */
    public List<FlightBackend.Node> peers() {
        List<FlightBackend.Node> out = new ArrayList<>();
        if (net == null || net.node().isEmpty()) {
            return out;
        }
        String me = net.nodeId();
        for (Member m : net.node().get().table().alive()) {
            if (m.nodeId().equals(me) || m.drained()) {
                continue;
            }
            hostOf(m.url()).filter(h -> !HostAddress.loopbackOrWildcard(h)).ifPresent(h -> out.add(new FlightBackend.Node(m.nodeId(), h, port)));
        }
        return out;
    }

    /** One node by id (this node or a live member), if it is up. */
    public Optional<FlightBackend.Node> byId(String id) {
        if (id == null || id.equals(selfId())) {
            return Optional.of(self());
        }
        return peers().stream().filter(n -> n.id().equals(id)).findFirst();
    }

    /** This node, then every live peer: an endpoint any member can answer (a kind, a model: each reads through the cluster itself). */
    public List<FlightBackend.Node> anyMember() {
        List<FlightBackend.Node> out = new ArrayList<>();
        out.add(self());
        out.addAll(peers());
        return out;
    }

    static Optional<String> hostOf(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        try {
            String h = URI.create(url).getHost();
            return h == null || h.isBlank() ? Optional.empty() : Optional.of(h);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
