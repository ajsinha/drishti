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
package com.ash.drishti.plugin.delta;

import io.delta.kernel.Scan;
import io.delta.kernel.Table;
import io.delta.kernel.data.ColumnarBatch;
import io.delta.kernel.data.FilteredColumnarBatch;
import io.delta.kernel.data.Row;
import io.delta.kernel.engine.Engine;
import io.delta.kernel.engine.FileReadResult;
import io.delta.kernel.internal.InternalScanFileUtils;
import io.delta.kernel.internal.deletionvectors.Base85Codec;
import io.delta.kernel.internal.util.Utils;
import io.delta.kernel.types.StringType;
import io.delta.kernel.types.StructType;
import io.delta.kernel.utils.CloseableIterator;
import io.delta.kernel.utils.FileStatus;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.CRC32;
import org.roaringbitmap.RoaringBitmap;

/**
 * Deletes one row of a Delta table in place, as Databricks or Spark do with deletion vectors: a deletion-vector file
 * beside the table ({@code deletion_vector_<uuid>.bin}: a format byte, then the bitmap's size, the bitmap as a
 * portable RoaringBitmapArray, and its CRC-32) and a commit that upgrades the protocol to the {@code deletionVectors}
 * feature and re-adds the data file with the vector. delta-rs does not write deletion vectors, so tests make them
 * this way, by the Delta protocol.
 */
final class DeletionVectorFixture {

    private DeletionVectorFixture() {}

    /** Deletes {@code id}'s row on {@code date} from the table at {@code table}; returns the new version. */
    static long delete(Engine engine, Path table, String date, String id) throws Exception {
        var snapshot = Table.forPath(engine, table.toString()).getLatestSnapshot(engine);
        Scan scan = snapshot.getScanBuilder().build();
        try (CloseableIterator<FilteredColumnarBatch> files = scan.getScanFiles(engine)) {
            while (files.hasNext()) {
                try (CloseableIterator<Row> rows = files.next().getRows()) {
                    while (rows.hasNext()) {
                        Row file = rows.next();
                        if (!date.equals(InternalScanFileUtils.getPartitionValues(file).get("business_date"))) {
                            continue;
                        }
                        FileStatus fs = InternalScanFileUtils.getAddFileStatus(file);
                        long row = rowOf(engine, fs, id);
                        if (row >= 0) {
                            return write(table, snapshot.getVersion() + 1, fs, date, row);
                        }
                    }
                }
            }
        }
        throw new IllegalArgumentException(id + " is not in " + table + " on " + date);
    }

    private static long rowOf(Engine engine, FileStatus file, String id) throws Exception {
        long at = 0;
        try (CloseableIterator<FileReadResult> it = engine.getParquetHandler().readParquetFiles(Utils.singletonCloseableIterator(file),
                new StructType().add("id", StringType.STRING), Optional.empty())) {
            while (it.hasNext()) {
                ColumnarBatch b = it.next().getData();
                for (int i = 0; i < b.getSize(); i++, at++) {
                    if (id.equals(b.getColumnVector(0).getString(i))) {
                        return at;
                    }
                }
            }
        }
        return -1;
    }

    private static long write(Path table, long version, FileStatus file, String date, long row) throws Exception {
        RoaringBitmap bitmap = RoaringBitmap.bitmapOf((int) row);
        ByteBuffer data = ByteBuffer.allocate(4 + 8 + 4 + bitmap.serializedSizeInBytes()).order(ByteOrder.LITTLE_ENDIAN);
        data.putInt(1681511377).putLong(1).putInt(0);              // portable format: magic, one bitmap, its key
        bitmap.serialize(data);
        byte[] bytes = data.array();
        CRC32 crc = new CRC32();
        crc.update(bytes);
        UUID uuid = UUID.randomUUID();
        ByteArrayOutputStream dv = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(dv)) {
            out.writeByte(1);                                      // format version
            out.writeInt(bytes.length);
            out.write(bytes);
            out.writeInt((int) crc.getValue());
        }
        Files.write(table.resolve("deletion_vector_" + uuid + ".bin"), dv.toByteArray());
        String rel = file.getPath().substring(file.getPath().indexOf("business_date="));
        long now = System.currentTimeMillis();
        String pv = "{\"business_date\":\"" + date + "\"}";
        String commit = String.join("\n",
                "{\"commitInfo\":{\"timestamp\":" + now + ",\"operation\":\"DELETE\"}}",
                "{\"protocol\":{\"minReaderVersion\":3,\"minWriterVersion\":7,\"readerFeatures\":[\"deletionVectors\"],"
                        + "\"writerFeatures\":[\"deletionVectors\"]}}",
                "{\"remove\":{\"path\":\"" + rel + "\",\"deletionTimestamp\":" + now + ",\"dataChange\":true,\"extendedFileMetadata\":true,"
                        + "\"partitionValues\":" + pv + ",\"size\":" + file.getSize() + "}}",
                "{\"add\":{\"path\":\"" + rel + "\",\"partitionValues\":" + pv + ",\"size\":" + file.getSize() + ",\"modificationTime\":" + now
                        + ",\"dataChange\":true,\"deletionVector\":{\"storageType\":\"u\",\"pathOrInlineDv\":\"" + Base85Codec.encodeUUID(uuid)
                        + "\",\"offset\":1,\"sizeInBytes\":" + bytes.length + ",\"cardinality\":1}}}") + "\n";
        Files.writeString(table.resolve("_delta_log").resolve("%020d.json".formatted(version)), commit, StandardCharsets.UTF_8);
        return version;
    }
}
