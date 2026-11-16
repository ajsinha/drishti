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
                fieldPatterns.add(Pattern.compile("(?i)(\"?\\b" + anyConvention(f.strip()) + "\\b\"?\\s*[:=]\\s*)(\"[^\"]*\"|[^\\s,;}\\]]+)"));
            }
        }
        for (String p : extraPatterns) {
            if (p != null && !p.isBlank()) {
                plainPatterns.add(Pattern.compile(p));
            }
        }
    }

    /**
     * True when two field names are the same field in any case and convention ({@code counterpartyId}, {@code COUNTERPARTY_ID},
     * {@code counterparty-id}): a mask written before the UPPER_SNAKE rename still masks the renamed field.
     */
    public static boolean sameField(String a, String b) {
        return canonical(a).equals(canonical(b));
    }

    private static String canonical(String name) {
        StringBuilder out = new StringBuilder(name.length());
        for (char c : name.toCharArray()) {
            if (c != '_' && c != '-' && c != ' ') {
                out.append(Character.toLowerCase(c));
            }
        }
        return out.toString();
    }

    /** The field name as a pattern that matches it in any convention (case is ignored by the caller's flag; a separator may sit between any two characters). */
    private static String anyConvention(String field) {
        String c = canonical(field);
        if (c.isEmpty()) {
            return Pattern.quote(field);
        }
        StringBuilder p = new StringBuilder();
        for (int i = 0; i < c.length(); i++) {
            p.append(i == 0 ? "" : "[_-]?").append(Pattern.quote(String.valueOf(c.charAt(i))));
        }
        return p.toString();
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
