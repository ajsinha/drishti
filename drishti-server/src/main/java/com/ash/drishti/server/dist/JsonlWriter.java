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
package com.ash.drishti.server.dist;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON Lines of an export, Drishti's data format (docs/guides/DATA_FORMATS.md): UTF-8, one JSON object per row, its keys the column names
 * the CSV of the same export has as its header, values typed (numbers stay numbers, dates ISO text, an empty cell null). A masked cell
 * reads as on the screen. A document with several sections gives each row a {@code "section"} key holding its section's title, where the
 * CSV writes the title above the section.
 */
public final class JsonlWriter {

    /** The media type a JSONL download and a webhook delivery are sent with. */
    public static final String MEDIA_TYPE = "application/jsonl";

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private JsonlWriter() {}

    public static byte[] write(ExportDoc doc) {
        StringBuilder b = new StringBuilder();
        boolean several = doc.sections().size() > 1;
        for (ExportDoc.Section s : doc.sections()) {
            for (List<Object> row : s.rows()) {
                Map<String, Object> line = new LinkedHashMap<>();
                if (several) {
                    line.put("section", s.title());
                }
                for (int c = 0; c < s.columns().size(); c++) {
                    Object v = s.shown(row, c);
                    line.put(s.columns().get(c), "".equals(v) ? null : v);
                }
                b.append(line(line));
            }
        }
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Rows of a table: one line per row, its cells under the columns' names. */
    public static byte[] rows(List<String> columns, List<? extends List<?>> rows) {
        StringBuilder b = new StringBuilder();
        for (List<?> row : rows) {
            Map<String, Object> line = new LinkedHashMap<>();
            for (int c = 0; c < columns.size(); c++) {
                line.put(columns.get(c), c < row.size() ? row.get(c) : null);
            }
            b.append(line(line));
        }
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** One object as one line, newline included. */
    public static String line(Object row) {
        try {
            return JSON.writeValueAsString(row) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("a row could not be written as JSON: " + e.getOriginalMessage(), e);
        }
    }
}
