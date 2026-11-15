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
 * Everything {@code config/net/} and the settings file's cluster sections say, as one immutable snapshot: swapped whole when a file
 * changes, so a request or a membership change always reads one consistent set of settings.
 *
 * @param node {@code node.yaml} (with the keys it leaves out taken from {@code [net.node]} and {@code [net.cluster]})
 * @param routing {@code routing.yaml}
 * @param access {@code access.yaml}
 * @param cluster the settings file's {@code [net.*]} sections (timings, agreement, TLS, engine calls)
 */
public record NetConfig(NodeSettings node, RoutingSettings routing, AccessSettings access, ClusterSettings cluster) {

    /** No folder, or a folder that switches nothing on: a single server, exactly as without Drishti*net. */
    public static final NetConfig OFF = new NetConfig(NodeSettings.OFF, RoutingSettings.DEFAULTS, AccessSettings.OPEN, ClusterSettings.DEFAULTS);

    public NetConfig {
        cluster = cluster == null ? ClusterSettings.DEFAULTS : cluster;
    }

    public boolean enabled() {
        return node.enabled();
    }

    public NetConfig withNode(NodeSettings n) {
        return new NetConfig(n, routing, access, cluster);
    }

    public NetConfig withRouting(RoutingSettings r) {
        return new NetConfig(node, r, access, cluster);
    }

    public NetConfig withAccess(AccessSettings a) {
        return new NetConfig(node, routing, a, cluster);
    }

    /** The seeds ({@code host:port} of cluster ports), as node.yaml or {@code [net.cluster] seeds} names them. */
    public List<String> seeds() {
        return node.seeds();
    }
}
