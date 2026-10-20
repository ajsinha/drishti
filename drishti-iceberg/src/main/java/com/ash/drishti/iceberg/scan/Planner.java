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
import com.ash.drishti.iceberg.io.FileAccess;
import com.ash.drishti.iceberg.manifest.DataFile;
import com.ash.drishti.iceberg.manifest.ManifestEntry;
import com.ash.drishti.iceberg.manifest.ManifestFile;
import com.ash.drishti.iceberg.manifest.ManifestReader;
import com.ash.drishti.iceberg.meta.PartitionSpec;
import com.ash.drishti.iceberg.meta.Snapshot;
import com.ash.drishti.iceberg.meta.TableMetadata;
import com.ash.drishti.iceberg.types.Schema;
import com.ash.drishti.iceberg.types.Type;
import com.ash.drishti.iceberg.types.UnsupportedFeature;
import com.ash.drishti.iceberg.types.Values;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Plans a snapshot: reads its manifest list, drops the manifests whose partition summaries rule out the filter, reads
 * the rest at once (each on its own thread of {@code pool}), drops entries by partition value and column bounds, and
 * gives each live data file the delete files that apply to it:
 *
 * <ul>
 *   <li>a <b>position</b> delete file applies when it is of the same spec and partition and its data sequence number is
 *       at or after the data file's (and, when it names one, its referenced data file is this one);</li>
 *   <li>an <b>equality</b> delete file applies when it is of the same spec and partition, or of an unpartitioned spec
 *       (a global delete), and its data sequence number is strictly after the data file's.</li>
 * </ul>
 *
 * <p>Manifests never change once written, so their parsed entries are kept ({@code drishti.iceberg.manifest-cache-entries},
 * 2,000,000 entries): a new snapshot reads only the manifests its commit added. Refused by name: data files that are
 * not Parquet, and encrypted files.
 */
public final class Planner {

    private static final Cache<String, List<ManifestEntry>> MANIFESTS = Caffeine.newBuilder()
            .maximumWeight(Long.getLong("drishti.iceberg.manifest-cache-entries", 2_000_000))
            .weigher((String k, List<ManifestEntry> v) -> 1 + v.size()).build();

    /** Field ids of a position delete file's columns (the spec's reserved ids). */
    public static final int DELETE_FILE_PATH = 2147483546;
    public static final int DELETE_POS = 2147483545;

    private Planner() {}

    /** Forgets every parsed manifest. */
    public static void purge() {
        MANIFESTS.invalidateAll();
    }

    /** The data files of {@code snapshot} that may hold rows matching {@code filter}, each with its delete files. */
    public static List<FileTask> plan(TableMetadata table, Snapshot snapshot, Expr filter, FileAccess io, ExecutorService pool) {
        Schema schema = table.schemaFor(snapshot);
        List<ManifestFile> manifests = manifests(table, snapshot, io);
        List<Future<List<ManifestEntry>>> reading = new ArrayList<>();
        Map<Integer, List<com.ash.drishti.iceberg.expr.Expr.Pred>> projected = new HashMap<>();
        for (ManifestFile m : manifests) {
            if (!m.hasLiveFiles()) {
                continue;
            }
            int specId = m.specId();
            if (specId >= 0 && m.content() == ManifestFile.DATA) {
                PartitionSpec spec = table.spec(specId);
                List<Expr.Pred> p = projected.computeIfAbsent(specId, k -> Pruning.project(filter, spec, schema));
                if (!m.partitions().isEmpty() && !Pruning.manifest(m, p, spec.partitionType(schema))) {
                    continue;                                   // its summaries rule the filter out
                }
            }
            reading.add(pool.submit(() -> entries(m, table, io)));
        }
        List<ManifestEntry> data = new ArrayList<>();
        List<ManifestEntry> deletes = new ArrayList<>();
        try {
            for (Future<List<ManifestEntry>> f : reading) {
                for (ManifestEntry e : f.get()) {
                    if (!e.live()) {
                        continue;
                    }
                    (e.file().isData() ? data : deletes).add(e);
                }
            }
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException re ? re : new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("planning interrupted", e);
        }
        DeleteIndex index = new DeleteIndex(deletes, table);
        List<FileTask> out = new ArrayList<>();
        for (ManifestEntry e : data) {
            DataFile f = e.file();
            PartitionSpec spec = table.spec(f.specId());
            List<Expr.Pred> p = projected.computeIfAbsent(f.specId(), k -> Pruning.project(filter, spec, schema));
            if (!Pruning.partition(f.partition(), p, spec.partitionType(schema)) || !Pruning.metrics(f, filter, schema)) {
                continue;
            }
            check(f);
            out.add(new FileTask(f, e.sequenceNumber(), spec, index.forFile(f, e.sequenceNumber())));
        }
        return out;
    }

    /** Every live file entry of the snapshot, data and delete files, unfiltered (for writers: what a commit replaces). */
    public static List<ManifestEntry> liveEntries(TableMetadata table, Snapshot snapshot, FileAccess io) {
        List<ManifestEntry> out = new ArrayList<>();
        for (ManifestFile m : manifests(table, snapshot, io)) {
            for (ManifestEntry e : entries(m, table, io)) {
                if (e.live()) {
                    out.add(e);
                }
            }
        }
        return out;
    }

    /** The snapshot's manifests (from its manifest list, or a v1 snapshot's own list of them). */
    public static List<ManifestFile> manifests(TableMetadata table, Snapshot snapshot, FileAccess io) {
        try {
            if (snapshot.manifestList() != null) {
                return ManifestReader.readList(io.readAll(snapshot.manifestList()), snapshot.manifestList());
            }
            List<ManifestFile> out = new ArrayList<>();
            for (String path : snapshot.manifests()) {
                out.add(new ManifestFile(path, -1, -1, ManifestFile.DATA, 0, 0, snapshot.snapshotId(), null, null, null, null, null, null, List.of(),
                        null));
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("reading the manifest list of snapshot " + snapshot.snapshotId() + " of " + table.location(), e);
        }
    }

    /** A manifest's entries, parsed once. */
    public static List<ManifestEntry> entries(ManifestFile m, TableMetadata table, FileAccess io) {
        List<ManifestEntry> known = MANIFESTS.getIfPresent(m.path());
        if (known != null) {
            return known;
        }
        try {
            List<ManifestEntry> read = List.copyOf(ManifestReader.read(io.readAll(m.path()), m, table));
            MANIFESTS.put(m.path(), read);
            return read;
        } catch (IOException e) {
            throw new UncheckedIOException("reading manifest " + m.path(), e);
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

    /** The delete files of a snapshot by partition, for matching to data files. */
    private static final class DeleteIndex {
        private record Entry(DataFile file, long sequence) {}

        private final Map<String, List<Entry>> byPartition = new HashMap<>();
        private final List<Entry> global = new ArrayList<>();

        DeleteIndex(List<ManifestEntry> deletes, TableMetadata table) {
            for (ManifestEntry e : deletes) {
                DataFile d = e.file();
                if (!"PARQUET".equals(d.format())) {
                    throw new UnsupportedFeature(d.format() + " delete files").in(d.path());
                }
                if (d.content() == DataFile.EQUALITY_DELETES && table.spec(d.specId()).isUnpartitioned()) {
                    global.add(new Entry(d, e.sequenceNumber()));
                } else {
                    byPartition.computeIfAbsent(key(d), k -> new ArrayList<>()).add(new Entry(d, e.sequenceNumber()));
                }
            }
        }

        List<DataFile> forFile(DataFile f, long dataSeq) {
            List<DataFile> out = new ArrayList<>();
            for (Entry d : byPartition.getOrDefault(key(f), List.of())) {
                if (d.file().content() == DataFile.POSITION_DELETES) {
                    if (d.sequence() >= dataSeq && (d.file().referencedDataFile() == null || d.file().referencedDataFile().equals(f.path()))
                            && mayReference(d.file(), f.path())) {
                        out.add(d.file());
                    }
                } else if (d.sequence() > dataSeq) {
                    out.add(d.file());
                }
            }
            for (Entry d : global) {
                if (d.sequence() > dataSeq) {
                    out.add(d.file());
                }
            }
            return out;
        }

        /** Whether the position delete file's {@code file_path} bounds include the data file. */
        private static boolean mayReference(DataFile deletes, String path) {
            byte[] lo = deletes.lowerBounds().get(DELETE_FILE_PATH);
            byte[] hi = deletes.upperBounds().get(DELETE_FILE_PATH);
            if (lo == null || hi == null) {
                return true;
            }
            String l = (String) Values.fromBytes(Type.Primitive.STRING, lo);
            String h = (String) Values.fromBytes(Type.Primitive.STRING, hi);
            return Values.compareCodePoints(path, l) >= 0 && (Values.compareCodePoints(path, h) <= 0 || path.startsWith(h));
        }

        private static String key(DataFile f) {
            StringBuilder b = new StringBuilder().append(f.specId());
            for (Object v : f.partition()) {
                b.append('\u001f').append(v instanceof byte[] bytes ? java.util.HexFormat.of().formatHex(bytes) : String.valueOf(v));
            }
            return b.toString();
        }
    }
}
