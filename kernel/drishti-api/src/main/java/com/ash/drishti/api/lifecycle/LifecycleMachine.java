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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The one implementation of the lifecycle table ({@link LifecycleState}) for a module, a pack or a connector: one transition at a time, the
 * request gate, the drain, the counters and the event every transition raises (the host audits it, puts it on the event bus and in the
 * cluster digest). A part keeps one machine per instance; after {@code SHUTDOWN} a new start is a new machine.
 *
 * <p>The gate: {@link #enter} admits a request only while {@code RUNNING}. A suspend closes the gate first (new requests answer
 * {@link ComponentSuspended}), then waits up to the drain for requests in flight to finish, then runs the part's own step (stop pulling); a
 * shutdown does the same and always ends {@code SHUTDOWN}, a failing step recorded in its event. A failing init or start leaves the part
 * {@code FAILED}; a failing suspend puts the gate back ({@code RUNNING}); a failing resume leaves it {@code SUSPENDED}.
 */
public final class LifecycleMachine {

    /** A part's own work for one transition. */
    @FunctionalInterface
    public interface Step {
        void run() throws Exception;
    }

    /** Told after every transition, refused and failed ones too. */
    @FunctionalInterface
    public interface Listener {
        void on(LifecycleEvent e);
    }

    /** A step's checked exception, carried unchanged as the cause ({@link #getCause}) so a part can tell its own failures apart. */
    public static final class StepFailed extends RuntimeException {
        private static final long serialVersionUID = 1L;

        StepFailed(Exception cause) {
            super(cause.getMessage(), cause);
        }
    }

    /** The exception a step threw: the checked one inside {@link StepFailed}, else {@code e} itself. */
    public static Exception cause(RuntimeException e) {
        return e instanceof StepFailed f && f.getCause() instanceof Exception c ? c : e;
    }

    /** The default time a suspend or shutdown waits for requests in flight. */
    public static final Duration DRAIN = Duration.ofSeconds(30);

    private final Lifecycle.Type type;
    private final String name;
    private final Clock clock;
    private final Duration drain;
    private final ReentrantLock lock = new ReentrantLock();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final StatsRecorder stats;
    private volatile LifecycleState state = LifecycleState.NEW;
    private volatile Instant since;
    private volatile String by = "";
    private volatile String failure;

    public LifecycleMachine(Lifecycle.Type type, String name) {
        this(type, name, Clock.systemUTC(), DRAIN);
    }

    public LifecycleMachine(Lifecycle.Type type, String name, Clock clock, Duration drain) {
        this.type = type;
        this.name = name;
        this.clock = clock;
        this.drain = drain;
        this.stats = new StatsRecorder(clock);
        this.since = clock.instant();
    }

    public Lifecycle.Type type() {
        return type;
    }

    public String name() {
        return name;
    }

    public LifecycleState state() {
        return state;
    }

    /** When the current state began. */
    public Instant since() {
        return since;
    }

    /** Who made the last transition. */
    public String by() {
        return by;
    }

    /** Why init or start failed, or why the last shutdown step failed; null otherwise. */
    public String failure() {
        return failure;
    }

    public StatsRecorder recorder() {
        return stats;
    }

    public void listen(Listener l) {
        listeners.add(l);
    }

    /**
     * Runs a transition: refused ({@link LifecycleRefused}, nothing changed) when the table does not allow it; else the part's {@code step}
     * as described on the class. A shutdown of a shut-down part is harmless and raises nothing.
     */
    public void transition(Transition t, String actor, Step step) {
        transition(t, actor, null, step);
    }

    /**
     * As {@link #transition(Transition, String, Step)}, with the part's own gate: for a suspend or a shutdown, {@code close} runs as soon as
     * the machine's gate closes and before the drain (a connector swaps in its suspended stand-in there, so no new read reaches the plugin);
     * a failing {@code close} reopens the gate and is rethrown. For the other transitions {@code close} is not used.
     */
    public void transition(Transition t, String actor, Step close, Step step) {
        lock.lock();
        try {
            LifecycleState from = state;
            if (from == LifecycleState.SHUTDOWN && t == Transition.SHUTDOWN) {
                return;
            }
            LifecycleState to = from.after(t);
            if (to == null) {
                LifecycleRefused r = new LifecycleRefused(type, name, from, t);
                tell(t, from, from, actor, "refused", r.getMessage());
                throw r;
            }
            switch (t) {
                case SUSPEND -> suspend(from, actor, close, step);
                case SHUTDOWN -> shutdown(from, actor, close, step);
                default -> plain(t, from, to, actor, step);
            }
        } finally {
            lock.unlock();
        }
    }

    /** As {@link #transition}, refused with the part's own words (an engine not running, a dependent still running) before anything runs. */
    public void refuse(Transition t, String actor, String words) {
        LifecycleRefused r = new LifecycleRefused(type, name, state, t, words);
        tell(t, state, state, actor, "refused", words);
        throw r;
    }

    private void plain(Transition t, LifecycleState from, LifecycleState to, String actor, Step step) {
        try {
            run(step);
        } catch (RuntimeException e) {
            if (t == Transition.INIT || t == Transition.START) {
                failure = words(e);
                set(LifecycleState.FAILED, actor);
                tell(t, from, LifecycleState.FAILED, actor, "failed", failure);
            } else {
                tell(t, from, from, actor, "failed", words(e));
            }
            throw e;
        }
        if (t == Transition.START) {
            failure = null;
        }
        set(to, actor);
        tell(t, from, to, actor, "done", "");
    }

    private void suspend(LifecycleState from, String actor, Step close, Step step) {
        Instant was = since;
        String wasBy = by;
        set(LifecycleState.SUSPENDED, actor);                // the gate closes: new requests are refused from here
        String waited;
        try {
            run(close);
            waited = awaitDrain();
            run(step);
        } catch (RuntimeException e) {
            state = from;                                      // the gate opens again: the part serves as before
            since = was;
            by = wasBy;
            tell(Transition.SUSPEND, from, from, actor, "failed", words(e));
            throw e;
        }
        tell(Transition.SUSPEND, from, LifecycleState.SUSPENDED, actor, "done", waited);
    }

    private void shutdown(LifecycleState from, String actor, Step close, Step step) {
        set(LifecycleState.SHUTDOWN, actor);                 // the gate closes for good
        String waited = "";
        String detail = "";
        try {
            run(close);
            waited = awaitDrain();
            detail = waited;
            run(step);
        } catch (RuntimeException e) {
            failure = words(e);
            detail = (waited.isEmpty() ? "" : waited + "; ") + "released with a problem: " + failure;
        }
        tell(Transition.SHUTDOWN, from, LifecycleState.SHUTDOWN, actor, "done", detail);
    }

    /** Waits for requests in flight, at most the drain; returns words when some were still running at the end. */
    private String awaitDrain() {
        long deadline = System.nanoTime() + drain.toNanos();
        while (stats.inFlight() > 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        long left = stats.inFlight();
        return left == 0 ? "" : left + " request(s) still running after the drain of " + drain.toSeconds() + " s";
    }

    private static void run(Step step) {
        if (step == null) {
            return;
        }
        try {
            step.run();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new StepFailed(e);
        }
    }

    private static String words(Throwable e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m.lines().findFirst().orElse(m);
    }

    private void set(LifecycleState s, String actor) {
        state = s;
        since = clock.instant();
        by = actor == null ? "" : actor;
    }

    private void tell(Transition t, LifecycleState from, LifecycleState to, String actor, String outcome, String detail) {
        LifecycleEvent e = new LifecycleEvent(type, name, t, from, to, actor == null ? "" : actor, clock.instant(), outcome, detail == null ? "" : detail);
        for (Listener l : listeners) {
            try {
                l.on(e);
            } catch (RuntimeException ignored) {
                // a listener (the audit, the digest) never undoes a transition
            }
        }
    }

    // -- the gate ---------------------------------------------------------------------------------------------------------------

    /**
     * Admits one request: a ticket counted in flight (and in the stats) until it ends. Refused with {@link ComponentSuspended} while suspended,
     * and with {@link LifecycleRefused}-free words ({@link IllegalStateException}) when the part is not running at all.
     */
    public StatsRecorder.Ticket enter() {
        check();
        StatsRecorder.Ticket ticket = stats.begin();
        if (state != LifecycleState.RUNNING) {                  // suspended between the check and the count: the drain must not wait for it
            ticket.failed();
            check();
        }
        return ticket;
    }

    /** Throws as {@link #enter} does when the part does not serve now; counts the refusal. Nothing is counted when it serves. */
    public void check() {
        LifecycleState s = state;
        if (s == LifecycleState.RUNNING) {
            return;
        }
        stats.refused();
        if (s == LifecycleState.SUSPENDED) {
            throw new ComponentSuspended(type, name, by, since);
        }
        throw new IllegalStateException(type.word() + " " + name + " is not running (" + s.word() + ")");
    }

    /** True while the part serves. */
    public boolean serving() {
        return state == LifecycleState.RUNNING;
    }

    /** The health the state alone implies; a part folds its own knowledge into it. */
    public ComponentHealth stateHealth() {
        return ComponentHealth.ofState(state, failure, clock.instant());
    }

    public ComponentStats stats() {
        return stats.snapshot();
    }

    /** Admin's row: state, since, by, failure. */
    public java.util.Map<String, Object> describe() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("type", type.word());
        m.put("name", name);
        m.put("state", state.name());
        m.put("since", since.toString());
        m.put("by", by);
        if (failure != null) {
            m.put("failure", failure);
        }
        return m;
    }
}
