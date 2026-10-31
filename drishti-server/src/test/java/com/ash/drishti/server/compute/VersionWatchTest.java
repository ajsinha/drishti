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
package com.ash.drishti.server.compute;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The version watch (2.9.7, O9, PO-9): a restated date with no signal is reloaded within the interval; a request during the reload gets
 * the old figures and the warning; no double reload under 32 concurrent requests; a failed reload keeps the old date with a warning; an
 * unreadable version gives no warning.
 */
class VersionWatchTest {

    static final LocalDate D = LocalDate.of(2026, 9, 30);

    @Test
    void aChangedDateIsReloadedOnceInTheBackgroundAndWarnedMeanwhile() throws Exception {
        AtomicLong now = new AtomicLong(1_000_000);
        AtomicReference<String> lake = new AtomicReference<>("v1");
        VersionWatch w = new VersionWatch("test", Duration.ofSeconds(30), d -> lake.get(), now::get);
        AtomicInteger reloads = new AtomicInteger();
        CountDownLatch hold = new CountDownLatch(1);
        Runnable reload = () -> {
            reloads.incrementAndGet();
            try {
                hold.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        assertThat(w.check(D, "v1", false, reload)).isNull();                  // unchanged
        lake.set("v2");                                                         // restated, no signal
        assertThat(w.check(D, "v1", false, reload)).as("within the interval: not asked again").isNull();
        now.addAndGet(30_000);
        Thread[] ts = new Thread[32];
        String[] said = new String[32];
        for (int i = 0; i < 32; i++) {
            int k = i;
            ts[i] = Thread.ofVirtual().start(() -> said[k] = w.check(D, "v1", false, reload));
        }
        for (Thread t : ts) {
            t.join();
        }
        assertThat(said).allMatch(s -> s != null && s.contains("changed") && s.contains("a new version is loading"));
        assertThat(w.refreshing(D)).isTrue();
        hold.countDown();
        for (int i = 0; i < 100 && w.refreshing(D); i++) {
            Thread.sleep(20);
        }
        assertThat(reloads.get()).as("one reload, however many asked").isEqualTo(1);
        assertThat(w.reloads()).isEqualTo(1);
        now.addAndGet(30_000);
        assertThat(w.check(D, "v2", false, reload)).as("the new version open: quiet").isNull();
    }

    @Test
    void aFailedReloadKeepsTheOldDateWithAWarningAndAnUnreadableVersionIsQuiet() throws Exception {
        AtomicLong now = new AtomicLong(0);
        VersionWatch w = new VersionWatch("test", Duration.ofSeconds(30), d -> "v2", now::get);
        w.check(D, "v1", false, () -> {
            throw new IllegalStateException("the lake is gone");
        });
        for (int i = 0; i < 100 && w.refreshing(D); i++) {
            Thread.sleep(20);
        }
        assertThat(w.check(D, "v1", false, () -> { })).contains("could not be loaded").contains("the lake is gone");
        VersionWatch broken = new VersionWatch("test", Duration.ofSeconds(30), d -> {
            throw new IllegalStateException("log unavailable");
        }, now::get);
        assertThat(broken.check(D, "v1", false, () -> { })).isNull();
        VersionWatch off = new VersionWatch("test", Duration.ZERO, d -> "v2", now::get);
        assertThat(off.check(D, "v1", false, () -> { })).as("0: switched off").isNull();
        assertThat(w.check(D, "v1", true, () -> { })).as("left to its signal").isNull();
    }
}
