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
package com.ash.drishti.iceberg.scan;

import com.ash.drishti.iceberg.expr.Expr;
import com.ash.drishti.iceberg.io.DrishtiFileIO;
import com.ash.drishti.iceberg.manifest.DataFile;
import com.ash.drishti.iceberg.meta.Bridge;
import com.ash.drishti.iceberg.meta.PartitionSpec;
import com.ash.drishti.iceberg.meta.Snapshot;
import com.ash.drishti.iceberg.types.Schema;
import com.ash.drishti.iceberg.types.UnsupportedFeature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import org.apache.iceberg.ContentFile;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.ManifestFile;
import org.apache.iceberg.ManifestFiles;
import org.apache.iceberg.Table;
import org.apache.iceberg.io.CloseableIterable;

/**
 * Plans a snapshot through Apache Iceberg's own scan (manifest pruning, delete-file matching, sequence numbers) and hands each live data
 * file with its deletes to Drishti's Parquet reader as a {@link FileTask}. Refused by name: data files that are not Parquet, encrypted
 * files, and format-version-3 deletion vectors.
 */
public final class Planner {

    /** Field ids of a position delete file's columns (the spec's reserved ids). */
    public static final int DELETE_FILE_PATH = 2147483546;
    public static final int DELETE_POS = 2147483545;

    private Planner() {}

    /** Forgets every parsed manifest. */
    public static void purge() {
        DrishtiFileIO.purge();
    }

    /** The data files of {@code snapshot} that may hold rows matching {@code filter}, each with its delete files. */
    public static List<FileTask> plan(Table t, Schema schema, Snapshot snapshot, Expr filter, ExecutorService pool) {
        Map<Integer, PartitionSpec> specs = new HashMap<>();
        var scan = t.newScan().useSnapshot(snapshot.snapshotId()).includeColumnStats().filter(Bridge.expression(filter, schema)).planWith(pool)
                .metricsReporter(report -> { });
        List<FileTask> out = new ArrayList<>();
        try (CloseableIterable<FileScanTask> tasks = scan.planFiles()) {
            for (FileScanTask task : tasks) {
                DataFile f = Bridge.file(task.file(), task.spec());
                check(f);
                List<DataFile> deletes = new ArrayList<>();
                for (DeleteFile d : task.deletes()) {
                    DataFile del = Bridge.file(d, t.specs().get(d.specId()));
                    if (!"PARQUET".equals(del.format())) {
                        throw new UnsupportedFeature(del.format() + " delete files (deletion vectors, format version 3)").in(del.path());
                    }
                    deletes.add(del);
                }
                Long sequence = task.file().dataSequenceNumber();
                out.add(new FileTask(f, sequence == null ? 0 : sequence, specs.computeIfAbsent(task.spec().specId(), k -> Bridge.spec(task.spec())), deletes));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("planning " + t.name(), e);
        }
        return out;
    }

    /** Every live data and delete file of a snapshot, unfiltered. */
    public static void live(Table t, org.apache.iceberg.Snapshot s, Consumer<ContentFile<?>> sink) {
        for (ManifestFile m : s.allManifests(t.io())) {
            try (CloseableIterable<? extends ContentFile<?>> files = m.content() == org.apache.iceberg.ManifestContent.DATA
                    ? ManifestFiles.read(m, t.io(), t.specs()) : ManifestFiles.readDeleteManifest(m, t.io(), t.specs())) {
                files.forEach(sink);
            } catch (IOException e) {
                throw new UncheckedIOException("reading manifest " + m.path(), e);
            }
        }
    }

    private static void check(DataFile f) {
        if (!"PARQUET".equals(f.format())) {
            throw new UnsupportedFeature(f.format() + " data files").in(f.path());
        }
        if (f.keyMetadata() != null && f.keyMetadata().length > 0) {
            throw new UnsupportedFeature("encrypted data files").in(f.path());
        }
    }
}
