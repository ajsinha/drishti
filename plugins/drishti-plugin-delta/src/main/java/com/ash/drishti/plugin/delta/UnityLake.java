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

import com.ash.drishti.deltalake.NativeEngine;
import com.ash.drishti.lakehouse.unity.UnityTables;
import io.delta.kernel.engine.Engine;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A lake whose tables are named in Unity Catalog rather than by folder ({@code unity.host}; {@code table.<kind>: catalog.schema.table},
 * or {@code schema.table} / {@code table} with {@code unity.catalog} and {@code unity.schema}): each table's storage location and
 * temporary credentials come from Unity Catalog's credential vending, and the table is read with Delta Kernel at that location by an
 * engine of its own whose S3 keys renew themselves before they expire. Unity Catalog's grants decide what may be read. The native engine
 * only; tables in S3 (or on local disk, the open-source server's). A table Unity refuses is left out, its reason (in words) in health.
 */
final class UnityLake implements LakeStore {

    private record Opened(String location, NativeEngine engine) {}

    private final UnityTables unity;
    private final Map<String, String> names;
    private final Map<String, String> settings;
    private final NativeEngine base;
    private final Map<String, Opened> opened = new ConcurrentHashMap<>();
    private final Map<String, String> refused = new ConcurrentHashMap<>();
    private final Map<String, Long> askedAt = new ConcurrentHashMap<>();

    private UnityLake(UnityTables unity, Map<String, String> names, Map<String, String> settings) {
        this.unity = unity;
        this.names = Map.copyOf(names);
        this.settings = Map.copyOf(settings);
        this.base = NativeEngine.create(settings);
    }

    static UnityLake open(Map<String, String> settings, EngineKind engine) {
        if (engine == EngineKind.HADOOP) {
            throw new IllegalArgumentException("unity.host: tables named in Unity Catalog are read by the native engine (set engine: native)");
        }
        Map<String, String> names = new TreeMap<>();
        settings.forEach((k, v) -> {
            if (k.startsWith("table.") && v != null && !v.isBlank()) {
                names.put(k.substring("table.".length()), v.trim());
            }
        });
        if (names.isEmpty()) {
            throw new IllegalArgumentException("unity.host is set but no table.<kind> names a Unity Catalog table (table.trade: main.trading.trade_endur)");
        }
        UnityLake lake = new UnityLake(UnityTables.from(settings), names, settings);
        names.keySet().forEach(lake::resolve);
        return lake;
    }

    /** The kind's table found in Unity Catalog (once; after a refusal, again a minute later at the soonest), or null with the reason kept. */
    private Opened resolve(String kind) {
        Opened o = opened.get(kind);
        if (o != null) {
            return o;
        }
        long now = System.currentTimeMillis();
        if (refused.containsKey(kind) && now - askedAt.getOrDefault(kind, 0L) < 60_000) {
            return null;                                         // refused a moment ago: Unity is not asked on every refresh
        }
        askedAt.put(kind, now);
        String name = names.get(kind);
        if (name == null) {
            refused.put(kind, kind + ": no table." + kind + " names its Unity Catalog table");
            return null;
        }
        try {
            UnityTables.Resolved r = unity.resolve(name);
            Opened n = new Opened(r.location(), NativeEngine.create(r.s3(), settings));
            opened.put(kind, n);
            refused.remove(kind);
            return n;
        } catch (IOException | RuntimeException e) {
            refused.put(kind, kind + ": " + e.getMessage());
            return null;
        }
    }

    private Opened require(String kind) {
        Opened o = resolve(kind);
        if (o == null) {
            throw new IllegalStateException(refused.get(kind));
        }
        return o;
    }

    @Override
    public String table(String kind) {
        return require(kind).location();
    }

    @Override
    public Engine engine(String kind) {
        return require(kind).engine();
    }

    @Override
    public List<String> tables() {
        return names.keySet().stream().filter(k -> resolve(k) != null).sorted().toList();
    }

    @Override
    public boolean reachable() {
        return !opened.isEmpty() || names.keySet().stream().anyMatch(k -> resolve(k) != null);
    }

    @Override
    public String describe() {
        return "Unity Catalog " + unity.client().host() + (refused.isEmpty() ? "" : " (" + String.join("; ", new TreeMap<>(refused).values()) + ")");
    }

    @Override
    public Engine engine() {
        return base;
    }

    @Override
    public String engineName() {
        return EngineKind.NATIVE.label() + ", Unity Catalog";
    }

    @Override
    public void close() {
        opened.values().forEach(o -> o.engine().close());
        base.close();
    }
}
