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
package com.ash.drishti.iceberg.scan;

import com.ash.drishti.iceberg.manifest.DataFile;
import com.ash.drishti.iceberg.meta.PartitionSpec;
import java.util.List;

/**
 * One data file of a snapshot to read, with the delete files that apply to it (by the spec's rules: position deletes
 * of its partition at or after its data sequence number, equality deletes of its partition, or global ones, strictly
 * after it) and the spec its partition values are of.
 */
public record FileTask(DataFile file, long dataSequenceNumber, PartitionSpec spec, List<DataFile> deletes) {

    public FileTask {
        deletes = List.copyOf(deletes);
    }

    public boolean hasDeletes() {
        return !deletes.isEmpty();
    }

    public boolean hasPositionDeletes() {
        return deletes.stream().anyMatch(d -> d.content() == DataFile.POSITION_DELETES);
    }
}
