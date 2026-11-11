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
package com.ash.drishti.server.collab;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Durations in messages read as people say them, never as ISO text. */
class HumanDurationTest {

    @Test
    void readsLikeAPerson() {
        assertThat(HumanDuration.of(Duration.ofMinutes(15))).isEqualTo("15-minute");
        assertThat(HumanDuration.of(Duration.ofSeconds(5))).isEqualTo("5-second");
        assertThat(HumanDuration.of(Duration.ofHours(2))).isEqualTo("2-hour");
        assertThat(HumanDuration.of(Duration.ofSeconds(90))).isEqualTo("90-second");
        assertThat(HumanDuration.of(Duration.ofDays(1))).isEqualTo("1-day");
    }
}
