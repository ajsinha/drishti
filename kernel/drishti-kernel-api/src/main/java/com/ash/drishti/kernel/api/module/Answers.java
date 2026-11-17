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
package com.ash.drishti.kernel.api.module;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * Reading the fields of a JSON answer ({@link Asker}) without casts at every step: a missing or mistyped field is
 * {@code null}, an empty map or an empty list, never an exception. The reading rules of the answers' JSON shape, part of the contract that
 * gives them: pure static functions, no state and nothing to implement (an interface, as the kernel API holds no implementation class).
 */
public interface Answers {

    /** The field as an object ({} when absent or not one). */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object node, String field) {
        Object v = node instanceof Map<?, ?> m ? m.get(field) : null;
        return v instanceof Map<?, ?> m2 ? (Map<String, Object>) m2 : Map.of();
    }

    /** The value as an object ({} when not one). */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    /** The field as a list ([] when absent or not one). */
    @SuppressWarnings("unchecked")
    public static List<Object> list(Object node, String field) {
        Object v = node instanceof Map<?, ?> m ? m.get(field) : null;
        return v instanceof List<?> l ? (List<Object>) l : List.of();
    }

    /** The field's value (null when absent). */
    public static Object get(Object node, String field) {
        return node instanceof Map<?, ?> m ? m.get(field) : null;
    }

    /** The field as text ("" when absent or null). */
    public static String str(Object node, String field) {
        Object v = get(node, field);
        return v == null ? "" : String.valueOf(v);
    }

    /** A value as text ("" for null). */
    public static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    /** A finite number, else null (a boolean, a masked value or text that is not a number is no number). */
    public static Double num(Object v) {
        if (v == null || v instanceof Boolean) {
            return null;
        }
        double d;
        if (v instanceof Number n) {
            d = n.doubleValue();
        } else {
            try {
                d = Double.parseDouble(String.valueOf(v).strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return Double.isFinite(d) ? d : null;
    }

    /** True for a value the server masked for the person's role (its mask characters). */
    public static boolean masked(Object v) {
        return v instanceof String s && s.indexOf('•') >= 0;
    }

    /** The first ten characters as a date, else null. */
    public static LocalDate day(Object v) {
        String s = v == null ? "" : String.valueOf(v);
        if (s.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(s.substring(0, 10));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** A number rounded to a whole number (the exact binary value, ties to even), as a long; null for null. */
    public static Long round(Double v) {
        return v == null ? null : new java.math.BigDecimal(v).setScale(0, java.math.RoundingMode.HALF_EVEN).longValue();
    }

    /** A number rounded to {@code places} decimals (the exact binary value, ties to even); null for null. */
    public static Double round(Double v, int places) {
        if (v == null) {
            return null;
        }
        return new java.math.BigDecimal(v).setScale(places, java.math.RoundingMode.HALF_EVEN).doubleValue();
    }
}
