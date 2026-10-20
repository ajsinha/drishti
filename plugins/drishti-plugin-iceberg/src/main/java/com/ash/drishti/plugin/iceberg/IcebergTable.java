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
package com.ash.drishti.plugin.iceberg;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.PartitionField;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.apache.iceberg.data.GenericDeleteFilter;
import org.apache.iceberg.data.IdentityPartitionConverters;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.data.parquet.GenericParquetReaders;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.parquet.Parquet;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.util.PartitionUtil;
import org.apache.iceberg.util.SnapshotUtil;

/**
 * A kind's table read by the {@code library} engine: Apache Iceberg Java over Hadoop's file systems (the connector's
 * code before drishti-iceberg). Planning is Iceberg's scan; a file is read with Iceberg's own Parquet reader, the
 * delete files applied by {@link GenericDeleteFilter}. A day's {@link Day#files()} are Iceberg {@link FileScanTask}s.
 * Immutable per snapshot; thread-safe.
 */
final class IcebergTable extends KindTable {

    private final String kind;
    private final IcebergLake lake;
    private volatile Table table;

    IcebergTable(String kind, IcebergLake lake, ExecutorService pool) {
        super(pool);
        this.kind = kind;
        this.lake = lake;
    }

    /** A table already loaded (maintenance). */
    IcebergTable(Table table, ExecutorService pool) {
        this(table.name(), null, pool);
        this.table = table;
    }

    /** The table, loaded once; empty while it does not exist. */
    Optional<Table> table() {
        Table t = table;
        if (t == null) {
            t = lake.load(kind).orElse(null);
            table = t;
        }
        return Optional.ofNullable(t);
    }

    @Override
    Optional<Long> refresh() {
        Optional<Table> t = table();
        if (t.isEmpty()) {
            return Optional.empty();
        }
        t.get().refresh();
        return Optional.ofNullable(t.get().currentSnapshot()).map(Snapshot::snapshotId);
    }

    @Override
    Optional<Long> current() {
        return table().map(Table::currentSnapshot).map(Snapshot::snapshotId);
    }

    @Override
    Optional<Long> snapshotAt(Instant knownAt) {
        return table().map(t -> {
            Long id = SnapshotUtil.nullableSnapshotIdAsOfTime(t, knownAt.toEpochMilli());
            return id != null && t.snapshot(id) != null ? id : null;
        });
    }

    @Override
    long sequenceNumber(long snapshotId) {
        return table().map(t -> t.snapshot(snapshotId)).map(Snapshot::sequenceNumber).orElse(0L);
    }

    /** The day's files as Iceberg's tasks. */
    @SuppressWarnings("unchecked")
    static List<FileScanTask> tasks(Day day) {
        return (List<FileScanTask>) day.files();
    }

    @Override
    Layout layout(long snapshotId, boolean stats) {
        Table t = table().orElseThrow();
        Snapshot s = t.snapshot(snapshotId);
        if (s == null) {
            t.refresh();
            s = t.snapshot(snapshotId);
        }
        Schema schema = SnapshotUtil.schemaFor(t, snapshotId);
        Map<LocalDate, List<FileScanTask>> byDate = new TreeMap<>();
        // no scan report in the log for every snapshot planned (Iceberg logs each one at INFO by default)
        var scan = t.newScan().useSnapshot(snapshotId).planWith(pool).metricsReporter(report -> { });
        try (CloseableIterable<FileScanTask> tasks = (stats ? scan.includeColumnStats() : scan).planFiles()) {
            for (FileScanTask task : tasks) {
                byDate.computeIfAbsent(dateOf(task), k -> new ArrayList<>()).add(task);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        NavigableMap<LocalDate, Day> days = new TreeMap<>();
        byDate.forEach((date, files) -> {
            files.sort(Comparator.comparing(f -> f.file().location()));
            long rows = 0;
            long fp = 1125899906842597L;
            boolean deletes = false;
            for (FileScanTask f : files) {
                rows += f.file().recordCount();
                fp = 31 * fp + f.file().location().hashCode();
                for (var d : f.deletes()) {
                    deletes = true;
                    fp = 31 * fp + d.location().hashCode();
                }
            }
            days.put(date, new Day(date, List.copyOf(files), rows, deletes, fp ^ ((long) files.size() << 48)));
        });
        Map<String, Boolean> columns = new LinkedHashMap<>();
        schema.columns().forEach(c -> columns.put(c.name(), numeric(c.type())));
        return new Layout(snapshotId, s == null ? 0 : s.timestampMillis(), columns, days);
    }

    /** The business date a file belongs to (its identity partition on {@code business_date}); MIN for an undated table. */
    private static LocalDate dateOf(FileScanTask task) {
        List<PartitionField> fields = task.spec().fields();
        for (int i = 0; i < fields.size(); i++) {
            PartitionField f = fields.get(i);
            if (f.transform().isIdentity() && IcebergLayout.DATE.equals(task.spec().schema().findColumnName(f.sourceId()))) {
                Integer days = task.file().partition().get(i, Integer.class);
                return days == null ? LocalDate.MIN : LocalDate.ofEpochDay(days);
            }
        }
        return LocalDate.MIN;
    }

    /**
     * Opens one data file with only {@code projection}'s columns, Parquet row groups skipped by {@code filter}, and the
     * file's delete files applied. Rows that pass the filter's row groups but not the filter itself are returned too:
     * callers check the value they asked for.
     */
    static CloseableIterable<Record> open(FileIO io, Schema tableSchema, FileScanTask task, Schema projection, Expression filter) {
        if (task.file().format() != FileFormat.PARQUET) {
            throw new UnsupportedOperationException("only Parquet data files are read, not " + task.file().format() + ": " + task.file().location());
        }
        GenericDeleteFilter deletes = task.deletes().isEmpty() ? null : new GenericDeleteFilter(io, task, tableSchema, projection);
        Schema read = deletes == null ? projection : deletes.requiredSchema();
        Map<Integer, ?> constants = PartitionUtil.constantsMap(task, IdentityPartitionConverters::convertConstant);
        CloseableIterable<Record> rows = Parquet.read(io.newInputFile(task.file().location(), task.file().fileSizeInBytes()))
                .project(read)
                .createReaderFunc(fileSchema -> GenericParquetReaders.buildReader(read, fileSchema, constants))
                .filter(filter)
                .reuseContainers()
                .build();
        return deletes == null ? rows : deletes.filter(rows);
    }

    /** The columns in the order asked (records are read by position). */
    private static Schema select(Schema schema, List<String> columns) {
        return new Schema(columns.stream().map(schema::findField).toList());
    }

    @Override
    void scan(Layout l, Day day, int file, List<String> columns, String idEquals, Consumer<Object[]> sink) {
        Table t = table().orElseThrow();
        Schema schema = SnapshotUtil.schemaFor(t, l.snapshotId());
        Expression filter = idEquals == null ? Expressions.alwaysTrue() : Expressions.equal(IcebergLayout.ID, idEquals);
        try (CloseableIterable<Record> rows = open(t.io(), schema, tasks(day).get(file), select(schema, columns), filter)) {
            Object[] values = new Object[columns.size()];
            for (Record r : rows) {
                for (int i = 0; i < values.length; i++) {
                    values[i] = r.get(i);
                }
                sink.accept(values);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** True when a column of this type is read as numbers. */
    static boolean numeric(Type type) {
        return switch (type.typeId()) {
            case DOUBLE, FLOAT, LONG, INTEGER, DECIMAL -> true;
            default -> false;
        };
    }

    /** A fresh pool for callers outside the connector (maintenance): virtual threads. */
    static ExecutorService virtualPool() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
