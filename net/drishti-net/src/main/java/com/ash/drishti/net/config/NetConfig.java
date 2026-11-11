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
package com.ash.drishti.net.config;

import java.util.List;

/**
 * Everything {@code config/net/} says, as one immutable snapshot: swapped whole when a file changes, so a request or a gossip
 * round always reads one consistent set of settings.
 */
public record NetConfig(NodeSettings node, List<SeedPeer> peers, GossipSettings gossip, RoutingSettings routing, AccessSettings access) {

    /** No folder, or a folder that switches nothing on: a single server, exactly as without Drishti*net. */
    public static final NetConfig OFF = new NetConfig(NodeSettings.OFF, List.of(), GossipSettings.DEFAULTS, RoutingSettings.DEFAULTS, AccessSettings.OPEN);

    public NetConfig {
        peers = peers == null ? List.of() : List.copyOf(peers);
    }

    public boolean enabled() {
        return node.enabled();
    }

    public NetConfig withNode(NodeSettings n) {
        return new NetConfig(n, peers, gossip, routing, access);
    }

    public NetConfig withPeers(List<SeedPeer> p) {
        return new NetConfig(node, p, gossip, routing, access);
    }

    public NetConfig withGossip(GossipSettings g) {
        return new NetConfig(node, peers, g, routing, access);
    }

    public NetConfig withRouting(RoutingSettings r) {
        return new NetConfig(node, peers, gossip, r, access);
    }

    public NetConfig withAccess(AccessSettings a) {
        return new NetConfig(node, peers, gossip, routing, a);
    }

    /** The seed urls, in file-name order. */
    public List<String> seedUrls() {
        return peers.stream().map(SeedPeer::url).filter(u -> !u.isBlank()).toList();
    }
}
