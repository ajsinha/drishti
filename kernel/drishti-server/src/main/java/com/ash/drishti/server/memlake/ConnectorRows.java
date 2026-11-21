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
package com.ash.drishti.server.memlake;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.lake.spi.RowSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Drishti's connectors as Drishti Lake's {@link RowSource}: what fills a memory table from a connector ({@code source.connector}),
 * marks a connector's state for its replenish job, and names the memory-only connectors ({@code Retention}).
 */
final class ConnectorRows implements RowSource {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>> MAP = new com.fasterxml.jackson.core.type.TypeReference<>() {};

    private final SourceRegistry registry;

    ConnectorRows(SourceRegistry registry) {
        this.registry = registry;
    }

    @Override
    public List<Map<String, Object>> documents(String connector, String kind) throws Exception {
        SourcePlugin plugin = registry.connector(connector, Duration.ofSeconds(30))
                .orElseThrow(() -> new IllegalStateException("connector " + connector + " is not running"));
        if (plugin.memoryOnly()) {
            throw new IllegalStateException("connector " + connector + " is itself held in memory; a memory table is filled from its source, not from another memory table");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        boolean listed = plugin.documents(kind, AsOf.LATEST, d -> {
            if (!d.deleted() && d.data() != null) {
                Object v = d.data().unwrap();
                if (v instanceof Map<?, ?>) {
                    out.add(JSON.convertValue(v, MAP));
                }
            }
        });
        if (!listed) {
            throw new IllegalStateException("connector " + connector + " cannot list its documents of " + kind
                    + "; fill the table from a file or a URL of the feed instead");
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> fetch(String connector, String kind, List<String> ids) throws Exception {
        SourcePlugin plugin = registry.connector(connector, Duration.ofSeconds(30))
                .orElseThrow(() -> new IllegalStateException("connector " + connector + " is not running"));
        List<Map<String, Object>> out = new ArrayList<>();
        int failed = 0;
        for (String id : ids) {
            try {
                var doc = plugin.fetch(new EntityRef(kind, id));
                if (doc.isPresent() && !doc.get().deleted() && doc.get().data() != null && doc.get().data().unwrap() instanceof Map<?, ?> m) {
                    out.add(JSON.convertValue(m, MAP));
                }
            } catch (Exception e) {
                failed++;                                        // one entity's feed failing does not stop the others
            }
        }
        if (!ids.isEmpty() && out.isEmpty() && failed > 0) {
            throw new IllegalStateException("connector " + connector + " answered none of " + ids.size() + " ids (" + failed + " failed)");
        }
        return out;
    }

    @Override
    public String mark(String connector, String kind) {
        try {
            SourcePlugin plugin = registry.connector(connector).orElse(null);
            if (plugin == null) {
                return null;
            }
            Instant at = plugin.lastUpdate();
            String v = kind == null ? "" : plugin.dataVersion(kind).orElse("");
            return (at == null ? "" : at.toString()) + "|" + v;
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public List<Served> memoryOnly() {
        List<Served> out = new ArrayList<>();
        for (String name : registry.connectorStatuses().keySet()) {
            SourcePlugin p = registry.connector(name).orElse(null);
            if (p == null || !p.memoryOnly()) {
                continue;
            }
            var applied = registry.applied(name);
            String root = applied.map(c -> c.settings().getOrDefault("root", "")).orElse("");
            out.add(new Served(name, root, applied.map(c -> List.copyOf(c.kinds())).orElse(List.of())));
        }
        return out;
    }
}
