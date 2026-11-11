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
package com.ash.drishti.server.bi;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Says a YAML syntax error once and short: the line and column the reader gives, and the parser's own reason without its internal source name
 * ({@code in 'reader'}) or the quoted context lines.
 */
public final class YamlErrors {

    private static final Pattern CONTEXT = Pattern.compile("(?s)\\s*in '[^']*',\\s*line \\d+,\\s*column \\d+:.*$");
    private static final Pattern REASON = Pattern.compile("(?s)\\^\\s*(.*?)\\s*$");

    private YamlErrors() {}

    /** @return for example {@code line 3, column 8: expected ',' or ']'} */
    public static String describe(JsonProcessingException e) {
        String raw = e.getOriginalMessage() == null ? e.getMessage() : e.getOriginalMessage();
        String text = raw == null ? "" : raw;
        Matcher reason = REASON.matcher(text);
        String why;
        if (reason.find() && !reason.group(1).isBlank()) {
            why = reason.group(1).replaceAll("\\s+", " ").replaceAll(" in '[^']*', line \\d+, column \\d+.*$", "");
        } else {
            why = CONTEXT.matcher(text).replaceAll("").replaceAll("\\s+", " ").trim();
        }
        JsonLocation at = e.getLocation();
        String where = at == null || at.getLineNr() < 1 ? "" : "line " + at.getLineNr() + ", column " + Math.max(1, at.getColumnNr()) + ": ";
        return where + (why.isBlank() ? "the YAML is not valid" : why);
    }
}
