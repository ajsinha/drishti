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
package com.ash.drishti.iceberg.read;

import com.ash.drishti.iceberg.expr.Expr;
import com.ash.drishti.iceberg.io.FileAccess;
import com.ash.drishti.iceberg.meta.PartitionSpec;
import com.ash.drishti.iceberg.meta.TableMetadata;
import com.ash.drishti.iceberg.meta.Transform;
import com.ash.drishti.iceberg.scan.FileTask;
import com.ash.drishti.iceberg.types.NestedField;
import com.ash.drishti.iceberg.types.Schema;
import com.ash.drishti.lakeio.ParquetInput;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * The live rows of one planned file: its Parquet rows ({@link ParquetRowReader}) less the rows its delete files delete
 * ({@link Deletes}). Columns the equality deletes compare are read too, when the projection lacks them, and dropped
 * from what is returned. Used by one thread; close it (or read it to the end) to release the file.
 */
public final class TaskReader implements Closeable {

    private final ParquetRowReader reader;
    private final Deletes deletes;
    private final int width;
    private final Map<Integer, Integer> slot = new HashMap<>();
    private Object[] next;
    private long nextPosition;

    /**
     * @param schema the snapshot's schema (the projection's fields come from it)
     * @param projection the columns to return, in order
     * @param filter for row-group pruning (rows are not filtered by it: the caller checks)
     * @param pageIndex skip pages by the column index too, when the file's positions are not needed by its deletes
     */
    public TaskReader(FileTask task, TableMetadata table, Schema schema, List<NestedField> projection, Expr filter, FileAccess io, boolean pageIndex)
            throws IOException {
        this.deletes = Deletes.of(task.file(), task.deletes(), table.schema(), io);
        this.width = projection.size();
        List<NestedField> read = new ArrayList<>(projection);
        for (int i = 0; i < read.size(); i++) {
            slot.put(read.get(i).id(), i);
        }
        for (int id : deletes.equalityIds()) {
            if (!slot.containsKey(id)) {
                NestedField f = schema.field(id) != null ? schema.field(id) : table.schema().field(id);
                slot.put(id, read.size());
                read.add(f);
            }
        }
        Map<Integer, Object> constants = new HashMap<>();
        PartitionSpec spec = task.spec();
        for (int i = 0; i < spec.fields().size(); i++) {
            if (spec.fields().get(i).transform() instanceof Transform.Identity && i < task.file().partition().length) {
                constants.put(spec.fields().get(i).sourceId(), task.file().partition()[i]);
            }
        }
        String p = io.resolve(task.file().path());
        this.reader = new ParquetRowReader(ParquetInput.of(io.storage(p), p, task.file().fileSizeInBytes()), task.file().path(),
                task.file().fileSizeInBytes(), read, table.nameMapping().orElse(null), constants, filter, io.codecs(),
                pageIndex && !deletes.hasPositions());
    }

    /** Whether there is another live row. */
    public boolean hasNext() {
        while (next == null) {
            if (!reader.hasNext()) {
                return false;
            }
            ParquetRowReader.Row r = reader.next();
            Object[] v = r.values();
            if (!deletes.isEmpty() && deletes.deleted(r.position(), id -> v[slot.get(id)])) {
                continue;
            }
            next = v.length == width ? v : java.util.Arrays.copyOf(v, width);
            nextPosition = r.position();
        }
        return true;
    }

    /** The next live row's values, in the projection's order. */
    public Object[] next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        Object[] out = next;
        next = null;
        return out;
    }

    /** The position in its file of the row {@link #next()} returned last (before it is called again). */
    public long lastPosition() {
        return nextPosition;
    }

    /** Whether any delete file applies to this file. */
    public boolean hasDeletes() {
        return !deletes.isEmpty();
    }

    @Override
    public void close() {
        try {
            reader.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
