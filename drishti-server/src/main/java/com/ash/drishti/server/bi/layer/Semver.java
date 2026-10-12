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
package com.ash.drishti.server.bi.layer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A semantic version of a dataset's or a model's contract ({@code 1.3.0}, docs/architecture/BI_MODEL_SPEC.md section 2.8): a new major breaks consumers, a
 * minor adds, a patch changes words and formats. Ordered.
 */
public record Semver(int major, int minor, int patch) implements Comparable<Semver> {

    private static final Pattern FULL = Pattern.compile("(\\d{1,6})\\.(\\d{1,6})\\.(\\d{1,6})");

    /** The version or null when the text is not {@code major.minor.patch}. */
    public static Semver parse(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = FULL.matcher(text.trim());
        return m.matches() ? new Semver(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))) : null;
    }

    @Override
    public int compareTo(Semver o) {
        int c = Integer.compare(major, o.major);
        if (c == 0) {
            c = Integer.compare(minor, o.minor);
        }
        return c == 0 ? Integer.compare(patch, o.patch) : c;
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }

    /**
     * A version range in a reference ({@code name@^1}): {@code 1.2.0} exactly, {@code ^1} or {@code ^1.2} (the same major, at least that), {@code ~1.2} (the
     * same major and minor), {@code 1} (any 1.x, as {@code @1} in a consumer binding), or empty (any version).
     */
    public record Range(String text, Semver low, int major, Integer minor, boolean exact) {

        public static final Range ANY = new Range("", null, -1, null, false);
        private static final Pattern R = Pattern.compile("([\\^~]?)(\\d{1,6})(?:\\.(\\d{1,6}))?(?:\\.(\\d{1,6}))?");

        /** The range, or null when the text is not one of the forms above. */
        public static Range parse(String text) {
            if (text == null || text.isBlank()) {
                return ANY;
            }
            Matcher m = R.matcher(text.trim());
            if (!m.matches()) {
                return null;
            }
            int major = Integer.parseInt(m.group(2));
            Integer minor = m.group(3) == null ? null : Integer.parseInt(m.group(3));
            Integer patch = m.group(4) == null ? null : Integer.parseInt(m.group(4));
            String op = m.group(1);
            Semver low = new Semver(major, minor == null ? 0 : minor, patch == null ? 0 : patch);
            if (op.isEmpty() && patch != null) {
                return new Range(text.trim(), low, major, minor, true);
            }
            return new Range(text.trim(), low, major, "~".equals(op) ? minor : null, false);
        }

        public boolean matches(Semver v) {
            if (v == null) {
                return false;
            }
            if (low == null) {
                return true;
            }
            if (exact) {
                return v.equals(low);
            }
            return v.major() == major && (minor == null || v.minor() == minor) && v.compareTo(low) >= 0;
        }
    }

    /** A reference {@code name@range} split: the name and the range (any version when there is no {@code @}). */
    public record Ref(String name, Range range) {

        /** The reference, or null when the range part is not a range. */
        public static Ref parse(String text) {
            String t = text == null ? "" : text.trim();
            int at = t.lastIndexOf('@');
            if (at < 0) {
                return new Ref(t, Range.ANY);
            }
            Range r = Range.parse(t.substring(at + 1));
            return r == null ? null : new Ref(t.substring(0, at), r);
        }

        @Override
        public String toString() {
            return range.text().isEmpty() ? name : name + "@" + range.text();
        }
    }
}
