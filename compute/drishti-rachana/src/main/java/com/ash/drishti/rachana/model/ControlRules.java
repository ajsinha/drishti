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
package com.ash.drishti.rachana.model;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** What a control's id, default and references may be: one place for the parser, the server and the designer's checks. */
public final class ControlRules {

    /** A control id: a letter, then letters, digits and underscores (it is written {@code ctl.<id>} in expressions). */
    public static final String ID_PATTERN = "[A-Za-z][A-Za-z0-9_]{0,31}";
    /** The most controls one Sutra declares. */
    public static final int MAX_CONTROLS = 12;
    /** The relative dates a date or date-range default may use, besides {@code next-90d} style counts. */
    public static final List<String> RELATIVE = List.of("today", "yesterday", "previous-business-day", "this-week", "this-month", "last-month",
            "next-month", "this-quarter", "this-year", "ytd", "mtd");

    private static final Pattern COUNT = Pattern.compile("(next|last)-(\\d{1,4})(d|bd)");
    private static final Pattern REF = Pattern.compile("(?<![A-Za-z0-9_.$@])ctl\\.([A-Za-z][A-Za-z0-9_]*)");
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");

    private ControlRules() {}

    /** The control ids an expression or template reads ({@code ctl.leg}), in order of appearance. */
    public static Set<String> references(String source) {
        Set<String> out = new LinkedHashSet<>();
        if (source != null && source.contains("ctl.")) {
            Matcher m = REF.matcher(source);
            while (m.find()) {
                out.add(m.group(1));
            }
        }
        return out;
    }

    /** Whether {@code token} is a relative date or range ({@code next-90d}, {@code this-month}, {@code ytd}). */
    public static boolean isRelative(String token) {
        return RELATIVE.contains(token) || COUNT.matcher(token).matches();
    }

    /** Why {@code def} cannot be the default of a control of {@code kind}; null when it can. {@code options} are the fixed ones, if any. */
    public static String defaultProblem(ControlKind kind, String def, List<String> options) {
        String d = def.trim();
        switch (kind) {
            case DROPDOWN, CASCADING, TOGGLE, SEGMENTED -> {
                if (!d.equals("first") && !options.isEmpty() && !options.contains(d)) {
                    return "'" + d + "' is not one of its options (" + String.join(", ", options) + ") or first";
                }
            }
            case MULTI_SELECT -> {
                if (!d.equals("all") && !d.equals("first")) {
                    for (String one : d.split(",")) {
                        if (!options.isEmpty() && !options.contains(one.trim())) {
                            return "'" + one.trim() + "' is not one of its options (" + String.join(", ", options) + "), first or all";
                        }
                    }
                }
            }
            case TOP_N -> {
                if (!NUMBER.matcher(d).matches() || d.contains(".") || d.startsWith("-") || "0".equals(d)) {
                    return "'" + d + "' is not a whole number of rows";
                }
                if (!options.isEmpty() && !options.contains(d)) {
                    return "'" + d + "' is not one of its options (" + String.join(", ", options) + ")";
                }
            }
            case DATE -> {
                if (!isRelative(d) && !isDate(d)) {
                    return "'" + d + "' is not a date (2026-10-01) or a relative one (today, yesterday, previous-business-day, next-5bd)";
                }
            }
            case DATE_RANGE -> {
                if (!isRelative(d) && !isDateRange(d)) {
                    return "'" + d + "' is not a range (2026-10-01..2026-12-31) or a relative one (next-90d, last-30d, this-month, ytd)";
                }
            }
            case SLIDER -> {
                if (!isNumber(d) && !isNumberRange(d)) {
                    return "'" + d + "' is not a number (5000000) or a range of numbers (1000000..5000000)";
                }
            }
            case NUMBER_RANGE -> {
                if (!isNumberRange(d)) {
                    return "'" + d + "' is not a range of numbers (1000000..5000000, either end may be empty)";
                }
            }
            default -> { }
        }
        return null;
    }

    /** A plain number (an optional sign, digits, an optional fraction). */
    public static boolean isNumber(String s) {
        return NUMBER.matcher(s.trim()).matches();
    }

    /** An ISO date. */
    public static boolean isDate(String s) {
        try {
            LocalDate.parse(s);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /** {@code from..to} of ISO dates, either end may be empty. */
    public static boolean isDateRange(String s) {
        int cut = s.indexOf("..");
        if (cut < 0) {
            return false;
        }
        String a = s.substring(0, cut).trim();
        String b = s.substring(cut + 2).trim();
        return (a.isEmpty() || isDate(a)) && (b.isEmpty() || isDate(b));
    }

    /** {@code min..max} of numbers, either end may be empty. */
    public static boolean isNumberRange(String s) {
        int cut = s.indexOf("..");
        if (cut < 0) {
            return false;
        }
        String a = s.substring(0, cut).trim();
        String b = s.substring(cut + 2).trim();
        return (a.isEmpty() || NUMBER.matcher(a).matches()) && (b.isEmpty() || NUMBER.matcher(b).matches());
    }
}
