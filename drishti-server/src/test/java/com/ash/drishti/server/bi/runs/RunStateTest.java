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

import com.ash.drishti.server.bi.runs.RunState.IllegalTransition;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * The run's states and record (docs/architecture/BI_RUNS.md sections 3.2 to 3.4, test 19.1 RunStateTest): every allowed transition, every illegal one
 * refused; the wire names; the record's stamps on a move; the run id and the node it names.
 */
class RunStateTest {

    /** The table of section 3.3, written out independently of RunState so a change to one is caught by the other. */
    static final Map<RunState, Set<RunState>> ALLOWED = Map.of(
            RunState.PLANNED, EnumSet.of(RunState.REUSED, RunState.AWAITING_CONFIRM, RunState.QUEUED, RunState.RUNNING, RunState.FAILED, RunState.CANCELLED),
            RunState.AWAITING_CONFIRM, EnumSet.of(RunState.QUEUED, RunState.CANCELLED, RunState.FAILED),
            RunState.QUEUED, EnumSet.of(RunState.RUNNING, RunState.CANCELLED, RunState.FAILED),
            RunState.RUNNING, EnumSet.of(RunState.STORING, RunState.DONE, RunState.REUSED, RunState.FAILED, RunState.CANCELLED, RunState.QUEUED),
            RunState.STORING, EnumSet.of(RunState.DONE, RunState.FAILED, RunState.CANCELLED, RunState.QUEUED),
            RunState.DONE, EnumSet.of(RunState.EXPIRED),
            RunState.REUSED, EnumSet.of(RunState.EXPIRED),
            RunState.FAILED, EnumSet.noneOf(RunState.class),
            RunState.CANCELLED, EnumSet.noneOf(RunState.class),
            RunState.EXPIRED, EnumSet.noneOf(RunState.class));

    static final Instant T0 = Instant.parse("2026-10-20T09:14:03Z");

    @Test
    void everyAllowedTransitionPassesAndEveryOtherIsRefused() {
        int allowed = 0;
        int refused = 0;
        for (RunState from : RunState.values()) {
            assertThat(from.next()).isEqualTo(ALLOWED.get(from));
            for (RunState to : RunState.values()) {
                if (ALLOWED.get(from).contains(to)) {
                    assertThat(from.check(to)).isEqualTo(to);
                    allowed++;
                } else {
                    assertThatThrownBy(() -> from.check(to)).isInstanceOf(IllegalTransition.class)
                            .hasMessage("a run cannot go from " + from.wire() + " to " + to.wire());
                    refused++;
                }
            }
        }
        assertThat(allowed).isEqualTo(24);
        assertThat(refused).isEqualTo(100 - 24);
    }

    @Test
    void finishedStatesLeaveOnlyByRetention() {
        for (RunState s : RunState.values()) {
            if (s.finished()) {
                assertThat(s.next()).isSubsetOf(EnumSet.of(RunState.EXPIRED));
            }
        }
        assertThat(EnumSet.allOf(RunState.class).stream().filter(RunState::active)).containsExactly(RunState.QUEUED, RunState.RUNNING, RunState.STORING);
    }

    @Test
    void wireNamesRoundTrip() {
        assertThat(RunState.AWAITING_CONFIRM.wire()).isEqualTo("awaiting-confirm");
        for (RunState s : RunState.values()) {
            assertThat(RunState.of(s.wire())).isEqualTo(s);
        }
        assertThat(Tier.of("ad-hoc-heavy")).isEqualTo(Tier.AD_HOC_HEAVY);
        assertThatThrownBy(() -> RunState.of("paused")).isInstanceOf(IllegalArgumentException.class);
    }

    static Run planned() {
        return Run.planned(RunId.next("java-2", T0, new SplittableRandom(7)), "ajsinha", "ajsinha", "report", "{\"mq\":1}", "p_1", "sig", Tier.AD_HOC_HEAVY,
                "local", "{}", "java-2", T0);
    }

    @Test
    void aRunMovesThroughItsLifeWithItsTimesStamped() {
        Run r = planned();
        assertThat(r.state()).isEqualTo(RunState.PLANNED);
        Run q = r.moveTo(RunState.QUEUED, null, null, T0.plusSeconds(1));
        Run running = q.moveTo(RunState.RUNNING, null, null, T0.plusSeconds(2)).progress(0.29, "building 12 of 41 dates");
        assertThat(running.startedAt()).isEqualTo(T0.plusSeconds(2));
        assertThat(running.progress()).isEqualTo(0.29);
        Run done = running.moveTo(RunState.STORING, null, null, T0.plusSeconds(200)).moveTo(RunState.DONE, null, null, T0.plusSeconds(252));
        assertThat(done.finishedAt()).isEqualTo(T0.plusSeconds(252));
        assertThat(done.progress()).isEqualTo(1.0);
        assertThat(done.moveTo(RunState.EXPIRED, null, "removed by retention", T0.plusSeconds(999)).finishedAt()).isEqualTo(T0.plusSeconds(252));
        assertThatThrownBy(() -> done.moveTo(RunState.RUNNING, null, null, T0)).isInstanceOf(IllegalTransition.class);
        assertThatThrownBy(() -> done.progress(0.5, "x")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aRestartRequeuesWithTheNextAttemptAndAFailureKeepsItsCode() {
        Run running = planned().moveTo(RunState.QUEUED, null, null, T0).moveTo(RunState.RUNNING, null, null, T0).progress(0.5, "scanning");
        Run again = running.moveTo(RunState.QUEUED, null, "started again after the server restarted (attempt 2 of 2)", T0.plusSeconds(60));
        assertThat(again.attempt()).isEqualTo(2);
        assertThat(again.progress()).isNull();
        assertThat(again.startedAt()).isNull();
        Run failed = again.moveTo(RunState.FAILED, "DRS-9085", "the server stopped while this ran, twice", T0.plusSeconds(90));
        assertThat(failed.code()).isEqualTo("DRS-9085");
        assertThat(failed.finishedAt()).isEqualTo(T0.plusSeconds(90));
    }

    @Test
    void theRecordRefusesWhatTheTableWouldNotHold() {
        assertThatThrownBy(() -> Run.planned("r-n-01JAC4Q8Z6T3M4R9B0X1Y2Z3W4", "a", "a", "dashboard", "{}", "p", "s", Tier.INTERACTIVE, "local", "{}", "n", T0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("dashboard");
        assertThatThrownBy(() -> planned().moveTo(RunState.QUEUED, null, null, T0).moveTo(RunState.RUNNING, null, null, T0).progress(1.5, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void runIdsNameTheirNodeAndSortByTime() {
        SplittableRandom random = new SplittableRandom(1);
        String a = RunId.next("java-2", T0, random);
        String b = RunId.next("java-2", T0.plusMillis(1), random);
        assertThat(a).matches("r-java-2-[0-9A-HJKMNP-TV-Z]{26}");
        assertThat(RunId.node(a)).isEqualTo("java-2");
        assertThat(RunId.valid(a)).isTrue();
        assertThat(a.compareTo(b)).isNegative();
        assertThat(planned().ownerNode()).isEqualTo("java-2");
        assertThat(RunId.node("r-drishti-main-01JAC4Q8Z6T3M4R9B0X1Y2Z3W4")).isEqualTo("drishti-main");
        assertThatThrownBy(() -> RunId.node("r-x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RunId.next("bad node", T0, random)).isInstanceOf(IllegalArgumentException.class);
    }
}
