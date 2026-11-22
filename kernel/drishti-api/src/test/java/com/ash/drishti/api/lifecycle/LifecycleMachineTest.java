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
package com.ash.drishti.api.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The lifecycle table (docs/architecture/LIFECYCLE.md section 2) as the one machine implements it: every legal transition, every illegal one refused. */
class LifecycleMachineTest {

    private static LifecycleMachine machine(List<LifecycleEvent> events) {
        LifecycleMachine m = new LifecycleMachine(Lifecycle.Type.PACK, "p", java.time.Clock.systemUTC(), Duration.ofSeconds(2));
        m.listen(events::add);
        return m;
    }

    /** Brings a machine to {@code s} by legal transitions. */
    private static LifecycleMachine at(LifecycleState s) {
        LifecycleMachine m = machine(new ArrayList<>());
        switch (s) {
            case NEW -> { }
            case INITIALISED -> m.transition(Transition.INIT, "t", null);
            case RUNNING -> {
                m.transition(Transition.INIT, "t", null);
                m.transition(Transition.START, "t", null);
            }
            case SUSPENDED -> {
                m.transition(Transition.INIT, "t", null);
                m.transition(Transition.START, "t", null);
                m.transition(Transition.SUSPEND, "t", null);
            }
            case FAILED -> {
                try {
                    m.transition(Transition.INIT, "t", () -> {
                        throw new IllegalStateException("bad file");
                    });
                } catch (IllegalStateException expected) {
                    // FAILED now
                }
            }
            case SHUTDOWN -> m.transition(Transition.SHUTDOWN, "t", null);
        }
        assertThat(m.state()).isEqualTo(s);
        return m;
    }

    @Test
    void theWholeLifeInOrderRaisesOneEventPerTransition() {
        List<LifecycleEvent> events = new CopyOnWriteArrayList<>();
        LifecycleMachine m = machine(events);
        assertThat(m.state()).isEqualTo(LifecycleState.NEW);
        m.transition(Transition.INIT, "ops", null);
        assertThat(m.state()).isEqualTo(LifecycleState.INITIALISED);
        m.transition(Transition.START, "ops", null);
        assertThat(m.state()).isEqualTo(LifecycleState.RUNNING);
        m.transition(Transition.SUSPEND, "ops", null);
        assertThat(m.state()).isEqualTo(LifecycleState.SUSPENDED);
        m.transition(Transition.RESUME, "ops", null);
        assertThat(m.state()).isEqualTo(LifecycleState.RUNNING);
        m.transition(Transition.SHUTDOWN, "ops", null);
        assertThat(m.state()).isEqualTo(LifecycleState.SHUTDOWN);
        assertThat(events).extracting(LifecycleEvent::auditAction)
                .containsExactly("pack-init", "pack-start", "pack-suspend", "pack-resume", "pack-shutdown");
        assertThat(events).extracting(LifecycleEvent::words).containsExactly("new -> initialised", "initialised -> running",
                "running -> suspended", "suspended -> running", "running -> shutdown");
        assertThat(events).allMatch(e -> e.actor().equals("ops") && e.target().equals("pack/p"));
    }

    /** Every pair of state and transition: allowed exactly as the table says, and a refusal changes nothing. */
    @ParameterizedTest
    @EnumSource(LifecycleState.class)
    void everyTransitionFromEveryStateIsAllowedOrRefusedAsTheTableSays(LifecycleState from) {
        for (Transition t : Transition.values()) {
            LifecycleMachine m = at(from);
            LifecycleState want = from.after(t);
            if (want == null) {
                assertThatThrownBy(() -> m.transition(t, "x", null)).isInstanceOf(LifecycleRefused.class)
                        .hasMessageContaining("is " + from.word()).hasMessageContaining(t.word() + " is not allowed")
                        .satisfies(e -> assertThat(((LifecycleRefused) e).errorCode().code()).isEqualTo("DRS-1036"));
                assertThat(m.state()).as(from + " after a refused " + t).isEqualTo(from);
            } else {
                m.transition(t, "x", null);
                assertThat(m.state()).as(from + " + " + t).isEqualTo(want);
            }
        }
    }

    @Test
    void theTableItself() {
        assertThat(LifecycleState.NEW.allows(Transition.INIT)).isTrue();
        assertThat(LifecycleState.NEW.allows(Transition.START)).isFalse();
        assertThat(LifecycleState.INITIALISED.allows(Transition.START)).isTrue();
        assertThat(LifecycleState.RUNNING.allows(Transition.SUSPEND)).isTrue();
        assertThat(LifecycleState.RUNNING.allows(Transition.RESUME)).isFalse();
        assertThat(LifecycleState.SUSPENDED.allows(Transition.RESUME)).isTrue();
        assertThat(LifecycleState.SUSPENDED.allows(Transition.START)).isFalse();
        assertThat(LifecycleState.FAILED.allows(Transition.START)).isFalse();
        for (LifecycleState s : LifecycleState.values()) {
            assertThat(s.allows(Transition.SHUTDOWN)).as(s + " may always shut down").isTrue();
        }
        assertThat(Transition.of("Suspend")).isEqualTo(Transition.SUSPEND);
        assertThatThrownBy(() -> Transition.of("pause")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("init, start, suspend, resume or shutdown");
    }

    @Test
    void aSecondShutdownIsHarmlessAndRaisesNothing() {
        List<LifecycleEvent> events = new ArrayList<>();
        LifecycleMachine m = machine(events);
        AtomicBoolean released = new AtomicBoolean();
        m.transition(Transition.SHUTDOWN, "a", () -> released.set(true));
        released.set(false);
        m.transition(Transition.SHUTDOWN, "a", () -> released.set(true));
        assertThat(released).isFalse();
        assertThat(events).hasSize(1);
    }

    @Test
    void aFailedInitOrStartIsFailedAndOnlyShutdownFollows() {
        LifecycleMachine m = machine(new ArrayList<>());
        m.transition(Transition.INIT, "a", null);
        assertThatThrownBy(() -> m.transition(Transition.START, "a", () -> {
            throw new java.io.IOException("broker down");
        })).isInstanceOf(LifecycleMachine.StepFailed.class).hasMessage("broker down");
        assertThat(m.state()).isEqualTo(LifecycleState.FAILED);
        assertThat(m.failure()).isEqualTo("broker down");
        assertThat(m.stateHealth().status()).isEqualTo(ComponentHealth.Status.DOWN);
        assertThat(m.stateHealth().reasons()).containsExactly("broker down");
        assertThatThrownBy(() -> m.transition(Transition.START, "a", null)).isInstanceOf(LifecycleRefused.class);
        m.transition(Transition.SHUTDOWN, "a", null);
        assertThat(m.state()).isEqualTo(LifecycleState.SHUTDOWN);
    }

    @Test
    void aFailingSuspendServesOnAndAFailingResumeStaysSuspended() {
        LifecycleMachine m = at(LifecycleState.RUNNING);
        assertThatThrownBy(() -> m.transition(Transition.SUSPEND, "a", () -> {
            throw new IllegalStateException("cannot pause");
        })).hasMessage("cannot pause");
        assertThat(m.state()).isEqualTo(LifecycleState.RUNNING);
        m.enter().close();                                                  // the gate is open again
        m.transition(Transition.SUSPEND, "a", null);
        assertThatThrownBy(() -> m.transition(Transition.RESUME, "a", () -> {
            throw new IllegalStateException("cannot resume");
        })).hasMessage("cannot resume");
        assertThat(m.state()).isEqualTo(LifecycleState.SUSPENDED);
    }

    @Test
    void aSuspendClosesTheGateAtOnceAndWaitsForRequestsInFlight() throws Exception {
        LifecycleMachine m = at(LifecycleState.RUNNING);
        StatsRecorder.Ticket flying = m.enter();
        CountDownLatch suspended = new CountDownLatch(1);
        AtomicBoolean stepRanBeforeTheRequestEnded = new AtomicBoolean();
        AtomicBoolean ended = new AtomicBoolean();
        Thread t = Thread.ofVirtual().start(() -> {
            m.transition(Transition.SUSPEND, "ops", () -> stepRanBeforeTheRequestEnded.set(!ended.get()));
            suspended.countDown();
        });
        Thread.sleep(200);
        assertThat(m.state()).as("the gate closed before the drain").isEqualTo(LifecycleState.SUSPENDED);
        assertThatThrownBy(m::enter).isInstanceOf(ComponentSuspended.class).hasMessageContaining("pack p is suspended")
                .hasMessageContaining("by ops").satisfies(e -> assertThat(((ComponentSuspended) e).errorCode().code()).isEqualTo("DRS-1037"));
        assertThat(suspended.getCount()).as("still draining").isEqualTo(1);
        ended.set(true);
        flying.ok(10, 100);                                                 // the request in flight finishes
        assertThat(suspended.await(5, TimeUnit.SECONDS)).isTrue();
        t.join();
        assertThat(stepRanBeforeTheRequestEnded).as("the plugin's own step runs after the drain").isFalse();
        ComponentStats s = m.stats();
        assertThat(s.total().requests()).isEqualTo(1);
        assertThat(s.total().rowsRead()).isEqualTo(10);
        assertThat(s.total().refusals()).isEqualTo(1);
        m.transition(Transition.RESUME, "ops", null);
        m.enter().ok(1, 0);
        assertThat(m.stats().total().requests()).isEqualTo(2);
    }

    @Test
    void theDrainHasALimitAndSaysWhatWasStillRunning() {
        List<LifecycleEvent> events = new ArrayList<>();
        LifecycleMachine m = new LifecycleMachine(Lifecycle.Type.CONNECTOR, "c", java.time.Clock.systemUTC(), Duration.ofMillis(100));
        m.listen(events::add);
        m.transition(Transition.INIT, "a", null);
        m.transition(Transition.START, "a", null);
        m.enter();                                                          // never ends
        m.transition(Transition.SHUTDOWN, "a", null);
        assertThat(m.state()).isEqualTo(LifecycleState.SHUTDOWN);
        assertThat(events.get(events.size() - 1).detail()).contains("1 request(s) still running after the drain");
    }

    @Test
    void aShutdownAlwaysEndsShutDownEvenWhenReleasingFails() {
        LifecycleMachine m = at(LifecycleState.RUNNING);
        m.transition(Transition.SHUTDOWN, "a", () -> {
            throw new IllegalStateException("close failed");
        });
        assertThat(m.state()).isEqualTo(LifecycleState.SHUTDOWN);
        assertThat(m.failure()).isEqualTo("close failed");
    }

    @Test
    void theOwnGateRunsBeforeTheDrainAndAFailingGateReopens() {
        LifecycleMachine m = at(LifecycleState.RUNNING);
        List<String> order = new ArrayList<>();
        m.transition(Transition.SUSPEND, "a", () -> order.add("close"), () -> order.add("step"));
        assertThat(order).containsExactly("close", "step");
        m.transition(Transition.RESUME, "a", null);
        assertThatThrownBy(() -> m.transition(Transition.SUSPEND, "a", () -> {
            throw new IllegalStateException("no stand-in");
        }, null)).hasMessage("no stand-in");
        assertThat(m.state()).isEqualTo(LifecycleState.RUNNING);
    }
}
