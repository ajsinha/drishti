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
package com.ash.drishti.iceberg.manifest;

import java.util.List;
import java.util.Map;

/**
 * A data file or a delete file as a manifest lists it (the spec's {@code data_file} struct): what it holds, where its
 * partition is and its column metrics (counts and the lower and upper bounds, in their binary single-value form),
 * which planning prunes with.
 *
 * @param content 0 data, 1 position deletes, 2 equality deletes
 * @param format {@code PARQUET}, {@code ORC}, {@code AVRO} (only Parquet is read)
 * @param specId the partition spec the file was written with
 * @param partition the partition values in the spec's field order (internal form)
 * @param equalityIds an equality delete file's key columns, else empty
 * @param keyMetadata non-null for an encrypted file (refused)
 * @param referencedDataFile a position delete file that deletes from one data file names it (or null)
 */
public record DataFile(int content, String path, String format, int specId, Object[] partition, long recordCount, long fileSizeInBytes,
        Map<Integer, Long> columnSizes, Map<Integer, Long> valueCounts, Map<Integer, Long> nullValueCounts, Map<Integer, Long> nanValueCounts,
        Map<Integer, byte[]> lowerBounds, Map<Integer, byte[]> upperBounds, byte[] keyMetadata, List<Long> splitOffsets, List<Integer> equalityIds,
        Integer sortOrderId, String referencedDataFile) {

    public static final int DATA = 0;
    public static final int POSITION_DELETES = 1;
    public static final int EQUALITY_DELETES = 2;

    public boolean isData() {
        return content == DATA;
    }

    /** The same file with other column metrics (bounds dropped when the table's metrics mode drops them). */
    public DataFile withPartition(int spec, Object[] values) {
        return new DataFile(content, path, format, spec, values, recordCount, fileSizeInBytes, columnSizes, valueCounts, nullValueCounts,
                nanValueCounts, lowerBounds, upperBounds, keyMetadata, splitOffsets, equalityIds, sortOrderId, referencedDataFile);
    }

    @Override
    public String toString() {
        return (content == DATA ? "data file " : content == POSITION_DELETES ? "position delete file " : "equality delete file ") + path;
    }
}
