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
package com.ash.drishti.engine.control;

import com.ash.drishti.common.BusinessCalendar;
import com.ash.drishti.engine.search.form.RelativeDates;
import com.ash.drishti.rachana.model.ControlRules;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a date or date-range control's value: an ISO date, {@code from..to} (either end may be empty), or a relative token
 * ({@code today}, {@code next-90d}, {@code last-5bd}, {@code this-month}, {@code ytd}) resolved against a business date on the
 * pack's calendar. Business-day counts ({@code bd}) skip the calendar's weekends and holidays.
 */
final class ControlDates {

    private static final Pattern COUNT = Pattern.compile("(next|last)-(\\d{1,4})(d|bd)");

    private ControlDates() {}

    /** A range as {@code from..to} text (an end may be empty). */
    record Span(LocalDate from, LocalDate to) {
        String text() {
            return (from == null ? "" : from.toString()) + ".." + (to == null ? "" : to.toString());
        }
    }

    /** The range a value means on {@code today}, or empty when it is not a range or a relative token. */
    static Optional<Span> range(String value, LocalDate today, BusinessCalendar cal) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) {
            return Optional.empty();
        }
        if (ControlRules.isDateRange(v)) {
            int cut = v.indexOf("..");
            String a = v.substring(0, cut).trim();
            String b = v.substring(cut + 2).trim();
            LocalDate from = a.isEmpty() ? null : LocalDate.parse(a);
            LocalDate to = b.isEmpty() ? null : LocalDate.parse(b);
            return from != null && to != null && to.isBefore(from) ? Optional.of(new Span(to, from)) : Optional.of(new Span(from, to));
        }
        return relative(v, today, cal);
    }

    /** One date: an ISO date, or the first day of a relative token (today, yesterday, previous-business-day, next-5bd is its last day). */
    static Optional<LocalDate> date(String value, LocalDate today, BusinessCalendar cal) {
        String v = value == null ? "" : value.trim();
        if (ControlRules.isDate(v)) {
            return Optional.of(LocalDate.parse(v));
        }
        Matcher m = COUNT.matcher(v);
        if (m.matches()) {
            int n = Integer.parseInt(m.group(2));
            boolean back = m.group(1).equals("last");
            LocalDate d = today;
            for (int i = 0; i < n; i++) {
                d = m.group(3).equals("bd") ? (back ? cal.previous(d) : cal.next(d)) : (back ? d.minusDays(1) : d.plusDays(1));
            }
            return Optional.of(d);
        }
        return switch (v) {
            case "today" -> Optional.of(today);
            case "yesterday" -> Optional.of(today.minusDays(1));
            case "previous-business-day" -> Optional.of(cal.previous(today));
            default -> Optional.empty();
        };
    }

    private static Optional<Span> relative(String token, LocalDate today, BusinessCalendar cal) {
        Matcher m = COUNT.matcher(token);
        if (m.matches()) {
            String phrase = m.group(1) + " " + m.group(2) + (m.group(3).equals("bd") ? " business days" : " days");
            return RelativeDates.resolve(phrase, today, cal).map(r -> new Span(r.from(), r.to()));
        }
        return switch (token) {
            case "today" -> Optional.of(new Span(today, today));
            case "yesterday" -> Optional.of(new Span(today.minusDays(1), today.minusDays(1)));
            case "previous-business-day" -> Optional.of(new Span(cal.previous(today), cal.previous(today)));
            case "this-week" -> one("this week", today, cal);
            case "this-month" -> one("this month", today, cal);
            case "last-month" -> one("last month", today, cal);
            case "next-month" -> {
                LocalDate first = today.withDayOfMonth(1).plusMonths(1);
                yield Optional.of(new Span(first, first.withDayOfMonth(first.lengthOfMonth())));
            }
            case "this-quarter" -> {
                LocalDate first = today.withMonth((today.getMonthValue() - 1) / 3 * 3 + 1).withDayOfMonth(1);
                LocalDate last = first.plusMonths(2);
                yield Optional.of(new Span(first, last.withDayOfMonth(last.lengthOfMonth())));
            }
            case "this-year" -> Optional.of(new Span(today.withDayOfYear(1), today.withDayOfYear(today.lengthOfYear())));
            case "ytd" -> one("year to date", today, cal);
            case "mtd" -> one("month to date", today, cal);
            default -> Optional.empty();
        };
    }

    private static Optional<Span> one(String phrase, LocalDate today, BusinessCalendar cal) {
        return RelativeDates.resolve(phrase, today, cal).map(r -> new Span(r.from(), r.to()));
    }

    /** The presets a date-range control offers, resolved on {@code today}: value is the {@code from..to} text. */
    static List<String[]> presets(LocalDate today, BusinessCalendar cal) {
        String[][] named = {{"today", "Today"}, {"next-7d", "Next 7 days"}, {"next-30d", "Next 30 days"}, {"next-90d", "Next 90 days"},
            {"last-30d", "Last 30 days"}, {"this-month", "This month"}, {"last-month", "Last month"}, {"this-quarter", "This quarter"},
            {"ytd", "Year to date"}};
        List<String[]> out = new ArrayList<>();
        for (String[] n : named) {
            relative(n[0], today, cal).ifPresent(s -> out.add(new String[] {s.text(), n[1]}));
        }
        return out;
    }
}
