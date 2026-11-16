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
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How the connector reads a JSON-lines line and the document in it, the same way for the index and for a read, so a
 * line the index accepts can be opened: one parser factory with deliberate limits rather than Jackson's defaults (a
 * 20 MB string, which one large trade document already passes).
 *
 * <ul>
 *   <li>{@code max-document-mb} (default 64): the longest line, and so the longest document or string in it. A longer
 *       line is skipped and counted, never held in memory whole.
 *   <li>{@code max-nesting-depth} (default 1000): the deepest nesting of objects and arrays.
 *   <li>{@code NaN}, {@code Infinity} and {@code -Infinity}, which Python's {@code json.dumps} writes by default, are
 *       read and mean "no value": a promoted number is empty (as a missing field is) and the document holds null.
 * </ul>
 *
 * <p>Immutable and thread-safe: parsers are per call.
 */
final class JsonlFormat {

    static final int DEFAULT_MAX_DOCUMENT_MB = 64;
    static final int DEFAULT_MAX_NESTING_DEPTH = 1000;
    /** The defaults, for tests and the loader. */
    static final JsonlFormat DEFAULT = new JsonlFormat(DEFAULT_MAX_DOCUMENT_MB, DEFAULT_MAX_NESTING_DEPTH);

    private final JsonFactory factory;
    private final int maxLineBytes;
    private final int maxDocumentMb;
    private final int maxNestingDepth;

    JsonlFormat(int maxDocumentMb, int maxNestingDepth) {
        if (maxDocumentMb < 1 || maxDocumentMb > 2000) {
            throw new IllegalArgumentException("max-document-mb must be 1..2000: " + maxDocumentMb);
        }
        if (maxNestingDepth < 1) {
            throw new IllegalArgumentException("max-nesting-depth must be positive: " + maxNestingDepth);
        }
        this.maxDocumentMb = maxDocumentMb;
        this.maxNestingDepth = maxNestingDepth;
        this.maxLineBytes = maxDocumentMb << 20;
        this.factory = JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxDocumentLength(maxLineBytes)
                        .maxStringLength(maxLineBytes)
                        .maxNestingDepth(maxNestingDepth)
                        .build())
                .enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
                .build();
    }

    /** The format a connector's settings ask for. */
    static JsonlFormat of(Map<String, String> settings) {
        return new JsonlFormat(Integer.parseInt(settings.getOrDefault("max-document-mb", String.valueOf(DEFAULT_MAX_DOCUMENT_MB)).trim()),
                Integer.parseInt(settings.getOrDefault("max-nesting-depth", String.valueOf(DEFAULT_MAX_NESTING_DEPTH)).trim()));
    }

    int maxLineBytes() {
        return maxLineBytes;
    }

    /** Why a line was not read, in words, for an over-long line. */
    String tooLong() {
        return "longer than max-document-mb (" + maxDocumentMb + " MB)";
    }

    JsonParser parser(byte[] b, int offset, int length) throws IOException {
        return factory.createParser(b, offset, length);
    }

    JsonParser parser(byte[] b) throws IOException {
        return factory.createParser(b);
    }

    /** A scalar as a promoted value: a Double (none when not finite), a String, or null. */
    static Object value(JsonParser p, JsonToken v) throws IOException {
        if (v == JsonToken.VALUE_NUMBER_INT || v == JsonToken.VALUE_NUMBER_FLOAT) {
            double d = p.getDoubleValue();
            return Double.isFinite(d) ? d : null;
        }
        if (v == JsonToken.VALUE_NULL) {
            return null;
        }
        if (v.isScalarValue()) {
            return p.getText();
        }
        p.skipChildren();
        return null;
    }

    /** A document as a tree, with this format's limits; a number that is not finite is null. */
    DataNode read(byte[] doc) throws IOException {
        try (JsonParser p = parser(doc)) {
            JsonToken t = p.nextToken();
            return t == null ? DataNode.missing() : node(p, t);
        } catch (com.fasterxml.jackson.core.exc.StreamConstraintsException e) {
            throw new IOException(e.getMessage() + " (max-document-mb " + maxDocumentMb + ", max-nesting-depth " + maxNestingDepth + ")", e);
        }
    }

    private static DataNode node(JsonParser p, JsonToken t) throws IOException {
        switch (t) {
            case START_OBJECT -> {
                Map<String, DataNode> fields = new LinkedHashMap<>();
                while (p.nextToken() == JsonToken.FIELD_NAME) {
                    String name = p.currentName();
                    fields.put(name, node(p, p.nextToken()));
                }
                return new DataNode.Obj(fields);
            }
            case START_ARRAY -> {
                List<DataNode> items = new ArrayList<>();
                JsonToken n;
                while ((n = p.nextToken()) != JsonToken.END_ARRAY) {
                    items.add(node(p, n));
                }
                return new DataNode.Arr(items);
            }
            case VALUE_STRING -> {
                return new DataNode.Val(p.getText());
            }
            case VALUE_NUMBER_INT -> {
                return new DataNode.Val(p.getNumberType() == JsonParser.NumberType.BIG_INTEGER ? p.getBigIntegerValue() : (Object) p.getLongValue());
            }
            case VALUE_NUMBER_FLOAT -> {
                double d = p.getDoubleValue();
                return Double.isFinite(d) ? new DataNode.Val(d) : DataNode.nullValue();
            }
            case VALUE_TRUE -> {
                return new DataNode.Val(Boolean.TRUE);
            }
            case VALUE_FALSE -> {
                return new DataNode.Val(Boolean.FALSE);
            }
            case VALUE_NULL -> {
                return DataNode.nullValue();
            }
            default -> throw new IOException("unexpected token " + t + " at " + p.currentLocation());
        }
    }
}
