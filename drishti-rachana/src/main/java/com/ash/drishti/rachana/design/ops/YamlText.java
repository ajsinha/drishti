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
package com.ash.drishti.rachana.design.ops;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Writes plain Java values (maps, lists, text, numbers, booleans, null) as one-line YAML flow text. Stateless. */
final class YamlText {

    private static final Pattern PLAIN = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$@.]*");
    private static final Set<String> WORDS = Set.of("true", "false", "null", "yes", "no", "on", "off", "y", "n");

    private YamlText() {}

    static String flow(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                return "{}";
            }
            StringBuilder sb = new StringBuilder("{ ");
            String sep = "";
            for (Map.Entry<?, ?> e : m.entrySet()) {
                sb.append(sep).append(text(String.valueOf(e.getKey()))).append(": ").append(flow(e.getValue()));
                sep = ", ";
            }
            return sb.append(" }").toString();
        }
        if (v instanceof List<?> l) {
            StringBuilder sb = new StringBuilder("[");
            String sep = "";
            for (Object o : l) {
                sb.append(sep).append(flow(o));
                sep = ", ";
            }
            return sb.append("]").toString();
        }
        if (v instanceof Number || v instanceof Boolean) {
            return v.toString();
        }
        return text(v.toString());
    }

    /** Plain when that reads back as the same text, else double-quoted. */
    static String text(String s) {
        if (PLAIN.matcher(s).matches() && !WORDS.contains(s.toLowerCase(Locale.ROOT))) {
            return s;
        }
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
