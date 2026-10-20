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
package com.ash.drishti.iceberg.write;

import com.ash.drishti.iceberg.Table;
import com.ash.drishti.iceberg.catalog.CommitConflict;
import com.ash.drishti.iceberg.catalog.TableOps;
import com.ash.drishti.iceberg.io.FileAccess;
import com.ash.drishti.iceberg.manifest.DataFile;
import com.ash.drishti.iceberg.manifest.ManifestEntry;
import com.ash.drishti.iceberg.manifest.ManifestFile;
import com.ash.drishti.iceberg.meta.PartitionSpec;
import com.ash.drishti.iceberg.meta.Snapshot;
import com.ash.drishti.iceberg.meta.TableMetadata;
import com.ash.drishti.iceberg.meta.TableUpdate;
import com.ash.drishti.iceberg.scan.Planner;
import com.ash.drishti.iceberg.types.Schema;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Makes one snapshot from a change and commits it, the way every Iceberg writer does: the change is computed on the
 * newest version of the table ({@link Change#compute}, which also validates it), the manifests that list removed files
 * are rewritten (their entries {@code EXISTING} or {@code DELETED}), new files go in new manifests (their sequence
 * numbers left to inherit), a manifest list names them all, and the snapshot is committed with the requirements that
 * the table is the same table and {@code main} has not moved. When another writer committed first
 * ({@link CommitConflict}) the files of the attempt are deleted, the table re-read and the change computed again, up
 * to {@code commit.retry.num-retries} (4) times with a growing wait ({@code commit.retry.min-wait-ms}, 100). A change
 * whose validation fails on the newer version is not retried ({@link ValidationFailed}). Files a commit wrote that
 * end up in no snapshot are removed at once.
 */
public final class Commit {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** A change no longer valid on the newest version of the table (a day rewritten under it changed meanwhile). */
    public static final class ValidationFailed extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ValidationFailed(String message) {
            super(message);
        }
    }

    /** What a commit changes: files added and files removed (data or delete files, by path), with the snapshot's operation. */
    public record Delta(String operation, List<DataFile> add, Set<String> remove, Map<String, String> summary,
            Function<ManifestEntry, String> regroup) {

        public Delta(String operation, List<DataFile> add, Set<String> remove, Map<String, String> summary) {
            this(operation, add, remove, summary, null);
        }
    }

    /** Computes the change on a version of the table: its live files (data and delete files) are given. */
    public interface Change {
        Delta compute(TableMetadata base, Optional<Snapshot> current, List<ManifestEntry> live);
    }

    private Commit() {}

    /** A fresh snapshot id: a positive random long, as Iceberg makes them. */
    static long newSnapshotId() {
        long id;
        do {
            id = RANDOM.nextLong() & Long.MAX_VALUE;
        } while (id == 0);
        return id;
    }

    /**
     * Commits {@code change} to {@code table}; returns the new snapshot, or empty when the change was empty (nothing
     * added or removed: no snapshot is made).
     */
    public static Optional<Snapshot> run(Table table, Change change) {
        TableMetadata md = table.metadata();
        int retries = Integer.parseInt(md.property("commit.retry.num-retries", "4"));
        long wait = Long.parseLong(md.property("commit.retry.min-wait-ms", "100"));
        long maxWait = Long.parseLong(md.property("commit.retry.max-wait-ms", "60000"));
        for (int attempt = 0;; attempt++) {
            List<String> written = new ArrayList<>();
            try {
                Optional<Snapshot> s = attempt(table, change, written);
                return s;
            } catch (CommitConflict e) {
                delete(table.io(), written);
                if (attempt >= retries) {
                    throw new CommitConflict(e.getMessage() + "; gave up after " + (attempt + 1) + " attempts");
                }
                sleep(Math.min(maxWait, wait << Math.min(attempt, 10)));
                table.refresh();
            } catch (IOException e) {
                delete(table.io(), written);
                throw new UncheckedIOException("committing to " + table, e);
            } catch (RuntimeException e) {
                delete(table.io(), written);
                throw e;
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while retrying a commit", e);
        }
    }

    /** Best effort: files of a failed attempt. */
    static void delete(FileAccess io, List<String> paths) {
        for (String p : paths) {
            try {
                String r = io.resolve(p);
                io.storage(r).delete(r);
            } catch (IOException | RuntimeException e) {
                // left behind: a later maintenance run's orphan listing finds it
            }
        }
    }

    private static Optional<Snapshot> attempt(Table table, Change change, List<String> written) throws IOException {
        TableMetadata base = table.metadata();
        Optional<Snapshot> current = base.currentSnapshot();
        FileAccess io = table.io();
        List<ManifestEntry> live = current.map(s -> Planner.liveEntries(base, s, io)).orElse(List.of());
        Delta delta = change.compute(base, current, live);
        if (delta.add().isEmpty() && delta.remove().isEmpty() && delta.regroup() == null) {
            return Optional.empty();
        }
        int fv = base.formatVersion();
        long snapshotId = newSnapshotId();
        long seq = fv >= 2 ? base.lastSequenceNumber() + 1 : 0;
        Schema schema = base.schema();
        String meta = base.location().replaceAll("/+$", "") + "/metadata/";
        String commitId = UUID.randomUUID().toString();
        List<ManifestFile> manifests = new ArrayList<>();
        int n = 0;
        Set<String> removed = new HashSet<>();
        if (delta.regroup() != null) {
            // every live entry, regrouped: one manifest per key and spec and content
            Map<String, List<ManifestEntry>> groups = new LinkedHashMap<>();
            for (ManifestEntry e : live) {
                String key = e.file().specId() + "|" + (e.file().isData() ? 0 : 1) + "|" + delta.regroup().apply(e);
                groups.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(new ManifestEntry(ManifestEntry.EXISTING, e.snapshotId(), e.sequenceNumber(), e.fileSequenceNumber(), e.file()));
            }
            for (List<ManifestEntry> g : groups.values()) {
                DataFile f = g.get(0).file();
                String path = meta + commitId + "-m" + n++ + ".avro";
                written.add(path);
                manifests.add(ManifestWriter.write(io, path, base, schema, base.spec(f.specId()), f.isData() ? ManifestFile.DATA : ManifestFile.DELETES,
                        snapshotId, seq, g));
            }
        } else if (current.isPresent()) {
            for (ManifestFile m : Planner.manifests(base, current.get(), io)) {
                List<ManifestEntry> entries = Planner.entries(m, base, io);
                boolean touched = entries.stream().anyMatch(e -> e.live() && delta.remove().contains(e.file().path()));
                if (!touched) {
                    manifests.add(m);
                    continue;
                }
                List<ManifestEntry> kept = new ArrayList<>();
                for (ManifestEntry e : entries) {
                    if (!e.live()) {
                        continue;                              // deleted by an earlier snapshot: gone from this one
                    }
                    if (delta.remove().contains(e.file().path())) {
                        removed.add(e.file().path());
                        kept.add(new ManifestEntry(ManifestEntry.DELETED, snapshotId, e.sequenceNumber(), e.fileSequenceNumber(), e.file()));
                    } else {
                        kept.add(new ManifestEntry(ManifestEntry.EXISTING, e.snapshotId(), e.sequenceNumber(), e.fileSequenceNumber(), e.file()));
                    }
                }
                String path = meta + commitId + "-m" + n++ + ".avro";
                written.add(path);
                PartitionSpec spec = base.spec(entries.get(0).file().specId());
                manifests.add(ManifestWriter.write(io, path, base, schema, spec, m.content(), snapshotId, seq, kept));
            }
        }
        Map<String, List<ManifestEntry>> added = new LinkedHashMap<>();
        for (DataFile f : delta.add()) {
            added.computeIfAbsent(f.specId() + "|" + (f.isData() ? 0 : 1), k -> new ArrayList<>())
                    .add(new ManifestEntry(ManifestEntry.ADDED, snapshotId, -1, null, f));
        }
        for (List<ManifestEntry> g : added.values()) {
            DataFile f = g.get(0).file();
            String path = meta + commitId + "-m" + n++ + ".avro";
            written.add(path);
            manifests.add(0, ManifestWriter.write(io, path, base, schema, base.spec(f.specId()), f.isData() ? ManifestFile.DATA : ManifestFile.DELETES,
                    snapshotId, seq, g));
        }
        Map<String, String> summary = summary(delta, live, removed);
        String list = meta + "snap-" + snapshotId + "-1-" + commitId + ".avro";
        written.add(list);
        Long parent = current.map(Snapshot::snapshotId).orElse(null);
        ManifestWriter.writeList(io, list, fv, snapshotId, parent, seq, manifests);
        Snapshot snap = new Snapshot(snapshotId, parent, seq, Math.max(System.currentTimeMillis(), base.lastUpdatedMs() + 1), list, List.of(),
                delta.operation(), summary, schema.schemaId());
        TableUpdate u = new TableUpdate().requireUuid(base.uuid()).requireMainAt(parent).addSnapshot(snap, fv).setMain(snapshotId);
        TableOps.Loaded next = table.ops().commit(table.state(), u);
        table.committed(next);
        return Optional.of(snap);
    }

    private static Map<String, String> summary(Delta d, List<ManifestEntry> live, Set<String> removed) {
        Map<String, String> s = new LinkedHashMap<>();
        long addedData = 0;
        long addedDeletes = 0;
        long addedRecords = 0;
        for (DataFile f : d.add()) {
            if (f.isData()) {
                addedData++;
                addedRecords += f.recordCount();
            } else {
                addedDeletes++;
            }
        }
        long removedData = 0;
        long removedDeletes = 0;
        long removedRecords = 0;
        long totalData = 0;
        long totalDeletes = 0;
        long totalRecords = 0;
        for (ManifestEntry e : live) {
            boolean gone = removed.contains(e.file().path());
            if (e.file().isData()) {
                if (gone) {
                    removedData++;
                    removedRecords += e.file().recordCount();
                } else {
                    totalData++;
                    totalRecords += e.file().recordCount();
                }
            } else if (gone) {
                removedDeletes++;
            } else {
                totalDeletes++;
            }
        }
        if (d.regroup() == null) {
            s.put("added-data-files", String.valueOf(addedData));
            s.put("added-records", String.valueOf(addedRecords));
            s.put("deleted-data-files", String.valueOf(removedData));
            s.put("deleted-records", String.valueOf(removedRecords));
            if (addedDeletes > 0) {
                s.put("added-delete-files", String.valueOf(addedDeletes));
            }
            if (removedDeletes > 0) {
                s.put("removed-delete-files", String.valueOf(removedDeletes));
            }
        }
        s.put("total-data-files", String.valueOf(totalData + addedData));
        s.put("total-records", String.valueOf(totalRecords + addedRecords));
        s.put("total-delete-files", String.valueOf(totalDeletes + addedDeletes));
        s.put("engine-name", "drishti-iceberg");
        s.putAll(d.summary());
        return s;
    }
}
