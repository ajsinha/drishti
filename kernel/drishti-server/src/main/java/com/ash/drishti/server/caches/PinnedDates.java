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
package com.ash.drishti.server.caches;

import com.ash.drishti.common.BusinessCalendar;
import java.time.LocalDate;
import java.time.Month;
import java.time.MonthDay;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Pinned dates (VAR_GUIDE.md section 26.8; the VaR and exposure engines share it): the dates a bank comes back to, found from the
 * business-date calendars and kept ready. Each rule says how far back it reaches and how the date is kept: {@code disk} (its hot file
 * built in the background after the official record, never trimmed) or {@code memory} (open, counted within {@code hot-dates}).
 *
 * <p>A date is the <b>last business day</b> of its period in <b>every</b> calendar named ({@code drishti.business-date.calendar}, for
 * example {@code USNY+CATO}): a weekend or a holiday in New York or Toronto moves it back. A date pinned by two rules is kept once, with
 * both reasons, in memory when either rule says so.
 */
public final class PinnedDates {

    private PinnedDates() {}

    /** How a pinned date is kept. */
    public enum Keep {
        DISK, MEMORY;

        static Keep of(String s) {
            return s != null && s.trim().equalsIgnoreCase("memory") ? MEMORY : DISK;
        }

        public String word() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * One rule.
     *
     * @param back how many of its dates, newest first, on or before the newest business date (0: the rule is off)
     * @param keep {@code disk} (the default) or {@code memory}
     */
    public record Rule(Integer back, String keep) {
        public Rule {
            back = back == null || back < 0 ? 0 : back;
            keep = Keep.of(keep).word();
        }

        static Rule off() {
            return new Rule(0, null);
        }
    }

    /**
     * The settings of an engine's pinned dates ({@code drishti.var.pinned}, {@code drishti.ccr.exposure.pinned}).
     *
     * @param monthEnd the last business day of each month
     * @param quarterEnd the last business day of March, June, September and December
     * @param yearEnd the last business day of December
     * @param fiscalYearEnd the last business day of the fiscal year (the engine's {@code fiscal-year-end}, {@code MM-DD})
     * @param stressWindow the first and last dates of the stress window in use ({@code back} is ignored: on when above 0)
     * @param dates any dates the bank names (a crisis day, an audit date), kept as {@code datesKeep} says
     * @param datesKeep how the named dates are kept
     */
    public record Settings(Rule monthEnd, Rule quarterEnd, Rule yearEnd, Rule fiscalYearEnd, Rule stressWindow, List<String> dates,
            String datesKeep) {
        public Settings {
            monthEnd = monthEnd == null ? Rule.off() : monthEnd;
            quarterEnd = quarterEnd == null ? Rule.off() : quarterEnd;
            yearEnd = yearEnd == null ? Rule.off() : yearEnd;
            fiscalYearEnd = fiscalYearEnd == null ? Rule.off() : fiscalYearEnd;
            stressWindow = stressWindow == null ? Rule.off() : stressWindow;
            dates = dates == null ? List.of() : dates.stream().filter(s -> s != null && !s.isBlank()).map(String::trim).toList();
            datesKeep = Keep.of(datesKeep).word();
            for (String d : dates) {
                try {
                    LocalDate.parse(d);
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("a pinned date is yyyy-MM-dd, not '" + d + "'");
                }
            }
        }

        public static Settings none() {
            return new Settings(null, null, null, null, null, null, null);
        }

        /** True when no rule is on and no date is named. */
        public boolean off() {
            return monthEnd.back() == 0 && quarterEnd.back() == 0 && yearEnd.back() == 0 && fiscalYearEnd.back() == 0
                    && stressWindow.back() == 0 && dates.isEmpty();
        }
    }

    /** A pinned date: why (every rule that pins it) and how it is kept. */
    public record Pin(LocalDate date, List<String> reasons, Keep keep) {
        public boolean memory() {
            return keep == Keep.MEMORY;
        }
    }

    /** Reads a fiscal year-end ({@code MM-DD}, for example {@code 10-31}); null when blank. */
    public static MonthDay fiscalYearEnd(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return MonthDay.parse("--" + s.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("the fiscal year-end is MM-DD (for example 10-31), not '" + s + "'");
        }
    }

    /** The last business day on or before the last day of {@code ym}. */
    public static LocalDate monthEnd(BusinessCalendar cal, YearMonth ym) {
        return cal.onOrBefore(ym.atEndOfMonth());
    }

    /** The last business day of the fiscal year ending in {@code year} (on or before its year-end day). */
    public static LocalDate fiscalYearEnd(BusinessCalendar cal, MonthDay end, int year) {
        return cal.onOrBefore(end.atYear(year));
    }

    /**
     * Every pinned date on or before {@code newest} (the newest business date), with its reasons.
     *
     * @param stressWindow the first and last dates of the stress window in use (empty when not known yet)
     */
    public static List<Pin> of(Settings s, BusinessCalendar cal, LocalDate newest, MonthDay fiscalEnd, Collection<LocalDate> stressWindow) {
        Map<LocalDate, Pin> out = new TreeMap<>(java.util.Comparator.reverseOrder());
        if (newest == null) {
            return List.of();
        }
        periods(s.monthEnd(), "month-end", cal, newest, m -> true, out);
        periods(s.quarterEnd(), "quarter-end", cal, newest, m -> m.getValue() % 3 == 0, out);
        periods(s.yearEnd(), "year-end", cal, newest, m -> m == Month.DECEMBER, out);
        if (s.fiscalYearEnd().back() > 0 && fiscalEnd != null) {
            int got = 0;
            for (int y = newest.getYear() + 1; got < s.fiscalYearEnd().back() && y > newest.getYear() - 200; y--) {
                LocalDate d = fiscalYearEnd(cal, fiscalEnd, y);
                if (!d.isAfter(newest)) {
                    add(out, d, "fiscal-year-end", Keep.of(s.fiscalYearEnd().keep()));
                    got++;
                }
            }
        }
        if (s.stressWindow().back() > 0 && stressWindow != null && !stressWindow.isEmpty()) {
            LocalDate first = stressWindow.stream().min(LocalDate::compareTo).orElseThrow();
            LocalDate last = stressWindow.stream().max(LocalDate::compareTo).orElseThrow();
            add(out, first, "stress-window (first)", Keep.of(s.stressWindow().keep()));
            add(out, last, "stress-window (last)", Keep.of(s.stressWindow().keep()));
        }
        for (String d : s.dates()) {
            LocalDate x = LocalDate.parse(d);
            if (!x.isAfter(newest)) {
                add(out, x, "named", Keep.of(s.datesKeep()));
            }
        }
        return List.copyOf(out.values());
    }

    private static void periods(Rule r, String why, BusinessCalendar cal, LocalDate newest, java.util.function.Predicate<Month> month,
            Map<LocalDate, Pin> out) {
        int got = 0;
        YearMonth ym = YearMonth.from(newest);
        for (int i = 0; got < r.back() && i < 12 * 200; i++, ym = ym.minusMonths(1)) {
            if (!month.test(ym.getMonth())) {
                continue;
            }
            LocalDate d = monthEnd(cal, ym);
            if (!d.isAfter(newest)) {
                add(out, d, why, Keep.of(r.keep()));
                got++;
            }
        }
    }

    private static void add(Map<LocalDate, Pin> out, LocalDate d, String why, Keep keep) {
        Pin p = out.get(d);
        if (p == null) {
            out.put(d, new Pin(d, List.of(why), keep));
            return;
        }
        Set<String> rs = new LinkedHashSet<>(p.reasons());
        rs.add(why);
        out.put(d, new Pin(d, List.copyOf(rs), p.memory() || keep == Keep.MEMORY ? Keep.MEMORY : Keep.DISK));
    }
}
