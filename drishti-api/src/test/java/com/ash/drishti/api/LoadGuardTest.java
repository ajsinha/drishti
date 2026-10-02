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
package com.ash.drishti.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LoadGuardTest {

    /** 2026-10-01 03:00 UTC: still 2026-09-30 in New York. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-01T03:00:00Z"), ZoneOffset.UTC);

    private static LoadGuard guard(String... args) {
        return LoadGuard.fromArgs(args, Map.of(), CLOCK);
    }

    @Test
    void todayIsInTheBusinessZoneAndTomorrowIsTheLatestDateByDefault() {
        LoadGuard g = guard();
        assertThat(g.today()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(g.latestAllowed()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(guard("--zone", "Asia/Tokyo").today()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(LoadGuard.fromArgs(new String[0], Map.of("DRISHTI_BUSINESS_ZONE", "Asia/Tokyo"), CLOCK).today()).isEqualTo(LocalDate.of(2026, 10, 1));
    }

    @Test
    void aFutureBusinessDateIsQuarantinedAndTheLoadEndsWithAnError() {
        LoadGuard g = guard();
        assertThat(g.accept(LocalDate.of(2026, 9, 30), "ok")).isTrue();
        assertThat(g.accept(LocalDate.of(2026, 10, 1), "tomorrow")).isTrue();
        assertThat(g.accept(LocalDate.of(2027, 3, 1), "MX-1")).isFalse();
        assertThat(g.quarantined()).isEqualTo(1);
        assertThatThrownBy(g::finish).isInstanceOf(IllegalStateException.class).hasMessageContaining("1 rows not loaded")
                .hasMessageContaining("after 2026-10-01");
        assertThat(guard("--future-days", "0").accept(LocalDate.of(2026, 10, 1), "tomorrow")).isFalse();
        guard().finish();                                             // nothing quarantined: no error
    }

    @Test
    void retentionCountsBackFromTodayOrTheAsOfDateNeverFromTheData() {
        LoadGuard g = guard();
        assertThat(g.keepFromDays(30)).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(g.keepFromMonths(2)).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(guard("--as-of", "2026-06-15").keepFromMonths(1)).isEqualTo(LocalDate.of(2026, 6, 1));
        List<LocalDate> days = List.of(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 9, 29), LocalDate.of(2026, 9, 30), LocalDate.of(2027, 3, 1));
        assertThat(g.keepFromNewest(days, 2)).contains(LocalDate.of(2026, 9, 29));   // the future day is neither counted nor dropped
        assertThat(g.keepFromNewest(days, 3)).isEmpty();
    }

    @Test
    void droppingMostOfATableNeedsForceDrop() {
        assertThatThrownBy(() -> guard().checkDrop("trading", 9, 10, "business days")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("would drop 9 of 10 business days").hasMessageContaining("--force-drop");
        guard().checkDrop("trading", 5, 10, "business days");
        guard("--force-drop").checkDrop("trading", 10, 10, "rows");
        guard("--max-drop-share", "1").checkDrop("trading", 10, 10, "rows");
        guard().checkDrop("empty", 0, 0, "rows");
        assertThatThrownBy(() -> guard("--max-drop-share", "2")).isInstanceOf(IllegalArgumentException.class);
    }
}
