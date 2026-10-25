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
package com.ash.drishti.compute.reactive;

import com.ash.drishti.compute.ConfigValues;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One typed parameter of a computed kind ({@code kinds.<kind>.params.<name>}, REACTIVE_CONNECTORS.md section 17): its type, default,
 * allowed values and bounds. Checked twice: at load (the declaration itself) and per request ({@link #normalise}), where a value outside
 * what is allowed is refused in words: <em>confidence 0.9 is not allowed for var-result; allowed: 0.95, 0.975, 0.99</em>.
 *
 * @param name the parameter's name
 * @param type {@code text}, {@code scope}, {@code decimal}, {@code int}, {@code bool}, {@code enum}, {@code date}, {@code sandbox-ref}
 * @param required whether a request must give it (one with a default never must)
 * @param dflt the default (already of the type), or null
 * @param allowed the values allowed (of the type; for {@code enum} its options), empty for any
 * @param min the smallest number allowed, or null
 * @param max the largest number allowed (for text: the longest), or null
 */
public record ParamSpec(String name, String type, boolean required, Object dflt, List<Object> allowed, BigDecimal min, BigDecimal max) {

    static final List<String> TYPES = List.of("text", "scope", "decimal", "int", "bool", "enum", "date", "sandbox-ref");
    private static final int MAX_TEXT = 512;

    public ParamSpec {
        allowed = allowed == null ? List.of() : List.copyOf(allowed);
    }

    /** Reads one declaration ({@code { type: decimal, default: 0.99, allowed: [0.95, 0.99] }}); refused in words when it is not usable. */
    static ParamSpec parse(String kind, String name, ConfigValues c) {
        String where = "kinds." + kind + ".params." + name;
        if (!name.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException(where + ": a parameter name is letters, digits, '_' and '-', starting with a letter");
        }
        String type = c.text("type", "text").toLowerCase(Locale.ROOT);
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException(where + ".type is one of " + TYPES + ", not '" + type + "'");
        }
        List<Object> allowed = new ArrayList<>();
        Object a = c.raw("enum".equals(type) && c.has("options") ? "options" : "allowed");
        if (a instanceof List<?> l) {
            for (Object x : l) {
                allowed.add(convert(where, type, x, null, null));
            }
        } else if (a != null) {
            throw new IllegalArgumentException(where + ": " + ("enum".equals(type) ? "options" : "allowed") + " is a list");
        }
        if ("enum".equals(type) && allowed.isEmpty()) {
            throw new IllegalArgumentException(where + ": an enum parameter lists its options");
        }
        BigDecimal min = number(where + ".min", c.raw("min"));
        BigDecimal max = number(where + ".max", c.raw("max"));
        Object d = c.raw("default");
        Object dflt = d == null ? null : convert(where + ".default", type, d, min, max);
        ParamSpec p = new ParamSpec(name, type, c.flag("required", false) && dflt == null, dflt, allowed, min, max);
        if (dflt != null && !allowed.isEmpty() && !allowed.contains(dflt)) {
            throw new IllegalArgumentException(where + ": the default " + dflt + " is not one of the allowed values " + allowed);
        }
        return p;
    }

    private static BigDecimal number(String where, Object v) {
        if (v == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(where + " is a number, not '" + v + "'");
        }
    }

    /**
     * The value a request gives, of this parameter's type and within what is allowed; the default when none is given; null when the
     * parameter is optional and absent. Refused (with words a person may read) otherwise.
     */
    Object normalise(String kind, Object given) {
        if (given == null || given instanceof String s && s.isBlank()) {
            if (required) {
                throw new IllegalArgumentException(name + " is required for " + kind);
            }
            return dflt;
        }
        Object v = convert(name + " for " + kind, type, given, min, max);
        if (!allowed.isEmpty() && !allowed.contains(v)) {
            throw new IllegalArgumentException(name + " " + text(v) + " is not allowed for " + kind + "; allowed: "
                    + String.join(", ", allowed.stream().map(ParamSpec::text).toList()));
        }
        return v;
    }

    /** A value as it is written in a canonical id or a key: numbers without trailing zeros, dates as ISO text. */
    static String text(Object v) {
        if (v instanceof BigDecimal b) {
            return b.stripTrailingZeros().toPlainString();
        }
        return String.valueOf(v);
    }

    private static Object convert(String what, String type, Object v, BigDecimal min, BigDecimal max) {
        String s = String.valueOf(v).trim();
        Object out;
        switch (type) {
            case "decimal" -> {
                try {
                    out = new BigDecimal(s).stripTrailingZeros();
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(what + " is a number, not '" + s + "'");
                }
            }
            case "int" -> {
                try {
                    out = new BigDecimal(s).toBigIntegerExact().longValueExact();
                } catch (ArithmeticException | NumberFormatException e) {
                    throw new IllegalArgumentException(what + " is a whole number, not '" + s + "'");
                }
            }
            case "bool" -> {
                if (!s.equalsIgnoreCase("true") && !s.equalsIgnoreCase("false")) {
                    throw new IllegalArgumentException(what + " is true or false, not '" + s + "'");
                }
                out = Boolean.parseBoolean(s.toLowerCase(Locale.ROOT));
            }
            case "date" -> {
                try {
                    out = LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s).toString();
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException(what + " is a date (YYYY-MM-DD), not '" + s + "'");
                }
            }
            default -> {
                if (s.length() > MAX_TEXT) {
                    throw new IllegalArgumentException(what + " is longer than " + MAX_TEXT + " characters");
                }
                if (s.chars().anyMatch(ch -> ch < 0x20)) {
                    throw new IllegalArgumentException(what + " holds a control character");
                }
                out = s;
            }
        }
        if (out instanceof Number n) {
            BigDecimal b = new BigDecimal(n.toString());
            if (min != null && b.compareTo(min) < 0 || max != null && b.compareTo(max) > 0) {
                throw new IllegalArgumentException(what + " is " + text(out) + ": it must be"
                        + (min != null ? " at least " + text(min) : "") + (min != null && max != null ? " and" : "") + (max != null ? " at most " + text(max) : ""));
            }
        } else if (out instanceof String t && max != null && t.length() > max.intValue()) {
            throw new IllegalArgumentException(what + " is longer than " + max.intValue() + " characters");
        }
        return out;
    }

    /** The declaration as Admin and the designer show it. */
    Map<String, Object> describe() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("type", type);
        m.put("required", required);
        if (dflt != null) {
            m.put("default", text(dflt));
        }
        if (!allowed.isEmpty()) {
            m.put("allowed", allowed.stream().map(ParamSpec::text).toList());
        }
        if (min != null) {
            m.put("min", text(min));
        }
        if (max != null) {
            m.put("max", text(max));
        }
        return m;
    }
}
