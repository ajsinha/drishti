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
package com.ash.drishti.lakehouse.unity;

import com.ash.drishti.deltalake.NativeEngine;
import com.ash.drishti.iceberg.Table;
import com.ash.drishti.iceberg.catalog.Catalog;
import com.ash.drishti.iceberg.io.FileAccess;
import com.ash.drishti.iceberg.io.Relocation;
import com.ash.drishti.iceberg.meta.PartitionSpec;
import com.ash.drishti.iceberg.meta.SortOrder;
import com.ash.drishti.iceberg.types.Schema;
import com.ash.drishti.lakehouse.delta.DeltaTableOps;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Plain Delta tables of one Unity Catalog schema ({@code namespace} = {@code catalog.schema}, or {@code schema} with
 * {@code unity.catalog}), read as Iceberg: each table's storage location and vended credentials from Unity ({@link UnityTables}), then its
 * Delta log and files read there ({@link DeltaTableOps}), each table with its own engine and its own renewing credentials. Read-only.
 * Thread-safe.
 */
public final class UnityDeltaCatalog implements Catalog {

    private record Opened(DeltaTableOps ops, NativeEngine engine, FileAccess io) {}

    private final UnityTables unity;
    private final List<String> namespace;
    private final Map<String, String> settings;
    private final int history;
    private final List<Opened> opened = new CopyOnWriteArrayList<>();
    private final Map<String, String> why = new java.util.concurrent.ConcurrentHashMap<>();

    public UnityDeltaCatalog(UnityTables unity, List<String> namespace, Map<String, String> settings, int history) {
        this.unity = unity;
        this.namespace = List.copyOf(namespace);
        this.settings = Map.copyOf(settings);
        this.history = history;
    }

    private String name(String table) {
        return namespace.isEmpty() ? table : String.join(".", namespace) + "." + table;
    }

    @Override
    public Optional<Table> load(String table) throws IOException {
        String name = name(table);
        UnityTables.Resolved r;
        try {
            r = unity.resolve(name);
        } catch (UnityException e) {
            why.put(table, e.getMessage());
            if (e.reason() == UnityException.Reason.NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
        why.remove(table);
        NativeEngine engine = NativeEngine.create(r.s3(), settings);
        FileAccess io = new FileAccess(r.s3(), Relocation.none(), settings.get("lz4-decoder"));
        DeltaTableOps ops = new DeltaTableOps(r.info().fullName(), r.location(), engine, io, history);
        opened.add(new Opened(ops, engine, io));
        return Optional.of(Table.load(r.info().fullName(), ops));
    }

    @Override
    public String missing(String table) {
        return why.getOrDefault(table, "no table " + table + " in Unity Catalog " + unity.client().host());
    }

    @Override
    public List<String> tables() throws IOException {
        if (namespace.size() == 2) {
            return unity.tables(namespace.get(0), namespace.get(1));
        }
        String full = unity.fullName((namespace.isEmpty() ? "" : namespace.get(0) + ".") + "x");
        String[] p = full.split("\\.");
        return unity.tables(p[0], p[1]);
    }

    @Override
    public Table create(String name, Schema schema, PartitionSpec spec, SortOrder order, Map<String, String> properties) {
        throw new UnsupportedOperationException("tables in Unity Catalog are read, never created, by this connector");
    }

    @Override
    public boolean reachable() {
        try {
            tables();
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public String describe() {
        return "Unity Catalog " + unity.client().host() + (namespace.isEmpty() ? "" : " " + String.join(".", namespace));
    }

    @Override
    public void close() {
        for (Opened o : opened) {
            o.ops().close();
            o.engine().close();
            o.io().close();
        }
    }
}
