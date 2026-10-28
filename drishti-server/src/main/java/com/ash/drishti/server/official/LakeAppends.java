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
package com.ash.drishti.server.official;

import com.ash.drishti.plugin.iceberg.IcebergAppender;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Appends to a table of the risk lake in the lake's own format (2.9.5): the intraday PV stream's windows (VAR_GUIDE.md section 8.4)
 * go to Delta (the default) or, when the lake's table is an Iceberg table ({@code <lake>/<domain>/<kind>/metadata}, no
 * {@code _delta_log}), as an Iceberg append, so an Iceberg risk lake takes intraday micro-batches as a Delta one does. Stateless.
 */
public final class LakeAppends {

    private LakeAppends() {
    }

    /** Whether the table is an Iceberg table (its metadata folder, no Delta log). */
    public static boolean iceberg(Path lake, String domain, String kind) {
        Path t = lake.resolve(domain).resolve(kind);
        return Files.isDirectory(t.resolve("metadata")) && !Files.isDirectory(t.resolve("_delta_log"));
    }

    /**
     * Appends one date's rows to the Iceberg table {@code <lake>/<domain>/<kind>} in one commit (nothing replaced: a window beside the
     * morning's run).
     *
     * @param rows id, the document's JSON text and its promoted columns (a vector as a list of numbers)
     * @param types each promoted column's type ({@code double} or {@code string})
     */
    public static long appendIceberg(Path lake, String domain, String kind, LocalDate day, List<IcebergAppender.Row> rows, Map<String, String> types,
            String vector) throws IOException {
        Map<String, Boolean> promoted = new LinkedHashMap<>();
        types.forEach((c, t) -> promoted.put(c, "double".equals(t)));
        return IcebergAppender.append(Map.of("root", lake.toString()), domain, kind, day, rows, promoted,
                vector == null ? Set.of() : Set.of(vector), Map.of());
    }
}
