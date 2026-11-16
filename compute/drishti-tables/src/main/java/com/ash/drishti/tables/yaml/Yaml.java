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
package com.ash.drishti.tables.yaml;

import com.ash.drishti.tables.Problem;
import com.ash.drishti.tables.TableCodes;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reading one definition's YAML: the tree, and helpers that collect problems by key instead of failing on the first. Shared by
 * {@link TableYaml} and {@link ModelYaml}.
 */
public final class Yaml {

    /** One mapper for the module: YAML in, plain maps and lists out. */
    public static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory());

    /** Bounds: a definition is configuration, never data. */
    public static final int MAX_BYTES = 512 * 1024;

    final List<Problem> problems = new ArrayList<>();

    /** The tree of {@code text}, or null with a problem. */
    JsonNode tree(String text) {
        if (text == null || text.isBlank()) {
            problems.add(Problem.error(TableCodes.UNREADABLE, "", "the file is empty"));
            return null;
        }
        if (text.length() > MAX_BYTES) {
            problems.add(Problem.error(TableCodes.UNREADABLE, "", "larger than " + MAX_BYTES / 1024 + " KB: a definition is configuration"));
            return null;
        }
        try {
            JsonNode n = MAPPER.readTree(text);
            if (n == null || !n.isObject()) {
                problems.add(Problem.error(TableCodes.UNREADABLE, "", "the file is not a YAML mapping"));
                return null;
            }
            return n;
        } catch (JsonProcessingException e) {
            problems.add(Problem.error(TableCodes.UNREADABLE, "", "not YAML: " + e.getOriginalMessage()));
            return null;
        }
    }

    void error(String code, String path, String message) {
        problems.add(Problem.error(code, path, message));
    }

    void warning(String code, String path, String message) {
        problems.add(Problem.warning(code, path, message));
    }

    void info(String code, String path, String message) {
        problems.add(Problem.info(code, path, message));
    }

    void unknownKeys(JsonNode n, Set<String> known, String path) {
        if (n == null || !n.isObject()) {
            return;
        }
        Iterator<String> it = n.fieldNames();
        while (it.hasNext()) {
            String k = it.next();
            if (!known.contains(k)) {
                error(TableCodes.UNREADABLE, join(path, k), "unknown key '" + k + "'");
            }
        }
    }

    /** The text at {@code key}, or {@code dflt}; a mapping or list there is a problem. */
    String text(JsonNode n, String key, String path, String dflt) {
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) {
            return dflt;
        }
        if (!v.isValueNode()) {
            error(TableCodes.UNREADABLE, join(path, key), "a single value, not a " + (v.isArray() ? "list" : "mapping"));
            return dflt;
        }
        return v.asText();
    }

    /** A list of texts at {@code key} (a single text is a list of one), never null. */
    List<String> strings(JsonNode n, String key, String path) {
        JsonNode v = n.get(key);
        List<String> out = new ArrayList<>();
        if (v == null || v.isNull()) {
            return out;
        }
        if (v.isValueNode()) {
            out.add(v.asText());
            return out;
        }
        if (!v.isArray()) {
            error(TableCodes.UNREADABLE, join(path, key), "a list of names");
            return out;
        }
        for (JsonNode x : v) {
            if (x.isValueNode()) {
                out.add(x.asText());
            } else {
                error(TableCodes.UNREADABLE, join(path, key), "a list of names, not of mappings");
            }
        }
        return out;
    }

    /** The plain value of a node: maps, lists, texts, numbers, booleans (key order kept). */
    @SuppressWarnings("unchecked")
    static Object plain(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return null;
        }
        return MAPPER.convertValue(n, Object.class);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(JsonNode n) {
        Object o = plain(n);
        return o instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
    }

    static String join(String path, String key) {
        return path == null || path.isEmpty() ? key : path + "." + key;
    }

    /** The descriptive keys every definition keeps as written. */
    static final Set<String> ABOUT = Set.of("id", "title", "description", "owner", "sample", "endorsement", "endorsedBy", "audience", "tags", "ai",
            "layout", "lifecycle", "replaced-by", "golden", "contract", "perspectives", "fields", "provenance");
}
