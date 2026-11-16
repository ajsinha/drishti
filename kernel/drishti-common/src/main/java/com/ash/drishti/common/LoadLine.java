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
package com.ash.drishti.common;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.io.IOException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One line of a load file as the loaders read it: {@code domain}, {@code kind}, {@code id}, {@code date}, {@code doc} and the promoted
 * {@code columns}; any other field is skipped. The date stays text until {@link #day()}, so a loader can refuse a line that lacks a field
 * before a bad date is reported.
 */
public record LoadLine(String domain, String kind, String id, String date, String doc, Map<String, Object> columns) {

    /** The line's business date, or null when it has none. */
    public LocalDate day() {
        return date == null ? null : LocalDate.parse(date);
    }

    /** Reads one line; a promoted value is a number (as a double), null or its text. */
    public static LoadLine parse(JsonFactory json, String line) throws IOException {
        return parse(json, line, false);
    }

    /** As {@link #parse(JsonFactory, String)}, with a promoted object or array read as null: a promoted path is a value, not an object. */
    public static LoadLine parseValuesOnly(JsonFactory json, String line) throws IOException {
        return parse(json, line, true);
    }

    private static LoadLine parse(JsonFactory json, String line, boolean valuesOnly) throws IOException {
        String domain = null;
        String kind = null;
        String id = null;
        String date = null;
        String doc = null;
        Map<String, Object> columns = new LinkedHashMap<>();
        try (JsonParser p = json.createParser(line)) {
            p.nextToken();
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String name = p.currentName();
                JsonToken t = p.nextToken();
                switch (name) {
                    case "domain" -> domain = p.getText();
                    case "kind" -> kind = p.getText();
                    case "id" -> id = p.getText();
                    case "date" -> date = p.getText();
                    case "doc" -> doc = p.getText();
                    case "columns" -> {
                        if (t == JsonToken.START_OBJECT) {
                            while (p.nextToken() == JsonToken.FIELD_NAME) {
                                String path = p.currentName();
                                columns.put(path, value(p, p.nextToken(), valuesOnly));
                            }
                        }
                    }
                    default -> p.skipChildren();
                }
            }
        }
        return new LoadLine(domain, kind, id, date, doc, columns);
    }

    private static Object value(JsonParser p, JsonToken v, boolean valuesOnly) throws IOException {
        if (v == JsonToken.VALUE_NUMBER_INT || v == JsonToken.VALUE_NUMBER_FLOAT) {
            return p.getDoubleValue();
        }
        if (v == JsonToken.VALUE_NULL) {
            return null;
        }
        if (valuesOnly && (v == JsonToken.START_OBJECT || v == JsonToken.START_ARRAY)) {
            p.skipChildren();
            return null;
        }
        return p.getText();
    }
}
