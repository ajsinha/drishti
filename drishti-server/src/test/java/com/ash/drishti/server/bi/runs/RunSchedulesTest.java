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
package com.ash.drishti.server.bi.runs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.BusinessCalendar;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.server.security.Principal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Scheduled runs (BI_RUNS 9.2, 14.1): fired when due as the owner now, an owner who lost access gets a failure, the run object's {@code schedule}. */
class RunSchedulesTest {

    @TempDir Path data;
    RunService runs;
    RunSchedules schedules;
    final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-21T06:00:00Z"));
    final AtomicBoolean mayAsk = new AtomicBoolean(true);
    final AtomicInteger asked = new AtomicInteger();
    final List<String> told = new CopyOnWriteArrayList<>();
    static final Principal ANN = new Principal("ann", List.of("bi-viewer"));

    final Clock clock = new Clock() {
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
    };

    @BeforeEach
    void setUp() {
        RunsProperties p = RunsProperties.defaults(data);
        runs = new RunService(p, new RunRecords.Memory(), new RunStore(p), Clock.systemUTC(), "java-1", RunMetrics.NONE);
        runs.notifier((u, r, s) -> told.add(u + ": " + s));
        schedules = new RunSchedules(runs, new RunSchedules.Memory(), r -> !mayAsk.get() ? Optional.empty()
                : Optional.of(new RunService.Question(new Principal(r.runner(), List.of("bi-viewer")), "mq", "trading", null, r.title(), null, "trading",
                        r.question(), null, "sig-ann", null, false, null, () -> {
                            asked.incrementAndGet();
                            return new RunService.Answer(Map.of("rowCount", 0, "rows", List.of()), null, 0);
                        })), ZoneOffset.UTC, BusinessCalendar.of("WEEKENDS"), clock);
    }

    @AfterEach
    void tearDown() {
        runs.close();
    }

    Run done() {
        Run r = Run.planned("r-java-1-01JA0000000000000000000001", "ann", "ann", "mq", "{\"type\":\"mq\",\"model\":\"trading\"}", "k", "sig-ann",
                Tier.AD_HOC_HEAVY, "local", "{}", "java-1", now.get());
        return r.moveTo(RunState.RUNNING, null, null, now.get()).moveTo(RunState.DONE, null, null, now.get());
    }

    @Test
    void aDueScheduleRunsAsItsOwnerAndMovesToItsNextTime() throws Exception {
        RunSchedules.Schedule s = schedules.create(done(), ANN, "daily 07:30", "latest");
        assertThat(s.nextAt()).isEqualTo(Instant.parse("2026-10-21T07:30:00Z"));
        assertThat(schedules.fireDue()).isEmpty();                                     // not due yet
        now.set(Instant.parse("2026-10-21T07:31:00Z"));
        List<String> started = schedules.fireDue();
        assertThat(started).hasSize(1);
        for (int i = 0; i < 100 && asked.get() == 0; i++) {
            Thread.sleep(20);
        }
        assertThat(asked.get()).isEqualTo(1);
        RunSchedules.Schedule after = schedules.of("ann").get(0);
        assertThat(after.lastRun()).isEqualTo(started.get(0));
        assertThat(after.nextAt()).isEqualTo(Instant.parse("2026-10-22T07:30:00Z"));
        assertThat(schedules.ofRun(done().id(), "ann")).containsEntry("schedule", "daily 07:30");
        assertThat(schedules.ofRun(done().id(), "bob")).isNull();
    }

    @Test
    void anOwnerWhoLostAccessGetsAFailureNeverSomeoneElsesRights() {
        schedules.create(done(), ANN, "daily 07:30", "latest");
        mayAsk.set(false);
        now.set(Instant.parse("2026-10-21T08:00:00Z"));
        assertThat(schedules.fireDue()).isEmpty();
        assertThat(schedules.of("ann").get(0).lastRun()).startsWith("failed: DRS-9021");
        assertThat(told).anySatisfy(t -> assertThat(t).startsWith("ann: Scheduled run failed"));
        assertThat(asked.get()).isZero();
    }

    @Test
    void schedulesAreTheOwnersAndAreChangedTurnedOffAndDeleted() {
        RunSchedules.Schedule s = schedules.create(done(), ANN, "weekly mon 08:00", "latest");
        assertThatThrownBy(() -> schedules.change(s.id(), "bob", null, false)).isInstanceOf(DrishtiException.class);
        assertThat(schedules.change(s.id(), "ann", "business-days 07:00", false).enabled()).isFalse();
        now.set(Instant.parse("2026-11-30T09:00:00Z"));
        assertThat(schedules.fireDue()).isEmpty();                                     // turned off
        assertThatThrownBy(() -> schedules.create(done(), ANN, "after-load trade", "latest")).isInstanceOf(DrishtiException.class);
        assertThatThrownBy(() -> schedules.create(done(), ANN, "sometimes", "latest")).isInstanceOf(DrishtiException.class);
        schedules.delete(s.id(), "ann");
        assertThat(schedules.of("ann")).isEmpty();
    }
}
