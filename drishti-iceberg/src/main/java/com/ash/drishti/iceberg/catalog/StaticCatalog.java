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
import com.ash.drishti.iceberg.meta.PartitionSpec;
import com.ash.drishti.iceberg.meta.SortOrder;
import com.ash.drishti.iceberg.types.Schema;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Tables named by their metadata files ({@code metadata-location}, or one per kind): read-only. */
public final class StaticCatalog implements Catalog {

    private final Map<String, String> files;
    private final FileAccess io;

    /** @param files table name to its metadata file */
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
            return Optional.of(Table.load(name, new StaticTableOps(f, io)));
        } catch (NoSuchTable e) {
            return Optional.empty();
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
        for (String f : files.values()) {
            try {
                String p = io.resolve(f);
                io.storage(p).status(p);
            } catch (FileNotFoundException e) {
                return false;
            } catch (IOException | RuntimeException e) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String describe() {
        return files.size() == 1 ? files.values().iterator().next() : files.size() + " metadata files";
    }

    @Override
    public void close() {
        io.close();
    }
}
