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

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One source of truth for a title. A dataset, report, board and notebook carry a {@code title:} in their YAML and a title on the item; the two must always
 * read the same. A rename on either side is carried to the other: the catalogue title written into the body, or the body's title read into the item.
 */
final class BiTitles {

    /** The kinds whose body has a top-level {@code title:}. */
    static final Set<String> KINDS = Set.of("dataset", "model", "report", "board", "notebook");

    private static final Pattern LINE = Pattern.compile("^title:[ \\t]*(.*?)[ \\t]*$", Pattern.MULTILINE);

    private BiTitles() {
    }

    /** The top-level {@code title:} of the body, or null when there is none (or it is not a plain or quoted scalar). */
    static String of(String body) {
        if (body == null) {
            return null;
        }
        Matcher m = LINE.matcher(body);
        if (!m.find()) {
            return null;
        }
        String v = m.group(1);
        if (v.isEmpty() || v.startsWith("|") || v.startsWith(">") || v.startsWith("#")) {
            return null;
        }
        if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"))) {
            String inner = v.substring(1, v.length() - 1);
            return v.charAt(0) == '"' ? inner.replace("\\\"", "\"").replace("\\\\", "\\") : inner.replace("''", "'");
        }
        int hash = v.indexOf(" #");
        return (hash >= 0 ? v.substring(0, hash) : v).trim();
    }

    /** The body with its top-level {@code title:} line set to {@code title}; the body unchanged when it has no such line. */
    static String with(String body, String title) {
        if (body == null || !LINE.matcher(body).find()) {
            return body;
        }
        String quoted = "\"" + title.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"";
        return LINE.matcher(body).replaceFirst(Matcher.quoteReplacement("title: " + quoted));
    }
}
