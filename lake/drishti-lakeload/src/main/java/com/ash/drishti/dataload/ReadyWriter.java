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
package com.ash.drishti.dataload;

import java.util.Map;
import java.util.StringJoiner;

/**
 * A {@link ReadyNote} as the text of a {@code .ready} file: one key a line, only the keys it says. {@link ReadyNote#parse} reads this text back
 * to the same note (the tests prove it for every key), so there is one set of rules.
 */
public final class ReadyWriter {

    private ReadyWriter() {}

    /** The marker's text; an empty note is the empty text. */
    public static String text(ReadyNote n) {
        StringBuilder s = new StringBuilder();
        put(s, "lake", n.lake());
        put(s, "pack", n.pack());
        put(s, "kind", n.kind());
        put(s, "connector", n.connector());
        put(s, "table", n.table());
        put(s, "business-date", n.businessDate() == null ? null : n.businessDate().toString());
        put(s, "mode", n.mode());
        put(s, "key", n.key());
        if (n.newTable()) {
            s.append("new-table: true\n");
        }
        if (n.folders() != null && !n.folders().isEmpty()) {
            StringJoiner j = new StringJoiner(", ", "{", "}");
            new java.util.TreeMap<>(n.folders()).forEach((k, v) -> j.add(scalar(k) + ": " + scalar(v)));
            s.append("folders: ").append(j).append('\n');
        }
        if (n.sha256() != null) {
            s.append("sha256: ").append(n.sha256()).append('\n');
        }
        if (n.rows() != null) {
            s.append("rows: ").append(n.rows()).append('\n');
        }
        if (n.hold()) {
            s.append("hold: true\n");
        }
        return s.toString();
    }

    /** One key alone, as written (a field's value checked by {@link ReadyNote#parse}). */
    static String line(String key, String value) {
        return key + ": " + scalar(value) + "\n";
    }

    private static void put(StringBuilder s, String key, String value) {
        if (value != null && !value.isEmpty()) {
            s.append(line(key, value));
        }
    }

    private static String scalar(String v) {
        if (v.matches("[A-Za-z_][A-Za-z0-9_./-]*") && !v.matches("(?i)true|false|null|yes|no|on|off|~") || v.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return v;
        }
        StringBuilder b = new StringBuilder("\"");
        for (char c : v.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> b.append(c);
            }
        }
        return b.append('"').toString();
    }

    /** The folders of a tar's form, as the note keeps them (lower case). */
    static Map<String, String> folders(Map<String, String> given) {
        Map<String, String> out = new java.util.TreeMap<>();
        if (given != null) {
            given.forEach((k, v) -> {
                if (k != null && v != null && !v.isBlank()) {
                    out.put(k.trim().toLowerCase(java.util.Locale.ROOT), v.trim());
                }
            });
        }
        return out;
    }
}
