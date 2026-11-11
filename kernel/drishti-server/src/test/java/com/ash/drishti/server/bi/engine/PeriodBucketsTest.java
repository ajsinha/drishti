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
package com.ash.drishti.server.bi.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.server.bi.dataset.Resolver;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** A time series asked by period (wave B): week_start, month_start, quarter_start and year_start are dates, the first day of the period. */
class PeriodBucketsTest {

    @Test
    void aPeriodIsItsFirstDayAndADate() {
        LocalDate d = LocalDate.of(2026, 8, 20);                       // a Thursday
        assertThat(RowEval.bucket("week_start", d)).isEqualTo(LocalDate.of(2026, 8, 17));
        assertThat(RowEval.bucket("month_start", d)).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(RowEval.bucket("quarter_start", d)).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(RowEval.bucket("year_start", d)).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(Resolver.partType("month_start")).isEqualTo("date");
        assertThat(Resolver.partType("month")).isEqualTo("integer");
    }

    @Test
    void bothDialectsTruncateToADate() {
        assertThat(Dialect.DUCKDB.part("month_start", "\"t\".\"d\"")).isEqualTo("CAST(date_trunc('month', \"t\".\"d\") AS DATE)");
        assertThat(Dialect.POSTGRESQL.part("week_start", "x")).isEqualTo("CAST(date_trunc('week', x) AS DATE)");
        assertThat(Dialect.DUCKDB.part("month", "x")).isEqualTo("month(x)");
    }
}
