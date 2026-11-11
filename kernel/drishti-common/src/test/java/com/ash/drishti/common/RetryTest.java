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
package com.ash.drishti.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RetryTest {

    private static final class Ticking extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        void advance(Duration d) {
            now.updateAndGet(i -> i.plus(d));
        }
    }

    private static RetryPolicy noJitter() {
        return new RetryPolicy(true, Duration.ofSeconds(5), 2.0, Duration.ofMinutes(5), 0, 3);
    }

    @Test
    void scheduleDoublesFromFiveSecondsAndStopsAtFiveMinutes() {
        RetryPolicy p = noJitter();
        List<Long> seconds = new ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            seconds.add(p.baseDelay(i).toSeconds());
        }
        assertThat(seconds).containsExactly(5L, 10L, 20L, 40L, 80L, 160L, 300L, 300L, 300L);
        assertThat(p.baseDelay(100000)).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void jitterStaysWithinItsFractionAndNeverPastMax() {
        RetryPolicy p = new RetryPolicy(true, Duration.ofSeconds(10), 2.0, Duration.ofSeconds(40), 0.2, 3);
        assertThat(p.delay(1, false, 0.0).toMillis()).isEqualTo(8000);
        assertThat(p.delay(1, false, 0.5).toMillis()).isEqualTo(10000);
        assertThat(p.delay(1, false, 0.999999).toMillis()).isBetween(11990L, 12000L);
        assertThat(p.delay(9, false, 0.999999).toMillis()).isEqualTo(40000);
    }

    @Test
    void settingsReadWithAreaOverride() {
        Map<String, String> env = Map.of("drishti.retry.initial", "2s", "drishti.retry.max", "1m", "drishti.retry.mcp.initial", "500ms",
                "drishti.retry.ai.enabled", "false", "drishti.retry.factor", "3");
        RetryPolicy packs = RetryPolicy.of(env::get, "packs");
        assertThat(packs.initial()).isEqualTo(Duration.ofSeconds(2));
        assertThat(packs.factor()).isEqualTo(3.0);
        assertThat(packs.max()).isEqualTo(Duration.ofMinutes(1));
        assertThat(RetryPolicy.of(env::get, "mcp").initial()).isEqualTo(Duration.ofMillis(500));
        assertThat(RetryPolicy.of(env::get, "ai").enabled()).isFalse();
        assertThat(RetryPolicy.of(k -> null, "connectors")).isEqualTo(RetryPolicy.defaults());
        Map<String, String> bad = Map.of("drishti.retry.max", "1s");
        assertThatThrownBy(() -> RetryPolicy.of(bad::get, null)).hasMessageContaining("max");
    }

    @Test
    void transientErrorsKeepBackingOffButRepeatedPermanentOnesSlowToMax() {
        Ticking clock = new Ticking();
        RetryTracker t = new RetryTracker(noJitter(), clock, () -> 0.5);
        assertThat(t.failed("connection refused", false).nextAt()).isEqualTo(clock.instant().plusSeconds(5));
        assertThat(t.failed("connection refused", false).nextAt()).isEqualTo(clock.instant().plusSeconds(10));
        RetryTracker c = new RetryTracker(noJitter(), clock, () -> 0.5);
        assertThat(c.failed("HTTP 401 unauthorized", true).permanent()).isFalse();
        assertThat(c.failed("HTTP 401 unauthorized", true).permanent()).isFalse();
        RetryTracker.Status third = c.failed("HTTP 401 unauthorized", true);
        assertThat(third.permanent()).isTrue();
        assertThat(third.nextAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(5)));
        c.failed("timeout", false);
        assertThat(c.status().permanent()).isFalse();
        assertThat(RetryPolicy.looksPermanent("HTTP 403 Forbidden")).isTrue();
        assertThat(RetryPolicy.looksPermanent(new IllegalArgumentException("x"))).isTrue();
        assertThat(RetryPolicy.looksPermanent("connection refused")).isFalse();
    }

    @Test
    void dueRetryNowAndRecovery() {
        Ticking clock = new Ticking();
        RetryTracker t = new RetryTracker(noJitter(), clock, () -> 0.5);
        assertThat(t.due()).isFalse();
        t.failed("down", false);
        assertThat(t.due()).isFalse();
        assertThat(t.status().nextInSeconds(clock.instant())).isEqualTo(5);
        clock.advance(Duration.ofSeconds(5));
        assertThat(t.due()).isTrue();
        t.failed("down", false);
        assertThat(t.status().attempt()).isEqualTo(2);
        t.retryNow();
        assertThat(t.due()).isTrue();
        assertThat(t.succeeded()).isEqualTo(2);
        assertThat(t.failing()).isFalse();
        assertThat(t.due()).isFalse();
    }

    @Test
    void disabledPolicyPlansNoRetry() {
        RetryTracker t = new RetryTracker(new RetryPolicy(false, Duration.ofSeconds(5), 2, Duration.ofMinutes(5), 0, 3));
        assertThat(t.failed("down", false).nextAt()).isNull();
        assertThat(t.due()).isFalse();
        assertThat(t.status().nextInSeconds(Instant.now())).isEqualTo(-1);
    }

    @Test
    void boardLogsAndAuditsOnPowersOfTwoAndOnRecovery() {
        RetryBoard board = new RetryBoard("connector", noJitter(), new Ticking());
        List<String> events = new ArrayList<>();
        board.listener(new RetryBoard.Listener() {
            @Override
            public void failed(String area, String key, RetryTracker.Status s, boolean logged) {
                if (logged) {
                    events.add("fail" + s.attempt());
                }
            }

            @Override
            public void recovered(String area, String key, int failures, String lastError) {
                events.add("ok" + failures);
            }
        });
        for (int i = 0; i < 5; i++) {
            board.failed("kafka", "broker down", false);
        }
        assertThat(events).containsExactly("fail1", "fail2", "fail4");
        assertThat(board.status("kafka")).isPresent();
        assertThat(board.succeeded("kafka")).isTrue();
        assertThat(events).endsWith("ok5");
        assertThat(board.succeeded("kafka")).isFalse();
        assertThat(board.statuses()).isEmpty();
    }
}
