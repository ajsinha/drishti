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

import com.ash.drishti.common.SafeRegex;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * More text (FUNCTION_CATALOGUE.md section 2), beside {@link TextFunctions}: {@code len}, {@code proper}, {@code rept}, {@code exact},
 * {@code char}, {@code code} (Unicode, Excel's UNICHAR and UNICODE), {@code find} (case counts) and {@code search} (ignoring case), both
 * a 1-based position or 0 when not found (SAS's INDEX rule, so {@code find(...) > 0} is a test), {@code text_before}, {@code text_after},
 * {@code regex_replace}, {@code reverse}, {@code clean}, {@code value} (text read as a number) and {@code text} (a number or a date written
 * with an Excel format code; {@code fmt} writes a pack's named display format instead). An empty text gives an empty result.
 */
final class TextParts {

    private TextParts() {}

    /** The longest text {@code rept} makes: a bound on the work. */
    static final int MAX_REPT = 10_000;

    static void register(Functions.Table t) {
        t.fn("len", 1, 1, (a, c) -> {
            String s = Args.text("len", a.get(0));
            return s == null ? null : (Object) (long) s.length();
        });
        t.fn("proper", 1, 1, (a, c) -> proper(Args.text("proper", a.get(0))));
        t.fn("rept", 2, 2, (a, c) -> {
            String s = Args.text("rept", a.get(0));
            Integer n = Args.whole("rept", a.get(1));
            if (s == null || n == null) {
                return null;
            }
            if (n < 0 || (long) n * s.length() > MAX_REPT) {
                throw Args.error("rept", "the count " + n + " is negative or makes a text longer than " + MAX_REPT);
            }
            return s.repeat(n);
        });
        t.fn("exact", 2, 2, (a, c) -> {
            Object x = Values.simplify(a.get(0));
            Object y = Values.simplify(a.get(1));
            return x == null || y == null ? x == y : (Object) Values.text(x).equals(Values.text(y));
        });
        t.fn("char", 1, 1, (a, c) -> {
            Integer n = Args.whole("char", a.get(0));
            if (n == null) {
                return null;
            }
            if (n < 1 || n > Character.MAX_CODE_POINT || Character.getType(n) == Character.SURROGATE) {
                throw Args.error("char", n + " is not a Unicode character");
            }
            return new String(Character.toChars(n));
        });
        t.fn("code", 1, 1, (a, c) -> {
            String s = Args.text("code", a.get(0));
            if (s == null) {
                return null;
            }
            if (s.isEmpty()) {
                throw Args.error("code", "the text is empty");
            }
            return (long) s.codePointAt(0);
        });
        t.fn("find", 2, 3, (a, c) -> position("find", a, false));
        t.fn("search", 2, 3, (a, c) -> position("search", a, true));
        t.fn("text_before", 2, 3, (a, c) -> around("text_before", a, true));
        t.fn("text_after", 2, 3, (a, c) -> around("text_after", a, false));
        t.fn("regex_replace", 3, 3, (a, c) -> {
            String s = Args.text("regex_replace", a.get(0));
            String p = Args.text("regex_replace", a.get(1));
            String r = Args.text("regex_replace", a.get(2));
            return s == null || p == null || r == null ? null : regex(p).replaceAll(s, r);
        });
        t.fn("reverse", 1, 1, (a, c) -> {
            String s = Args.text("reverse", a.get(0));
            return s == null ? null : new StringBuilder(s).reverse().toString();
        });
        t.fn("clean", 1, 1, (a, c) -> {
            String s = Args.text("clean", a.get(0));
            return s == null ? null : s.codePoints().filter(ch -> !Character.isISOControl(ch))
                    .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        });
        t.fn("value", 1, 3, (a, c) -> value(a));
        t.fn("text", 2, 2, (a, c) -> text(a));
    }

    private static final ConcurrentHashMap<String, SafeRegex> PATTERNS = new ConcurrentHashMap<>();

    private static SafeRegex regex(String pattern) {
        if (PATTERNS.size() > 512) {
            PATTERNS.clear();
        }
        return PATTERNS.computeIfAbsent(pattern, k -> SafeRegex.compile(pattern, false));
    }

    /** {@code proper(s)}: each word's first letter upper case, the rest lower (a letter after any non-letter starts a word: Excel's). */
    private static Object proper(String s) {
        if (s == null) {
            return null;
        }
        StringBuilder b = new StringBuilder(s.length());
        boolean start = true;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            b.append(start ? Character.toUpperCase(ch) : Character.toLowerCase(ch));
            start = !Character.isLetter(ch);
        }
        return b.toString();
    }

    /** {@code find(part, s, [start])}, {@code search(...)}: the 1-based position of part in s from start, or 0 when not there. */
    private static Object position(String fn, List<Object> a, boolean ignoreCase) {
        String part = Args.text(fn, a.get(0));
        String s = Args.text(fn, a.get(1));
        Integer start = a.size() > 2 ? Args.whole(fn, a.get(2)) : Integer.valueOf(1);
        if (part == null || s == null || start == null) {
            return null;
        }
        if (start < 1 || start > s.length() + 1) {
            throw Args.error(fn, "the start " + start + " is not from 1 to the text's length + 1");
        }
        String h = ignoreCase ? s.toLowerCase(Locale.ROOT) : s;
        String p = ignoreCase ? part.toLowerCase(Locale.ROOT) : part;
        return (long) (h.indexOf(p, start - 1) + 1);
    }

    /**
     * {@code text_before(s, delimiter, [instance])}, {@code text_after(...)}: the text before or after the n-th delimiter (1 the first;
     * a negative n counts from the end), as Excel's TEXTBEFORE and TEXTAFTER; empty when the delimiter is not there that often.
     */
    private static Object around(String fn, List<Object> a, boolean before) {
        String s = Args.text(fn, a.get(0));
        String d = Args.text(fn, a.get(1));
        Integer n = a.size() > 2 ? Args.whole(fn, a.get(2)) : Integer.valueOf(1);
        if (s == null || d == null || n == null) {
            return null;
        }
        if (d.isEmpty() || n == 0) {
            throw Args.error(fn, d.isEmpty() ? "the delimiter is empty" : "the instance is 0");
        }
        int at = -1;
        if (n > 0) {
            int from = 0;
            for (int i = 0; i < n; i++) {
                at = s.indexOf(d, from);
                if (at < 0) {
                    return null;
                }
                from = at + d.length();
            }
        } else {
            int from = s.length();
            for (int i = 0; i < -n; i++) {
                at = s.lastIndexOf(d, from);
                if (at < 0) {
                    return null;
                }
                from = at - 1;
            }
        }
        return before ? s.substring(0, at) : s.substring(at + d.length());
    }

    /**
     * {@code value(text, [decimal_separator], [group_separator])}: text read as a number, as Excel's VALUE and NUMBERVALUE: spaces around,
     * the group separator (',' by default) anywhere, one leading currency sign ($ € £ ¥), a trailing % (divides by 100) and accounting
     * brackets for a negative ({@code (1,250.50)}) are allowed; a number is itself. Anything else (a date among them) is {@code DRS-2102}.
     */
    private static Object value(List<Object> a) {
        Object v = Values.simplify(a.get(0));
        String dec = a.size() > 1 ? Args.text("value", a.get(1)) : ".";
        String grp = a.size() > 2 ? Args.text("value", a.get(2)) : ",";
        if (v == null || dec == null || grp == null) {
            return null;
        }
        if (v instanceof Number) {
            return Args.out(Args.decimal("value", v));
        }
        if (dec.length() != 1 || grp.length() > 1 || dec.equals(grp)) {
            throw Args.error("value", "the separators are one character each and differ");
        }
        String s = Values.text(v).strip();
        boolean negative = false;
        if (s.length() > 2 && s.startsWith("(") && s.endsWith(")")) {
            negative = true;
            s = s.substring(1, s.length() - 1).strip();
        }
        boolean percent = s.endsWith("%");
        if (percent) {
            s = s.substring(0, s.length() - 1).strip();
        }
        if (s.startsWith("-") || s.startsWith("+")) {
            negative ^= s.startsWith("-");
            s = s.substring(1).strip();
        }
        if (!s.isEmpty() && "$€£¥".indexOf(s.charAt(0)) >= 0) {
            s = s.substring(1).strip();
        }
        if (!grp.isEmpty()) {
            s = s.replace(grp, "");
        }
        s = s.replace(dec, ".");
        if (s.isEmpty() || !s.matches("[0-9]*\\.?[0-9]*([eE][+-]?[0-9]+)?") || s.equals(".")) {
            throw Args.typeError("value", v, "a number");
        }
        BigDecimal d = new BigDecimal(s);
        if (percent) {
            d = d.movePointLeft(2);
        }
        return Args.out(negative ? d.negate() : d);
    }

    /**
     * {@code text(x, format)}: x written with an Excel format code. Numbers: {@code 0} and {@code #} digits, {@code ,} groups of
     * thousands, {@code .} decimals, {@code %} (times 100), {@code 0.00E+00}, quoted literals, and up to three sections
     * {@code positive;negative;zero} (a negative's section writes its size: {@code #,##0;(#,##0)}); rounding half away from zero, in
     * decimal. Dates (ISO text): {@code yyyy}, {@code yy}, {@code mmmm}, {@code mmm}, {@code mm}, {@code m}, {@code dddd}, {@code ddd},
     * {@code dd}, {@code d} (English names), {@code hh}, {@code mm} after an hour (minutes), {@code ss}. {@code @} is the text itself.
     */
    private static Object text(List<Object> a) {
        Object v = Values.simplify(a.get(0));
        String f = Args.text("text", a.get(1));
        if (v == null || f == null) {
            return null;
        }
        if ("@".equals(f)) {
            return Values.text(v);
        }
        if (v instanceof String s && !Values.couldBeNumber(s) || looksLikeDateFormat(f)) {
            String s = Values.text(v);
            LocalDate d = Args.parseDate(s);
            if (d == null) {
                throw Args.typeError("text", v, "a number or a date for the format '" + f + "'");
            }
            return dateText(d, s, f);
        }
        return numberText("text", Args.decimal("text", v), f);
    }

    private static boolean looksLikeDateFormat(String f) {
        String plain = f.replaceAll("\"[^\"]*\"", "").toLowerCase(Locale.ROOT);
        return plain.contains("yy") || plain.contains("dd") || plain.contains("mmm") || plain.matches(".*\\bd\\b.*") || plain.contains("hh");
    }

    /** A date (and a timestamp's time, when the text has one) written with an Excel date code. */
    private static String dateText(LocalDate d, String raw, String f) {
        int hour = 0;
        int minute = 0;
        int second = 0;
        if (raw.length() >= 19 && (raw.charAt(10) == 'T' || raw.charAt(10) == ' ')) {
            hour = Integer.parseInt(raw.substring(11, 13));
            minute = Integer.parseInt(raw.substring(14, 16));
            second = Integer.parseInt(raw.substring(17, 19));
        }
        StringBuilder b = new StringBuilder();
        boolean afterHour = false;
        int i = 0;
        while (i < f.length()) {
            char ch = f.charAt(i);
            if (ch == '"') {
                int end = f.indexOf('"', i + 1);
                b.append(f, i + 1, end < 0 ? f.length() : end);
                i = end < 0 ? f.length() : end + 1;
                continue;
            }
            char lc = Character.toLowerCase(ch);
            int run = 1;
            while (i + run < f.length() && Character.toLowerCase(f.charAt(i + run)) == lc) {
                run++;
            }
            switch (lc) {
                case 'y' -> b.append(run <= 2 ? String.format("%02d", d.getYear() % 100) : String.format("%04d", d.getYear()));
                case 'm' -> {
                    if (afterHour && run <= 2) {
                        b.append(run == 1 ? String.valueOf(minute) : String.format("%02d", minute));
                        afterHour = false;
                    } else if (run >= 4) {
                        b.append(d.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH));
                    } else if (run == 3) {
                        b.append(d.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH));
                    } else {
                        b.append(run == 1 ? String.valueOf(d.getMonthValue()) : String.format("%02d", d.getMonthValue()));
                    }
                }
                case 'd' -> {
                    if (run >= 4) {
                        b.append(d.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH));
                    } else if (run == 3) {
                        b.append(d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH));
                    } else {
                        b.append(run == 1 ? String.valueOf(d.getDayOfMonth()) : String.format("%02d", d.getDayOfMonth()));
                    }
                }
                case 'h' -> {
                    b.append(run == 1 ? String.valueOf(hour) : String.format("%02d", hour));
                    afterHour = true;
                }
                case 's' -> b.append(run == 1 ? String.valueOf(second) : String.format("%02d", second));
                default -> b.append(f, i, i + run);
            }
            i += run;
        }
        return b.toString();
    }

    /** A number written with an Excel number code (sections, quoted literals, %, scientific), in decimal, half away from zero. */
    static String numberText(String fn, BigDecimal x, String format) {
        String[] sections = splitSections(format);
        String f;
        BigDecimal v = x;
        if (sections.length >= 3 && x.signum() == 0) {
            f = sections[2];
        } else if (sections.length >= 2 && x.signum() < 0) {
            f = sections[1];
            v = x.negate();
        } else {
            f = sections[0];
        }
        if (!f.replaceAll("\"[^\"]*\"", "").matches(".*[0#].*")) {
            return f.replace("\"", "");                                     // a section of literal text only ("-" for zero)
        }
        StringBuilder pattern = new StringBuilder();
        boolean scientific = false;
        for (int i = 0; i < f.length(); i++) {
            char ch = f.charAt(i);
            if (ch == '"') {
                int end = f.indexOf('"', i + 1);
                String lit = f.substring(i + 1, end < 0 ? f.length() : end);
                pattern.append('\'').append(lit.replace("'", "''")).append('\'');
                i = end < 0 ? f.length() : end;
            } else if ((ch == 'E' || ch == 'e') && i + 1 < f.length() && (f.charAt(i + 1) == '+' || f.charAt(i + 1) == '-')) {
                pattern.append('E');
                scientific = true;
                i++;
            } else if (ch == '\'') {
                pattern.append("''");
            } else if ("0#.,%".indexOf(ch) >= 0) {
                pattern.append(ch);
            } else {
                pattern.append('\'').append(ch).append('\'');
            }
        }
        try {
            DecimalFormat df = new DecimalFormat(pattern.toString(), DecimalFormatSymbols.getInstance(Locale.ROOT));
            df.setRoundingMode(RoundingMode.HALF_UP);
            String s = df.format(v);
            if (scientific) {
                s = s.replaceAll("E(\\d)", "E+$1");
            }
            return s;
        } catch (IllegalArgumentException e) {
            throw Args.error(fn, "the format '" + format + "' is not a number format this function reads");
        }
    }

    private static String[] splitSections(String f) {
        List<String> out = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (char ch : f.toCharArray()) {
            if (ch == '"') {
                quoted = !quoted;
            }
            if (ch == ';' && !quoted) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }
}
