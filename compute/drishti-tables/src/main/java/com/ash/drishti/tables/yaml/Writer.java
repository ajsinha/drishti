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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Pattern;

/**
 * Writes the layer's YAML by hand, in the shape the repository's files have: one key per line at the top, small mappings in flow style
 * ({@code { path: $.MTM, type: decimal }}), texts plain where YAML reads them back as the same text and double-quoted otherwise. Fixed
 * order and fixed quoting make the output a function of the definition, which the migration's idempotence needs.
 */
final class Writer {

    private static final Pattern PLAIN = Pattern.compile("[A-Za-z_][A-Za-z0-9_ ./()-]*[A-Za-z0-9_.)]|[A-Za-z_]");
    private static final Pattern PLAIN_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_ .&()/-]*[A-Za-z0-9_.)]|[A-Za-z_]");
    private static final Set<String> RESERVED = Set.of("true", "false", "null", "yes", "no", "on", "off", "y", "n", "~");

    private final StringBuilder sb = new StringBuilder();

    void line(String s) {
        sb.append(s).append('\n');
    }

    void kv(String key, Object value) {
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            line(key(key) + ": " + flow(value));
        } else {
            line(key(key) + ": " + scalar(value));
        }
    }

    String text() {
        return sb.toString();
    }

    static String key(String k) {
        return PLAIN_KEY.matcher(k).matches() && !RESERVED.contains(k.toLowerCase(java.util.Locale.ROOT)) ? k : quote(k);
    }

    static String flow(Object v) {
        if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                return "{}";
            }
            StringJoiner j = new StringJoiner(", ", "{ ", " }");
            m.forEach((k, x) -> j.add(key(String.valueOf(k)) + ": " + flow(x)));
            return j.toString();
        }
        if (v instanceof List<?> l) {
            StringJoiner j = new StringJoiner(", ", "[", "]");
            l.forEach(x -> j.add(flow(x)));
            return j.toString();
        }
        return scalar(v);
    }

    static String scalar(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Number || v instanceof Boolean) {
            return String.valueOf(v);
        }
        String s = String.valueOf(v);
        if (s.matches("\\d+\\.\\d+\\.\\d+")) {
            return s;                                                       // a version reads back as text (three parts are not a number)
        }
        return PLAIN.matcher(s).matches() && !RESERVED.contains(s.toLowerCase(java.util.Locale.ROOT)) && !s.contains("  ") ? s : quote(s);
    }

    static String quote(String s) {
        StringBuilder q = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> q.append("\\\"");
                case '\\' -> q.append("\\\\");
                case '\n' -> q.append("\\n");
                case '\t' -> q.append("\\t");
                default -> q.append(c);
            }
        }
        return q.append('"').toString();
    }
}
