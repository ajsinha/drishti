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
import com.ash.drishti.iceberg.manifest.DataFile;
import com.ash.drishti.iceberg.meta.Bridge;
import com.ash.drishti.iceberg.meta.Snapshot;
import com.ash.drishti.iceberg.scan.Planner;
import com.ash.drishti.iceberg.types.Schema;
import com.ash.drishti.iceberg.types.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import org.apache.iceberg.AppendFiles;
import org.apache.iceberg.ContentFile;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.DeleteFiles;
import org.apache.iceberg.ExpireSnapshots;
import org.apache.iceberg.OverwriteFiles;
import org.apache.iceberg.RewriteFiles;
import org.apache.iceberg.RewriteManifests;
import org.apache.iceberg.RowDelta;
import org.apache.iceberg.UpdateProperties;
import org.apache.iceberg.UpdateSchema;
import org.apache.iceberg.exceptions.ValidationException;

/**
 * The table operations, each one commit by Apache Iceberg's own snapshot producers (manifests written, conflicts retried by
 * {@code commit.retry.*}): append, replace partitions, delete partitions, rewrite files, rewrite manifests, expire snapshots, add columns,
 * set properties. Safe from any thread.
 */
public final class Operations {

    private Operations() {}

    /** The files read for a rewrite changed since. */
    public static final class ValidationFailed extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ValidationFailed(String message) {
            super(message);
        }
    }

    private static Optional<Snapshot> current(Table t) {
        t.refresh();
        return t.currentSnapshot();
    }

    /** Adds data files (a new day); empty when there are none. */
    public static Optional<Snapshot> append(Table t, List<DataFile> files, Map<String, String> summary) {
        if (files.isEmpty()) {
            return Optional.empty();
        }
        AppendFiles a = t.iceberg().newAppend();
        files.forEach(f -> a.appendFile(Bridge.iceberg(f, t.iceberg())));
        summary.forEach(a::set);
        a.commit();
        return current(t);
    }

    /** The live data and delete files {@code picked} chooses. */
    private record Picked(List<org.apache.iceberg.DataFile> data, List<DeleteFile> deletes) {
        boolean isEmpty() {
            return data.isEmpty() && deletes.isEmpty();
        }
    }

    private static Picked pick(Table t, Predicate<DataFile> picked) {
        org.apache.iceberg.Table table = t.iceberg();
        table.refresh();
        Picked p = new Picked(new ArrayList<>(), new ArrayList<>());
        if (table.currentSnapshot() == null) {
            return p;
        }
        Planner.live(table, table.currentSnapshot(), f -> {
            if (picked.test(Bridge.file(f, table.specs().get(f.specId())))) {
                if (f instanceof DeleteFile d) {
                    p.deletes.add(d.copy());
                } else {
                    p.data.add(((org.apache.iceberg.DataFile) f).copy());
                }
            }
        });
        return p;
    }

    /** Delete files of partitions just replaced: they apply to nothing now, so they go in a commit of their own. */
    private static void dropDeletes(org.apache.iceberg.Table table, List<DeleteFile> deletes) {
        if (!deletes.isEmpty()) {
            RowDelta r = table.newRowDelta();
            deletes.forEach(r::removeDeletes);
            r.commit();
        }
    }

    /**
     * Replaces the partitions {@code inPartition} picks: their live data files are removed and {@code files} added, in one snapshot
     * (an {@code overwrite}); a reader sees the old partitions or the new ones.
     */
    public static Optional<Snapshot> replacePartitions(Table t, Predicate<DataFile> inPartition, List<DataFile> files, Map<String, String> summary) {
        org.apache.iceberg.Table table = t.iceberg();
        Picked p = pick(t, inPartition);
        if (p.data.isEmpty() && files.isEmpty() && p.deletes.isEmpty()) {
            return Optional.empty();
        }
        if (p.data.isEmpty()) {
            if (!files.isEmpty()) {
                append(t, files, summary);
            }
        } else {
            OverwriteFiles o = table.newOverwrite();
            p.data.forEach(o::deleteFile);
            files.forEach(f -> o.addFile(Bridge.iceberg(f, table)));
            summary.forEach(o::set);
            o.commit();
        }
        dropDeletes(table, p.deletes);
        return current(t);
    }

    /** Removes whole partitions (metadata only: no data file is read or written). */
    public static Optional<Snapshot> deletePartitions(Table t, Predicate<DataFile> inPartition, Map<String, String> summary) {
        org.apache.iceberg.Table table = t.iceberg();
        Picked p = pick(t, inPartition);
        if (p.isEmpty()) {
            return Optional.empty();
        }
        if (!p.data.isEmpty()) {
            DeleteFiles d = table.newDelete();
            p.data.forEach(d::deleteFile);
            summary.forEach(d::set);
            d.commit();
        }
        dropDeletes(table, p.deletes);
        return current(t);
    }

    /**
     * Rewrites files: {@code remove} (data and delete files, by path) replaced by {@code add}, validated: the files {@code scope} picks
     * must be exactly those the rewrite read ({@code read}), so a day changed since fails with {@link ValidationFailed} instead of losing
     * that change.
     */
    public static Optional<Snapshot> rewriteFiles(Table t, Set<String> remove, List<DataFile> add, Predicate<DataFile> scope, Set<String> read) {
        org.apache.iceberg.Table table = t.iceberg();
        Picked inScope = pick(t, scope);
        Set<String> now = new HashSet<>();
        inScope.data.forEach(f -> now.add(f.location()));
        inScope.deletes.forEach(f -> now.add(f.location()));
        if (!now.equals(read)) {
            Set<String> added = new HashSet<>(now);
            added.removeAll(read);
            Set<String> gone = new HashSet<>(read);
            gone.removeAll(now);
            throw new ValidationFailed("the files being rewritten changed since they were read (" + added.size() + " added, " + gone.size()
                    + " removed by another writer); run the rewrite again");
        }
        Picked out = pick(t, f -> remove.contains(f.path()));
        if (out.isEmpty() && add.isEmpty()) {
            return Optional.empty();
        }
        RewriteFiles r = table.newRewrite().validateFromSnapshot(table.currentSnapshot().snapshotId());
        out.data.forEach(r::deleteFile);
        out.deletes.forEach(r::deleteFile);
        add.forEach(f -> r.addFile(Bridge.iceberg(f, table)));
        try {
            r.commit();
        } catch (ValidationException e) {
            throw new ValidationFailed(e.getMessage() + "; run the rewrite again");
        }
        return current(t);
    }

    /** Regroups every live file into manifests by {@code clusterBy} (one per month of business dates, say). */
    public static Optional<Snapshot> rewriteManifests(Table t, Function<DataFile, String> clusterBy) {
        org.apache.iceberg.Table table = t.iceberg();
        table.refresh();
        if (table.currentSnapshot() == null) {
            return Optional.empty();
        }
        RewriteManifests r = table.rewriteManifests();
        r.clusterBy(f -> clusterBy.apply(Bridge.file((ContentFile<?>) f, table.specs().get(f.specId()))));
        r.set("manifests-rewritten", "true");
        r.commit();
        return current(t);
    }

    /**
     * Expires the snapshots older than {@code olderThanMs}, always keeping the newest {@code retainLast}, the current one and every branch
     * and tag, and deletes the files only they used. Returns how many snapshots went.
     */
    public static int expireSnapshots(Table t, long olderThanMs, int retainLast) {
        org.apache.iceberg.Table table = t.iceberg();
        table.refresh();
        int before = sizeOf(table);
        if (before == 0) {
            return 0;
        }
        ExpireSnapshots e = table.expireSnapshots().expireOlderThan(olderThanMs).retainLast(Math.max(1, retainLast)).cleanExpiredFiles(true);
        e.commit();
        table.refresh();
        return before - sizeOf(table);
    }

    private static int sizeOf(org.apache.iceberg.Table table) {
        int n = 0;
        for (org.apache.iceberg.Snapshot s : table.snapshots()) {
            n += s == null ? 0 : 1;
        }
        return n;
    }

    /** Adds optional columns (name to type); columns already there are left as they are. Returns the table's schema after the change. */
    public static Schema addColumns(Table t, Map<String, Type> columns) {
        org.apache.iceberg.Table table = t.iceberg();
        table.refresh();
        UpdateSchema u = table.updateSchema();
        boolean changed = false;
        for (Map.Entry<String, Type> c : columns.entrySet()) {
            if (table.schema().findField(c.getKey()) == null) {
                u.addColumn(c.getKey(), Bridge.iceberg(c.getValue()));
                changed = true;
            }
        }
        if (changed) {
            u.commit();
        }
        t.refresh();
        return t.metadata().schema();
    }

    /** Sets table properties. */
    public static void setProperties(Table t, Map<String, String> props) {
        org.apache.iceberg.Table table = t.iceberg();
        table.refresh();
        if (table.properties().entrySet().containsAll(props.entrySet())) {
            return;
        }
        UpdateProperties u = table.updateProperties();
        props.forEach(u::set);
        u.commit();
        t.refresh();
    }
}
