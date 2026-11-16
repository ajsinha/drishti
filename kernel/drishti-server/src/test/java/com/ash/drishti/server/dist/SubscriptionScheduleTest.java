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
package com.ash.drishti.server.dist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.BusinessCalendar;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class SubscriptionScheduleTest {

    static final ZoneId NY = ZoneId.of("America/New_York");
    static final BusinessCalendar USNY = BusinessCalendar.of("USNY");

    private static ZonedDateTime at(String local) {
        return LocalDateTime.parse(local).atZone(NY);
    }

    private static ZonedDateTime next(String schedule, String after) {
        return SubscriptionSchedule.parse(schedule).next(at(after), NY, USNY);
    }

    @Test
    void everythingAScheduledReportAcceptsStillWorks() {
        // Wednesday 25 Nov 2026: Thursday is Thanksgiving
        assertThat(next("daily 07:00", "2026-11-25T19:00")).isEqualTo(at("2026-11-26T07:00"));
        assertThat(next("business-days 18:30", "2026-11-25T19:00")).isEqualTo(at("2026-11-27T18:30"));
        assertThat(next("hourly", "2026-11-25T19:00")).isEqualTo(at("2026-11-25T20:00"));
    }

    @Test
    void weeklyMovesPastAHolidayOnlyWhenAskedToBeBusinessDayAware() {
        // Monday 23 Nov; Thursday the 26th is Thanksgiving
        assertThat(next("weekly thu 07:30", "2026-11-23T09:00")).isEqualTo(at("2026-11-26T07:30"));
        assertThat(next("weekly thu 07:30 business", "2026-11-23T09:00")).isEqualTo(at("2026-11-27T07:30"));
        assertThat(next("weekly mon 07:30", "2026-11-23T09:00")).isEqualTo(at("2026-11-30T07:30"));
        assertThat(next("Weekly  MON 7:05", "2026-11-23T07:00")).isEqualTo(at("2026-11-23T07:05"));
    }

    @Test
    void monthlyUsesTheDayTheLastDayAndTheNextBusinessDay() {
        // 1 Nov 2026 is a Sunday, 1 Jan 2027 a Friday holiday
        assertThat(next("monthly 1 07:00", "2026-10-31T12:00")).isEqualTo(at("2026-11-01T07:00"));
        assertThat(next("monthly 1 07:00 business", "2026-10-31T12:00")).isEqualTo(at("2026-11-02T07:00"));
        assertThat(next("monthly 1 07:00 business", "2026-12-20T12:00")).isEqualTo(at("2027-01-04T07:00"));
        // the last day, and the last business day (30 Oct 2026 is a Friday, the 31st a Saturday)
        assertThat(next("monthly last 18:00", "2026-10-15T12:00")).isEqualTo(at("2026-10-31T18:00"));
        assertThat(next("monthly last 18:00 business", "2026-10-15T12:00")).isEqualTo(at("2026-10-30T18:00"));
        // a short month uses its last day
        assertThat(next("monthly 31 09:00", "2026-11-01T00:00")).isEqualTo(at("2026-11-30T09:00"));
        // once this month's time has passed it is next month's
        assertThat(next("monthly 15 09:00", "2026-11-15T09:00")).isEqualTo(at("2026-12-15T09:00"));
    }

    @Test
    void afterLoadWaitsForAnEventAndMayNameAKind() {
        SubscriptionSchedule any = SubscriptionSchedule.parse("after-load");
        SubscriptionSchedule trd = SubscriptionSchedule.parse("After-Load TRD");
        assertThat(any.isAfterLoad()).isTrue();
        assertThat(any.loadKind()).isNull();
        assertThat(trd.loadKind()).isEqualTo("TRD");
        assertThat(trd.next(at("2026-11-25T19:00"), NY, USNY)).isNull();
    }

    @Test
    void aMistakeSaysWhatIsAccepted() {
        assertThatThrownBy(() -> SubscriptionSchedule.parse("every tuesday")).hasMessageContaining("weekly mon HH:MM").hasMessageContaining("after-load");
        assertThatThrownBy(() -> SubscriptionSchedule.parse("monthly 32 07:00")).hasMessageContaining("not a schedule");
        assertThatThrownBy(() -> SubscriptionSchedule.parse("weekly funday 07:00")).hasMessageContaining("not a schedule");
    }
}
