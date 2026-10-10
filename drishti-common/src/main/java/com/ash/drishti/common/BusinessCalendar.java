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
package com.ash.drishti.common;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Business-day calendars for financial centres (USNY, GBLO, EUTA, JPTO) and joint calendars such as
 * {@code USNY+GBLO}: weekends plus each centre's public holidays, with weekend observance where the centre
 * observes it. Holiday sets are computed per year and cached; instances are immutable and thread-safe.
 */
public final class BusinessCalendar {

    private static final Set<String> KNOWN = Set.of("USNY", "GBLO", "EUTA", "JPTO", "WEEKENDS");
    private final List<String> centers;
    private final Map<Integer, java.util.SortedMap<LocalDate, String>> cache = new ConcurrentHashMap<>();

    private BusinessCalendar(List<String> centers) {
        this.centers = centers;
    }

    /** {@code "USNY"} or a joint calendar {@code "USNY+GBLO"}. */
    public static BusinessCalendar of(String spec) {
        List<String> cs = java.util.Arrays.stream(spec.toUpperCase(Locale.ROOT).split("\\+")).map(String::trim).toList();
        for (String c : cs) {
            if (!KNOWN.contains(c)) {
                throw new IllegalArgumentException("unknown calendar " + c + " (known: " + new TreeSet<>(KNOWN) + ")");
            }
        }
        return new BusinessCalendar(cs);
    }

    public String name() {
        return String.join("+", centers);
    }

    public boolean isBusinessDay(LocalDate d) {
        DayOfWeek w = d.getDayOfWeek();
        return w != DayOfWeek.SATURDAY && w != DayOfWeek.SUNDAY && !holidays(d.getYear()).contains(d);
    }

    /** {@code d} itself if it is a business day, otherwise the business day before it. */
    public LocalDate onOrBefore(LocalDate d) {
        LocalDate out = d;
        while (!isBusinessDay(out)) {
            out = out.minusDays(1);
        }
        return out;
    }

    public LocalDate previous(LocalDate d) {
        return onOrBefore(d.minusDays(1));
    }

    public LocalDate next(LocalDate d) {
        LocalDate out = d.plusDays(1);
        while (!isBusinessDay(out)) {
            out = out.plusDays(1);
        }
        return out;
    }

    /** The holidays of {@code year} (weekdays only), sorted. */
    public Set<LocalDate> holidays(int year) {
        return holidayNames(year).keySet();
    }

    /**
     * The holidays of {@code year} (weekdays only), sorted, each with its name; a substitute day carries
     * "(observed)". Where joint centres share a day the names are joined with " / ".
     */
    public java.util.SortedMap<LocalDate, String> holidayNames(int year) {
        return cache.computeIfAbsent(year, y -> {
            java.util.TreeMap<LocalDate, String> out = new java.util.TreeMap<>();
            centers.forEach(c -> centre(c, y).forEach(h -> {
                if (h.day().getDayOfWeek().getValue() <= 5) {
                    out.merge(h.day(), h.label(), (x, z) -> x.equals(z) ? x : x + " / " + z);
                }
            }));
            return java.util.Collections.unmodifiableSortedMap(out);
        });
    }

    /** A holiday kept on {@code day}; {@code nominal} is the date it falls on before any weekend substitution. */
    private record Holiday(LocalDate day, String name, LocalDate nominal) {
        String label() {
            return day.equals(nominal) ? name : name + " (observed)";
        }
    }

    private static Holiday fixed(int y, int m, int d, String name) {
        LocalDate n = LocalDate.of(y, m, d);
        return new Holiday(n, name, n);
    }

    private static Holiday obs(int y, int m, int d, String name) {
        LocalDate n = LocalDate.of(y, m, d);
        return new Holiday(observed(n), name, n);
    }

    private static Holiday rule(LocalDate d, String name) {
        return new Holiday(d, name, d);
    }

    private static List<Holiday> centre(String c, int y) {
        LocalDate e = easter(y);
        return switch (c) {
            case "USNY" -> List.of(obs(y, 1, 1, "New Year's Day"), rule(nth(y, Month.JANUARY, DayOfWeek.MONDAY, 3), "Martin Luther King Jr. Day"),
                    rule(nth(y, Month.FEBRUARY, DayOfWeek.MONDAY, 3), "Presidents' Day"), rule(last(y, Month.MAY, DayOfWeek.MONDAY), "Memorial Day"),
                    obs(y, 6, 19, "Juneteenth"), obs(y, 7, 4, "Independence Day"), rule(nth(y, Month.SEPTEMBER, DayOfWeek.MONDAY, 1), "Labor Day"),
                    rule(nth(y, Month.OCTOBER, DayOfWeek.MONDAY, 2), "Columbus Day"), obs(y, 11, 11, "Veterans Day"),
                    rule(nth(y, Month.NOVEMBER, DayOfWeek.THURSDAY, 4), "Thanksgiving Day"), obs(y, 12, 25, "Christmas Day"));
            case "GBLO" -> List.of(obs(y, 1, 1, "New Year's Day"), rule(e.minusDays(2), "Good Friday"), rule(e.plusDays(1), "Easter Monday"),
                    rule(nth(y, Month.MAY, DayOfWeek.MONDAY, 1), "Early May Bank Holiday"),
                    rule(last(y, Month.MAY, DayOfWeek.MONDAY), "Spring Bank Holiday"),
                    rule(last(y, Month.AUGUST, DayOfWeek.MONDAY), "Summer Bank Holiday"), obs(y, 12, 25, "Christmas Day"),
                    new Holiday(boxingDay(y), "Boxing Day", LocalDate.of(y, 12, 26)));
            case "EUTA" -> List.of(fixed(y, 1, 1, "New Year's Day"), rule(e.minusDays(2), "Good Friday"), rule(e.plusDays(1), "Easter Monday"),
                    fixed(y, 5, 1, "Labour Day"), fixed(y, 12, 25, "Christmas Day"), fixed(y, 12, 26, "Boxing Day (St Stephen's Day)"));
            case "JPTO" -> List.of(fixed(y, 1, 1, "New Year's Day"), fixed(y, 1, 2, "Bank Holiday (New Year)"),
                    fixed(y, 1, 3, "Bank Holiday (New Year)"), fixed(y, 2, 11, "National Foundation Day"), fixed(y, 4, 29, "Showa Day"),
                    fixed(y, 5, 3, "Constitution Memorial Day"), fixed(y, 5, 4, "Greenery Day"), fixed(y, 5, 5, "Children's Day"),
                    fixed(y, 11, 3, "Culture Day"), fixed(y, 11, 23, "Labour Thanksgiving Day"), fixed(y, 12, 31, "Year-End Bank Holiday"));
            default -> List.of();
        };
    }

    private static LocalDate boxingDay(int y) {
        LocalDate d = LocalDate.of(y, 12, 26);
        // Boxing Day moves to the next weekday not already taken by Christmas's substitute.
        return switch (d.getDayOfWeek()) {
            case SATURDAY, SUNDAY -> LocalDate.of(y, 12, 28);
            case MONDAY -> LocalDate.of(y, 12, 25).getDayOfWeek() == DayOfWeek.SUNDAY ? LocalDate.of(y, 12, 27) : d;
            default -> d;
        };
    }

    private static LocalDate observed(LocalDate d) {
        return switch (d.getDayOfWeek()) {
            case SATURDAY -> d.minusDays(1);
            case SUNDAY -> d.plusDays(1);
            default -> d;
        };
    }

    private static LocalDate nth(int y, Month m, DayOfWeek w, int n) {
        return LocalDate.of(y, m, 1).with(TemporalAdjusters.dayOfWeekInMonth(n, w));
    }

    private static LocalDate last(int y, Month m, DayOfWeek w) {
        return LocalDate.of(y, m, 1).with(TemporalAdjusters.lastInMonth(w));
    }

    /** Western Easter Sunday (anonymous Gregorian algorithm). */
    static LocalDate easter(int y) {
        int a = y % 19, b = y / 100, c = y % 100, d = b / 4, e = b % 4, f = (b + 8) / 25, g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30, i = c / 4, k = c % 4, l = (32 + 2 * e + 2 * i - h - k) % 7, m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31, day = (h + l - 7 * m + 114) % 31 + 1;
        return LocalDate.of(y, month, day);
    }
}
