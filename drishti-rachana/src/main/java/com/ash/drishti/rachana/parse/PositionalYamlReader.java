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
package com.ash.drishti.rachana.parse;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads YAML into a {@link PNode} tree, keeping line and column for every node. Thread-safe. */
public final class PositionalYamlReader {

    private final YAMLFactory factory = new YAMLFactory();

    public PNode read(String yaml) throws IOException {
        try (JsonParser p = factory.createParser(yaml)) {
            JsonToken t = p.nextToken();
            return t == null ? new PNode(Map.of(), 1, 1) : node(p, t);
        }
    }

    private PNode node(JsonParser p, JsonToken t) throws IOException {
        JsonLocation loc = p.currentTokenLocation();
        int line = loc.getLineNr();
        int col = loc.getColumnNr();
        return switch (t) {
            case START_OBJECT -> {
                Map<String, PNode> m = new LinkedHashMap<>();
                while (p.nextToken() == JsonToken.FIELD_NAME) {
                    String name = p.currentName();
                    m.put(name, node(p, p.nextToken()));
                }
                yield new PNode(m, line, col);
            }
            case START_ARRAY -> {
                List<PNode> l = new ArrayList<>();
                JsonToken n;
                while ((n = p.nextToken()) != JsonToken.END_ARRAY) {
                    l.add(node(p, n));
                }
                yield new PNode(l, line, col);
            }
            case VALUE_NUMBER_INT -> new PNode(p.getLongValue(), line, col);
            case VALUE_NUMBER_FLOAT -> new PNode(p.getDoubleValue(), line, col);
            case VALUE_TRUE -> new PNode(Boolean.TRUE, line, col);
            case VALUE_FALSE -> new PNode(Boolean.FALSE, line, col);
            case VALUE_NULL -> new PNode(null, line, col);
            default -> new PNode(p.getText(), line, col);
        };
    }
}
