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
import com.ash.drishti.iceberg.io.FileAccess;
import com.ash.drishti.iceberg.manifest.DataFile;
import com.ash.drishti.iceberg.manifest.ManifestEntry;
import com.ash.drishti.iceberg.manifest.ManifestFile;
import com.ash.drishti.iceberg.meta.Snapshot;
import com.ash.drishti.iceberg.meta.TableMetadata;
import com.ash.drishti.iceberg.meta.TableUpdate;
import com.ash.drishti.iceberg.scan.Planner;
import com.ash.drishti.iceberg.types.NestedField;
import com.ash.drishti.iceberg.types.Schema;
import com.ash.drishti.iceberg.types.Type;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The table operations of DRISHTI_ICEBERG.md section 8, each one commit ({@link Commit}): append, replace partitions,
 * delete partitions, rewrite files (validated against the snapshot read), rewrite manifests, expire snapshots (and
 * delete the files only they used), add columns and set properties. Stateless; safe from any thread (each call
 * commits on its own, retrying on conflicts as {@link Commit} says).
 */
public final class Operations {

    private Operations() {}

    /** Adds data files (a new day). */
    public static Optional<Snapshot> append(Table t, List<DataFile> files, Map<String, String> summary) {
        return Commit.run(t, (base, cur, live) -> new Commit.Delta("append", files, Set.of(), summary));
    }

    /**
     * Replaces the partitions {@code inPartition} picks: their live data and delete files are removed and
     * {@code files} added, in one snapshot (an {@code overwrite}); a reader sees the old partitions or the new ones.
     */
    public static Optional<Snapshot> replacePartitions(Table t, Predicate<DataFile> inPartition, List<DataFile> files, Map<String, String> summary) {
        return Commit.run(t, (base, cur, live) -> {
            Set<String> remove = new HashSet<>();
            for (ManifestEntry e : live) {
                if (inPartition.test(e.file())) {
                    remove.add(e.file().path());
                }
            }
            return new Commit.Delta(remove.isEmpty() ? "append" : "overwrite", files, remove, summary);
        });
    }

    /** Removes whole partitions (metadata only: no data file is read or written). */
    public static Optional<Snapshot> deletePartitions(Table t, Predicate<DataFile> inPartition, Map<String, String> summary) {
        return Commit.run(t, (base, cur, live) -> {
            Set<String> remove = new HashSet<>();
            for (ManifestEntry e : live) {
                if (inPartition.test(e.file())) {
                    remove.add(e.file().path());
                }
            }
            return new Commit.Delta("delete", List.of(), remove, summary);
        });
    }

    /**
     * Rewrites files: {@code remove} (data and delete files, by path) replaced by {@code add}, validated: the files
     * {@code scope} picks must be exactly those the rewrite read ({@code read}), so a day changed since (a file added
     * or deleted by another writer) fails with {@link Commit.ValidationFailed} instead of losing that change.
     */
    public static Optional<Snapshot> rewriteFiles(Table t, Set<String> remove, List<DataFile> add, Predicate<DataFile> scope, Set<String> read) {
        return Commit.run(t, (base, cur, live) -> {
            Set<String> now = new HashSet<>();
            for (ManifestEntry e : live) {
                if (scope.test(e.file())) {
                    now.add(e.file().path());
                }
            }
            if (!now.equals(read)) {
                Set<String> added = new HashSet<>(now);
                added.removeAll(read);
                Set<String> gone = new HashSet<>(read);
                gone.removeAll(now);
                throw new Commit.ValidationFailed("the files being rewritten changed since they were read (" + added.size() + " added, " + gone.size()
                        + " removed by another writer); run the rewrite again");
            }
            return new Commit.Delta("replace", add, remove, Map.of());
        });
    }

    /** Regroups every live file into manifests by {@code clusterBy} (one per month of business dates, say). */
    public static Optional<Snapshot> rewriteManifests(Table t, Function<DataFile, String> clusterBy) {
        return Commit.run(t, (base, cur, live) -> cur.isEmpty() ? new Commit.Delta("replace", List.of(), Set.of(), Map.of())
                : new Commit.Delta("replace", List.of(), Set.of(), Map.of("manifests-rewritten", "true"), e -> clusterBy.apply(e.file())));
    }

    /**
     * Expires the snapshots older than {@code olderThanMs}, always keeping the newest {@code retainLast} and the
     * current one, then deletes the files only the expired snapshots used: their manifest lists, the manifests no
     * kept snapshot lists, and the data and delete files no kept snapshot holds. Returns how many snapshots went.
     */
    public static int expireSnapshots(Table t, long olderThanMs, int retainLast) {
        for (int attempt = 0;; attempt++) {
            TableMetadata base = t.metadata();
            Optional<Snapshot> current = base.currentSnapshot();
            if (current.isEmpty()) {
                return 0;
            }
            List<Snapshot> all = new ArrayList<>(base.snapshots());
            all.sort((a, b) -> Long.compare(b.timestampMs(), a.timestampMs()));
            Set<Long> keep = new HashSet<>();
            keep.add(current.get().snapshotId());
            keep.addAll(base.refs().values());
            for (int i = 0; i < Math.min(retainLast, all.size()); i++) {
                keep.add(all.get(i).snapshotId());
            }
            for (Snapshot s : all) {
                if (s.timestampMs() >= olderThanMs) {
                    keep.add(s.snapshotId());
                }
            }
            Set<Long> gone = new HashSet<>();
            all.forEach(s -> {
                if (!keep.contains(s.snapshotId())) {
                    gone.add(s.snapshotId());
                }
            });
            if (gone.isEmpty()) {
                return 0;
            }
            FileAccess io = t.io();
            Set<String> keptManifests = new HashSet<>();
            Set<String> keptFiles = new HashSet<>();
            for (Snapshot s : all) {
                if (keep.contains(s.snapshotId())) {
                    for (ManifestFile m : Planner.manifests(base, s, io)) {
                        keptManifests.add(m.path());
                        for (ManifestEntry e : Planner.entries(m, base, io)) {
                            if (e.live()) {
                                keptFiles.add(e.file().path());
                            }
                        }
                    }
                }
            }
            List<String> delete = new ArrayList<>();
            for (Snapshot s : all) {
                if (!gone.contains(s.snapshotId())) {
                    continue;
                }
                if (s.manifestList() != null) {
                    delete.add(s.manifestList());
                }
                for (ManifestFile m : Planner.manifests(base, s, io)) {
                    if (keptManifests.contains(m.path())) {
                        continue;
                    }
                    delete.add(m.path());
                    for (ManifestEntry e : Planner.entries(m, base, io)) {
                        if (!keptFiles.contains(e.file().path())) {
                            delete.add(e.file().path());
                        }
                    }
                }
            }
            TableUpdate u = new TableUpdate().requireUuid(base.uuid()).requireMainAt(current.get().snapshotId()).removeSnapshots(gone);
            try {
                t.committed(t.ops().commit(t.state(), u));
            } catch (CommitConflict e) {
                if (attempt >= 4) {
                    throw e;
                }
                t.refresh();
                continue;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            Commit.delete(io, new ArrayList<>(new java.util.LinkedHashSet<>(delete)));
            return gone.size();
        }
    }

    /**
     * Adds optional columns (name to type), each with the next field id; columns already there are left as they are.
     * Returns the table's schema after the change.
     */
    public static Schema addColumns(Table t, Map<String, Type> columns) {
        for (int attempt = 0;; attempt++) {
            TableMetadata base = t.metadata();
            Schema s = base.schema();
            int next = Math.max(base.lastColumnId(), s.highestFieldId());
            List<NestedField> fields = new ArrayList<>(s.columns());
            boolean changed = false;
            for (Map.Entry<String, Type> c : columns.entrySet()) {
                if (s.field(c.getKey()) == null) {
                    fields.add(NestedField.optional(++next, c.getKey(), c.getValue()));
                    changed = true;
                }
            }
            if (!changed) {
                return s;
            }
            int newId = base.schemas().stream().mapToInt(Schema::schemaId).max().orElse(0) + 1;
            Schema added = new Schema(newId, fields, s.identifierFieldIds());
            TableUpdate u = new TableUpdate().requireUuid(base.uuid()).requireCurrentSchema(s.schemaId()).requireLastColumnId(base.lastColumnId())
                    .addSchema(added, next).setCurrentSchema(-1);
            try {
                t.committed(t.ops().commit(t.state(), u));
                return t.metadata().schema();
            } catch (CommitConflict e) {
                if (attempt >= 4) {
                    throw e;
                }
                t.refresh();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** Sets table properties. */
    public static void setProperties(Table t, Map<String, String> props) {
        for (int attempt = 0;; attempt++) {
            TableMetadata base = t.metadata();
            if (new LinkedHashMap<>(base.properties()).entrySet().containsAll(props.entrySet())) {
                return;
            }
            try {
                t.committed(t.ops().commit(t.state(), new TableUpdate().requireUuid(base.uuid()).setProperties(props)));
                return;
            } catch (CommitConflict e) {
                if (attempt >= 4) {
                    throw e;
                }
                t.refresh();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
