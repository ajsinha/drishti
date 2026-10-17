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
package com.ash.drishti.deltalake.write;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What one {@code lake write} does, as {@code tools/lake/writers.py} writes it: the table, its columns and partitioning, the mode,
 * and the staged files (one per business date and partition values, a JSON array {@code [id, document, {promoted}]} per line).
 *
 * @param table the table's folder
 * @param partitionColumns {@code business_date} then the partition columns
 * @param columns every column with its type ({@code string}, {@code double}, {@code date})
 * @param promoted the promoted columns, read from the third element of a staged row
 * @param mode {@code overwrite-dates}, {@code overwrite-partitions} or {@code append}
 * @param batchRows rows per batch handed to the Parquet writer
 * @param statsColumns the columns that keep min/max statistics (never the document)
 * @param parts the staged files
 */
public record LakeManifest(String table, List<String> partitionColumns, List<Column> columns, List<String> promoted, String mode,
        int batchRows, List<String> statsColumns, List<Part> parts) {

    /** A column and its type. */
    public record Column(String name, String type) {}

    /** A staged file: its rows all have these partition values ({@code business_date} as yyyy-MM-dd; a null value is null). */
    public record Part(Path file, long rows, Map<String, String> values) {}

    public static final List<String> MODES = List.of("overwrite-dates", "overwrite-partitions", "append");

    /** Reads and checks a manifest file. */
    public static LakeManifest read(Path file) throws IOException {
        JsonNode n = new ObjectMapper().readTree(file.toFile());
        List<Column> columns = new ArrayList<>();
        n.path("columns").forEach(c -> columns.add(new Column(c.path("name").asText(), c.path("type").asText("string"))));
        List<Part> parts = new ArrayList<>();
        for (JsonNode p : n.path("parts")) {
            Map<String, String> values = new LinkedHashMap<>();
            p.path("values").fields().forEachRemaining(e -> values.put(e.getKey(), e.getValue().isNull() ? null : e.getValue().asText()));
            parts.add(new Part(Path.of(p.path("file").asText()), p.path("rows").asLong(), values));
        }
        LakeManifest m = new LakeManifest(n.path("table").asText(), strings(n.path("partitionColumns")), columns, strings(n.path("promoted")),
                n.path("mode").asText("overwrite-dates"), n.path("batchRows").asInt(50_000), strings(n.path("statsColumns")), parts);
        m.check();
        return m;
    }

    private static List<String> strings(JsonNode a) {
        List<String> out = new ArrayList<>();
        a.forEach(x -> out.add(x.asText()));
        return out;
    }

    void check() {
        if (table.isBlank()) {
            throw new IllegalArgumentException("the manifest names no table");
        }
        if (!MODES.contains(mode)) {
            throw new IllegalArgumentException("mode " + mode + " is not one of " + MODES);
        }
        if (partitionColumns.isEmpty() || !partitionColumns.get(0).equals("business_date")) {
            throw new IllegalArgumentException("the first partition column must be business_date");
        }
        for (Part p : parts) {
            if (!p.values().keySet().containsAll(partitionColumns)) {
                throw new IllegalArgumentException(p.file() + ": the partition values do not name every partition column");
            }
        }
        if (batchRows < 1) {
            throw new IllegalArgumentException("batchRows must be at least 1");
        }
    }
}
