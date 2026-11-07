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

import com.ash.drishti.iceberg.io.FileAccess;
import com.ash.drishti.iceberg.manifest.DataFile;
import com.ash.drishti.iceberg.read.DeletionVectors;
import io.delta.kernel.engine.Engine;
import io.delta.kernel.internal.actions.DeletionVectorDescriptor;
import io.delta.kernel.internal.deletionvectors.DeletionVectorStoredBitmap;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Delta's deletion vectors (Databricks writes them by default: rows deleted or updated in place) for the Iceberg reader: a data file with
 * a vector gets a position-delete {@link DataFile} of format {@value #FORMAT} whose path names the vector, and the reader asks this class
 * for the deleted positions, read through Delta Kernel with the engine (and credentials) of the table the vector belongs to. Inline,
 * relative ({@code u}) and absolute ({@code p}) vectors are read. Tables register while they are open.
 */
public final class DeltaVectors implements DeletionVectors {

    /** The delete-file format of a Delta deletion vector. */
    public static final String FORMAT = "DELTA-DV";

    private static final String PREFIX = "deltadv:";
    private static final Map<String, Owner> TABLES = new ConcurrentHashMap<>();

    private record Owner(Engine engine, String tablePath) {}

    /** Registers a table's engine and path under {@code key} (until {@link #unregister}). */
    static void register(String key, Engine engine, String tablePath) {
        TABLES.put(key, new Owner(engine, tablePath));
    }

    static void unregister(String key) {
        TABLES.remove(key);
    }

    /** The position-delete file standing for {@code dv} of the data file {@code dataPath} of the table registered as {@code key}. */
    static DataFile file(String key, String dataPath, Object[] partition, int specId, DeletionVectorDescriptor dv) {
        String path = PREFIX + key + "/" + dv.getStorageType() + "/" + dv.getOffset().map(String::valueOf).orElse("-") + "/" + dv.getSizeInBytes()
                + "/" + dv.getCardinality() + "/" + dv.getPathOrInlineDv();
        return new DataFile(DataFile.POSITION_DELETES, path, FORMAT, specId, partition, dv.getCardinality(), dv.getSizeInBytes(), Map.of(),
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), null, List.of(), List.of(), null, dataPath);
    }

    @Override
    public String format() {
        return FORMAT;
    }

    @Override
    public long[] positions(DataFile vector, FileAccess io) throws IOException {
        String p = vector.path();
        if (!p.startsWith(PREFIX)) {
            throw new IOException("not a Delta deletion vector: " + p);
        }
        String[] parts = p.substring(PREFIX.length()).split("/", 6);
        Owner owner = TABLES.get(parts[0]);
        if (owner == null) {
            throw new IOException("the Delta table of the deletion vector of " + vector.referencedDataFile() + " is closed");
        }
        DeletionVectorDescriptor dv = new DeletionVectorDescriptor(parts[1], parts[5], parts[2].equals("-") ? Optional.empty()
                : Optional.of(Integer.parseInt(parts[2])), Integer.parseInt(parts[3]), Long.parseLong(parts[4]));
        long[] out = new DeletionVectorStoredBitmap(dv, Optional.of(owner.tablePath())).load(owner.engine().getFileSystemClient()).toArray();
        java.util.Arrays.sort(out);
        return out;
    }
}
