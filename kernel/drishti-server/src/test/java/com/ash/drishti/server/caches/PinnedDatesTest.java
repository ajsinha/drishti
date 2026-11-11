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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.BusinessCalendar;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pinned dates from the business-date calendars (VAR_GUIDE.md section 26.8): the last business day in <b>every</b> calendar named, so a
 * weekend, a New York holiday or a Toronto holiday moves a month-, quarter-, year- or fiscal-year-end back.
 */
class PinnedDatesTest {

    static final BusinessCalendar NY_TO = BusinessCalendar.of("USNY+CATO");

    @Test
    void aWeekendMovesAMonthEndBack() {
        assertThat(PinnedDates.monthEnd(NY_TO, YearMonth.of(2026, 5))).isEqualTo(LocalDate.of(2026, 5, 29));   // the 31st is a Sunday
        // 2026-09-30 is Toronto's National Day for Truth and Reconciliation: the quarter ends on the 29th in New York and Toronto
        assertThat(PinnedDates.monthEnd(BusinessCalendar.of("USNY"), YearMonth.of(2026, 9))).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(PinnedDates.monthEnd(NY_TO, YearMonth.of(2026, 9))).isEqualTo(LocalDate.of(2026, 9, 29));
        assertThat(PinnedDates.monthEnd(NY_TO, YearMonth.of(2026, 12))).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    @Test
    void aNewYorkHolidayMovesItBackInTheJointCalendar() {
        // 2027-05-31 is Memorial Day (the last Monday of May): a business day in Toronto, not in New York
        assertThat(BusinessCalendar.of("CATO").isBusinessDay(LocalDate.of(2027, 5, 31))).isTrue();
        assertThat(PinnedDates.monthEnd(BusinessCalendar.of("CATO"), YearMonth.of(2027, 5))).isEqualTo(LocalDate.of(2027, 5, 31));
        assertThat(PinnedDates.monthEnd(NY_TO, YearMonth.of(2027, 5))).isEqualTo(LocalDate.of(2027, 5, 28));
    }

    @Test
    void aTorontoHolidayMovesItBackInTheJointCalendar() {
        // 2024-03-29 is Good Friday: a Toronto holiday, a New York business day; the quarter ends on the 28th in the joint calendar
        assertThat(BusinessCalendar.of("USNY").isBusinessDay(LocalDate.of(2024, 3, 29))).isTrue();
        assertThat(PinnedDates.monthEnd(BusinessCalendar.of("USNY"), YearMonth.of(2024, 3))).isEqualTo(LocalDate.of(2024, 3, 29));
        assertThat(PinnedDates.monthEnd(NY_TO, YearMonth.of(2024, 3))).isEqualTo(LocalDate.of(2024, 3, 28));
    }

    @Test
    void theFiscalYearEnd() {
        MonthDay oct31 = PinnedDates.fiscalYearEnd("10-31");
        assertThat(PinnedDates.fiscalYearEnd(NY_TO, oct31, 2026)).isEqualTo(LocalDate.of(2026, 10, 30));     // the 31st is a Saturday
        assertThat(PinnedDates.fiscalYearEnd(NY_TO, oct31, 2025)).isEqualTo(LocalDate.of(2025, 10, 31));
        assertThat(PinnedDates.fiscalYearEnd("")).isNull();
        assertThatThrownBy(() -> PinnedDates.fiscalYearEnd("31-10")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theRulesReachBackAndADatePinnedTwiceIsKeptOnce() {
        PinnedDates.Settings s = new PinnedDates.Settings(new PinnedDates.Rule(3, "disk"), new PinnedDates.Rule(2, "memory"),
                new PinnedDates.Rule(1, null), new PinnedDates.Rule(2, "disk"), new PinnedDates.Rule(1, "disk"), List.of("2020-03-16", "2027-01-04"),
                "disk");
        List<PinnedDates.Pin> ps = PinnedDates.of(s, NY_TO, LocalDate.of(2026, 11, 1), PinnedDates.fiscalYearEnd("10-31"),
                List.of(LocalDate.of(2008, 9, 15), LocalDate.of(2009, 9, 14)));
        List<String> dates = ps.stream().map(p -> p.date().toString()).toList();
        // newest first; month-ends on or before 2026-11-01: October (the 30th, also the fiscal year-end), September (the 29th, also a
        // quarter-end: the 30th is a Toronto holiday), August
        assertThat(dates).containsExactly("2026-10-30", "2026-09-29", "2026-08-31", "2026-06-30", "2025-12-31", "2025-10-31", "2020-03-16",
                "2009-09-14", "2008-09-15");
        PinnedDates.Pin oct = ps.get(0);
        assertThat(oct.reasons()).containsExactly("month-end", "fiscal-year-end");
        assertThat(oct.memory()).isFalse();
        PinnedDates.Pin sep = ps.get(1);
        assertThat(sep.reasons()).containsExactly("month-end", "quarter-end");
        assertThat(sep.memory()).as("in memory when either rule says so").isTrue();
        assertThat(ps.get(4).reasons()).containsExactly("year-end");
        assertThat(ps.get(6).reasons()).containsExactly("named");
        assertThat(dates).as("a named date after the newest business date is not pinned yet").doesNotContain("2027-01-04");
    }

    @Test
    void offByDefault() {
        PinnedDates.Settings none = PinnedDates.Settings.none();
        assertThat(none.off()).isTrue();
        assertThat(PinnedDates.of(none, NY_TO, LocalDate.of(2026, 11, 1), null, List.of())).isEmpty();
        assertThatThrownBy(() -> new PinnedDates.Settings(null, null, null, null, null, List.of("30-09-2026"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
