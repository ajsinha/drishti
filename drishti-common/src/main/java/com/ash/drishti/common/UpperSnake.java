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
package com.ash.drishti.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Attribute names as production systems write them: UPPER_SNAKE ({@code changePct} is {@code CHANGE_PCT}, {@code fiftyTwoWeekLow}
 * is {@code FIFTY_TWO_WEEK_LOW}, {@code pd1y} is {@code PD_1Y}). The connectors that build their own documents (news, quotes, public
 * feeds, the demo ticker) name every attribute so, as the shipped packs' generators do (tools/packgen/common/upper.py).
 */
public final class UpperSnake {

    private static final Pattern UPPER = Pattern.compile("^[A-Z0-9_]+$");
    private static final Pattern CAMEL = Pattern.compile("(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");
    private static final Pattern DIGITS = Pattern.compile("(?<=[A-Za-z])(?=\\d)");

    private UpperSnake() {
    }

    /** One name in UPPER_SNAKE; a name already so, or one starting with an underscore ({@code _meta}), is returned as it is. */
    public static String name(String n) {
        if (n == null || n.isEmpty() || n.startsWith("_") || UPPER.matcher(n).matches()) {
            return n;
        }
        String s = DIGITS.matcher(CAMEL.matcher(n).replaceAll("_")).replaceAll("_");
        return s.replaceAll("_+", "_").replaceAll("^_|_$", "").toUpperCase(Locale.ROOT);
    }

    /** Every key of a JSON-like value (maps and lists of them) in UPPER_SNAKE, in its order; other values as they are. */
    public static Object keys(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, x) -> out.put(name(String.valueOf(k)), keys(x)));
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            l.forEach(x -> out.add(keys(x)));
            return out;
        }
        return v;
    }
}
