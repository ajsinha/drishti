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
package com.ash.drishti.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RequestActivityTest {

    @Test
    void requestsAreCountedInTheirMinuteAndAnHourOldSlotStartsAgain() {
        RequestActivity a = new RequestActivity();
        long now = System.currentTimeMillis() / 60_000;
        a.record(now, 10_000, false);                             // 10 ms
        a.record(now, 30_000, true);                              // 30 ms, an error
        a.record(now - 2, 5_000, false);
        a.record(now - RequestActivity.MINUTES, 99_000_000, false);   // an hour ago, the same slot as now: replaced by now's
        List<Map<String, Object>> last = a.lastMinutes(30);
        assertThat(last).hasSize(30);
        Map<String, Object> current = last.get(29);
        assertThat(current.get("requests")).isEqualTo(2L);
        assertThat(current.get("avgMs")).isEqualTo(20.0);
        assertThat(current.get("maxMs")).isEqualTo(30.0);
        assertThat(current.get("errors")).isEqualTo(1L);
        assertThat(last.get(27).get("requests")).isEqualTo(1L);
        assertThat(last.get(28).get("requests")).isEqualTo(0L);   // a quiet minute is zero
    }
}
