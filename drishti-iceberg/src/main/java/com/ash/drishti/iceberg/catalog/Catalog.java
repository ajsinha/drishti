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
import com.ash.drishti.iceberg.meta.PartitionSpec;
import com.ash.drishti.iceberg.meta.SortOrder;
import com.ash.drishti.iceberg.types.Schema;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Where a domain's tables are, by name: a folder of path-based tables, one table named by its metadata file, or a
 * namespace of a REST catalog. Thread-safe.
 */
public interface Catalog extends AutoCloseable {

    /** The table, or empty when it does not exist. */
    Optional<Table> load(String name) throws IOException;

    /** The tables there are, sorted. */
    List<String> tables() throws IOException;

    /**
     * Creates a table (format version 2 unless the properties say {@code format-version}); fails with
     * {@link TableExists} when another writer created it first.
     */
    Table create(String name, Schema schema, PartitionSpec spec, SortOrder order, Map<String, String> properties) throws IOException;

    /** Whether the folder or namespace can be reached (for health). */
    boolean reachable();

    /** For messages: where this is. */
    String describe();

    @Override
    default void close() {
    }

    /** A table of that name exists already. */
    final class TableExists extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public TableExists(String message) {
            super(message);
        }
    }
}
