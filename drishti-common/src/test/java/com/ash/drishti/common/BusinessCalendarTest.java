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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class BusinessCalendarTest {

    @Test
    void newYorkHolidaysAndObservance() {
        BusinessCalendar ny = BusinessCalendar.of("USNY");
        assertThat(ny.holidays(2026)).contains(LocalDate.of(2026, 11, 26), LocalDate.of(2026, 7, 3),   // July 4th is a Saturday
                LocalDate.of(2026, 6, 19), LocalDate.of(2026, 10, 12), LocalDate.of(2026, 12, 25));
        assertThat(ny.onOrBefore(LocalDate.of(2026, 9, 27))).isEqualTo(LocalDate.of(2026, 9, 25));      // Sunday → Friday
        assertThat(ny.onOrBefore(LocalDate.of(2026, 11, 26))).isEqualTo(LocalDate.of(2026, 11, 25));    // Thanksgiving
        assertThat(ny.next(LocalDate.of(2026, 12, 24))).isEqualTo(LocalDate.of(2026, 12, 28));
    }

    @Test
    void jointCalendarsAndEaster() {
        assertThat(BusinessCalendar.easter(2026)).isEqualTo(LocalDate.of(2026, 4, 5));
        BusinessCalendar ldnNy = BusinessCalendar.of("GBLO+USNY");
        assertThat(ldnNy.isBusinessDay(LocalDate.of(2026, 4, 3))).isFalse();                            // Good Friday (London)
        assertThat(ldnNy.isBusinessDay(LocalDate.of(2026, 11, 26))).isFalse();                          // Thanksgiving (New York)
        assertThat(ldnNy.name()).isEqualTo("GBLO+USNY");
        assertThatThrownBy(() -> BusinessCalendar.of("MARS")).hasMessageContaining("unknown calendar");
    }

    /**
     * The same dates, in the same order, as the Python generator (tools/samplegen/dates.py): a trailing * marks an
     * observed substitute. tools/samplegen/test_samplegen.py asserts this very table, so the two cannot drift.
     */
    private static final String[][] GOLDEN = {
        {"USNY", "2026", "01-01,01-19,02-16,05-25,06-19,07-03*,09-07,10-12,11-11,11-26,12-25"},
        {"USNY", "2027", "01-01,01-18,02-15,05-31,06-18*,07-05*,09-06,10-11,11-11,11-25,12-24*"},
        {"GBLO", "2026", "01-01,04-03,04-06,05-04,05-25,08-31,12-25,12-28*"},
        {"GBLO", "2027", "01-01,03-26,03-29,05-03,05-31,08-30,12-24*,12-28*"},
        {"EUTA", "2026", "01-01,04-03,04-06,05-01,12-25"},
        {"EUTA", "2027", "01-01,03-26,03-29"},
        {"JPTO", "2026", "01-01,01-02,02-11,04-29,05-04,05-05,11-03,11-23,12-31"},
        {"JPTO", "2027", "01-01,02-11,04-29,05-03,05-04,05-05,11-03,11-23,12-31"},
        {"CATO", "2026", "01-01,02-16,04-03,05-18,07-01,08-03,09-07,09-30,10-12,11-11,12-25,12-28*"},
        {"CATO", "2027", "01-01,02-15,03-26,05-24,07-01,08-02,09-06,09-30,10-11,11-11,12-27*,12-28*"},
    };

    @Test
    void holidayDatesAndObservanceMatchThePythonGenerator() {
        for (String[] g : GOLDEN) {
            int y = Integer.parseInt(g[1]);
            String got = BusinessCalendar.of(g[0]).holidayNames(y).entrySet().stream()
                    .map(e -> e.getKey().toString().substring(5) + (e.getValue().endsWith("(observed)") ? "*" : ""))
                    .collect(java.util.stream.Collectors.joining(","));
            assertThat(got).as(g[0] + " " + y).isEqualTo(g[2]);
        }
    }

    @Test
    void everyHolidayHasAName() {
        BusinessCalendar ny = BusinessCalendar.of("USNY");
        assertThat(ny.holidayNames(2026)).containsEntry(LocalDate.of(2026, 7, 3), "Independence Day (observed)")
                .containsEntry(LocalDate.of(2026, 11, 26), "Thanksgiving Day").containsEntry(LocalDate.of(2026, 10, 12), "Columbus Day");
        assertThat(BusinessCalendar.of("GBLO").holidayNames(2026)).containsEntry(LocalDate.of(2026, 12, 28), "Boxing Day (observed)")
                .containsEntry(LocalDate.of(2026, 4, 3), "Good Friday");
        for (String c : new String[] {"USNY", "GBLO", "EUTA", "JPTO", "CATO"}) {
            assertThat(BusinessCalendar.of(c).holidayNames(2026).values()).doesNotContain("Public holiday").allMatch(n -> !n.isBlank());
        }
    }
}
