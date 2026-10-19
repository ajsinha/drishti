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
 * The router's fallback to the peers (DRISHTI_NET.md section 7, data forwarding): one {@link PeerSource} per peer, in the order
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

    public PeerSources(NetRouting routing, NetService net, JsonCodec codec, ForwardLog log, SourceRouter router) {
        this.routing = routing;
        this.net = net;
        this.codec = codec;
        this.log = log;
        Runnable apply = () -> router.fallback(net.running() ? this : null);
        net.onChange(n -> apply.run());
        apply.run();
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
        List<SourcePlugin> out = new ArrayList<>();
        for (String id : routing.dataPeers(ref.kind(), c.principal(), asOf == null ? null : asOf.businessDate(), asOf == null || asOf.live(), ref.id())) {
            out.add(source(id));
        }
        return out;
    }

    @Override
    public Optional<DrishtiException> unreachable(EntityRef ref) {
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
