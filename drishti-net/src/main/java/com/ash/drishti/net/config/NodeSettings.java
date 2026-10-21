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
import java.util.Map;

/**
 * This node ({@code config/net/node.yaml}). The cluster key is only ever an environment reference in the file
 * ({@code secret: ${DRISHTI_NET_SECRET}}); what is held here is its resolved value, never written back and never shown.
 *
 * @param enabled false (the default): the server is a single server exactly as without Drishti*net
 * @param nodeId the configured id, or empty to use the one generated once and kept in {@code <data dir>/net/node-id}
 * @param advertiseUrl the address peers and consoles call this node at
 * @param tags this node's tags ({@code region:emea}, {@code tier:risk}), for nodes per role
 * @param peerOnlyPacks packs loaded and served to peers but hidden from this node's own users
 * @param secret the cluster key (resolved), or empty
 * @param previousSecret the previous cluster key, accepted (never used to sign) during a rollover, or empty
 * @param tls mutual TLS settings ({@code tls.keystore.path}, ...), flattened; empty for none
 * @param gossipPort the port of the node's own gossip connector (2.6.7): {@link #GOSSIP_AUTO} (0, the default: a free port chosen when the
 *     node joins, advertised through gossip), {@link #GOSSIP_OFF} (-1: gossip on the server's port, as before 2.6.7) or a port number
 */
public record NodeSettings(boolean enabled, String nodeId, String advertiseUrl, List<String> tags, List<String> peerOnlyPacks, String secret,
        String previousSecret, Map<String, String> tls, int gossipPort) {

    /** {@code gossip-port: auto}: a free port chosen when the node joins. */
    public static final int GOSSIP_AUTO = 0;
    /** {@code gossip-port: off}: gossip on the server's own port. */
    public static final int GOSSIP_OFF = -1;

    public static final NodeSettings OFF = new NodeSettings(false, "", "", List.of(), List.of(), "", "", Map.of());

    public NodeSettings(boolean enabled, String nodeId, String advertiseUrl, List<String> tags, List<String> peerOnlyPacks, String secret,
            String previousSecret, Map<String, String> tls) {
        this(enabled, nodeId, advertiseUrl, tags, peerOnlyPacks, secret, previousSecret, tls, GOSSIP_AUTO);
    }

    public NodeSettings {
        nodeId = nodeId == null ? "" : nodeId.trim();
        advertiseUrl = advertiseUrl == null ? "" : advertiseUrl.trim().replaceAll("/+$", "");
        tags = tags == null ? List.of() : List.copyOf(tags);
        peerOnlyPacks = peerOnlyPacks == null ? List.of() : List.copyOf(peerOnlyPacks);
        secret = secret == null ? "" : secret;
        previousSecret = previousSecret == null ? "" : previousSecret;
        tls = tls == null ? Map.of() : Map.copyOf(tls);
        gossipPort = gossipPort < GOSSIP_OFF || gossipPort > 65535 ? GOSSIP_AUTO : gossipPort;
    }

    /** Never prints the keys. */
    @Override
    public String toString() {
        return "NodeSettings[enabled=" + enabled + ", nodeId=" + nodeId + ", advertiseUrl=" + advertiseUrl + ", tags=" + tags
                + ", peerOnlyPacks=" + peerOnlyPacks + ", secret=" + (secret.isEmpty() ? "unset" : "set")
                + ", previousSecret=" + (previousSecret.isEmpty() ? "unset" : "set") + ", tls=" + tls.keySet() + ", gossipPort=" + gossipPort + "]";
    }
}
