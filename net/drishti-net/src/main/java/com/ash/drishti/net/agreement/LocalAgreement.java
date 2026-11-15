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
package com.ash.drishti.net.agreement;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * The {@code local} mode (CLUSTER_I5_DESIGN.md sections 2.3 and 3): the referee is this process. One node has nothing to agree with; unit
 * tests start several nodes over one shared {@link Referee} (the contract suite, routing and placement tests), and can take the referee
 * away ({@link Referee#down}) to see a change refused while reads go on.
 */
public final class LocalAgreement extends AgreementBase {

    /** An in-process referee: one {@link AgreementState}, its time this process's clock, one command at a time. */
    public static final class Referee implements AgreementBase.Referee {
        private final AgreementState state = new AgreementState();
        private final ReentrantLock lock = new ReentrantLock();
        private final LongSupplier clock;
        private volatile boolean down;

        public Referee(LongSupplier clock) {
            this.clock = clock;
        }

        /** Takes the referee away (true) or brings it back (false): while away, every command and query is refused. */
        public void down(boolean away) {
            this.down = away;
        }

        public boolean isDown() {
            return down;
        }

        @Override
        public byte[] apply(byte[] command) {
            check();
            lock.lock();
            try {
                state.apply(AgreementState.tick(clock.getAsLong()));
                return state.apply(command);
            } finally {
                lock.unlock();
            }
        }

        @Override
        public byte[] query(byte[] query) {
            check();
            lock.lock();
            try {
                state.apply(AgreementState.tick(clock.getAsLong()));
                return state.query(query);
            } finally {
                lock.unlock();
            }
        }

        private void check() {
            if (down) {
                throw new AgreementUnavailableException("the cluster cannot decide right now: the referee is away");
            }
        }
    }

    private final Referee referee;

    /** One node of a set sharing {@code referee}; the keeper starts at once. */
    public LocalAgreement(String self, Referee referee, Duration ttl, Duration renew, LongSupplier clock) {
        super(self, ttl, renew, clock);
        this.referee = referee;
        startKeeper();
    }

    /** A single node with a referee of its own, on the system clock. */
    public static LocalAgreement single(String self, Duration ttl, Duration renew) {
        return new LocalAgreement(self, new Referee(System::currentTimeMillis), ttl, renew, System::currentTimeMillis);
    }

    @Override
    protected AgreementBase.Referee referee() {
        return referee;
    }

    @Override
    public Mode mode() {
        return Mode.LOCAL;
    }

    @Override
    public AgreementHealth health() {
        boolean decides = !referee.isDown() && !closed();
        return new AgreementHealth(Mode.LOCAL, decides, decides ? self() : "", 0, List.of(), List.of(),
                decides ? "one node: this process decides" : "the referee is away");
    }

    @Override
    protected void closeProvider() {
        // nothing held: the referee is shared and outlives this node
    }
}
