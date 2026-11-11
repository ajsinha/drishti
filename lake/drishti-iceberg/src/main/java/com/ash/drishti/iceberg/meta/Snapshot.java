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
package com.ash.drishti.iceberg.meta;

import java.util.List;
import java.util.Map;

/**
 * A snapshot: the table's state after one commit. Its files are listed by its manifest list (or, in some v1 tables, by
 * a list of manifests in the metadata itself). The sequence number orders commits (v2; 0 in v1), which decides which
 * delete files apply to which data files.
 *
 * @param operation the summary's {@code operation} (append, overwrite, replace, delete)
 * @param manifests v1 only: the manifests listed in the metadata when there is no manifest list, else empty
 */
public record Snapshot(long snapshotId, Long parentId, long sequenceNumber, long timestampMs, String manifestList, List<String> manifests,
        String operation, Map<String, String> summary, Integer schemaId) {

    public Snapshot {
        manifests = List.copyOf(manifests);
        summary = Map.copyOf(summary);
    }
}
