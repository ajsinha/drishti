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
package com.ash.drishti.iceberg.write;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which column metrics a data file's manifest entry carries (the table properties
 * {@code write.metadata.metrics.default}, {@code truncate(16)} unless set, and
 * {@code write.metadata.metrics.column.<name>}): {@code none}, {@code counts} (value and null counts), {@code
 * truncate(N)} (counts and bounds, text and binary bounds cut to N characters or bytes) or {@code full}.
 */
record MetricsConfig(Map<String, String> properties) {

    /** One column's mode: no counts at all; counts; counts and bounds of at most {@code truncate} characters (or none). */
    record Mode(boolean counts, boolean bounds, int truncate) {
        static final Mode NONE = new Mode(false, false, 0);
        static final Mode COUNTS = new Mode(true, false, 0);
        static final Mode FULL = new Mode(true, true, Integer.MAX_VALUE);
    }

    private static final Pattern TRUNCATE = Pattern.compile("truncate\\((\\d+)\\)");

    Mode mode(String column) {
        String m = properties.getOrDefault("write.metadata.metrics.column." + column,
                properties.getOrDefault("write.metadata.metrics.default", "truncate(16)"));
        return parse(m);
    }

    static Mode parse(String text) {
        String t = text.trim().toLowerCase(Locale.ROOT);
        switch (t) {
            case "none":
                return Mode.NONE;
            case "counts":
                return Mode.COUNTS;
            case "full":
                return Mode.FULL;
            default:
                Matcher m = TRUNCATE.matcher(t);
                if (m.matches()) {
                    return new Mode(true, true, Integer.parseInt(m.group(1)));
                }
                throw new IllegalArgumentException("unknown metrics mode '" + text + "': none, counts, truncate(N) or full");
        }
    }
}
