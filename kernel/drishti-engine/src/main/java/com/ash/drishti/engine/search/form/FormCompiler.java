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
package com.ash.drishti.engine.search.form;

import com.ash.drishti.common.BusinessCalendar;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.common.SafeRegex;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns what was typed into a form into a command of the existing search grammar
 * ({@code TRD where mtm between ... order by mtm desc limit 50}); the command is what the server runs, so masks, kinds per role and the
 * packs a person may open apply exactly as for a typed search. Text comparisons ignore case unless asked; regular expressions are
 * checked by {@link SafeRegex} here, before anything runs, and a pattern it refuses is a {@code DRS-4010}.
 */
public final class FormCompiler {

    private static final Pattern AMOUNT = Pattern.compile("(?i)-?\\d[\\d_]*(\\.\\d+)?(k|m|mm|mn|b|bn|t|tn)?");
    private static final Pattern WORDS = Pattern.compile("[\\s,;]+");

    private final int maxValues;

    /** @param maxValues at most this many values for an {@code in} (a pasted list) */
    public FormCompiler(int maxValues) {
        this.maxValues = Math.max(1, maxValues);
    }

    public FormCompiler() {
        this(500);
    }

    /**
     * The command for these criteria.
     *
     * @param mnemonic the kind's mnemonic (or the kind)
     * @param spec the form the criteria were entered in
     * @param criteria one per field the person filled in (empty ones are skipped by the caller)
     * @param orderBy a field to sort by, or null
     * @param descending sort direction
     * @param limit result size, or 0 for the search's default
     * @param today the business date relative ranges are measured from
     * @param serverCalendar the calendar used when the form names none
     */
    public String compile(String mnemonic, FormSpec spec, List<Criterion> criteria, String orderBy, boolean descending, int limit,
            LocalDate today, BusinessCalendar serverCalendar) {
        BusinessCalendar cal = spec.calendar().isEmpty() ? serverCalendar : BusinessCalendar.of(spec.calendar());
        List<String> clauses = new ArrayList<>();
        for (FormField f : spec.fields()) {
            if (f.required() && criteria.stream().noneMatch(c -> sameField(c, f))) {
                throw bad(f.label() + " is required");
            }
        }
        for (Criterion c : criteria) {
            FormField f = spec.field(c.path()).orElseThrow(() -> bad("'" + c.path() + "' is not a field of this form"));
            String clause = clause(f, c, today, cal);
            if (clause != null) {
                clauses.add(clause);
            }
        }
        StringBuilder out = new StringBuilder(mnemonic);
        if (!clauses.isEmpty()) {
            out.append(" where ").append(String.join(" and ", clauses));
        }
        if (orderBy != null && !orderBy.isBlank()) {
            String path = orderBy.startsWith("$.") ? orderBy.substring(2) : orderBy;
            if (!path.matches("[A-Za-z_][A-Za-z0-9_.\\[\\]]*")) {
                throw bad("'" + orderBy + "' cannot be sorted by");
            }
            out.append(" order by ").append(path).append(descending ? " desc" : "");
        }
        if (limit > 0) {
            out.append(" limit ").append(Math.min(limit, 1000));
        }
        return out.toString();
    }

    private static boolean sameField(Criterion c, FormField f) {
        return c.path().equals(f.path()) || c.path().equals("$." + f.path());
    }

    private String clause(FormField f, Criterion c, LocalDate today, BusinessCalendar cal) {
        List<String> v = c.values().stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
        String op = c.op().isBlank() ? f.operators().get(0) : c.op();
        if (v.isEmpty() && f.type() != FieldType.BOOL) {
            return null;                                                   // nothing typed: no constraint
        }
        if (!f.type().operators().contains(op)) {
            throw bad(f.label() + ": '" + op + "' is not an operator of a " + f.type().id() + " field");
        }
        if (!f.operators().contains(op)) {
            throw bad(f.label() + ": the form does not offer '" + op + "' (offers " + String.join(", ", f.operators()) + ")");
        }
        String p = f.path();
        return switch (f.type()) {
            case TEXT -> text(f, p, op, one(f, v), c.caseSensitive());
            case NUMBER, AMOUNT -> number(f, p, op, v);
            case DATE -> date(f, p, op, v, today, cal);
            case ENUM, ID -> any(f, p, v);
            case BOOL -> bool(f, p, v);
        };
    }

    private String text(FormField f, String p, String op, String value, boolean exact) {
        switch (op) {
            case "regex" -> {
                SafeRegex.compile(value, !exact);                          // refused here (DRS-4010) rather than while scanning
                return (exact ? "matchesCase(" : "matches(") + p + ", " + quote(value) + ")";
            }
            case "contains" -> {
                return exact ? "matchesCase(" + p + ", " + quote(escape(value)) + ")" : p + " contains " + quote(value);
            }
            case "starts" -> {
                return exact ? "matchesCase(" + p + ", " + quote("^" + escape(value)) + ")" : p + " startswith " + quote(value);
            }
            default -> {
                return exact ? "matchesCase(" + p + ", " + quote("^" + escape(value) + "$") + ")" : p + " = " + quote(value);
            }
        }
    }

    private String number(FormField f, String p, String op, List<String> v) {
        if (op.equals("between")) {
            if (v.size() != 2) {
                throw bad(f.label() + ": between needs two values");
            }
            return "(" + p + " >= " + amount(f, v.get(0)) + " and " + p + " <= " + amount(f, v.get(1)) + ")";
        }
        return p + " " + (op.equals("=") ? "=" : op) + " " + amount(f, v.get(0));
    }

    private static String amount(FormField f, String text) {
        String t = text.replace(",", "");
        if (!AMOUNT.matcher(t).matches()) {
            throw bad(f.label() + ": '" + text + "' is not a number (try 250000, 250k, 1.5m, 2bn)");
        }
        return t;
    }

    private String date(FormField f, String p, String op, List<String> v, LocalDate today, BusinessCalendar cal) {
        switch (op) {
            case "relative" -> {
                var r = RelativeDates.resolve(v.get(0), today, cal).orElseThrow(() -> bad(f.label() + ": '" + v.get(0)
                        + "' is not a range I know (try 'last 5 business days', 'next 10 days', 'month to date', 'this week')"));
                return range(p, r.from(), r.to());
            }
            case "between" -> {
                if (v.size() != 2) {
                    throw bad(f.label() + ": between needs two dates");
                }
                LocalDate a = day(f, v.get(0));
                LocalDate b = day(f, v.get(1));
                return a.isAfter(b) ? range(p, b, a) : range(p, a, b);
            }
            case "before" -> {
                return p + " < " + quote(day(f, v.get(0)).toString());
            }
            case "after" -> {
                return p + " >= " + quote(day(f, v.get(0)).plusDays(1).toString());
            }
            default -> {
                LocalDate d = day(f, v.get(0));
                return range(p, d, d);
            }
        }
    }

    /** Inclusive days, written so a date and a date-time value of the last day both match. */
    private static String range(String p, LocalDate from, LocalDate to) {
        return "(" + p + " >= " + quote(from.toString()) + " and " + p + " < " + quote(to.plusDays(1).toString()) + ")";
    }

    private static LocalDate day(FormField f, String text) {
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw bad(f.label() + ": '" + text + "' is not a date (write 2026-09-30)");
        }
    }

    private String any(FormField f, String p, List<String> pasted) {
        List<String> values = new ArrayList<>();
        for (String chunk : pasted) {
            for (String w : f.type() == FieldType.ID ? WORDS.split(chunk) : new String[] {chunk}) {
                if (!w.isBlank() && !values.contains(w.strip())) {
                    values.add(w.strip());
                }
            }
        }
        if (values.size() > maxValues) {
            throw bad(f.label() + ": " + values.size() + " values; at most " + maxValues + " at once");
        }
        List<String> parts = new ArrayList<>();
        for (String w : values) {
            if (f.type() == FieldType.ID && w.contains("*")) {             // MX-2* : any id starting so
                parts.add("matches(" + p + ", " + quote("^" + escape(w).replace("\\*", ".*") + "$") + ")");
            } else {
                parts.add(p + " = " + quote(w));
            }
        }
        return parts.isEmpty() ? null : parts.size() == 1 ? parts.get(0) : "(" + String.join(" or ", parts) + ")";
    }

    private String bool(FormField f, String p, List<String> v) {
        if (v.isEmpty() || v.get(0).equalsIgnoreCase("any")) {
            return null;
        }
        String t = v.get(0).toLowerCase(Locale.ROOT);
        if (!t.equals("true") && !t.equals("false")) {
            throw bad(f.label() + ": choose yes or no (true or false)");
        }
        return p + " = " + t;
    }

    private static String one(FormField f, List<String> v) {
        if (v.size() > 1) {
            throw bad(f.label() + ": one value expected");
        }
        return v.get(0);
    }

    /** A string literal of the search grammar: single-quoted, or double-quoted when it holds an apostrophe. */
    static String quote(String s) {
        if (s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            throw bad("a value cannot span lines");
        }
        if (s.indexOf('\'') >= 0 && s.indexOf('"') >= 0) {
            throw bad("a value cannot hold both kinds of quote: " + s);
        }
        return s.indexOf('\'') >= 0 ? "\"" + s + "\"" : "'" + s + "'";
    }

    /** The text as a regular expression that matches it literally. */
    static String escape(String s) {
        StringBuilder out = new StringBuilder();
        for (char c : s.toCharArray()) {
            if ("\\.^$|?*+()[]{}".indexOf(c) >= 0) {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

    private static DrishtiException bad(String message) {
        return new DrishtiException(ErrorCode.BAD_SEARCH, message);
    }
}
