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
package com.ash.drishti.rachana.format;

import com.ash.drishti.rachana.el.Values;

/**
 * Resolves a Sutra {@code tone:} and a value to a theme tone class: {@code pos}, {@code neg}, {@code link},
 * {@code accent}, {@code ok}, {@code warn}, {@code bad} or null. The console maps these to tokens and
 * always pairs colour with a sign or glyph.
 */
public final class Tones {

    /** Every tone a Sutra may name. */
    public static final java.util.List<String> NAMES = java.util.List.of("sign", "status", "pos", "neg", "link", "accent", "ok", "warn", "bad");

    private Tones() {}

    public static String resolve(String tone, Object value) {
        if (tone == null) {
            return null;
        }
        return switch (tone) {
            case "sign" -> {
                double d = Values.number(value);
                yield Double.isNaN(d) || d == 0 ? null : d > 0 ? "pos" : "neg";
            }
            case "status" -> status(Values.text(value));
            case "pos", "neg", "link", "accent", "ok", "warn", "bad" -> tone;
            default -> null;
        };
    }

    private static String status(String s) {
        String v = s.toLowerCase(java.util.Locale.ROOT);
        if (v.contains("fail") || v.contains("reject") || v.contains("dispute") || v.contains("breach")) {
            return "bad";
        }
        if (v.contains("pending") || v.contains("unmatched") || v.contains("warn")) {
            return "warn";
        }
        return v.isEmpty() ? null : "ok";
    }
}
