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
package com.ash.drishti.server.bi.migrate;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Writes the YAML the converter produces in the style of the shipped samples: a key per line, small mappings in flow style ({@code { type: decimal, format:
 * signed0 }}), a scalar plain when YAML reads it back as the same text, else double-quoted. Pure.
 */
final class YamlText {

    private static final Pattern PLAIN = Pattern.compile("[A-Za-z_][A-Za-z0-9_ ./@()&'-]*");
    private static final Set<String> WORDS = Set.of("true", "false", "yes", "no", "on", "off", "null", "y", "n", "~");

    private YamlText() {}

    /** A scalar as YAML: plain when it is safe, else double-quoted. */
    static String scalar(String s) {
        if (s == null) {
            return "null";
        }
        boolean plain = PLAIN.matcher(s).matches() && !WORDS.contains(s.toLowerCase(java.util.Locale.ROOT)) && !s.endsWith(" ") && !s.contains(" #")
                && !s.contains(": ") && !s.startsWith("'");
        return plain ? s : quoted(s);
    }

    /** A key: plain when it is safe, else double-quoted. */
    static String key(String s) {
        return scalar(s);
    }

    static String quoted(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\t' -> b.append("\\t");
                default -> b.append(ch);
            }
        }
        return b.append('"').toString();
    }

    /** A flow mapping of already-written values ({@code { a: 1, b: x }}); entries with a null value are left out. */
    static String flow(Map<String, String> entries) {
        List<String> parts = new ArrayList<>();
        entries.forEach((k, v) -> {
            if (v != null) {
                parts.add(key(k) + ": " + v);
            }
        });
        return parts.isEmpty() ? "{}" : "{ " + String.join(", ", parts) + " }";
    }

    /** A flow sequence of scalars. */
    static String list(List<String> items) {
        return "[" + String.join(", ", items.stream().map(YamlText::scalar).toList()) + "]";
    }

    /** A JSON tree (a test, a parameter block) as flow YAML. */
    static String node(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return "null";
        }
        if (n.isObject()) {
            List<String> parts = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> it = n.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                parts.add(key(e.getKey()) + ": " + node(e.getValue()));
            }
            return parts.isEmpty() ? "{}" : "{ " + String.join(", ", parts) + " }";
        }
        if (n.isArray()) {
            List<String> parts = new ArrayList<>();
            n.forEach(x -> parts.add(node(x)));
            return "[" + String.join(", ", parts) + "]";
        }
        if (n.isNumber() || n.isBoolean()) {
            return n.asText();
        }
        return scalar(n.asText());
    }
}
