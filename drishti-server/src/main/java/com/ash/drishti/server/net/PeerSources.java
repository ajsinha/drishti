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

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.engine.source.SourceRouter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The router's fallback to the peers (CLUSTER_DEPLOYMENT.md › Drishti*net: many servers, one Drishti, section 7, data forwarding): one {@link PeerSource} per peer, in the order
 * {@link NetRouting#dataPeers} chooses for the caller of the read (only nodes the caller's roles reach, never for a forwarded request).
 * Set on the router while the cluster runs and taken off when it stops, so a single server reads exactly as before.
 */
public final class PeerSources implements SourceRouter.Fallback {

    private final NetRouting routing;
    private final NetService net;
    private final JsonCodec codec;
    private final ForwardLog log;
    private final Map<String, PeerSource> byNode = new ConcurrentHashMap<>();
    private volatile GroupGather groups;
    private volatile SearchScatter scatter;

    public PeerSources(NetRouting routing, NetService net, JsonCodec codec, ForwardLog log, SourceRouter router) {
        this.routing = routing;
        this.net = net;
        this.codec = codec;
        this.log = log;
        // set from the moment node.yaml has the node on, before it joins: a read the server answers while still starting then waits for the
        // peers' digests or says the node is joining, instead of "no source holds" (SOAK3-01)
        Runnable apply = () -> router.fallback(net.running() || net.awaitingJoin() ? this : null);
        net.onChange(n -> apply.run());
        apply.run();
    }

    /** Sets what asks the peers for a listing (the configuration does: the search's scatter). */
    public void scatter(SearchScatter s) {
        this.scatter = s;
    }

    /**
     * The entities of the kind the peers hold, for the caller (QA4-02): the peers a search of the kind asks ({@link SearchScatter#targets}),
     * each listing its own data for the on-behalf-of user ({@code GET /api/v1/net/list/{kind}}). A peer that cannot answer is named in
     * {@code failures}. None while forwarding is off, for a forwarded request, or with no caller.
     */
    @Override
    public List<com.ash.drishti.api.EntityHit> list(String kind, int limit, java.time.Duration budget, AsOf asOf,
            com.ash.drishti.engine.source.SourceFailures failures) {
        SearchScatter s = scatter;
        NetCaller.Caller c = NetCaller.current().orElse(null);
        if (s == null || c == null || c.principal() == null) {
            return List.of();
        }
        List<String> nodes = s.targets(kind, c.principal());
        if (nodes.isEmpty()) {
            return List.of();
        }
        String path = "/api/v1/net/list/" + java.net.URLEncoder.encode(kind, java.nio.charset.StandardCharsets.UTF_8) + "?limit=" + limit;
        List<com.ash.drishti.api.EntityHit> out = new ArrayList<>();
        for (SearchScatter.Share share : s.ask(nodes, path, asOf, c.principal(), budget, "list", kind)) {
            if (share.answer() == null) {
                failures.add("node " + share.nodeId(), share.failure());
                continue;
            }
            for (var h : share.answer().path("hits")) {
                out.add(new com.ash.drishti.api.EntityHit(EntityRef.of(kind, h.path("id").asText()), h.path("title").asText(null),
                        h.path("subtitle").asText(null)));
            }
        }
        return out;
    }

    /** Sets what gathers grouped pages across the nodes (the configuration does). */
    public void groups(GroupGather g) {
        this.groups = g;
    }

    @Override
    public Optional<com.ash.drishti.api.EntityDocument> gather(EntityRef ref, AsOf asOf, com.ash.drishti.api.EntityDocument local) {
        GroupGather g = groups;
        return g == null ? Optional.empty() : g.gather(ref, asOf, local);
    }

    private PeerSource source(String nodeId) {
        return byNode.computeIfAbsent(nodeId, id -> new PeerSource(id, () -> url(id), net, codec, log));
    }

    private String url(String nodeId) {
        return net.node().flatMap(n -> n.table().known(nodeId)).map(k -> k.member().url()).orElse("");
    }

    @Override
    public List<SourcePlugin> peers(EntityRef ref, AsOf asOf) {
        NetCaller.Caller c = NetCaller.current().orElse(null);
        if (c == null || c.principal() == null) {
            return List.of();
        }
        awaitJoined();
        List<SourcePlugin> out = new ArrayList<>();
        for (String id : routing.dataPeers(ref.kind(), c.principal(), asOf == null ? null : asOf.businessDate(), asOf == null || asOf.live(), ref.id())) {
            out.add(source(id));
        }
        return out;
    }

    /**
     * A read that has gone on to the peers (no source here holds the entity) waits routing.yaml's {@code link-budget} (1 s) when it is one
     * of several a view reads at once (its linked entities): a call between servers takes longer than {@code drishti.graph.link-budget}
     * (40 ms), the wait for this node's own sources, which is unchanged. Answered by whichever node holds it, the link shows as itself,
     * never as pending. A forwarded request asks no peer, so its reads keep the local budget.
     */
    @Override
    public java.time.Duration budget(EntityRef ref, AsOf asOf, java.time.Duration local) {
        if (!routing.forwarding() || NetCaller.fromPeer()) {
            return local;
        }
        java.time.Duration peer = net.config().routing().linkBudget();
        return peer.compareTo(local) > 0 ? peer : local;
    }

    /**
     * The peers this caller's read of {@code ref} would ask, each with its sources' data versions for the kind from its digest (gossiped
     * every interval, so a load on a peer changes this within a few seconds): {@code net:qa1-b{trade-endur=60@…}}. Empty when a peer's
     * digest gives no version for one of its sources.
     */
    @Override
    public Optional<String> version(EntityRef ref, AsOf asOf) {
        NetCaller.Caller c = NetCaller.current().orElse(null);
        if (c == null || c.principal() == null) {
            return Optional.of("");
        }
        StringBuilder out = new StringBuilder();
        var table = routing.digests().table();
        for (String id : routing.dataPeers(ref.kind(), c.principal(), asOf == null ? null : asOf.businessDate(), asOf == null || asOf.live(),
                ref.id())) {
            var kind = table.node(id).flatMap(n -> n.digest().kind(ref.kind()));
            if (kind.isEmpty()) {
                return Optional.empty();
            }
            out.append(out.isEmpty() ? "" : "|").append("net:").append(id).append('{');
            boolean first = true;
            for (var s : kind.get().sources()) {
                if (s.dataVersion() == null || s.dataVersion().isBlank()) {
                    return Optional.empty();
                }
                out.append(first ? "" : ",").append(s.connector()).append('=').append(s.dataVersion());
                first = false;
            }
            out.append('}');
        }
        return Optional.of(out.toString());
    }

    @Override
    public List<String> downHolders(String kind) {
        return routing.forwarding() && !NetCaller.fromPeer() ? routing.downHolders(kind) : List.of();
    }

    @Override
    public List<String> downHolders(String kind, java.time.LocalDate date) {
        return routing.forwarding() && !NetCaller.fromPeer() ? routing.downHolders(kind, date) : List.of();
    }

    @Override
    public boolean mayHold(SourcePlugin peer, String kind, java.time.LocalDate date) {
        String id = peer.manifest().name().replaceFirst("^net:", "");
        return routing.digests().table().node(id).flatMap(n -> n.digest().kind(kind))
                .map(k -> k.sources().isEmpty() || k.sources().stream().anyMatch(s -> s.mayHold(date))).orElse(true);
    }

    /** What a read nothing here holds says while this node is joining; the console tries such a read once on another server. */
    public static final String JOINING = "this node is still joining the cluster and does not know yet which nodes hold ";

    /**
     * On a node still joining (SOAK3-01), a read that none of its sources holds waits for its peers' first digests: routing.yaml's
     * {@code join-wait}, never more than half the {@code forward-timeout}, so the read still has time to ask the peer. A forwarded request
     * asks no peer and does not wait.
     */
    private void awaitJoined() {
        if (!NetCaller.fromPeer()) {
            routing.awaitJoined();
        }
    }

    @Override
    public Optional<String> joining(String kind) {
        return !NetCaller.fromPeer() && routing.joining() ? Optional.of(JOINING + kind) : Optional.empty();
    }

    @Override
    public Optional<DrishtiException> unreachable(EntityRef ref) {
        if (!NetCaller.fromPeer() && routing.joining()) {
            return Optional.of(new DrishtiException(ErrorCode.NET_PEER_FAILED, "no source here holds " + ref + ", and " + JOINING + ref.kind()
                    + "; try again in a moment"));
        }
        if (!routing.forwarding()) {
            return Optional.empty();                      // a forwarded request asks no peer, but may still say which holder is down
        }
        List<String> down = routing.downHolders(ref.kind());
        if (down.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new DrishtiException(ErrorCode.NET_PEER_FAILED, "no source here holds " + ref + ", and the nodes that hold "
                + ref.kind() + " cannot be reached now: " + String.join(", ", down) + "; try again in a moment"));
    }
}
