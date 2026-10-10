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
package com.ash.drishti.ai;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Removes what must not leave the building from prompt text: the value after any field named in
 * {@code drishti.security.redact} (as {@code name: value}, {@code name=value} or {@code "name":"value"}), and anything matching
 * the extra patterns the administrator lists. Best effort and additive to the masking a page already applied to its own values.
 */
public final class PromptMasker {

    public static final String MASK = "•••";
    private static final PromptMasker NONE = new PromptMasker(List.of(), List.of());

    private final List<Pattern> fieldPatterns = new ArrayList<>();
    private final List<Pattern> plainPatterns = new ArrayList<>();

    public PromptMasker(Collection<String> redactedFields, Collection<String> extraPatterns) {
        for (String f : redactedFields) {
            if (f != null && !f.isBlank()) {
                fieldPatterns.add(Pattern.compile("(?i)(\"?\\b" + Pattern.quote(f.strip()) + "\\b\"?\\s*[:=]\\s*)(\"[^\"]*\"|[^\\s,;}\\]]+)"));
            }
        }
        for (String p : extraPatterns) {
            if (p != null && !p.isBlank()) {
                plainPatterns.add(Pattern.compile(p));
            }
        }
    }

    public static PromptMasker none() {
        return NONE;
    }

    public boolean active() {
        return !fieldPatterns.isEmpty() || !plainPatterns.isEmpty();
    }

    public String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String t = text;
        for (Pattern p : fieldPatterns) {
            t = p.matcher(t).replaceAll(m -> java.util.regex.Matcher.quoteReplacement(m.group(1) + (m.group(2).startsWith("\"") ? "\"" + MASK + "\"" : MASK)));
        }
        for (Pattern p : plainPatterns) {
            t = p.matcher(t).replaceAll(java.util.regex.Matcher.quoteReplacement(MASK));
        }
        return t;
    }

    public ChatRequest mask(ChatRequest r) {
        if (!active()) {
            return r;
        }
        return r.withText(mask(r.system()), r.messages().stream().map(m -> new ChatMessage(m.role(), mask(m.content()))).toList());
    }
}
