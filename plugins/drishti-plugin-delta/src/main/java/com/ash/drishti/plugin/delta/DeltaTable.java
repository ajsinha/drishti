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

import io.delta.kernel.Scan;
import io.delta.kernel.Snapshot;
import io.delta.kernel.Table;
import io.delta.kernel.data.ColumnarBatch;
import io.delta.kernel.data.FilteredColumnarBatch;
import io.delta.kernel.data.Row;
import io.delta.kernel.engine.Engine;
import io.delta.kernel.engine.FileReadResult;
import io.delta.kernel.exceptions.TableNotFoundException;
import io.delta.kernel.internal.InternalScanFileUtils;
import io.delta.kernel.internal.data.ScanStateRow;
import io.delta.kernel.internal.util.Utils;
import io.delta.kernel.types.StringType;
import io.delta.kernel.types.StructType;
import io.delta.kernel.utils.CloseableIterator;
import io.delta.kernel.utils.FileStatus;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

/**
 * One Delta table of entities: a row per entity per business date, {@code (id STRING, doc STRING)} with the
 * date as the partition column. Reads go through Delta Kernel (no Spark): the snapshot's file list is grouped by
 * partition, and a partition's Parquet files are read on demand. Immutable per snapshot; thread-safe.
 */
final class DeltaTable {

    /** A snapshot's files by business date. */
    record Layout(long version, long timestamp, Row scanState, NavigableMap<LocalDate, List<Row>> files) {}

    private final Engine engine;
    private final String path;
    private final String idColumn;
    private final String docColumn;
    private final String dateColumn;

    DeltaTable(Engine engine, String path, String idColumn, String docColumn, String dateColumn) {
        this.engine = engine;
        this.path = path;
        this.idColumn = idColumn;
        this.docColumn = docColumn;
        this.dateColumn = dateColumn;
    }

    String path() {
        return path;
    }

    /** The latest snapshot's layout, or the one as known at {@code knownAt}; empty if the table does not exist. */
    Optional<Layout> layout(Instant knownAt) {
        Snapshot s;
        try {
            Table t = Table.forPath(engine, path);
            s = knownAt == null ? t.getLatestSnapshot(engine) : t.getSnapshotAsOfTimestamp(engine, knownAt.toEpochMilli());
        } catch (TableNotFoundException e) {
            return Optional.empty();
        }
        Scan scan = s.getScanBuilder().withReadSchema(new StructType().add(idColumn, StringType.STRING).add(docColumn, StringType.STRING)).build();
        NavigableMap<LocalDate, List<Row>> byDate = new TreeMap<>();
        try (CloseableIterator<FilteredColumnarBatch> it = scan.getScanFiles(engine)) {
            while (it.hasNext()) {
                try (CloseableIterator<Row> rows = it.next().getRows()) {
                    while (rows.hasNext()) {
                        Row r = rows.next();
                        String d = InternalScanFileUtils.getPartitionValues(r).get(dateColumn);
                        LocalDate date = d == null ? LocalDate.MIN : LocalDate.parse(d);
                        byDate.computeIfAbsent(date, k -> new ArrayList<>()).add(r);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Optional.of(new Layout(s.getVersion(), s.getTimestamp(engine), scan.getScanState(engine), byDate));
    }

    /** Every entity in one business date's partition: id to JSON text. */
    Map<String, String> read(Layout layout, LocalDate date) {
        Map<String, String> out = new LinkedHashMap<>();
        List<Row> files = layout.files().get(date);
        if (files == null) {
            return out;
        }
        StructType physical = ScanStateRow.getPhysicalDataReadSchema(layout.scanState());
        for (Row fileRow : files) {
            FileStatus fs = InternalScanFileUtils.getAddFileStatus(fileRow);
            try (CloseableIterator<FileReadResult> raw = engine.getParquetHandler()
                    .readParquetFiles(Utils.singletonCloseableIterator(fs), physical, Optional.empty());
                 CloseableIterator<FilteredColumnarBatch> data = Scan.transformPhysicalData(engine, layout.scanState(), fileRow,
                         raw.map(FileReadResult::getData))) {
                while (data.hasNext()) {
                    FilteredColumnarBatch b = data.next();
                    ColumnarBatch cols = b.getData();
                    int idIx = cols.getSchema().indexOf(idColumn);
                    int docIx = cols.getSchema().indexOf(docColumn);
                    try (CloseableIterator<Row> rows = b.getRows()) {
                        while (rows.hasNext()) {
                            Row r = rows.next();
                            if (!r.isNullAt(idIx) && !r.isNullAt(docIx)) {
                                out.put(r.getString(idIx), r.getString(docIx));
                            }
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return out;
    }
}
