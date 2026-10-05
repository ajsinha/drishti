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

import com.ash.drishti.api.DataNode;
import com.ash.drishti.identity.collab.Share.Span;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The text of a note, and the ranges of it that copy a masked field's value. The note is stored as written; readers without
 * {@code raw} see those ranges as the mask. Matching is exact (the {@code mask-copies} rules: the values come from
 * {@code Entitlements.maskedValues}), so a case variant or a value known from elsewhere is not found (documented; use
 * {@code deny-patterns}).
 */
public final class NoteText {

    private NoteText() {}

    /** Merged, sorted ranges of {@code text} where any of {@code secrets} occurs (longest first, so a longer value wins an overlap). */
    public static List<Span> spans(String text, List<String> secrets) {
        List<int[]> found = new ArrayList<>();
        for (String secret : secrets) {
            if (secret == null || secret.isEmpty()) {
                continue;
            }
            for (int at = text.indexOf(secret); at >= 0; at = text.indexOf(secret, at + 1)) {
                found.add(new int[] {at, at + secret.length()});
            }
        }
        found.sort(Comparator.<int[]>comparingInt(a -> a[0]).thenComparingInt(a -> a[1]));
        List<Span> out = new ArrayList<>();
        for (int[] f : found) {
            if (!out.isEmpty() && f[0] <= out.get(out.size() - 1).end()) {
                Span last = out.remove(out.size() - 1);
                out.add(new Span(last.start(), Math.max(last.end(), f[1])));
            } else {
                out.add(new Span(f[0], f[1]));
            }
        }
        return out;
    }

    /** The text for a reader: the spans replaced by the mask when {@code scrub}, else as written. */
    public static String render(String text, List<Span> spans, boolean scrub) {
        if (!scrub || spans.isEmpty()) {
            return text;
        }
        StringBuilder b = new StringBuilder();
        int at = 0;
        for (Span s : spans) {
            int start = Math.max(at, Math.min(s.start(), text.length()));
            int end = Math.min(text.length(), Math.max(start, s.end()));
            b.append(text, at, start).append(DataNode.MASK);
            at = end;
        }
        return b.append(text, at, text.length()).toString();
    }

    /** At most {@code max} characters, with an ellipsis when cut. */
    public static String excerpt(String text, int max) {
        return text.length() <= max ? text : text.substring(0, Math.max(0, max - 1)) + "…";
    }
}
