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
import com.ash.drishti.iceberg.catalog.NoSuchTable;
import com.ash.drishti.iceberg.catalog.TableOps;
import com.ash.drishti.iceberg.expr.Expr;
import com.ash.drishti.iceberg.io.FileAccess;
import com.ash.drishti.iceberg.manifest.DataFile;
import com.ash.drishti.iceberg.meta.Snapshot;
import com.ash.drishti.iceberg.meta.TableUpdate;
import com.ash.drishti.iceberg.scan.FileTask;
import com.ash.drishti.lakeio.FileEntry;
import com.ash.drishti.lakeio.Storage;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.delta.kernel.Scan;
import io.delta.kernel.Table;
import io.delta.kernel.data.FilteredColumnarBatch;
import io.delta.kernel.data.Row;
import io.delta.kernel.exceptions.TableNotFoundException;
import io.delta.kernel.internal.InternalScanFileUtils;
import io.delta.kernel.internal.actions.DeletionVectorDescriptor;
import io.delta.kernel.utils.CloseableIterator;
import io.delta.kernel.utils.FileStatus;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.regex.Pattern;

/**
 * A Delta table read as an Iceberg table by drishti-iceberg's reader: the Delta log is replayed by Delta Kernel (checkpoints, protocol
 * checks: a reader feature Kernel does not know is refused by name), each version's {@code add} files become file tasks
 * ({@link DeltaFiles}) with their deletion vectors ({@link DeltaVectors}), and the Parquet files are read by name through the schema's
 * name mapping ({@link DeltaSchemas}). Never written. The last {@code history} versions are kept as snapshots, for <em>known at</em>.
 * Thread-safe.
 */
public final class DeltaTableOps implements TableOps, AutoCloseable {

    private static final Pattern COMMIT = Pattern.compile("(\\d{20})\\.json");

    private final String name;
    private final String location;
    private final NativeEngine engine;
    private final FileAccess io;
    private final int history;
    private final String key = UUID.randomUUID().toString();
    private final Cache<Long, List<FileTask>> plans = Caffeine.newBuilder().maximumWeight(64L << 20)
            .weigher((Long v, List<FileTask> t) -> (int) Math.min(Integer.MAX_VALUE, 1024L + 1024L * t.size())).build();   // about 1 KB a planned file
    private volatile DeltaView view;

    /**
     * @param name the table's name, for messages
     * @param location the table's folder (local, {@code file:}, {@code s3://})
     * @param engine the Kernel engine that reads its log (with the table's credentials)
     * @param io the Iceberg reader's files (the same store and credentials)
     * @param history how many recent versions are kept as snapshots (at least 1)
     */
    public DeltaTableOps(String name, String location, NativeEngine engine, FileAccess io, int history) {
        this.name = name;
        this.location = location.replaceAll("/+$", "");
        this.engine = engine;
        this.io = io;
        this.history = Math.max(1, history);
        DeltaVectors.register(key, engine, this.location);
    }

    /** The table's folder. */
    public String location() {
        return location;
    }

    @Override
    public Loaded load() throws IOException {
        io.delta.kernel.Snapshot s;
        try {
            s = Table.forPath(engine, location).getLatestSnapshot(engine);
        } catch (TableNotFoundException e) {
            throw new NoSuchTable("no Delta table " + name + ": " + location + "/_delta_log is not there");
        } catch (UncheckedIOException e) {
            if (e.getCause() instanceof FileNotFoundException) {
                throw new NoSuchTable("no Delta table " + name + ": " + location + "/_delta_log is not there");
            }
            throw e;
        } catch (RuntimeException e) {
            throw new IOException("cannot read the Delta table " + name + " (" + location + "): " + e.getMessage(), e);
        }
        long version = s.getVersion();
        DeltaSchemas.Converted c;
        try {
            c = DeltaSchemas.convert(s.getMetadata().getSchemaString(), s.getPartitionColumnNames());
        } catch (IllegalArgumentException e) {
            throw new IOException("the Delta table " + name + ": " + e.getMessage(), e);
        }
        DeltaView v = DeltaView.of(location, s.getMetadata().getId(), c, versions(version, s.getTimestamp(engine)));
        view = v;
        return new Loaded(v.metadata(), "delta:" + location + "@v" + version, null);
    }

    /** The recent versions with their commit times (the commit files' times, as Delta's own time travel takes them). */
    private List<DeltaView.Version> versions(long current, long currentTs) throws IOException {
        List<DeltaView.Version> out = new ArrayList<>();
        long from = Math.max(0, current - history + 1);
        String log = location + "/_delta_log/";
        try {
            Storage st = engine.storage(log);
            for (FileEntry e : st.listFrom(log + "%020d.json".formatted(from))) {
                String file = e.path().substring(e.path().lastIndexOf('/') + 1);
                var m = COMMIT.matcher(file);
                if (m.matches()) {
                    long v = Long.parseLong(m.group(1));
                    if (v >= from && v < current) {
                        out.add(new DeltaView.Version(v, e.modified()));
                    }
                }
            }
        } catch (FileNotFoundException e) {
            // a log of checkpoints only: the current version alone
        }
        out.add(new DeltaView.Version(current, currentTs));
        return out;
    }

    @Override
    public Optional<Loaded> refresh(Loaded current) throws IOException {
        long version = Long.parseLong(current.location().substring(current.location().lastIndexOf("@v") + 2));
        String log = location + "/_delta_log/";
        String next = log + "%020d.json".formatted(version + 1);
        try {
            List<FileEntry> after = engine.storage(log).listFrom(next);
            if (after.stream().noneMatch(e -> COMMIT.matcher(e.path().substring(e.path().lastIndexOf('/') + 1)).matches())) {
                return Optional.empty();
            }
        } catch (FileNotFoundException e) {
            throw new NoSuchTable("the Delta table " + name + " is gone: " + log + " is not there");
        }
        return Optional.of(load());
    }

    @Override
    public Optional<List<FileTask>> plan(Loaded current, Snapshot snapshot, Expr filter, ExecutorService pool) {
        long version = DeltaView.version(snapshot.snapshotId());
        return Optional.of(plans.get(version, this::files));
    }

    private List<FileTask> files(long version) {
        DeltaView v = view;
        List<FileTask> out = new ArrayList<>();
        try {
            io.delta.kernel.Snapshot s = Table.forPath(engine, location).getSnapshotAsOfVersion(engine, version);
            Scan scan = s.getScanBuilder().build();
            CloseableIterator<FilteredColumnarBatch> files = scan instanceof io.delta.kernel.internal.ScanImpl si ? si.getScanFiles(engine, true)
                    : scan.getScanFiles(engine);
            try (CloseableIterator<FilteredColumnarBatch> it = files) {
                while (it.hasNext()) {
                    try (CloseableIterator<Row> rows = it.next().getRows()) {
                        while (rows.hasNext()) {
                            out.add(task(v, rows.next(), version));
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("listing the files of " + name + " version " + version, e);
        } catch (TableNotFoundException e) {
            throw new IllegalStateException("the Delta table " + name + " has no version " + version + " any more (vacuumed?)", e);
        }
        return List.copyOf(out);
    }

    private FileTask task(DeltaView v, Row r, long version) {
        FileStatus fs = InternalScanFileUtils.getAddFileStatus(r);
        Row add = r.getStruct(InternalScanFileUtils.ADD_FILE_ORDINAL);
        int at = add.getSchema().indexOf("stats");
        String stats = at < 0 || add.isNullAt(at) ? null : add.getString(at);
        FileTask t = DeltaFiles.task(v.converted(), v.spec(), fs.getPath(), fs.getSize(), InternalScanFileUtils.getPartitionValues(r), stats,
                DeltaView.snapshotId(version), List.of());
        DeletionVectorDescriptor dv = InternalScanFileUtils.getDeletionVectorDescriptorFromRow(r);
        if (dv == null) {
            return t;
        }
        DataFile deletes = DeltaVectors.file(key, fs.getPath(), t.file().partition(), v.spec().specId(), dv);
        return new FileTask(t.file(), t.dataSequenceNumber(), t.spec(), List.of(deletes));
    }

    @Override
    public Loaded commit(Loaded base, TableUpdate update) {
        throw new UnsupportedOperationException("a Delta table (" + name + ") is read, never written, by this connector");
    }

    @Override
    public FileAccess io(Loaded current) {
        return io;
    }

    @Override
    public String describe() {
        return name + " (Delta, " + location + ")";
    }

    @Override
    public void close() {
        DeltaVectors.unregister(key);
    }
}
