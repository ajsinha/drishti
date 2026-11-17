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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Figures of rows a module read, computed by the server's pivot engine ({@code POST /api/v1/pivot/inline}, as the person): the rows go
 * in with the measures wanted ({@code sum(MTM)}, {@code median(S)}, {@code stddev_pop(H)}, a formula of aggregates) and the engine's
 * cells come back. The engine aggregates (compensated sums, exact medians, Welford's deviations); nothing in a module adds up a figure.
 *
 * <p>{@link Asker#figures} asks and answers {@link Figures}: {@link Figures#get} a cell's figure by its {@code as} name (a
 * null member is the total along that axis), {@link Figures#members} the row members in the engine's order. No rows: nothing is asked
 * and every figure is null. The kernel implements the asking and the reading of the engine's answer; this holds the request's words.
 */
public interface InlineFigures {

    /** The engine's most inline rows ({@code InlinePivot.MAX_ROWS} of drishti-bicompute). */
    int MAX_ROWS = 100_000;
    /** The member of rows with no value of the dimension ({@code CellKey.BLANK}). */
    String BLANK = "(blank)";
    /** The member that stands for the rows a top N left out. */
    String OTHERS = "\u0000Others";

    /** One field of the rows: its name and {@code string} or {@code number}. */
    record Field(String name, String type) {
        private static final Pattern NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,63}$");

        public Field {
            if (name == null || !NAME.matcher(name).matches() || !("string".equals(type) || "number".equals(type))) {
                throw new IllegalArgumentException("an inline field is letters, digits and _, a string or a number: " + name + " " + type);
            }
        }
    }

    /** One measure: {@code {measure, as}}. */
    static Map<String, Object> measure(String measure, String as) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("measure", measure);
        m.put("as", as);
        return m;
    }

    /** One measure shown as another figure ({@code pct-grand}): {@code {measure, as, showAs}}. */
    static Map<String, Object> measure(String measure, String as, String showAs) {
        Map<String, Object> m = measure(measure, as);
        m.put("showAs", showAs);
        return m;
    }

    /** The engine's answer, by member. */
    interface Figures {

        /** Whether the engine was asked (false: there were no rows). */
        boolean answered();

        /** A figure by its {@code as} name at a row and column member (null: the total along that axis); null when the engine has none. */
        Double get(String name, Object row, Object column);

        /** The grand total's figure. */
        default Double get(String name) {
            return get(name, null, null);
        }

        /** The row members the engine answered, in its order (the total and Others left out). */
        List<Object> members();
    }
}
