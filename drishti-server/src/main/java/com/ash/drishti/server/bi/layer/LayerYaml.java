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
package com.ash.drishti.server.bi.layer;

import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** What the parsers of the model layer share: reading the YAML tree, the problems they report (with the DRS code and the path), and small readers of values. */
final class LayerYaml {

    static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");
    static final Pattern ALIAS = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");

    private final List<DatasetParser.Problem> out = new ArrayList<>();

    List<DatasetParser.Problem> problems() {
        return out;
    }

    void error(String code, String path, String message) {
        out.add(new DatasetParser.Problem("error", code, path, message));
    }

    void warning(String code, String path, String message) {
        out.add(new DatasetParser.Problem("warning", code, path, message));
    }

    /** The YAML tree, or null with a problem of {@code code} when it is not a mapping. */
    JsonNode read(String yaml, String code, String what) {
        JsonNode root;
        try {
            root = yaml == null || yaml.isBlank() ? null : YAML.readTree(yaml);
        } catch (JsonProcessingException e) {
            error(code, "", "the YAML is not valid: " + com.ash.drishti.server.bi.YamlErrors.describe(e));
            return null;
        }
        if (root == null || !root.isObject()) {
            error(code, "", "a " + what + " is a YAML mapping (key: value)");
            return null;
        }
        return root;
    }

    void unknownKeys(JsonNode node, Set<String> known, String code, String path) {
        node.fieldNames().forEachRemaining(k -> {
            if (!known.contains(k)) {
                warning(code, path.isEmpty() ? k : path + "." + k, "unknown key '" + k + "' is ignored");
            }
        });
    }

    static String text(JsonNode n, String key, String dflt) {
        JsonNode v = n.path(key);
        return v.isMissingNode() || v.isNull() ? dflt : v.asText();
    }

    /** A list of names: a YAML list, or one name. */
    List<String> strings(JsonNode n, String path, String code) {
        List<String> out = new ArrayList<>();
        if (n.isMissingNode() || n.isNull()) {
            return out;
        }
        if (n.isTextual()) {
            out.add(n.asText());
            return out;
        }
        if (!n.isArray()) {
            error(code, path, "a list of names is expected");
            return out;
        }
        n.forEach(x -> {
            if (x.isValueNode()) {
                out.add(x.asText());
            } else {
                error(code, path, "a list of names is expected");
            }
        });
        return out;
    }

    /** True when the node holds something: a non-empty mapping or list, or a value. */
    static boolean nonEmpty(JsonNode n) {
        return !(n.isMissingNode() || n.isNull() || (n.isContainerNode() && n.isEmpty()));
    }
}
