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
import java.util.Set;

/**
 * Reads YAML into a {@link PNode} tree, keeping line and column for every node. Thread-safe.
 *
 * <p>YAML a Sutra must not depend on is reported as an {@link Issue} rather than read silently: a key written twice in
 * one mapping (YAML keeps the last), a second document after {@code ---} (it would be ignored) and a tag other than the
 * core ones ({@code !panel}, {@code !!java…}, {@code !!binary}). A whole number too large for a {@code long} is kept
 * as a {@link java.math.BigInteger}, so the builder can say which value is out of range.
 */
public final class PositionalYamlReader {

    /**
     * YAML the reader accepted but a Sutra may not contain.
     *
     * @param message what is wrong, for the author
     * @param line 1-based line
     * @param column 1-based column
     */
    public record Issue(String message, int line, int column) {}

    /** The tags of the YAML core schema: writing them changes nothing a Sutra cannot express plainly. */
    private static final Set<String> CORE_TAGS = Set.of("tag:yaml.org,2002:str", "tag:yaml.org,2002:int", "tag:yaml.org,2002:float",
            "tag:yaml.org,2002:bool", "tag:yaml.org,2002:null", "tag:yaml.org,2002:map", "tag:yaml.org,2002:seq");

    private final YAMLFactory factory = new YAMLFactory();

    /** The tree, ignoring the {@link Issue}s (for editors that rewrite a Sutra already checked). */
    public PNode read(String yaml) throws IOException {
        return read(yaml, new ArrayList<>());
    }

    /**
     * @param yaml the text
     * @param issues receives what a Sutra may not contain, with where it was written
     */
    public PNode read(String yaml, List<Issue> issues) throws IOException {
        try (JsonParser p = factory.createParser(yaml)) {
            JsonToken t = p.nextToken();
            if (t == null) {
                return new PNode(Map.of(), 1, 1);
            }
            PNode root = node(p, t, issues);
            JsonToken more = p.nextToken();
            if (more != null) {
                JsonLocation l = p.currentTokenLocation();
                issues.add(new Issue("a second YAML document (after '---'): a Sutra file holds exactly one, and the rest would be ignored",
                        Math.max(1, l.getLineNr() - 1), 1));
            }
            return root;
        }
    }

    private PNode node(JsonParser p, JsonToken t, List<Issue> issues) throws IOException {
        JsonLocation loc = p.currentTokenLocation();
        int line = loc.getLineNr();
        int col = loc.getColumnNr();
        Object typeId = p.getTypeId();
        String tag = typeId == null ? null : typeId.toString();
        if (tag != null && !CORE_TAGS.contains(tag)) {
            String shown = tag.startsWith("tag:yaml.org,2002:") ? "!!" + tag.substring("tag:yaml.org,2002:".length()) : "!" + tag;
            issues.add(new Issue("YAML tag '" + shown + "' is not allowed in a Sutra: write the value plainly", line, col));
        }
        return switch (t) {
            case START_OBJECT -> {
                Map<String, PNode> m = new LinkedHashMap<>();
                Map<String, Integer> keyLines = new LinkedHashMap<>();
                while (p.nextToken() == JsonToken.FIELD_NAME) {
                    String name = p.currentName();
                    JsonLocation at = p.currentTokenLocation();
                    PNode value = node(p, p.nextToken(), issues);
                    Integer first = keyLines.putIfAbsent(name, at.getLineNr());
                    m.putIfAbsent(name, value);
                    if (first != null) {
                        issues.add(new Issue("duplicate key '" + name + "' (first written at line " + first
                                + "): YAML would keep only the last, so write each key once", at.getLineNr(), at.getColumnNr()));
                    }
                }
                yield new PNode(m, line, col);
            }
            case START_ARRAY -> {
                List<PNode> l = new ArrayList<>();
                JsonToken n;
                while ((n = p.nextToken()) != JsonToken.END_ARRAY) {
                    l.add(node(p, n, issues));
                }
                yield new PNode(l, line, col);
            }
            case VALUE_NUMBER_INT -> new PNode(p.getNumberType() == JsonParser.NumberType.BIG_INTEGER ? p.getBigIntegerValue() : p.getLongValue(),
                    line, col);
            case VALUE_NUMBER_FLOAT -> new PNode(p.getDoubleValue(), line, col);
            case VALUE_TRUE -> new PNode(Boolean.TRUE, line, col);
            case VALUE_FALSE -> new PNode(Boolean.FALSE, line, col);
            case VALUE_NULL -> new PNode(null, line, col);
            default -> new PNode(p.getText(), line, col);
        };
    }
}
