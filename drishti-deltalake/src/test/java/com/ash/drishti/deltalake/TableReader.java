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
package com.ash.drishti.deltalake;

import io.delta.kernel.Scan;
import io.delta.kernel.Snapshot;
import io.delta.kernel.Table;
import io.delta.kernel.data.ColumnarBatch;
import io.delta.kernel.data.FilteredColumnarBatch;
import io.delta.kernel.data.Row;
import io.delta.kernel.engine.Engine;
import io.delta.kernel.engine.FileReadResult;
import io.delta.kernel.expressions.Column;
import io.delta.kernel.expressions.Literal;
import io.delta.kernel.expressions.Predicate;
import io.delta.kernel.internal.InternalScanFileUtils;
import io.delta.kernel.internal.data.ScanStateRow;
import io.delta.kernel.internal.util.Utils;
import io.delta.kernel.types.StructType;
import io.delta.kernel.utils.CloseableIterator;
import io.delta.kernel.utils.FileStatus;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Reads a Delta table with any engine the way the Delta connector does (scan files, then each file's rows with
 * deletion vectors applied), returning plain Java values so two engines' answers compare with {@code equals}.
 */
final class TableReader {

    private TableReader() {}

    /** {@code business_date|id} to the row's {@code doc}, for the latest version or {@code version}. */
    static Map<String, String> rows(Engine engine, String table, Long version) throws IOException {
        Table t = Table.forPath(engine, table);
        Snapshot s = version == null ? t.getLatestSnapshot(engine) : t.getSnapshotAsOfVersion(engine, version);
        Scan scan = s.getScanBuilder().build();
        Row state = scan.getScanState(engine);
        StructType physical = ScanStateRow.getPhysicalDataReadSchema(state);
        Map<String, String> out = new TreeMap<>();
        try (CloseableIterator<FilteredColumnarBatch> files = scan.getScanFiles(engine)) {
            while (files.hasNext()) {
                try (CloseableIterator<Row> fileRows = files.next().getRows()) {
                    while (fileRows.hasNext()) {
                        Row file = fileRows.next();
                        String date = InternalScanFileUtils.getPartitionValues(file).get("business_date");
                        FileStatus fs = InternalScanFileUtils.getAddFileStatus(file);
                        try (CloseableIterator<FileReadResult> raw = engine.getParquetHandler()
                                .readParquetFiles(Utils.singletonCloseableIterator(fs), physical, Optional.empty());
                             CloseableIterator<FilteredColumnarBatch> data = Scan.transformPhysicalData(engine, state, file,
                                     raw.map(FileReadResult::getData))) {
                            while (data.hasNext()) {
                                FilteredColumnarBatch b = data.next();
                                int id = b.getData().getSchema().indexOf("id");
                                int doc = b.getData().getSchema().indexOf("doc");
                                try (CloseableIterator<Row> rows = b.getRows()) {
                                    while (rows.hasNext()) {
                                        Row r = rows.next();
                                        out.put(date + "|" + r.getString(id), r.isNullAt(doc) ? null : r.getString(doc));
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return out;
    }

    /** The data files of the latest version, as Kernel lists them. */
    static List<FileStatus> files(Engine engine, String table) throws IOException {
        Scan scan = Table.forPath(engine, table).getLatestSnapshot(engine).getScanBuilder().build();
        List<FileStatus> out = new ArrayList<>();
        try (CloseableIterator<FilteredColumnarBatch> files = scan.getScanFiles(engine)) {
            while (files.hasNext()) {
                try (CloseableIterator<Row> rows = files.next().getRows()) {
                    rows.forEachRemaining(r -> out.add(InternalScanFileUtils.getAddFileStatus(r)));
                }
            }
        }
        out.sort(java.util.Comparator.comparing(FileStatus::getPath));
        return out;
    }

    /** The ids a raw read of {@code file} returns with the predicate {@code id = value} (row groups pruned). */
    static List<String> idsWhere(Engine engine, FileStatus file, String value) throws IOException {
        Predicate eq = new Predicate("=", new Column("id"), Literal.ofString(value));
        StructType schema = new StructType().add("id", io.delta.kernel.types.StringType.STRING);
        List<String> out = new ArrayList<>();
        try (CloseableIterator<FileReadResult> it = engine.getParquetHandler()
                .readParquetFiles(Utils.singletonCloseableIterator(file), schema, Optional.of(eq))) {
            while (it.hasNext()) {
                ColumnarBatch b = it.next().getData();
                for (int i = 0; i < b.getSize(); i++) {
                    out.add(b.getColumnVector(0).getString(i));
                }
            }
        }
        return out;
    }
}
