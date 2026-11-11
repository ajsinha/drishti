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
package com.ash.drishti.server.connectors;

import com.ash.drishti.packs.ConnectorFiles;
import com.ash.drishti.server.net.DigestService;
import com.ash.drishti.server.net.RunCluster;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Connector files are per node: a save on one node changes only that node's file. The digest carries each node's files as
 * {@code name:etag,...} (the service {@code connector-files}), and Admin, Health warns when a connector present on two nodes differs.
 * A connector only some nodes have is not a difference: nodes can run different packs.
 */
public final class ConnectorFileDrift {

    public static final String SERVICE = "connector-files";

    private final ConnectorFiles files;
    private final Supplier<DigestService> digests;
    private final Supplier<RunCluster> cluster;

    public ConnectorFileDrift(ConnectorFiles files, Supplier<DigestService> digests, Supplier<RunCluster> cluster) {
        this.files = files;
        this.digests = digests;
        this.cluster = cluster;
    }

    /** This node's connector files as the digest carries them. */
    public String wire() {
        StringBuilder s = new StringBuilder();
        for (String n : files.names()) {
            String text = files.text(n);
            if (text != null) {
                s.append(s.isEmpty() ? "" : ",").append(n).append(':').append(ConnectorFiles.etag(text));
            }
        }
        return s.toString();
    }

    static Map<String, String> parse(String wire) {
        Map<String, String> out = new LinkedHashMap<>();
        if (wire != null) {
            for (String part : wire.split(",")) {
                int c = part.indexOf(':');
                if (c > 0) {
                    out.put(part.substring(0, c), part.substring(c + 1));
                }
            }
        }
        return out;
    }

    /** The connectors whose file on a live peer differs from this node's: name and the peers that differ. */
    public List<Map<String, Object>> differing() {
        DigestService d = digests.get();
        RunCluster c = cluster.get();
        if (d == null || c == null || !c.on()) {
            return List.of();
        }
        return compare(parse(wire()), d.peers().entrySet().stream().filter(e -> c.alive(e.getKey()))
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> parse(e.getValue().services().get(SERVICE)))));
    }

    static List<Map<String, Object>> compare(Map<String, String> mine, Map<String, Map<String, String>> peers) {
        Map<String, List<String>> diff = new TreeMap<>();
        new TreeMap<>(peers).forEach((node, theirs) -> theirs.forEach((name, etag) -> {
            String own = mine.get(name);
            if (own != null && !own.equals(etag)) {
                diff.computeIfAbsent(name, k -> new ArrayList<>()).add(node);
            }
        }));
        List<Map<String, Object>> out = new ArrayList<>();
        diff.forEach((name, nodes) -> out.add(Map.of("name", name, "nodes", nodes)));
        return out;
    }
}
