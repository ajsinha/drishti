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
package com.ash.drishti.identity.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The cluster check (docs/architecture/DATA_ARCHITECTURE.md › The content store: user content every node reaches, section 6): each node writes {@code nodes/<node>.json} (its store root as it sees it)
 * at start and warns when another node's record names a different root: the same share mounted at different paths, or two configurations
 * pointing at one store. Every node of a cluster must use the same store configuration.
 */
public final class ContentStoreNodes {

    private static final Logger LOG = LoggerFactory.getLogger(ContentStoreNodes.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private ContentStoreNodes() {}

    /** Records this node and returns the other nodes whose root differs (each as {@code node root}); never fails the start. */
    public static List<String> announce(ContentStore store, String node) {
        List<String> differ = new ArrayList<>();
        String safe = node.replaceAll("[^A-Za-z0-9._-]", "_");
        try {
            String me = JSON.createObjectNode().put("node", node).put("root", store.root()).put("at", System.currentTimeMillis()).toString();
            store.putLatest("nodes/" + safe + ".json", me.getBytes(StandardCharsets.UTF_8));
            for (String k : store.list("nodes/")) {
                if (k.equals("nodes/" + safe + ".json")) {
                    continue;
                }
                store.get(k).ifPresent(it -> {
                    try {
                        JsonNode n = JSON.readTree(it.bytes());
                        if (!store.root().equals(n.path("root").asText())) {
                            differ.add(n.path("node").asText() + " " + n.path("root").asText());
                        }
                    } catch (java.io.IOException e) {
                        // not a node record
                    }
                });
            }
        } catch (RuntimeException e) {
            LOG.warn("content store: could not record this node ({}): {}", node, e.getMessage());
        }
        if (!differ.isEmpty()) {
            LOG.warn("content store roots differ: this node {} uses {}, others {}; every node of a cluster must use the same store configuration "
                    + "(docs/architecture/DATA_ARCHITECTURE.md › The content store: user content every node reaches, section 6)", node, store.root(), differ);
        }
        return differ;
    }

    /** This machine's name and port, when no Drishti*net node id is set. */
    public static String localName(String port) {
        String host;
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException e) {
            host = "local";
        }
        return port == null || port.isBlank() ? host : host + "-" + port;
    }
}
