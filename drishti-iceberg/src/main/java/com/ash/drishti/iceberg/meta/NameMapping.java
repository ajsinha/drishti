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

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A table's name mapping ({@code schema.name-mapping.default}, the spec's "Column Projection"): for data files written
 * without field ids (files added from Hive or plain Parquet), which column name, at which nesting, is which field id.
 * A column it does not name is not read (it is null), never guessed.
 */
public final class NameMapping {

    private final Map<String, Integer> byPath = new HashMap<>();

    private NameMapping() {}

    public static NameMapping parse(String json) {
        try {
            NameMapping m = new NameMapping();
            m.add(TableMetadata.JSON.readTree(json), "");
            return m;
        } catch (IOException e) {
            throw new UncheckedIOException("unreadable schema.name-mapping.default", e);
        }
    }

    private void add(JsonNode fields, String prefix) {
        for (JsonNode f : fields) {
            List<String> names = new java.util.ArrayList<>();
            f.path("names").forEach(n -> names.add(n.asText()));
            for (String name : names) {
                String path = prefix + name;
                if (f.hasNonNull("field-id")) {
                    byPath.put(path, f.get("field-id").asInt());
                }
                add(f.path("fields"), path + ".");
            }
        }
    }

    /** The field id of the column at this path of names ({@code a.b}; a list's element and a map's parts by their names in the file), or null. */
    public Integer id(String path) {
        return byPath.get(path);
    }
}
