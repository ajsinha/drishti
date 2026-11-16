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
package com.ash.drishti.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * List attributes: a field holding a list of simple values ({@code TAGS: ["fx", "rates"]}, {@code TRADE_IDS}), as a document holds it
 * (a {@link Collection}) or as a promoted column hands it over (its JSON text, {@code ["fx","rates"]}: a lake's {@code array<string>}
 * column reads as that text, so a {@link ColumnSet} keeps one text per row). One reading of both forms for the filters
 * ({@code contains}, {@code any of}, {@code all of}), the pivot's explode and the displays (DATA_LAKE_GUIDE.md section 2.4).
 *
 * <p>A list's values are texts: a number as its plain text ({@code 5}, never {@code 5.0}), {@code true}/{@code false}; a null element is
 * left out. A list holding an object or a list is not a list of simple values: {@link #of} answers null for it.
 */
public final class ListValues {

    private ListValues() {
    }

    /** Whether {@code v} is a list of simple values (a collection of scalars, or the JSON text of one). */
    public static boolean isList(Object v) {
        return of(v) != null;
    }

    /**
     * The values of a list of simple values, as texts in order (duplicates kept); null when {@code v} is not one (a scalar, an object, a
     * list holding objects or lists, text that is not a JSON array).
     */
    public static List<String> of(Object v) {
        if (v instanceof Collection<?> c) {
            List<String> out = new ArrayList<>(c.size());
            for (Object e : c) {
                if (e == null) {
                    continue;
                }
                if (e instanceof Collection<?> || e instanceof java.util.Map<?, ?>) {
                    return null;
                }
                out.add(text(e));
            }
            return out;
        }
        if (v instanceof Object[] a) {
            return of(java.util.Arrays.asList(a));
        }
        if (v instanceof CharSequence s) {
            return parse(s.toString());
        }
        return null;
    }

    /** One element as text: a number in its plain form (an id written as a number reads as the id). */
    public static String text(Object e) {
        if (e instanceof Double || e instanceof Float) {
            return IdText.of(((Number) e).doubleValue());
        }
        if (e instanceof byte[] b) {
            return new String(b, java.nio.charset.StandardCharsets.UTF_8);
        }
        return String.valueOf(e);
    }

    /** The JSON text of a list of simple values: texts quoted, numbers and booleans bare, nulls kept ({@code ["fx","rates"]}). */
    public static String json(Collection<?> items) {
        StringBuilder b = new StringBuilder(16 + items.size() * 8).append('[');
        boolean first = true;
        for (Object e : items) {
            if (!first) {
                b.append(',');
            }
            first = false;
            if (e == null) {
                b.append("null");
            } else if (e instanceof Number n && !(e instanceof Double d && (d.isNaN() || d.isInfinite()))) {
                b.append(e instanceof Double || e instanceof Float ? IdText.of(n.doubleValue()) : n.toString());
            } else if (e instanceof Boolean) {
                b.append(e);
            } else {
                quote(b, text(e));
            }
        }
        return b.append(']').toString();
    }

    /** The values joined for one label ({@code fx, rates}): the pivot's "one joined label" option and plain-text exports. */
    public static String joined(Object v) {
        List<String> l = of(v);
        return l == null ? (v == null ? null : String.valueOf(v)) : String.join(", ", l);
    }

    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        b.append('"');
    }

    /** A JSON array of simple values, read without a library; null for anything else. */
    static List<String> parse(String s) {
        int n = s.length();
        int i = skip(s, 0);
        if (i >= n || s.charAt(i) != '[') {
            return null;
        }
        List<String> out = new ArrayList<>();
        i = skip(s, i + 1);
        if (i < n && s.charAt(i) == ']') {
            return skip(s, i + 1) == n ? out : null;
        }
        while (i < n) {
            char c = s.charAt(i);
            if (c == '"') {
                StringBuilder t = new StringBuilder();
                i++;
                while (i < n && s.charAt(i) != '"') {
                    char x = s.charAt(i++);
                    if (x == '\\' && i < n) {
                        char e = s.charAt(i++);
                        switch (e) {
                            case 'n' -> t.append('\n');
                            case 'r' -> t.append('\r');
                            case 't' -> t.append('\t');
                            case 'b' -> t.append('\b');
                            case 'f' -> t.append('\f');
                            case 'u' -> {
                                if (i + 4 > n) {
                                    return null;
                                }
                                t.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                                i += 4;
                            }
                            default -> t.append(e);
                        }
                    } else {
                        t.append(x);
                    }
                }
                if (i >= n) {
                    return null;
                }
                i++;
                out.add(t.toString());
            } else if (c == '{' || c == '[') {
                return null;
            } else {
                int start = i;
                while (i < n && s.charAt(i) != ',' && s.charAt(i) != ']' && !Character.isWhitespace(s.charAt(i))) {
                    i++;
                }
                String tok = s.substring(start, i);
                if (tok.isEmpty()) {
                    return null;
                }
                if (!tok.equals("null")) {
                    if (!tok.equals("true") && !tok.equals("false")) {
                        try {
                            double d = Double.parseDouble(tok);
                            tok = tok.matches("-?\\d+") ? tok : IdText.of(d);
                        } catch (NumberFormatException e) {
                            return null;
                        }
                    }
                    out.add(tok);
                }
            }
            i = skip(s, i);
            if (i >= n) {
                return null;
            }
            if (s.charAt(i) == ']') {
                return skip(s, i + 1) == n ? out : null;
            }
            if (s.charAt(i) != ',') {
                return null;
            }
            i = skip(s, i + 1);
        }
        return null;
    }

    private static int skip(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }
}
