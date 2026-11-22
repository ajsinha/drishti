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
package com.ash.drishti.enginehost;

/**
 * A pack's version range for an engine it uses ({@code uses.engines[].version: ">=1.0 <2"}): space-separated comparisons
 * ({@code >=}, {@code >}, {@code <=}, {@code <}, {@code =}, or a bare version for {@code =}), all of which must hold. Empty: any version.
 */
public final class VersionRange {

    private VersionRange() {
    }

    /** True when {@code version} ({@code 1.2.0}, a {@code -SNAPSHOT} suffix ignored) is within {@code range}. */
    public static boolean contains(String range, String version) {
        if (range == null || range.isBlank()) {
            return true;
        }
        for (String part : range.strip().split("\\s+")) {
            String op;
            String v;
            if (part.startsWith(">=") || part.startsWith("<=")) {
                op = part.substring(0, 2);
                v = part.substring(2);
            } else if (part.startsWith(">") || part.startsWith("<") || part.startsWith("=")) {
                op = part.substring(0, 1);
                v = part.substring(1);
            } else {
                op = "=";
                v = part;
            }
            int c = compare(version, v);
            boolean ok = switch (op) {
                case ">=" -> c >= 0;
                case "<=" -> c <= 0;
                case ">" -> c > 0;
                case "<" -> c < 0;
                default -> c == 0;
            };
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** Compares two versions part by part; a missing part is 0. IllegalArgumentException for a part that is not a whole number. */
    static int compare(String a, String b) {
        String[] x = a.split("-", 2)[0].split("\\.");
        String[] y = b.split("-", 2)[0].split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? Integer.parseInt(x[i].strip()) : 0;
            int q = i < y.length ? Integer.parseInt(y[i].strip()) : 0;
            if (p != q) {
                return Integer.compare(p, q);
            }
        }
        return 0;
    }
}
