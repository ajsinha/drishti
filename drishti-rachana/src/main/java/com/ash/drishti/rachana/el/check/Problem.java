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
package com.ash.drishti.rachana.el.check;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One finding about an expression in checked mode (moved from DEL): its code ({@link #LANGUAGE} {@code DRS-9127} for the type or
 * scope, {@link #LATER} {@code DRS-9129} for what is not yet built, {@link #NAME} {@code DRS-9152} for an unknown name, {@link #MASKED}
 * {@code DRS-9161} for a masked field filtered), what is wrong, how to fix it, and where ({@code start} to {@code end}, character
 * offsets in the text).
 *
 * @param level {@code error} or {@code warning}
 * @param fix what to write instead (null when there is no mechanical fix)
 */
public record Problem(String code, String level, String message, String fix, int start, int end) {

    public static final String ERROR = "error";
    public static final String WARNING = "warning";

    /** A type or scope the expression may not have (DEL's code, kept). */
    public static final String LANGUAGE = "DRS-9127";
    /** What is not yet built (a function of the next release, Q23). */
    public static final String LATER = "DRS-9129";
    /** A name the table or model does not have. */
    public static final String NAME = "DRS-9152";
    /** A filter, sort, {@code in} or grouping on a masked field. */
    public static final String MASKED = "DRS-9161";

    public boolean error() {
        return ERROR.equals(level);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("level", level);
        m.put("message", message);
        m.put("fix", fix);
        m.put("at", Map.of("start", start, "end", end));
        return m;
    }
}
