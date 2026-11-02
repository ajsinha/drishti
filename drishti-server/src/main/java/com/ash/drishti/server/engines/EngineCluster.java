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
package com.ash.drishti.server.engines;

import com.ash.drishti.net.digest.Digest;
import com.ash.drishti.net.transport.PeerHttp;
import com.ash.drishti.server.net.DigestService;
import com.ash.drishti.server.net.NetCallFilter;
import com.ash.drishti.server.net.NetCaller;
import com.ash.drishti.server.net.RunCluster;
import com.ash.drishti.server.security.Principal;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The engines across Drishti*net (docs/architecture/COMPUTE_ENGINES.md section 9): this node publishes its engines in its capability digest
 * (the service {@code engines}: {@code var:running,frtb-sa:off,...}; a change raises the digest's hash, so peers pull it within a gossip round),
 * and reads the peers' to route an engine's requests to a node that runs it (the least loaded; one that is starting or stopping is not
 * routed to) and to say what people see: an engine is available when any reachable node runs it.
 */
public final class EngineCluster implements EngineGateFilter.Peers {

    /** The digest service's name for the engines. */
    public static final String SERVICE = "engines";

    private final EngineRegistry registry;
    private final Supplier<DigestService> digests;
    private final Supplier<RunCluster> cluster;

    public EngineCluster(EngineRegistry registry, Supplier<DigestService> digests, Supplier<RunCluster> cluster) {
        this.registry = registry;
        this.digests = digests;
        this.cluster = cluster;
    }

    /** This node's engines as the digest carries them. */
    public String wire() {
        StringBuilder s = new StringBuilder();
        for (EngineCatalogue.Entry e : registry.entries()) {
            if (registry.state(e.id()) == EngineRegistry.State.NOT_INSTALLED) {
                continue;
            }
            s.append(s.isEmpty() ? "" : ",").append(e.id()).append(':').append(registry.state(e.id()).word().replace(' ', '-'));
        }
        return s.toString();
    }

    /** {@code var:running,frtb-sa:off} as a map. */
    static Map<String, String> parse(String wire) {
        Map<String, String> out = new LinkedHashMap<>();
        if (wire == null || wire.isBlank()) {
            return out;
        }
        for (String part : wire.split(",")) {
            int c = part.indexOf(':');
            if (c > 0) {
                out.put(part.substring(0, c).strip(), part.substring(c + 1).strip());
            }
        }
        return out;
    }

    /** Whether this node is a member of a running cluster. */
    public boolean on() {
        RunCluster c = cluster.get();
        return c != null && c.on();
    }

    /** Each other node that is alive and the state of its engines, as its digest last said. */
    public Map<String, Map<String, String>> peers() {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        DigestService d = digests.get();
        RunCluster c = cluster.get();
        if (d == null || c == null || !c.on()) {
            return out;
        }
        for (Map.Entry<String, Digest> p : d.peers().entrySet()) {
            if (c.alive(p.getKey())) {
                out.put(p.getKey(), parse(p.getValue().services().get(SERVICE)));
            }
        }
        return out;
    }

    /** Whether the engine runs on this node or on any reachable node. */
    public boolean available(String id) {
        return registry.running(id) || peers().values().stream().anyMatch(m -> "running".equals(m.get(id)));
    }

    @Override
    public Optional<String> runs(String engine) {
        DigestService d = digests.get();
        Map<String, Map<String, String>> peers = peers();
        return peers.entrySet().stream().filter(p -> "running".equals(p.getValue().get(engine))).map(Map.Entry::getKey)
                .min(Comparator.comparingDouble(n -> {
                    Digest g = d == null ? null : d.peers().get(n);
                    return g == null ? Double.MAX_VALUE : g.load().requestsPerSecond();
                }));
    }

    @Override
    public boolean fromPeer(HttpServletRequest request) {
        return NetCaller.fromPeer() || request.getAttribute(NetCallFilter.PEER) != null;
    }

    @Override
    public PeerHttp.Answer call(String node, String method, String pathAndQuery, byte[] body, Principal p) throws IOException {
        RunCluster c = cluster.get();
        if (c == null) {
            throw new IOException("no cluster");
        }
        return c.call(node, method, pathAndQuery, body, p, c.timeout());
    }

    /** The node's own id (blank outside a cluster). */
    public String self() {
        RunCluster c = cluster.get();
        return c == null || !c.on() ? "" : c.self();
    }

    /** The nodes known now, this one first. */
    public List<String> nodes() {
        List<String> out = new java.util.ArrayList<>();
        out.add(self().isEmpty() ? "this server" : self());
        out.addAll(peers().keySet());
        return out;
    }
}
