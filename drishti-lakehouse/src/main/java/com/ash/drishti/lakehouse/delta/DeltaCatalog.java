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
package com.ash.drishti.lakehouse.delta;

import com.ash.drishti.deltalake.NativeEngine;
import com.ash.drishti.iceberg.Table;
import com.ash.drishti.iceberg.catalog.Catalog;
import com.ash.drishti.iceberg.io.FileAccess;
import com.ash.drishti.iceberg.meta.PartitionSpec;
import com.ash.drishti.iceberg.meta.SortOrder;
import com.ash.drishti.iceberg.types.Schema;
import com.ash.drishti.lakeio.LocalPaths;
import com.ash.drishti.lakeio.Storage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Delta tables found by folder under a base (the connector's {@code root}, then the namespace's levels as folders): a table is a folder
 * with a {@code _delta_log}. Each is read as Iceberg through {@link DeltaTableOps}. Read-only. Thread-safe.
 */
public final class DeltaCatalog implements Catalog {

    private final String base;
    private final NativeEngine engine;
    private final FileAccess io;
    private final int history;
    private final List<DeltaTableOps> opened = new CopyOnWriteArrayList<>();

    /**
     * @param base the folder the tables are in (local, {@code file:} or {@code s3://})
     * @param history the versions kept as snapshots per table
     */
    public DeltaCatalog(String base, NativeEngine engine, FileAccess io, int history) {
        this.base = base.replaceAll("/+$", "");
        this.engine = engine;
        this.io = io;
        this.history = history;
    }

    private String path(String name) {
        if (name.contains("..") || name.startsWith("/") || name.contains("\\")) {
            throw new IllegalArgumentException("a Delta table is named by its folder under the root, not a path: " + name);
        }
        return base + "/" + name;
    }

    private boolean isTable(String path) {
        try {
            return engine.storage(path).isDirectory(path + "/_delta_log");
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public Optional<Table> load(String name) throws IOException {
        String p = path(name);
        if (!isTable(p)) {
            return Optional.empty();
        }
        DeltaTableOps ops = new DeltaTableOps(name, p, engine, io, history);
        opened.add(ops);
        return Optional.of(Table.load(name, ops));
    }

    @Override
    public String missing(String name) {
        return "no table " + name + ": " + describePath(path(name)) + "/_delta_log is not there (a Delta table is a folder with a _delta_log)";
    }

    @Override
    public List<String> tables() throws IOException {
        Storage s = engine.storage(base);
        List<String> out = new ArrayList<>();
        for (String d : s.directories(base)) {
            if (isTable(base + "/" + d)) {
                out.add(d);
            }
        }
        return out;
    }

    @Override
    public Table create(String name, Schema schema, PartitionSpec spec, SortOrder order, Map<String, String> properties) {
        throw new UnsupportedOperationException("Delta tables are read, never created, by this connector");
    }

    @Override
    public boolean reachable() {
        try {
            return engine.storage(base).isDirectory(base);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public String describe() {
        return describePath(base) + " (Delta)";
    }

    private static String describePath(String p) {
        return LocalPaths.isLocal(p) ? LocalPaths.toLocal(p, LocalPaths.WINDOWS) : p;
    }

    @Override
    public void close() {
        opened.forEach(DeltaTableOps::close);
        engine.close();
        io.close();
    }
}
