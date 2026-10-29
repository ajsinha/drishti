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
package com.ash.drishti.lakeload;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The duplicate-record rule both loaders share ({@code tools/lake/stage.py}'s {@code instant_micros} and {@code winner}; TOOLS.md section
 * 4.13): when one id appears more than once on one business date in one load, the record with the latest {@code latest-by} timestamp
 * ({@code COMPUTED_AT} by default) is kept; without one (or on a tie) the last one read, in the input order (files sorted by path, then
 * line order). A record with a timestamp beats one without. The others are dropped and counted; the load never fails for them.
 *
 * <p>A timestamp is ISO-8601 text: {@code 2026-09-30}, {@code 2026-09-30T17:05:00}, with seconds, a fraction (to microseconds) and an
 * offset ({@code Z}, {@code +01:00}); no offset is UTC. Anything else (a number, an out-of-range field) counts as no timestamp.
 */
final class Latest {

    /** No timestamp: lower than any. */
    static final long NONE = Long.MIN_VALUE;

    private static final Pattern ISO = Pattern.compile(
            "^(\\d{4})-(\\d{2})-(\\d{2})(?:[T ](\\d{2}):(\\d{2})(?::(\\d{2})(?:[.,](\\d+))?)?)?\\s*(Z|z|[+-]\\d{2}(?::?\\d{2})?)?$");

    private Latest() {}

    /** The timestamp as microseconds since the epoch (UTC), {@link #NONE} when the text is not one. */
    static long micros(String text) {
        if (text == null) {
            return NONE;
        }
        Matcher m = ISO.matcher(text.strip());
        if (!m.matches()) {
            return NONE;
        }
        try {
            long day = LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))).toEpochDay();
            int h = m.group(4) == null ? 0 : Integer.parseInt(m.group(4));
            int mi = m.group(5) == null ? 0 : Integer.parseInt(m.group(5));
            int s = m.group(6) == null ? 0 : Integer.parseInt(m.group(6));
            if (h > 23 || mi > 59 || s > 59) {
                return NONE;
            }
            String f = m.group(7) == null ? "" : m.group(7);
            f = (f + "000000").substring(0, 6);
            long offset = 0;
            String z = m.group(8);
            if (z != null && !z.equalsIgnoreCase("Z")) {
                String d = z.substring(1).replace(":", "");
                int oh = Integer.parseInt(d.substring(0, 2));
                int om = d.length() > 2 ? Integer.parseInt(d.substring(2)) : 0;
                if (oh > 23 || om > 59) {
                    return NONE;
                }
                offset = (z.charAt(0) == '-' ? -1 : 1) * (oh * 3600L + om * 60L);
            }
            return (day * 86400L + h * 3600L + mi * 60L + s - offset) * 1_000_000L + Long.parseLong(f);
        } catch (DateTimeException | NumberFormatException e) {
            return NONE;
        }
    }

    /** A place in the input as one number that orders as the input does (split, chunk, line): compare with {@link Long#compareUnsigned}. */
    static long order(int split, int seq, int line) {
        return ((long) split << 44) | ((long) (seq & 0xFFFFF) << 24) | (line & 0xFFFFFFL);
    }

    static int split(long order) {
        return (int) (order >>> 44);
    }

    static int seq(long order) {
        return (int) ((order >>> 24) & 0xFFFFF);
    }

    static int line(long order) {
        return (int) (order & 0xFFFFFF);
    }

    /** True when the record (timestamp {@code ts}, place {@code order}) beats the one held ({@code bestTs}, {@code bestOrder}). */
    static boolean beats(long ts, long order, long bestTs, long bestOrder) {
        return ts != bestTs ? ts > bestTs : Long.compareUnsigned(order, bestOrder) > 0;
    }
}
