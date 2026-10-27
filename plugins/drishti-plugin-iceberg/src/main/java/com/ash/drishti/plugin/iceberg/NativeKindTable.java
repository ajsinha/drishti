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

import com.ash.drishti.iceberg.Table;
import com.ash.drishti.iceberg.catalog.Catalog;
import com.ash.drishti.iceberg.expr.Expr;
import com.ash.drishti.iceberg.manifest.DataFile;
import com.ash.drishti.iceberg.meta.PartitionSpec;
import com.ash.drishti.iceberg.meta.Snapshot;
import com.ash.drishti.iceberg.meta.Transform;
import com.ash.drishti.iceberg.read.TaskReader;
import com.ash.drishti.iceberg.scan.FileTask;
import com.ash.drishti.iceberg.types.NestedField;
import com.ash.drishti.iceberg.types.Schema;
import com.ash.drishti.iceberg.types.Type;
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
import java.util.function.Consumer;

/**
 * A kind's table read by the {@code native} engine, drishti-iceberg: no Hadoop, so it runs on Windows without
 * {@code winutils.exe}. Planning reads the manifests itself (kept once read); a file is read by field id through the
 * shared footer cache, its deletes applied. A day's {@link Day#files()} are drishti-iceberg {@link FileTask}s.
 * Thread-safe.
 */
final class NativeKindTable extends KindTable {

    private final String kind;
    private final Catalog catalog;
    private volatile Table table;

    NativeKindTable(String kind, Catalog catalog, ExecutorService pool) {
        super(pool);
        this.kind = kind;
        this.catalog = catalog;
    }

    /** A table already loaded (the loader, maintenance). */
    NativeKindTable(Table table, ExecutorService pool) {
        this(table.name(), null, pool);
        this.table = table;
    }

    /** The table, loaded once; empty while it does not exist. */
    Optional<Table> table() {
        Table t = table;
        if (t == null && catalog != null) {
            try {
                t = catalog.load(kind).orElse(null);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            table = t;
        }
        return Optional.ofNullable(t);
    }

    /** The day's files as drishti-iceberg's tasks. */
    @SuppressWarnings("unchecked")
    static List<FileTask> tasks(Day day) {
        return (List<FileTask>) day.files();
    }

    @Override
    Optional<Long> refresh() {
        Optional<Table> t = table();
        if (t.isEmpty()) {
            return Optional.empty();
        }
        t.get().refresh();
        return t.get().currentSnapshot().map(Snapshot::snapshotId);
    }

    @Override
    Optional<Long> current() {
        return table().flatMap(Table::currentSnapshot).map(Snapshot::snapshotId);
    }

    @Override
    Optional<Long> snapshotAt(Instant knownAt) {
        return table().flatMap(t -> t.metadata().snapshotAsOf(knownAt.toEpochMilli())).map(Snapshot::snapshotId);
    }

    @Override
    long sequenceNumber(long snapshotId) {
        return table().flatMap(t -> t.metadata().snapshot(snapshotId)).map(Snapshot::sequenceNumber).orElse(0L);
    }

    @Override
    Optional<String[]> uuidAndLocation() {
        return table().map(t -> new String[] {t.metadata().uuid(), t.metadata().location()});
    }

    private Snapshot snapshot(Table t, long snapshotId) {
        Optional<Snapshot> s = t.metadata().snapshot(snapshotId);
        if (s.isEmpty()) {
            t.refresh();
            s = t.metadata().snapshot(snapshotId);
        }
        return s.orElseThrow(() -> new IllegalStateException(kind + " has no snapshot " + snapshotId + " (expired?)"));
    }

    @Override
    Layout layout(long snapshotId, boolean stats) {
        Table t = table().orElseThrow();
        Snapshot s = snapshot(t, snapshotId);
        Schema schema = t.metadata().schemaFor(s);
        NestedField date = schema.field(IcebergLayout.DATE);
        Map<LocalDate, List<FileTask>> byDate = new TreeMap<>();
        for (FileTask task : t.plan(s, Expr.TRUE, pool)) {
            byDate.computeIfAbsent(dateOf(task, date == null ? -1 : date.id()), k -> new ArrayList<>()).add(task);
        }
        NavigableMap<LocalDate, Day> days = new TreeMap<>();
        byDate.forEach((d, files) -> {
            files.sort(Comparator.comparing(f -> f.file().path()));
            long rows = 0;
            long fp = 1125899906842597L;
            boolean deletes = false;
            for (FileTask f : files) {
                rows += f.file().recordCount();
                fp = 31 * fp + f.file().path().hashCode();
                for (DataFile del : f.deletes()) {
                    deletes = true;
                    fp = 31 * fp + del.path().hashCode();
                }
            }
            days.put(d, new Day(d, List.copyOf(files), rows, deletes, fp ^ ((long) files.size() << 48)));
        });
        Map<String, Boolean> columns = new LinkedHashMap<>();
        schema.columns().forEach(c -> columns.put(c.name(), numeric(c.type())));
        return new Layout(snapshotId, s.timestampMs(), columns, days);
    }

    /** The business date of a file: its identity partition on {@code business_date}; MIN for an undated table. */
    static LocalDate dateOf(FileTask task, int dateFieldId) {
        PartitionSpec spec = task.spec();
        for (int i = 0; i < spec.fields().size(); i++) {
            PartitionSpec.Field f = spec.fields().get(i);
            if (f.sourceId() == dateFieldId && f.transform() instanceof Transform.Identity) {
                Object v = task.file().partition()[i];
                return v == null ? LocalDate.MIN : LocalDate.ofEpochDay(((Number) v).longValue());
            }
        }
        return LocalDate.MIN;
    }

    static boolean numeric(Type t) {
        return switch (t.id()) {
            case DOUBLE, FLOAT, LONG, INT, DECIMAL -> true;
            default -> false;
        };
    }

    @Override
    void scan(Layout l, Day day, int file, List<String> columns, String idEquals, Consumer<Object[]> sink) {
        Table t = table().orElseThrow();
        Schema snap = t.metadata().schemaFor(snapshot(t, l.snapshotId()));
        // a column added since the snapshot (maintenance fills it): the current schema, which reads it as null
        Schema schema = columns.stream().anyMatch(c -> snap.field(c) == null) ? t.metadata().schema() : snap;
        List<Integer> ids = new ArrayList<>();
        for (String c : columns) {
            NestedField f = schema.field(c);
            if (f == null) {
                throw new IllegalArgumentException(kind + " has no column " + c);
            }
            ids.add(f.id());
        }
        Expr filter = idEquals == null ? Expr.TRUE : Expr.eq(schema.field(IcebergLayout.ID).id(), idEquals);
        try (TaskReader r = t.rows(tasks(day).get(file), schema, ids, filter, idEquals != null)) {
            while (r.hasNext()) {
                sink.accept(r.next());
            }
        }
    }
}
