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

    /**
     * Merged, sorted ranges of {@code text} where any of {@code secrets} occurs. Matching is on a normalised form of both sides: NFKC,
     * case folded, zero-width and soft-hyphen characters dropped, runs of white space as one space, and the common Cyrillic and Greek
     * look-alikes of Latin letters folded to the Latin letter. A value that is only part of a longer word, or spelled with a
     * look-alike this table does not know, is not found (documented: use {@code deny-patterns}).
     */
    public static List<Span> spans(String text, List<String> secrets) {
        Folded t = fold(text);
        List<int[]> found = new ArrayList<>();
        for (String secret : secrets) {
            if (secret == null || secret.isEmpty()) {
                continue;
            }
            String needle = fold(secret).text;
            if (needle.isBlank()) {
                continue;
            }
            for (int at = t.text.indexOf(needle); at >= 0; at = t.text.indexOf(needle, at + 1)) {
                found.add(new int[] {t.from[at], t.to[at + needle.length() - 1]});
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

    private record Folded(String text, int[] from, int[] to) {}

    private static final String LOOKALIKE_FROM = "\u0430\u0435\u043e\u0440\u0441\u0443\u0445\u0456\u0458\u04bb\u0455\u043a\u043c\u043d\u0442\u0432\u03bf\u03b1\u03c1\u03bd\u03c5\u03b9\u03ba\u03c4\u0131";
    private static final String LOOKALIKE_TO = "aeopcyxijhskmntbo" + "a" + "pnuikt" + "i";

    /** The normalised text and, for each of its characters, the range of the original it came from. */
    private static Folded fold(String text) {
        StringBuilder b = new StringBuilder(text.length());
        int[] from = new int[text.length() + 8];
        int[] to = new int[text.length() + 8];
        int n = 0;
        boolean space = false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int len = Character.charCount(cp);
            i += len;
            if (isInvisible(cp)) {
                continue;
            }
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                if (space) {
                    to[n - 1] = i;                                   // a run of spaces is one space, covering the run
                } else {
                    b.append(' ');
                    from[n] = i - len;
                    to[n++] = i;
                    space = true;
                }
                continue;
            }
            space = false;
            String f = java.text.Normalizer.normalize(new String(Character.toChars(cp)), java.text.Normalizer.Form.NFKC).toLowerCase(java.util.Locale.ROOT);
            if (n + f.length() >= from.length) {
                from = java.util.Arrays.copyOf(from, (n + f.length()) * 2);
                to = java.util.Arrays.copyOf(to, from.length);
            }
            for (int k = 0; k < f.length(); k++) {
                int idx = LOOKALIKE_FROM.indexOf(f.charAt(k));
                b.append(idx >= 0 ? LOOKALIKE_TO.charAt(idx) : f.charAt(k));
                from[n] = i - len;
                to[n++] = i;
            }
        }
        return new Folded(b.toString(), from, to);
    }

    private static boolean isInvisible(int cp) {
        return cp == 0x00AD || cp >= 0x200B && cp <= 0x200F || cp >= 0x202A && cp <= 0x202E || cp == 0x2060 || cp == 0xFEFF || cp == 0x034F
                || Character.getType(cp) == Character.FORMAT;
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
