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
package com.ash.drishti.plugin.delta;

import io.delta.kernel.engine.Engine;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A lake whose tables are named in the connector's settings rather than after their kinds: {@code table: {trade: trade_endur}}
 * (flattened {@code table.trade}) reads kind {@code trade} from the folder {@code trade_endur} under the domain. Several connectors
 * then serve one kind from tables side by side, one per source system ({@code trade_endur}, {@code trade_calypso}, {@code trade_murex}).
 * Only the named kinds are this lake's tables; a kind without a name keeps its own folder.
 */
record NamedTables(LakeStore lake, Map<String, String> names) implements LakeStore {

    /** The {@code table.<kind>} settings: kind to folder. */
    static Map<String, String> of(Map<String, String> settings) {
        Map<String, String> out = new LinkedHashMap<>();
        settings.forEach((k, v) -> {
            if (k.startsWith("table.") && v != null && !v.isBlank()) {
                String folder = v.trim();
                if (folder.contains("/") || folder.contains("\\") || folder.contains("..")) {
                    throw new IllegalArgumentException(k + ": a table is a folder name under the domain, not a path: " + folder);
                }
                out.put(k.substring("table.".length()), folder);
            }
        });
        return out;
    }

    @Override
    public String table(String kind) {
        return lake.table(names.getOrDefault(kind, kind));
    }

    @Override
    public List<String> tables() throws IOException {
        List<String> folders = lake.tables();
        return names.entrySet().stream().filter(e -> folders.contains(e.getValue())).map(Map.Entry::getKey).sorted().toList();
    }

    @Override
    public boolean reachable() {
        return lake.reachable();
    }

    @Override
    public String describe() {
        return lake.describe() + " " + names;
    }

    @Override
    public Engine engine() {
        return lake.engine();
    }

    @Override
    public String engineName() {
        return lake.engineName();
    }

    @Override
    public void close() {
        lake.close();
    }

    @Override
    public com.ash.drishti.api.tls.TlsMaterial tls() {
        return lake.tls();
    }

    @Override
    public com.ash.drishti.api.proxy.ProxySettings proxy() {
        return lake.proxy();
    }
}
