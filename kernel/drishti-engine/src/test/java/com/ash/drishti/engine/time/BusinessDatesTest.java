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
package com.ash.drishti.engine.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.error.DrishtiException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** The business date's "today": the clock's, or a fixed one (drishti.business-date.today) for data that ends on a known date. */
class BusinessDatesTest {

    private static final Clock TUESDAY = Clock.fixed(Instant.parse("2026-11-17T15:00:00Z"), ZoneOffset.UTC);

    @Test
    void liveIsTheClockSTodayRolledBackToABusinessDay() {
        BusinessDates d = new BusinessDates(new BusinessDateProperties("USNY", "America/New_York", null, null), TUESDAY);
        assertThat(d.current()).isEqualTo(LocalDate.of(2026, 11, 17));
        assertThat(d.parse(null, null).businessDate()).isEqualTo(LocalDate.of(2026, 11, 17));
    }

    @Test
    void aFixedTodayIsLiveAndALaterDateIsInTheFuture() {
        BusinessDates d = new BusinessDates(new BusinessDateProperties("USNY", "America/New_York", null, "2026-11-06"), TUESDAY);
        assertThat(d.current()).isEqualTo(LocalDate.of(2026, 11, 6));
        assertThat(d.parse("live", null).businessDate()).isEqualTo(LocalDate.of(2026, 11, 6));
        assertThat(d.parse("2026-11-05", null).businessDate()).isEqualTo(LocalDate.of(2026, 11, 5));
        assertThatThrownBy(() -> d.parse("2026-11-09", null)).isInstanceOf(DrishtiException.class).hasMessageContaining("in the future");
        // a fixed Saturday rolls back to the Friday, as the clock's would
        assertThat(new BusinessDates(new BusinessDateProperties("USNY", "UTC", null, "2026-11-07"), TUESDAY).current()).isEqualTo(LocalDate.of(2026, 11, 6));
    }

    @Test
    void aFixedTodayThatIsNoDateStopsTheStart() {
        assertThatThrownBy(() -> new BusinessDateProperties("USNY", "UTC", null, "6 Nov")).hasMessageContaining("use yyyy-MM-dd");
        assertThat(new BusinessDateProperties("USNY", "UTC", null, " ").today()).isNull();
    }
}
