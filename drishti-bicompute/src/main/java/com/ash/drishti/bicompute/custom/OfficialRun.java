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
package com.ash.drishti.bicompute.custom;

import java.util.function.Supplier;

/**
 * Marks work done for an official run, report or sign-off (PIVOT_ENGINE.md decision 14): while marked, no custom function resolves; the
 * official measures stay the Java engines. The host wraps its official runs in {@link #during}.
 */
public final class OfficialRun {

    private static final ThreadLocal<String> RUN = new ThreadLocal<>();

    private OfficialRun() {}

    /** Runs {@code work} as part of the official run {@code what} (its name, said in a refusal). */
    public static <T> T during(String what, Supplier<T> work) {
        String before = RUN.get();
        RUN.set(what == null ? "an official run" : what);
        try {
            return work.get();
        } finally {
            if (before == null) {
                RUN.remove();
            } else {
                RUN.set(before);
            }
        }
    }

    /** The official run in progress on this thread, or null. */
    public static String current() {
        return RUN.get();
    }
}
