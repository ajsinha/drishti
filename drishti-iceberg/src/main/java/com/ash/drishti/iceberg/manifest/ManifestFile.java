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

/**
 * One manifest as a snapshot's manifest list names it: where it is, what it holds (data or delete files), the
 * sequence number of the commit that added it (the one its new entries inherit) and, per partition field, a summary of
 * the values in it, which planning prunes whole manifests with.
 *
 * @param content 0 data files, 1 delete files
 * @param partitions per partition field of its spec: whether it holds nulls, NaNs, and the lower and upper bound (binary form)
 */
public record ManifestFile(String path, long length, int specId, int content, long sequenceNumber, long minSequenceNumber, Long addedSnapshotId,
        Integer addedFilesCount, Integer existingFilesCount, Integer deletedFilesCount, Long addedRowsCount, Long existingRowsCount,
        Long deletedRowsCount, List<FieldSummary> partitions, byte[] keyMetadata) {

    public static final int DATA = 0;
    public static final int DELETES = 1;

    /** A partition field's values in the manifest. */
    public record FieldSummary(boolean containsNull, Boolean containsNan, byte[] lowerBound, byte[] upperBound) {}

    /** Whether the manifest may list live files (a manifest of only deleted entries does not). */
    public boolean hasLiveFiles() {
        return addedFilesCount == null || existingFilesCount == null || addedFilesCount + existingFilesCount > 0;
    }
}
