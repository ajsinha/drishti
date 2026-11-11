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
package com.ash.drishti.module.runtime;

import java.util.ArrayList;
import java.util.List;

/**
 * A version range as a manifest writes it ({@code ">=1.0 <2.0"}, {@code ">=1.2"}, {@code "1.4"} for exactly that version, {@code "*"} or
 * blank for any): space-separated comparisons, all of which must hold. Versions compare numerically part by part ({@code 1.10 > 1.9});
 * a missing part is 0, a pre-release suffix after {@code -} is ignored.
 */
public final class VersionRange {

    private record Bound(String op, String version) {
    }

    private final String text;
    private final List<Bound> bounds;

    private VersionRange(String text, List<Bound> bounds) {
        this.text = text;
        this.bounds = bounds;
    }

    /** The range the text describes; refuses an unreadable one with words. */
    public static VersionRange parse(String text) {
        String t = text == null ? "" : text.strip();
        List<Bound> out = new ArrayList<>();
        if (!t.isEmpty() && !"*".equals(t)) {
            for (String part : t.split("[\\s,]+")) {
                String op = part.startsWith(">=") || part.startsWith("<=") ? part.substring(0, 2)
                        : part.startsWith(">") || part.startsWith("<") || part.startsWith("=") ? part.substring(0, 1) : "=";
                String v = part.substring(part.startsWith(op) ? op.length() : 0);
                if (!isVersion(v)) {
                    throw new IllegalArgumentException("not a version range: " + text);
                }
                out.add(new Bound(op, v));
            }
        }
        return new VersionRange(t.isEmpty() ? "*" : t, List.copyOf(out));
    }

    /** True when the text is a readable version ({@code 1}, {@code 1.4}, {@code 3.0.0}, {@code 0.1.0-rc1}). */
    public static boolean isVersion(String v) {
        return v != null && v.matches("\\d+(\\.\\d+){0,3}(-[0-9A-Za-z.]+)?");
    }

    /** True when the version lies in the range. */
    public boolean contains(String version) {
        if (!isVersion(version)) {
            return false;
        }
        for (Bound b : bounds) {
            int c = compare(version, b.version());
            boolean ok = switch (b.op()) {
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

    /** Compares two versions numerically, part by part. */
    public static int compare(String a, String b) {
        String[] x = core(a).split("\\.");
        String[] y = core(b).split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            long p = i < x.length ? Long.parseLong(x[i]) : 0;
            long q = i < y.length ? Long.parseLong(y[i]) : 0;
            if (p != q) {
                return Long.compare(p, q);
            }
        }
        return 0;
    }

    private static String core(String v) {
        int dash = v.indexOf('-');
        return dash < 0 ? v : v.substring(0, dash);
    }

    @Override
    public String toString() {
        return text;
    }
}
