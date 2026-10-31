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
package com.ash.drishti.server.var;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ash.drishti.compute.var.VarBook;
import com.ash.drishti.compute.vector.OrdinalSet;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The viewers' views are bounded and released with their date (2.9.7, RESOURCES_AND_SCALE.md section 5.3, soak audit H10): before, one
 * view per date, version, currency and role set stayed for ever.
 */
class VarAccessViewsTest {

    static VarProperties props(Integer viewCacheMb) {
        return new VarProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, viewCacheMb, null, null, null, null, null);
    }

    private static VarDate date(LocalDate d, String version, int trades) {
        VarBook book = mock(VarBook.class);
        when(book.all()).thenReturn(OrdinalSet.range(0, trades));
        return new VarDate(d, book, Map.of(), version, Instant.now(), 0, 0, null, null, null, null, null);
    }

    @Test
    void sixtyDatesWithHourlyVersionsKeepOnlyWhatIsOpen() {
        VarAccess access = new VarAccess(props(null), null);
        LocalDate first = LocalDate.of(2026, 1, 1);
        for (int day = 0; day < 60; day++) {
            LocalDate d = first.plusDays(day);
            for (int v = 0; v < 8; v++) {
                access.view(null, date(d, "v" + v, 1_000));
            }
            if (day >= 2) {
                access.forget(d.minusDays(2));                          // the service retires dates beyond hot-dates (2)
            }
        }
        access.cache().cleanUp();
        assertThat(access.cache().estimatedSize()).isEqualTo(2 * 8);
        access.forget(null);
        assertThat(access.cache().asMap()).isEmpty();
    }

    @Test
    void boundedByBytes() {
        VarAccess access = new VarAccess(props(1), null);                    // 1 MB of bitsets
        LocalDate d = LocalDate.of(2026, 9, 30);
        for (int v = 0; v < 100; v++) {
            access.view(null, date(d, "v" + v, 1_000_000));                  // about 122 KB a view
        }
        access.cache().cleanUp();
        long bytes = access.cache().asMap().values().stream().mapToLong(x -> x.visible().words().length * 8L).sum();
        assertThat(bytes).isLessThanOrEqualTo(1L << 20);
        assertThat(access.cache().estimatedSize()).isLessThan(10);
    }

    @Test
    void theDefaultIs64Mb() {
        assertThat(props(null).viewCacheMb()).isEqualTo(64);
        assertThat(props(null).versionCheck()).hasSeconds(30);
        assertThat(props(null).loadThreads()).isEqualTo(2);
    }
}
