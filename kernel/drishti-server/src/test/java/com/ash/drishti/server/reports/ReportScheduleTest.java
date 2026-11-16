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
package com.ash.drishti.server.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.BusinessCalendar;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class ReportScheduleTest {

    static final ZoneId NY = ZoneId.of("America/New_York");
    static final BusinessCalendar USNY = BusinessCalendar.of("USNY");

    private static ZonedDateTime at(String local) {
        return java.time.LocalDateTime.parse(local).atZone(NY);
    }

    @Test
    void readsTheWaysPeopleSayIt() {
        // Wednesday 25 Nov 2026 19:00: the next weekday 18:30 is Thursday, which is Thanksgiving
        ZonedDateTime wed = at("2026-11-25T19:00");
        assertThat(ReportSchedule.parse("daily 07:00").next(wed, NY, USNY)).isEqualTo(at("2026-11-26T07:00"));
        assertThat(ReportSchedule.parse("weekdays 18:30").next(wed, NY, USNY)).isEqualTo(at("2026-11-26T18:30"));
        assertThat(ReportSchedule.parse("business-days 18:30").next(wed, NY, USNY)).isEqualTo(at("2026-11-27T18:30"));
        assertThat(ReportSchedule.parse("Weekdays  8:05").next(at("2026-11-27T09:00"), NY, USNY)).isEqualTo(at("2026-11-30T08:05"));
        assertThat(ReportSchedule.parse("hourly").next(wed, NY, USNY)).isEqualTo(at("2026-11-25T20:00"));
        assertThat(ReportSchedule.parse("cron 0 0 8,12,16 * * MON-FRI").next(wed, NY, USNY)).isEqualTo(at("2026-11-26T08:00"));
    }

    @Test
    void aMistakeSaysWhatIsAccepted() {
        assertThatThrownBy(() -> ReportSchedule.parse("every tuesday")).hasMessageContaining("daily HH:MM");
        assertThatThrownBy(() -> ReportSchedule.parse("daily 25:00")).hasMessageContaining("not a schedule");
        assertThatThrownBy(() -> ReportSchedule.parse("cron 0 0")).hasMessageContaining("six fields");
    }
}
