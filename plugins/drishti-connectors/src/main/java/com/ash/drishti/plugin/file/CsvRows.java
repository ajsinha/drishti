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
package com.ash.drishti.plugin.file;

import com.ash.drishti.api.DataNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads a CSV file with a header row into {@code {"rows": [ {...}, ... ]}}. Numbers and booleans are typed. */
final class CsvRows {

    private CsvRows() {}

    static DataNode read(BufferedReader in) throws IOException {
        com.ash.tools.csv.CsvReader csv = new com.ash.tools.csv.CsvReader(in);
        List<String> header = csv.next();
        if (header == null) {
            return new DataNode.Obj(Map.of("rows", new DataNode.Arr(List.of())));
        }
        List<DataNode> rows = new ArrayList<>();
        for (List<String> cells = csv.next(); cells != null; cells = csv.next()) {
            if (cells.size() == 1 && cells.get(0).isBlank()) {
                continue;
            }
            Map<String, DataNode> row = new LinkedHashMap<>();
            for (int i = 0; i < header.size(); i++) {
                row.put(header.get(i), typed(i < cells.size() ? cells.get(i) : ""));
            }
            rows.add(new DataNode.Obj(row));
        }
        Map<String, DataNode> doc = new LinkedHashMap<>();
        doc.put("rows", new DataNode.Arr(rows));
        return new DataNode.Obj(doc);
    }

    static DataNode typed(String cell) {
        String s = cell.trim();
        if (s.isEmpty()) {
            return DataNode.nullValue();
        }
        if ("true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s)) {
            return new DataNode.Val(Boolean.parseBoolean(s));
        }
        char c = s.charAt(0);
        if (Character.isDigit(c) || ((c == '-' || c == '+' || c == '.') && s.length() > 1)) {
            try {
                return s.contains(".") || s.contains("e") || s.contains("E")
                        ? new DataNode.Val(Double.parseDouble(s)) : new DataNode.Val(Long.parseLong(s));
            } catch (NumberFormatException ignored) {
                // not a number: fall through to text
            }
        }
        return new DataNode.Val(s);
    }
}
