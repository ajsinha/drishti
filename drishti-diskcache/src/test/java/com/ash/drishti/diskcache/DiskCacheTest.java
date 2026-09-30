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
package com.ash.drishti.diskcache;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiskCacheTest {

    static final ZoneId NY = ZoneId.of("America/New_York");

    @TempDir
    Path dir;

    @Test
    void storesReadsDeletesAndClears() throws Exception {
        ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
        try (DiskCache c = new DiskCache(dir, 64L * 1024 * 1024, null, NY, sched, Clock.systemUTC())) {
            c.put("trade/T-1", "{\"mtm\":1}".getBytes(StandardCharsets.UTF_8));
            c.put("trade/T-1", "{\"mtm\":2}".getBytes(StandardCharsets.UTF_8));
            assertThat(new String(c.get("trade/T-1"), StandardCharsets.UTF_8)).isEqualTo("{\"mtm\":2}");
            c.delete("trade/T-1");
            assertThat(c.get("trade/T-1")).isNull();
            c.put("trade/T-2", new byte[] {1});
            c.clear();
            assertThat(c.get("trade/T-2")).isNull();
            assertThat(c.resets()).isEqualTo(1);
            assertThat(c.hits()).isEqualTo(1);
        } finally {
            sched.shutdownNow();
        }
    }

    @Test
    void aRestartStartsEmpty() throws Exception {
        ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
        try {
            try (DiskCache c = new DiskCache(dir, 64L * 1024 * 1024, null, NY, sched, Clock.systemUTC())) {
                c.put("k", new byte[] {1});
            }
            try (DiskCache c = new DiskCache(dir, 64L * 1024 * 1024, null, NY, sched, Clock.systemUTC())) {
                assertThat(c.get("k")).isNull();
                try (Stream<Path> gens = Files.list(dir)) {
                    assertThat(gens.count()).isEqualTo(1);
                }
            }
        } finally {
            sched.shutdownNow();
        }
    }

    @Test
    void theNightlyClearingIsTheNextOccurrenceInTheZone() {
        ZonedDateTime evening = ZonedDateTime.of(2026, 9, 30, 21, 0, 0, 0, NY);
        assertThat(DiskCache.nextReset(evening, LocalTime.of(2, 0))).isEqualTo(ZonedDateTime.of(2026, 10, 1, 2, 0, 0, 0, NY));
        ZonedDateTime night = ZonedDateTime.of(2026, 10, 1, 1, 30, 0, 0, NY);
        assertThat(DiskCache.nextReset(night, LocalTime.of(2, 0))).isEqualTo(ZonedDateTime.of(2026, 10, 1, 2, 0, 0, 0, NY));
        // across the autumn clock change the clearing stays at 02:00 local time
        ZonedDateTime beforeDst = ZonedDateTime.of(2026, 10, 31, 22, 0, 0, 0, NY);
        assertThat(DiskCache.nextReset(beforeDst, LocalTime.of(2, 0)).toLocalTime()).isEqualTo(LocalTime.of(2, 0));
    }
}
