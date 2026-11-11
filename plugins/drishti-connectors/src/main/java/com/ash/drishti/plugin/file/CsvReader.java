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
final class CsvReader {

    private CsvReader() {}

    static DataNode read(BufferedReader in) throws IOException {
        String headerLine = in.readLine();
        if (headerLine == null) {
            return new DataNode.Obj(Map.of("rows", new DataNode.Arr(List.of())));
        }
        List<String> header = split(headerLine);
        List<DataNode> rows = new ArrayList<>();
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            List<String> cells = split(line);
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

    /** Splits one CSV line; supports double-quoted cells with doubled quotes inside. */
    static List<String> split(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }
}
