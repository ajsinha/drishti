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
import com.ash.drishti.iceberg.io.DrishtiFileIO;
import com.ash.drishti.iceberg.io.FileAccess;
import com.ash.drishti.iceberg.meta.PartitionSpec;
import com.ash.drishti.iceberg.meta.SortOrder;
import com.ash.drishti.iceberg.types.Schema;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.StaticTableOperations;
import org.apache.iceberg.exceptions.NotFoundException;

/** Tables named by their metadata files ({@code metadata-location.<table>}): someone else's tables, read-only; a refresh finds nothing new. */
public final class StaticCatalog implements Catalog {

    private final Map<String, String> files;
    private final FileAccess io;

    public StaticCatalog(Map<String, String> files, FileAccess io) {
        this.files = Map.copyOf(files);
        this.io = io;
    }

    @Override
    public Optional<Table> load(String name) throws IOException {
        String f = files.get(name);
        if (f == null) {
            return Optional.empty();
        }
        try {
            StaticTableOperations ops = new StaticTableOperations(f, new DrishtiFileIO(io));
            ops.current();
            return Optional.of(new Table(name, new BaseTable(ops, name, r -> { }), null));
        } catch (NotFoundException e) {
            throw new java.io.FileNotFoundException("no metadata file " + f);
        }
    }

    @Override
    public List<String> tables() {
        return files.keySet().stream().sorted().toList();
    }

    @Override
    public Table create(String name, Schema schema, PartitionSpec spec, SortOrder order, Map<String, String> properties) {
        throw new UnsupportedOperationException("tables named by their metadata files are read-only");
    }

    @Override
    public boolean reachable() {
        return files.values().stream().allMatch(f -> {
            try {
                String p = io.resolve(f);
                io.storage(p).status(p);
                return true;
            } catch (IOException | RuntimeException e) {
                return false;
            }
        });
    }

    @Override
    public String describe() {
        return "metadata files " + files.values();
    }

    @Override
    public void close() {
        io.close();
    }
}
