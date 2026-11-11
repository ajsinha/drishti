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
package com.ash.drishti.iceberg.catalog;

import com.ash.drishti.iceberg.Table;
import com.ash.drishti.iceberg.io.FileAccess;
import com.ash.drishti.iceberg.meta.Bridge;
import com.ash.drishti.iceberg.meta.PartitionSpec;
import com.ash.drishti.iceberg.meta.SortOrder;
import com.ash.drishti.iceberg.types.Schema;
import com.ash.drishti.lakeio.Storage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.exceptions.CommitFailedException;

/**
 * Path-based tables under one folder ({@code catalog: hadoop}, the name kept from Iceberg's {@code HadoopTables}; no Hadoop is involved):
 * table {@code t} is the folder {@code <base>/t} with its {@code metadata/} folder. Local disk (Windows paths included) or S3.
 */
public final class PathCatalog implements Catalog {

    private final String base;
    private final FileAccess io;

    /** @param base the folder, canonical ({@code file:/C:/lake/desk}, {@code s3a://bucket/lake/desk}) */
    public PathCatalog(String base, FileAccess io) {
        this.base = base.replaceAll("/+$", "");
        this.io = io;
    }

    public String base() {
        return base;
    }

    public FileAccess io() {
        return io;
    }

    private PathTableOps ops(String name) {
        return new PathTableOps(base + "/" + name, io);
    }

    @Override
    public Optional<Table> load(String name) throws IOException {
        PathTableOps ops = ops(name);
        if (!ops.exists()) {
            return Optional.empty();
        }
        return Optional.of(new Table(name, new BaseTable(ops, name, r -> { }), null));
    }

    @Override
    public String missing(String name) {
        String meta = base + "/" + name + "/metadata";
        String held;
        try {
            List<String> files = new ArrayList<>();
            for (var f : io.storage(io.resolve(meta)).listFrom(io.resolve(meta + "/!"))) {
                files.add(f.path().substring(f.path().lastIndexOf('/') + 1));
            }
            held = files.isEmpty() ? "the folder is empty"
                    : "it holds " + String.join(", ", files.subList(0, Math.min(3, files.size()))) + (files.size() > 3 ? " and " + (files.size() - 3) + " more" : "");
        } catch (java.io.FileNotFoundException e) {
            held = "the folder does not exist";
        } catch (IOException | RuntimeException e) {
            held = "it cannot be listed: " + e.getMessage();
        }
        return "no table " + name + ": looked in " + meta + " for version-hint.text, v<N>.metadata.json and a catalog's"
                + " NNNNN-<uuid>.metadata.json (" + held + ")";
    }

    @Override
    public List<String> tables() throws IOException {
        Storage s = io.storage(io.resolve(base));
        List<String> out = new ArrayList<>();
        for (String dir : s.directories(io.resolve(base))) {
            if (s.isDirectory(io.resolve(base + "/" + dir + "/metadata")) && ops(dir).exists()) {
                out.add(dir);
            }
        }
        return out;
    }

    @Override
    public Table create(String name, Schema schema, PartitionSpec spec, SortOrder order, Map<String, String> properties) throws IOException {
        PathTableOps ops = ops(name);
        org.apache.iceberg.Schema s = Bridge.iceberg(schema);
        TableMetadata md = TableMetadata.newTableMetadata(s, Bridge.iceberg(spec, schema, s), Bridge.iceberg(order, schema, s), ops.location(), properties);
        try {
            ops.commit(null, md);
        } catch (CommitFailedException e) {
            throw new TableExists("table " + name + " was created at " + ops.location() + " by another writer");
        }
        return new Table(name, new BaseTable(ops, name, r -> { }), null);
    }

    @Override
    public boolean reachable() {
        try {
            return io.storage(io.resolve(base)).isDirectory(io.resolve(base));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public String describe() {
        return base;
    }

    @Override
    public void close() {
        io.close();
    }
}
