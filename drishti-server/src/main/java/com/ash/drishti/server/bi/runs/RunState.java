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

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The states of a BI run and the transitions between them (docs/architecture/BI_DESIGN.md › BI runs: every BI query as a bounded, visible job (built in 2.6.7), sections 3.2 and 3.3). The allowed transitions are one
 * table, {@link #NEXT}; {@link #check} refuses every other one, so a run can never reach a state its life does not allow.
 *
 * <p>The table follows the diagram of 3.3 with the edges the rest of the design needs and the diagram does not draw: a twin that joins a running
 * run (13.3, single-flight) goes {@code planned → running} and ends {@code reused} or {@code failed} with its twin; a run cancelled while storing
 * ({@code storing → cancelled}, section 11); a run restarted from {@code storing} (12.1); a reused run whose shared result expires
 * ({@code reused → expired}); a queued run that no longer plans after a restart ({@code queued → failed}, DRS-9085), and an unconfirmed run
 * whose model changed ({@code awaiting-confirm → failed}).
 */
public enum RunState {
    PLANNED, AWAITING_CONFIRM, QUEUED, RUNNING, STORING, DONE, REUSED, FAILED, CANCELLED, EXPIRED;

    private static final Map<RunState, Set<RunState>> NEXT = new EnumMap<>(RunState.class);

    static {
        NEXT.put(PLANNED, EnumSet.of(REUSED, AWAITING_CONFIRM, QUEUED, RUNNING, FAILED, CANCELLED));
        NEXT.put(AWAITING_CONFIRM, EnumSet.of(QUEUED, CANCELLED, FAILED));
        NEXT.put(QUEUED, EnumSet.of(RUNNING, CANCELLED, FAILED));
        NEXT.put(RUNNING, EnumSet.of(STORING, DONE, REUSED, FAILED, CANCELLED, QUEUED));
        NEXT.put(STORING, EnumSet.of(DONE, FAILED, CANCELLED, QUEUED));
        NEXT.put(DONE, EnumSet.of(EXPIRED));
        NEXT.put(REUSED, EnumSet.of(EXPIRED));
        NEXT.put(FAILED, EnumSet.noneOf(RunState.class));
        NEXT.put(CANCELLED, EnumSet.noneOf(RunState.class));
        NEXT.put(EXPIRED, EnumSet.noneOf(RunState.class));
    }

    /** The name on the wire and in the {@code drishti_bi_run.state} column: {@code awaiting-confirm}, {@code running}, … */
    public String wire() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public static RunState of(String wire) {
        return Arrays.stream(values()).filter(s -> s.wire().equals(wire)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("not a run state: " + wire));
    }

    /** The states this one may move to. */
    public Set<RunState> next() {
        return Collections.unmodifiableSet(NEXT.get(this));
    }

    public boolean canMoveTo(RunState to) {
        return NEXT.get(this).contains(to);
    }

    /** {@code to}, when this state may move to it; otherwise an {@link IllegalTransition}. */
    public RunState check(RunState to) {
        if (!canMoveTo(to)) {
            throw new IllegalTransition(this, to);
        }
        return to;
    }

    /** No transition leaves it except retention ({@code done}, {@code reused} → {@code expired}). */
    public boolean finished() {
        return this == DONE || this == REUSED || this == FAILED || this == CANCELLED || this == EXPIRED;
    }

    /** Holding or waiting for resources: what the per-user queue limit counts and what a restart must resume or fail (section 12.1). */
    public boolean active() {
        return this == QUEUED || this == RUNNING || this == STORING;
    }

    /** A transition the table does not allow. */
    public static final class IllegalTransition extends IllegalStateException {
        private final RunState from;
        private final RunState to;

        IllegalTransition(RunState from, RunState to) {
            super("a run cannot go from " + from.wire() + " to " + to.wire());
            this.from = from;
            this.to = to;
        }

        public RunState from() {
            return from;
        }

        public RunState to() {
            return to;
        }
    }
}
