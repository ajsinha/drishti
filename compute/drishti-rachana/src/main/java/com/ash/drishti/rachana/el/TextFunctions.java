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
package com.ash.drishti.rachana.el;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.NodeType;
import com.ash.drishti.common.SafeRegex;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Text (FUNCTION_CATALOGUE.md section 2): {@code fmt}, {@code upper}, {@code lower}, the tests {@code contains},
 * {@code starts_with}, {@code ends_with}, {@code matches}, {@code matches_case}, and {@code concat}, {@code left},
 * {@code right}, {@code mid}, {@code replace}, {@code split}, {@code trim}, {@code pad}, {@code regex_extract}. The tests
 * are false for an empty value (a condition on a missing field is never true); the other functions give empty for an
 * empty text. A number reads as the text it prints ({@code left(20261016, 4)} is {@code 2026}).
 */
final class TextFunctions {

    private TextFunctions() {}

    /** The widest {@code pad} makes a text: a bound on the work. */
    static final int MAX_PAD = 10_000;

    static void register(Functions.Table t) {
        t.fn("fmt", 2, 2, (a, c) -> c.formats().format(Values.text(a.get(1)), a.get(0)));
        t.fn("upper", 1, 1, (a, c) -> Values.isNull(a.get(0)) ? null : Values.text(a.get(0)).toUpperCase(Locale.ROOT));   // null in, null out
        t.fn("lower", 1, 1, (a, c) -> Values.isNull(a.get(0)) ? null : Values.text(a.get(0)).toLowerCase(Locale.ROOT));   // null in, null out
        t.fn("contains", 2, 2, (a, c) -> contains(a.get(0), Values.text(a.get(1))));
        t.fn("starts_with", 2, 2, (a, c) -> lower(a.get(0)).startsWith(lower(a.get(1))));
        t.old("startsWith", "starts_with", 2, 2);                       // read for compatibility: existing Sutras write startsWith
        t.fn("ends_with", 2, 2, (a, c) -> lower(a.get(0)).endsWith(lower(a.get(1))));
        t.fn("matches", 2, 2, (a, c) -> matches(a.get(0), Values.text(a.get(1)), true));
        t.fn("matches_case", 2, 2, (a, c) -> matches(a.get(0), Values.text(a.get(1)), false));
        t.old("matchesCase", "matches_case", 2, 2);                     // read for compatibility
        t.fn("concat", 1, 64, (a, c) -> concat(a));
        t.fn("left", 2, 2, (a, c) -> part("left", a.get(0), a.get(1), true));
        t.fn("right", 2, 2, (a, c) -> part("right", a.get(0), a.get(1), false));
        t.fn("mid", 3, 3, (a, c) -> mid(a));
        t.fn("replace", 3, 3, (a, c) -> replace(a));
        t.fn("split", 2, 2, (a, c) -> split(a));
        t.fn("trim", 1, 1, (a, c) -> {
            String s = Args.text("trim", a.get(0));
            return s == null ? null : s.strip();
        });
        t.fn("pad", 2, 4, (a, c) -> pad(a)).check("pad", TextFunctions::checkPad);
        t.fn("regex_extract", 2, 3, (a, c) -> regexExtract(a)).check("regex_extract", TextFunctions::checkPattern);
    }

    private static String lower(Object v) {
        return Values.text(v).toLowerCase(Locale.ROOT);
    }

    /** Case-insensitive: text contains the part, or a list has an element that does. */
    private static boolean contains(Object where, String part) {
        String p = part.toLowerCase(Locale.ROOT);
        if (where instanceof DataNode n && n.type() == NodeType.ARRAY) {
            for (int i = 0; i < n.size(); i++) {
                if (contains(n.get(i), part)) {
                    return true;
                }
            }
            return false;
        }
        return !Values.isNull(where) && Values.text(where).toLowerCase(Locale.ROOT).contains(p);
    }

    private static final ConcurrentHashMap<String, SafeRegex> PATTERNS = new ConcurrentHashMap<>();

    /** A compiled pattern, by the linear-time engine ({@link SafeRegex}): a pattern it refuses raises DRS-4010. */
    private static SafeRegex pattern(String pattern, boolean ignoreCase) {
        if (PATTERNS.size() > 512) {
            PATTERNS.clear();
        }
        return PATTERNS.computeIfAbsent((ignoreCase ? "i:" : "c:") + pattern, k -> SafeRegex.compile(pattern, ignoreCase));
    }

    /**
     * Regular-expression search over text, by the linear-time engine: a pattern it refuses raises DRS-4010, one that runs
     * past its time budget DRS-4011. Matches anywhere in the value; null never matches.
     */
    private static boolean matches(Object value, String pattern, boolean ignoreCase) {
        SafeRegex re = pattern(pattern, ignoreCase);
        return !Values.isNull(value) && re.find(Values.text(value));
    }

    /** A pattern written in {@code regex_extract} is compiled when the expression is, so a refused one is found at load. */
    private static String checkPattern(List<Expr> args) {
        if (Functions.literal(args, 1) instanceof String p) {
            try {
                SafeRegex re = SafeRegex.compile(p, false);
                if (Functions.literal(args, 2) instanceof Number g && (g.intValue() < 0 || g.intValue() > re.groups())) {
                    return "the pattern '" + p + "' has " + re.groups() + " group(s); group " + g.intValue() + " was asked for";
                }
            } catch (com.ash.drishti.common.DrishtiException e) {
                return "the pattern '" + p + "' is refused: " + e.getMessage();
            }
        }
        return null;
    }

    /** {@code concat(a, b, ...)}: the texts joined; empty arguments add nothing (so a running text can start empty). */
    private static Object concat(List<Object> a) {
        StringBuilder sb = new StringBuilder();
        for (Object v : a) {
            String s = Args.text("concat", v);
            if (s != null) {
                sb.append(s);
            }
        }
        return sb.toString();
    }

    /** {@code left(s, n)}, {@code right(s, n)}: the first or last n characters (the whole text when it is shorter). */
    private static Object part(String fn, Object text, Object count, boolean fromLeft) {
        String s = Args.text(fn, text);
        Integer n = Args.whole(fn, count);
        if (s == null || n == null) {
            return null;
        }
        if (n < 0) {
            throw Args.error(fn, "the count " + n + " is negative");
        }
        int k = Math.min(n, s.length());
        return fromLeft ? s.substring(0, k) : s.substring(s.length() - k);
    }

    /** {@code mid(s, start, n)}: n characters from position start (1 the first, as in Excel). */
    private static Object mid(List<Object> a) {
        String s = Args.text("mid", a.get(0));
        Integer start = Args.whole("mid", a.get(1));
        Integer n = Args.whole("mid", a.get(2));
        if (s == null || start == null || n == null) {
            return null;
        }
        if (start < 1) {
            throw Args.error("mid", "the start " + start + " is before the first character (1)");
        }
        if (n < 0) {
            throw Args.error("mid", "the count " + n + " is negative");
        }
        int from = Math.min(start - 1, s.length());
        return s.substring(from, (int) Math.min((long) from + n, s.length()));
    }

    /** {@code replace(s, old, new)}: every {@code old} (as written, case counts) replaced by {@code new}. */
    private static Object replace(List<Object> a) {
        String s = Args.text("replace", a.get(0));
        String old = Args.text("replace", a.get(1));
        String now = Args.text("replace", a.get(2));
        if (s == null || old == null || now == null) {
            return null;
        }
        return old.isEmpty() ? s : s.replace(old, now);
    }

    /** {@code split(s, sep)}: the parts between each {@code sep} (as written), empty parts kept, as a list of text. */
    private static Object split(List<Object> a) {
        String s = Args.text("split", a.get(0));
        String sep = Args.text("split", a.get(1));
        if (s == null || sep == null) {
            return null;
        }
        if (sep.isEmpty()) {
            throw Args.error("split", "the separator is empty");
        }
        List<Object> parts = new ArrayList<>();
        int from = 0;
        for (int at = s.indexOf(sep); at >= 0; at = s.indexOf(sep, from)) {
            parts.add(s.substring(from, at));
            from = at + sep.length();
        }
        parts.add(s.substring(from));
        return Args.list(parts);
    }

    /**
     * {@code pad(s, n, [char], [side])}: s widened to n characters with {@code char} (a space by default) on the
     * {@code 'left'} (the default, for codes and numbers: {@code pad(7, 3, '0')} is {@code 007}) or the {@code 'right'};
     * a text already that wide is kept whole.
     */
    private static Object pad(List<Object> a) {
        String s = Args.text("pad", a.get(0));
        Integer n = Args.whole("pad", a.get(1));
        String ch = a.size() > 2 ? Args.text("pad", a.get(2)) : " ";
        String side = a.size() > 3 ? Args.text("pad", a.get(3)) : "left";
        if (s == null || n == null || ch == null || side == null) {
            return null;
        }
        String problem = padProblem(n, ch, side);
        if (problem != null) {
            throw Args.error("pad", problem);
        }
        if (s.length() >= n) {
            return s;
        }
        String fill = ch.repeat(n - s.length());
        return "left".equals(side) ? fill + s : s + fill;
    }

    private static String padProblem(Integer n, String ch, String side) {
        if (n != null && (n < 0 || n > MAX_PAD)) {
            return "the width " + n + " is outside 0.." + MAX_PAD;
        }
        if (ch != null && ch.length() != 1) {
            return "the fill '" + ch + "' is not one character";
        }
        if (side != null && !"left".equals(side) && !"right".equals(side)) {
            return "the side '" + side + "' is neither 'left' nor 'right'";
        }
        return null;
    }

    private static String checkPad(List<Expr> args) {
        Object n = Functions.literal(args, 1);
        Object ch = Functions.literal(args, 2);
        Object side = Functions.literal(args, 3);
        return padProblem(n instanceof Number x ? Integer.valueOf(x.intValue()) : null, ch instanceof String s ? s : null,
                side instanceof String s ? s : null);
    }

    /**
     * {@code regex_extract(s, regex, [group])}: the first match's text, or that of capturing group {@code group} (1 the first
     * {@code (}); empty when nothing matches. Case counts (start the pattern with {@code (?i)} to ignore it); the same
     * linear-time engine as {@code matches}.
     */
    private static Object regexExtract(List<Object> a) {
        String s = Args.text("regex_extract", a.get(0));
        String p = Args.text("regex_extract", a.get(1));
        Integer g = a.size() > 2 ? Args.whole("regex_extract", a.get(2)) : Integer.valueOf(0);
        if (s == null || p == null || g == null) {
            return null;
        }
        SafeRegex re = pattern(p, false);
        if (g < 0 || g > re.groups()) {
            throw Args.error("regex_extract", "the pattern '" + p + "' has " + re.groups() + " group(s); group " + g + " was asked for");
        }
        return re.extract(s, g);
    }
}
