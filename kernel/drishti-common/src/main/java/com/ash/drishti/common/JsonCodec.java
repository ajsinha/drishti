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

import com.ash.drishti.api.DataNode;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Streams JSON straight into {@link DataNode} trees and back, without an intermediate Jackson tree.
 * Thread-safe: the factory is shared, parsers are per call.
 */
public final class JsonCodec {

    private final JsonFactory factory =
            JsonFactory.builder().enable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION).build();

    public DataNode read(InputStream in) throws IOException {
        try (JsonParser p = factory.createParser(in)) {
            return readRoot(p);
        }
    }

    public DataNode read(String json) {
        try (JsonParser p = factory.createParser(json)) {
            return readRoot(p);
        } catch (IOException e) {
            throw new DrishtiException(ErrorCode.INVALID_JSON, e.getMessage(), e);
        }
    }

    private DataNode readRoot(JsonParser p) throws IOException {
        JsonToken t = p.nextToken();
        if (t == null) {
            return DataNode.missing();
        }
        return readValue(p, t);
    }

    private DataNode readValue(JsonParser p, JsonToken t) throws IOException {
        switch (t) {
            case START_OBJECT: {
                Map<String, DataNode> fields = new LinkedHashMap<>();
                while (p.nextToken() == JsonToken.FIELD_NAME) {
                    String name = p.currentName();
                    fields.put(name, readValue(p, p.nextToken()));
                }
                return new DataNode.Obj(fields);
            }
            case START_ARRAY: {
                ArrayList<DataNode> items = new ArrayList<>();
                JsonToken n;
                while ((n = p.nextToken()) != JsonToken.END_ARRAY) {
                    items.add(readValue(p, n));
                }
                return new DataNode.Arr(items);
            }
            case VALUE_STRING:
                return new DataNode.Val(p.getText());
            case VALUE_NUMBER_INT:
                return new DataNode.Val(p.getNumberType() == JsonParser.NumberType.BIG_INTEGER
                        ? p.getBigIntegerValue() : (Object) p.getLongValue());
            case VALUE_NUMBER_FLOAT:
                return new DataNode.Val(p.getDoubleValue());
            case VALUE_TRUE:
                return new DataNode.Val(Boolean.TRUE);
            case VALUE_FALSE:
                return new DataNode.Val(Boolean.FALSE);
            case VALUE_NULL:
                return DataNode.nullValue();
            default:
                throw new DrishtiException(ErrorCode.INVALID_JSON, "unexpected token " + t + " at " + p.currentLocation());
        }
    }

    public void write(DataNode node, JsonGenerator g) throws IOException {
        switch (node) {
            case DataNode.Obj o -> {
                g.writeStartObject();
                for (Map.Entry<String, DataNode> e : o.fields().entrySet()) {
                    g.writeFieldName(e.getKey());
                    write(e.getValue(), g);
                }
                g.writeEndObject();
            }
            case DataNode.Arr a -> {
                g.writeStartArray();
                for (DataNode e : a.elements()) {
                    write(e, g);
                }
                g.writeEndArray();
            }
            case DataNode.Val v -> g.writeObject(v.value());
            case DataNode.Missing m -> g.writeNull();
        }
    }

    public String toJson(DataNode node) {
        StringWriter w = new StringWriter();
        try (JsonGenerator g = factory.createGenerator(w)) {
            g.setCodec(new com.fasterxml.jackson.databind.ObjectMapper(factory));
            write(node, g);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return w.toString();
    }

    public JsonFactory factory() {
        return factory;
    }
}
