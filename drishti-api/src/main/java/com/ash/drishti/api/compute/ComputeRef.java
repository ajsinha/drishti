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
package com.ash.drishti.api.compute;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/**
 * The id of a computed answer: the question's id and its parameters, in one text the normal routing carries
 * ({@code DESK:RATES-NY~confidence=0.99~horizon=1}): readable, linkable and cacheable (REACTIVE_CONNECTORS.md section 6.1, "the id is the
 * question"). Parameters are sorted by name; {@code ~}, {@code =}, {@code |} and {@code %} inside a value are percent-encoded. A Sutra panel's
 * {@code source: { params: ... }} is written this way by the server; a link may be written by hand.
 *
 * @param id the question's id (the scope)
 * @param params the parameters as given (text), sorted by name
 */
public record ComputeRef(String id, Map<String, String> params) {

    public static final char SEP = '~';

    public ComputeRef {
        id = id == null ? "" : id;
        params = params == null ? Map.of() : java.util.Collections.unmodifiableMap(new TreeMap<>(params));
    }

    /** Reads an id that may carry parameters; an id without {@code ~} has none. */
    public static ComputeRef parse(String text) {
        if (text == null) {
            return new ComputeRef("", Map.of());
        }
        String[] parts = text.split(String.valueOf(SEP), -1);
        Map<String, String> p = new TreeMap<>();
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].isEmpty()) {
                continue;
            }
            int eq = parts[i].indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("'" + parts[i] + "' is not a parameter (name=value) in " + text);
            }
            String name = decode(parts[i].substring(0, eq));
            if (p.put(name, decode(parts[i].substring(eq + 1))) != null) {
                throw new IllegalArgumentException("parameter " + name + " is given twice in " + text);
            }
        }
        return new ComputeRef(decode(parts[0]), p);
    }

    /** The canonical text of an id and its parameters (numbers without trailing zeros). */
    public static String format(String id, Map<String, ?> params) {
        StringBuilder b = new StringBuilder(encode(id == null ? "" : id));
        new TreeMap<>(params == null ? Map.<String, Object>of() : params).forEach((k, v) -> {
            if (v != null) {
                b.append(SEP).append(encode(k)).append('=').append(encode(text(v)));
            }
        });
        return b.toString();
    }

    @Override
    public String toString() {
        return format(id, params);
    }

    private static String encode(String s) {
        if (s.indexOf('~') < 0 && s.indexOf('=') < 0 && s.indexOf('%') < 0 && s.indexOf('|') < 0) {
            return s;
        }
        return s.replace("%", "%25").replace("~", "%7E").replace("=", "%3D").replace("|", "%7C");
    }

    /** A parameter's value as it is written in an id or a key: numbers without trailing zeros, everything else as text. */
    public static String text(Object v) {
        if (v instanceof java.math.BigDecimal b) {
            return b.stripTrailingZeros().toPlainString();
        }
        if (v instanceof Double d && d == Math.rint(d) && Math.abs(d) < 1e15) {
            return Long.toString(d.longValue());
        }
        return String.valueOf(v);
    }

    private static String decode(String s) {
        return s.indexOf('%') < 0 ? s : URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
    }
}
