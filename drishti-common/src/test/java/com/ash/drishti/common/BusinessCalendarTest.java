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
}
