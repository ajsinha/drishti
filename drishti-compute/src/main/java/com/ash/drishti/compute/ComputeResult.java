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
package com.ash.drishti.compute;

import com.ash.drishti.compute.arrow.ArrowTable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What one request produced: small rows (JSON values), named tables (read back from the worker's Arrow files and validated), the engine's
 * metadata, and where and how long it ran. Delivered to its request id only.
 *
 * @param requestId the correlation id it answers
 * @param rows small results as rows
 * @param tables large results as tables
 * @param meta the engine's metadata (its version, counts)
 * @param worker the worker that ran it ({@code <node>/<pool>#<n>})
 * @param node the node that ran it
 * @param queuedMs time waiting for a worker
 * @param runMs time in the worker, inputs written to result read
 */
public record ComputeResult(String requestId, List<Map<String, Object>> rows, Map<String, ArrowTable> tables, Map<String, Object> meta,
        String worker, String node, long queuedMs, long runMs) {

    public ComputeResult {
        rows = rows == null ? List.of() : List.copyOf(rows);
        tables = tables == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(tables));
        meta = meta == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(meta));
    }

    public ArrowTable table(String name) {
        ArrowTable t = tables.get(name);
        if (t == null) {
            throw new IllegalArgumentException("the result has no table " + name + " (tables: " + tables.keySet() + ")");
        }
        return t;
    }
}
