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
package com.ash.drishti.plugin.delta;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.testkit.DatedSourceContract;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** UX-12: after start the newest dates of every table are warmed in the background (config: {@code warm-dates}). */
class DeltaWarmUpTest {

    private static DeltaSourcePlugin start(Map<String, String> extra) throws Exception {
        Map<String, String> settings = new HashMap<>(Map.of("root", Path.of("src/test/resources/lake").toAbsolutePath().toString(),
                "domain", "desk", "mode.counterparty", "effective", "source-name", "lake"));
        settings.putAll(extra);
        DeltaSourcePlugin p = new DeltaSourcePlugin();
        p.start(DatedSourceContract.context(settings));
        return p;
    }

    private static long idMaps(DeltaSourcePlugin p) {
        return ((Number) p.cacheStats().get("idMaps")).longValue();
    }

    private static void waitFor(DeltaSourcePlugin p, long n) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (idMaps(p) < n && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        Thread.sleep(300);                                                // and nothing more than that
    }

    @Test
    void recentPastDatesAreWarmedAfterStartByDefault() throws Exception {
        DeltaSourcePlugin p = start(Map.of());
        waitFor(p, 5);                                                    // trade 3 dates + counterparty 2
        assertThat(idMaps(p)).isEqualTo(5);
    }

    @Test
    void warmDatesZeroLeavesOnlyTheNewestDateWarm() throws Exception {
        DeltaSourcePlugin p = start(Map.of("warm-dates", "0"));
        waitFor(p, 5);
        assertThat(idMaps(p)).isEqualTo(2);                               // the reindex's: each table's newest date
    }

    @Test
    void warmDatesBoundsHowFarBackItGoes() throws Exception {
        DeltaSourcePlugin p = start(Map.of("warm-dates", "2"));
        waitFor(p, 4);                                                    // trade 2 + counterparty 2
        assertThat(idMaps(p)).isEqualTo(4);
    }
}
