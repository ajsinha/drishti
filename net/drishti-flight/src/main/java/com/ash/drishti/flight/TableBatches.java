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
package com.ash.drishti.flight;

import com.ash.drishti.compute.arrow.ArrowIpc;
import com.ash.drishti.compute.arrow.ArrowTable;
import java.util.Map;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Schema;

/**
 * A snapshot source over a table already in memory (a model answer, a pivot view, a measure's vector): batches of at most {@code batchRows}
 * rows, filled with the same codec as the compute transport ({@link ArrowIpc#fill}). An empty table sends its schema and no batch.
 */
public final class TableBatches implements FlightBackend.BatchSource {

    private final ArrowTable table;
    private final Schema schema;
    private final int batchRows;
    private int at;

    public TableBatches(ArrowTable table, Map<String, String> metadata, int batchRows) {
        this.table = table;
        this.schema = ArrowIpc.schema(table, metadata == null ? Map.of() : metadata);
        this.batchRows = Math.max(1, batchRows);
    }

    @Override
    public Schema schema() {
        return schema;
    }

    @Override
    public boolean next(VectorSchemaRoot root) {
        if (at >= table.rows()) {
            return false;
        }
        int to = Math.min(table.rows(), at + batchRows);
        ArrowIpc.fill(root, table, at, to);
        at = to;
        return true;
    }
}
