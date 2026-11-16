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
package com.ash.drishti.server.collab;

import java.time.Duration;

/** A duration as people read it ({@code 15-minute}, {@code 5-second}), for messages: never the ISO form ({@code PT15M}). */
public final class HumanDuration {

    private HumanDuration() {}

    /** The largest whole unit that divides the duration: {@code 15-minute}, {@code 2-hour}, {@code 90-second}, {@code 1-day}. */
    public static String of(Duration d) {
        long s = Math.max(0, d.getSeconds());
        if (s >= 86400 && s % 86400 == 0) {
            return s / 86400 + "-day";
        }
        if (s >= 3600 && s % 3600 == 0) {
            return s / 3600 + "-hour";
        }
        if (s >= 60 && s % 60 == 0) {
            return s / 60 + "-minute";
        }
        return s + "-second";
    }
}
