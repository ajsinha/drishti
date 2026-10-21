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
package com.ash.drishti.server.bi.report;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collection;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Whether a report visual's question depends on who views it (docs/architecture/BI_RUNS.md section 18.2b): a filter, parameter, measure or value that names
 * the viewer ({@code user.<attribute>}, {@code me}, {@code @me}, {@code ${user}}, {@code USERNAME()}, {@code CURRENT_USER}). Such a visual's answer is kept
 * per person; any other is shared between viewers with the same access profile. A match anywhere (a title, a text box) counts: when unsure the answer stays
 * personal. Pure.
 */
public final class ViewerIdentity {

    private static final Pattern REF = Pattern.compile(
            "(?<![\\w.])user\\.[A-Za-z_]\\w*"                                       // a DEL user attribute: user.name, user.desk
            + "|\\$\\{?\\s*(?:user|me|viewer|principal|username)\\b"                 // ${user}, $me
            + "|(?<!\\w)@me\\b"
            + "|\\b(?:current_?user|session_?user|user_?name|userprincipalname|viewer|principal)\\s*\\(",   // USERNAME(), CURRENT_USER()
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BARE = Pattern.compile("(?i)\\b(?:current_?user|session_?user)\\b");
    private static final Set<String> WORDS = Set.of("me", "@me", "$me", "${me}", "$user", "${user}", "current_user", "currentuser");

    private ViewerIdentity() {}

    /** Whether any of the parts (maps, lists, JSON, strings, records by their text) names the viewer. */
    public static boolean dependent(Object... parts) {
        for (Object o : parts) {
            if (names(o)) {
                return true;
            }
        }
        return false;
    }

    private static boolean names(Object o) {
        return switch (o) {
            case null -> false;
            case JsonNode j -> j.isTextual() ? text(j.asText()) : j.isContainerNode() && (anyText(j.fieldNames()) || anyNode(j.elements()));
            case Map<?, ?> m -> m.entrySet().stream().anyMatch(e -> names(e.getKey()) || names(e.getValue()));
            case Collection<?> c -> c.stream().anyMatch(ViewerIdentity::names);
            case CharSequence s -> text(s.toString());
            case Number n -> false;
            case Boolean b -> false;
            default -> text(String.valueOf(o));
        };
    }

    private static boolean anyText(Iterator<String> it) {
        while (it.hasNext()) {
            if (text(it.next())) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyNode(Iterator<JsonNode> it) {
        while (it.hasNext()) {
            if (names(it.next())) {
                return true;
            }
        }
        return false;
    }

    /** Whether one text names the viewer. */
    static boolean text(String s) {
        if (s == null || s.isBlank()) {
            return false;
        }
        return WORDS.contains(s.strip().toLowerCase(Locale.ROOT)) || REF.matcher(s).find() || BARE.matcher(s).find();
    }
}
