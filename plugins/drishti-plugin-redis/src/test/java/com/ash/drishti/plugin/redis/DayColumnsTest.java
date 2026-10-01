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
package com.ash.drishti.plugin.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** A day's columns are cut into chunks with the last one partial: no row may be left out of the column hash. */
class DayColumnsTest {

    private static DayColumns day(int rows) {
        DayColumns d = new DayColumns();
        for (int i = 0; i < rows; i++) {
            d.put(String.format("MX-%08d", i), Map.of("mtm", (double) i, "book", "BOOK-" + (i % 3)));
        }
        return d;
    }

    @Test
    void aDayThatIsNotAMultipleOfTheChunkSizeKeepsItsLastPartialChunk() {
        DayColumns.Chunks c = day(25).chunks(10, 1L);       // the scale benchmark's 25,000 trades in chunks of 10,000, in small
        assertThat(c.meta().rows()).isEqualTo(25);
        assertThat(c.meta().chunks()).isEqualTo(3);
        assertThat(c.fields()).containsKeys(RedisLayout.field(RedisLayout.IDS, 0), RedisLayout.field(RedisLayout.IDS, 2),
                RedisLayout.field("mtm", 2), RedisLayout.field("book", 2));
        assertThat(ColumnCodec.Meta.decode(c.meta().encode()).chunks()).isEqualTo(3);
    }

    @Test
    void exactMultiplesAndSmallDaysAreUnchanged() {
        assertThat(day(20).chunks(10, 1L).meta().chunks()).isEqualTo(2);
        assertThat(day(7).chunks(10, 1L).meta().chunks()).isEqualTo(1);
        assertThat(day(0).chunks(10, 1L).meta().chunks()).isEqualTo(1);
    }
}
