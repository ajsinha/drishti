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
package com.ash.drishti.iceberg;

import com.ash.drishti.iceberg.catalog.TableOps;
import com.ash.drishti.iceberg.expr.Expr;
import com.ash.drishti.iceberg.io.FileAccess;
import com.ash.drishti.iceberg.meta.Snapshot;
import com.ash.drishti.iceberg.meta.TableMetadata;
import com.ash.drishti.iceberg.read.TaskReader;
import com.ash.drishti.iceberg.scan.FileTask;
import com.ash.drishti.iceberg.scan.Planner;
import com.ash.drishti.iceberg.types.NestedField;
import com.ash.drishti.iceberg.types.Schema;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

/**
 * One Iceberg table read (and written) by drishti-iceberg: its current metadata, re-read on {@link #refresh()}, the
 * plans of its snapshots and the rows of their files. The metadata is swapped atomically, so readers on any thread
 * see one version or the next, never a mix. Writes go through {@link com.ash.drishti.iceberg.write.Transaction}.
 */
public final class Table {

    private final String name;
    private final TableOps ops;
    private volatile TableOps.Loaded state;

    public Table(String name, TableOps ops, TableOps.Loaded state) {
        this.name = name;
        this.ops = ops;
        this.state = state;
    }

    /** Opens the table at its current version. */
    public static Table load(String name, TableOps ops) throws IOException {
        return new Table(name, ops, ops.load());
    }

    public String name() {
        return name;
    }

    public TableOps ops() {
        return ops;
    }

    public TableMetadata metadata() {
        return state.metadata();
    }

    /** The metadata file (or REST metadata location) the current version was read from. */
    public String metadataLocation() {
        return state.location();
    }

    public TableOps.Loaded state() {
        return state;
    }

    /** The files of this table (through the catalog's vended credentials, when it gives any). */
    public FileAccess io() {
        return ops.io(state);
    }

    /** Re-reads the metadata; true when it changed. */
    public boolean refresh() {
        try {
            Optional<TableOps.Loaded> next = ops.refresh(state);
            next.ifPresent(n -> state = n);
            return next.isPresent();
        } catch (IOException e) {
            throw new UncheckedIOException("refreshing " + ops.describe(), e);
        }
    }

    /** After a commit: the version it made is current. */
    public void committed(TableOps.Loaded next) {
        state = next;
    }

    public Optional<Snapshot> currentSnapshot() {
        return metadata().currentSnapshot();
    }

    /** The data files of a snapshot that may hold rows matching {@code filter}, with their deletes. */
    public List<FileTask> plan(Snapshot snapshot, Expr filter, ExecutorService pool) {
        return Planner.plan(metadata(), snapshot, filter, io(), pool);
    }

    /**
     * The live rows of a planned file: the columns {@code ids} (top-level field ids of {@code schema}, the snapshot's)
     * in that order, row groups pruned by {@code filter}.
     */
    public TaskReader rows(FileTask task, Schema schema, List<Integer> ids, Expr filter, boolean pageIndex) {
        List<NestedField> projection = ids.stream().map(id -> {
            NestedField f = schema.field(id);
            if (f == null) {
                throw new IllegalArgumentException("no column " + id + " in schema " + schema.schemaId() + " of " + name);
            }
            return f;
        }).toList();
        try {
            return new TaskReader(task, metadata(), schema, projection, filter, io(), pageIndex);
        } catch (IOException e) {
            throw new UncheckedIOException("reading " + task.file().path(), e);
        }
    }

    @Override
    public String toString() {
        return name + " (" + ops.describe() + ")";
    }
}
