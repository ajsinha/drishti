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
package com.ash.drishti.lakeload;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The timestamp and order rules of the duplicate-record rule, as {@code tools/lake/test_build.py} checks {@code stage.instant_micros}. */
class LatestTest {

    @Test
    void timestampsAreIsoTextToMicrosecondsUtc() {
        assertThat(Latest.micros("1970-01-01")).isZero();
        assertThat(Latest.micros("1970-01-01T00:00:01.5Z")).isEqualTo(1_500_000L);
        assertThat(Latest.micros("2026-10-01T12:00:00+02:00")).isEqualTo(Latest.micros("2026-10-01T10:00:00Z"));
        assertThat(Latest.micros("2026-10-01 10:00")).isEqualTo(Latest.micros("2026-10-01T10:00:00"));
        assertThat(Latest.micros("2026-10-01T10:00:00.1234567Z") - Latest.micros("2026-10-01T10:00:00Z")).isEqualTo(123456L);
        for (String bad : new String[] {"2026-13-01", "soon", "20261001", null, "2026-10-01T25:00:00"}) {
            assertThat(Latest.micros(bad)).as(bad).isEqualTo(Latest.NONE);
        }
    }

    @Test
    void theLatestWinsThenTheLastRead() {
        long early = Latest.order(0, 0, 5);
        long late = Latest.order(1, 0, 1);
        long huge = Latest.order(900_000, 3, 7);                      // a split index past 2^19: compared unsigned
        assertThat(Latest.beats(Latest.NONE, late, Latest.NONE, early)).isTrue();
        assertThat(Latest.beats(Latest.NONE, huge, Latest.NONE, late)).isTrue();
        assertThat(Latest.beats(10, early, Latest.NONE, late)).isTrue();          // a timestamp beats none
        assertThat(Latest.beats(10, early, 20, late)).isFalse();
        assertThat(Latest.beats(20, early, 20, late)).isFalse();                  // a tie: the last read
        assertThat(Latest.split(huge)).isEqualTo(900_000);
        assertThat(Latest.seq(huge)).isEqualTo(3);
        assertThat(Latest.line(huge)).isEqualTo(7);
    }
}
