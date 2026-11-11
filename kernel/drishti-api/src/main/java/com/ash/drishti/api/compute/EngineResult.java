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
package com.ash.drishti.api.compute;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What an engine computed (DATA_ARCHITECTURE.md › Reactive connectors, section 8): small rows, named tables (a 509-row path, a contribution table) and the
 * engine's metadata. The server validates it, stamps it and turns it into the kind's document: one row's fields at the top level, every
 * row under {@code RESULTS}, the tables under {@code TABLES}, the stamp under {@code STAMP}.
 *
 * @param rows the result's rows (JSON values: numbers, text, booleans, lists, maps)
 * @param tables named tables, each a list of rows
 * @param meta the engine's notes (counts, version)
 */
public record EngineResult(List<Map<String, Object>> rows, Map<String, List<Map<String, Object>>> tables, Map<String, Object> meta) {

    public EngineResult {
        rows = rows == null ? List.of() : List.copyOf(rows);
        tables = tables == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(tables));
        meta = meta == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(meta));
    }

    /** A result of one row. */
    public static EngineResult of(Map<String, Object> row) {
        return new EngineResult(List.of(row), Map.of(), Map.of());
    }
}
