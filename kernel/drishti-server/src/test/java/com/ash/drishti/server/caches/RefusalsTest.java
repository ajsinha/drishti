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
package com.ash.drishti.server.caches;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.time.LocalDate;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** A refusal for room is remembered with its stamp and time; another stamp, the time up, or room freed forget it. */
class RefusalsTest {

    static final LocalDate D = LocalDate.of(2026, 9, 30);

    @Test
    void rememberedUntilTheTimeTheStampOrRoom() {
        AtomicLong now = new AtomicLong(1_000_000);
        Refusals r = new Refusals(Set.of(ErrorCode.VAR_CAPACITY), () -> 300, now::get);
        r.remember(D, "v1", new DrishtiException(ErrorCode.VAR_INCONSISTENT_DATE, "not room"));
        assertThat(r.size()).as("only the room's refusals").isZero();
        r.remember(D, "v1", new DrishtiException(ErrorCode.VAR_CAPACITY, "no room for 2026-09-30"));
        RefusedForNow e = r.check(D, "v1").orElseThrow();
        assertThat(e.errorCode()).isEqualTo(ErrorCode.VAR_CAPACITY);
        assertThat(e.retryAfterSeconds()).isEqualTo(300);
        assertThat(e.getMessage()).startsWith("DRS-5204 no room for 2026-09-30");
        now.addAndGet(100_000);
        assertThat(r.check(D, "v1").orElseThrow().retryAfterSeconds()).isEqualTo(200);
        assertThat(r.check(D, "v2")).as("a new version of the inputs: forgotten").isEmpty();
        assertThat(r.size()).isZero();
        r.remember(D, "v2", new DrishtiException(ErrorCode.VAR_CAPACITY, "no room"));
        now.addAndGet(300_000);
        assertThat(r.check(D, "v2")).as("the time is up").isEmpty();
        r.remember(D, "v2", new DrishtiException(ErrorCode.VAR_CAPACITY, "no room"));
        r.roomFreed();
        assertThat(r.check(D, "v2")).as("room freed").isEmpty();
        Refusals off = new Refusals(Set.of(ErrorCode.VAR_CAPACITY), () -> 0, now::get);
        off.remember(D, "v1", new DrishtiException(ErrorCode.VAR_CAPACITY, "no room"));
        assertThat(off.size()).as("refusal-ttl-seconds 0: not remembered").isZero();
    }
}
