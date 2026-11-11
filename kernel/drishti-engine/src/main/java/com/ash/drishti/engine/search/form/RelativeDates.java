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
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Relative date ranges on a business-day calendar: {@code today}, {@code yesterday}, {@code last 5 business days},
 * {@code next 3 business days}, {@code last 30 days}, {@code next 7 days}, {@code this week}, {@code this month},
 * {@code last month}, {@code month to date}, {@code year to date}. "Today" is the date the search is run on.
 * Business-day ranges end on the last business day on or before today (or start on the next one after it).
 */
public final class RelativeDates {

    /** An inclusive range of days. */
    public record Range(LocalDate from, LocalDate to) {}

    private static final Pattern COUNT = Pattern.compile("(last|next|past|coming)\\s+(\\d{1,4})\\s+(business\\s+days?|bd|days?)");

    private RelativeDates() {}

    /** The range a phrase means, or empty when it is not one of the above. */
    public static Optional<Range> resolve(String phrase, LocalDate today, BusinessCalendar cal) {
        String p = phrase == null ? "" : phrase.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        switch (p) {
            case "today" -> {
                return Optional.of(new Range(today, today));
            }
            case "yesterday" -> {
                return Optional.of(new Range(today.minusDays(1), today.minusDays(1)));
            }
            case "previous business day", "last business day" -> {
                LocalDate d = cal.previous(today);
                return Optional.of(new Range(d, d));
            }
            case "this week" -> {
                LocalDate mon = today.with(DayOfWeek.MONDAY);
                return Optional.of(new Range(mon, mon.plusDays(6)));
            }
            case "this month", "month to date" -> {
                return Optional.of(new Range(today.withDayOfMonth(1), p.equals("this month") ? today.withDayOfMonth(today.lengthOfMonth()) : today));
            }
            case "last month" -> {
                LocalDate first = today.withDayOfMonth(1).minusMonths(1);
                return Optional.of(new Range(first, first.withDayOfMonth(first.lengthOfMonth())));
            }
            case "year to date" -> {
                return Optional.of(new Range(today.withDayOfYear(1), today));
            }
            default -> { }
        }
        Matcher m = COUNT.matcher(p);
        if (!m.matches()) {
            return Optional.empty();
        }
        int n = Integer.parseInt(m.group(2));
        if (n < 1) {
            return Optional.empty();
        }
        boolean back = m.group(1).equals("last") || m.group(1).equals("past");
        boolean business = !m.group(3).startsWith("day");
        if (!business) {
            return Optional.of(back ? new Range(today.minusDays(n - 1L), today) : new Range(today, today.plusDays(n - 1L)));
        }
        if (back) {
            LocalDate end = cal.onOrBefore(today);
            LocalDate start = end;
            for (int i = 1; i < n; i++) {
                start = cal.previous(start);
            }
            return Optional.of(new Range(start, end));
        }
        LocalDate start = cal.isBusinessDay(today) ? today : cal.next(today);
        LocalDate end = start;
        for (int i = 1; i < n; i++) {
            end = cal.next(end);
        }
        return Optional.of(new Range(start, end));
    }
}
