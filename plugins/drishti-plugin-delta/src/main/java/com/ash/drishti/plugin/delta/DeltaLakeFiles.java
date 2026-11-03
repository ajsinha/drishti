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

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.LakeFiles;
import com.ash.drishti.api.RowEstimate;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A Delta table's files for the pivot engine's direct lake route (docs/architecture/PIVOT_ENGINE.md section 6.2): the Parquet files of the
 * date's snapshot and the promoted columns, only for a snapshot table (one partition holds the date) on a local disk, without deletion
 * vectors (a reader of the bare files would see deleted rows). No data file is read: the layout is the cached one.
 */
final class DeltaLakeFiles {

    private DeltaLakeFiles() {}

    static Optional<LakeFiles> of(DeltaSourcePlugin plugin, String kind, AsOf asOf) {
        DeltaTable table = plugin.tables.get(kind);
        if ("effective".equals(plugin.modes.get(kind)) || table == null) {
            return Optional.empty();
        }
        Optional<DeltaTable.Layout> l = plugin.readableLayout(kind, asOf.knownAt());
        if (l.isEmpty() || l.get().deletionVectors()) {
            return Optional.empty();
        }
        List<LocalDate> dates = plugin.candidates(kind, l.get(), asOf.businessDate());
        if (dates.isEmpty()) {
            return Optional.empty();
        }
        LocalDate d = dates.get(0);
        List<String> files = new ArrayList<>();
        long bytes = 0;
        for (io.delta.kernel.data.Row r : l.get().files().getOrDefault(d, List.of())) {
            io.delta.kernel.utils.FileStatus fs = io.delta.kernel.internal.InternalScanFileUtils.getAddFileStatus(r);
            String path = fs.getPath();
            if (path.startsWith("file:")) {
                path = java.nio.file.Path.of(java.net.URI.create(path)).toString();
            } else if (path.contains("://")) {
                return Optional.empty();                       // an object store: the connector reads it
            }
            files.add(path);
            bytes += fs.getSize();
        }
        if (files.isEmpty()) {
            return Optional.empty();
        }
        long rows;
        try {
            rows = plugin.rowEstimate(kind, d).map(RowEstimate::rows).orElse(-1L);
        } catch (RuntimeException e) {
            rows = -1;
        }
        return Optional.of(new LakeFiles("delta", files, plugin.promotedColumns(kind, l.get()), table.idColumn(), d, bytes, rows));
    }
}
