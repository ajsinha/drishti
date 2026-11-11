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
package com.ash.drishti.server.collab.mail;

import com.ash.drishti.identity.collab.OutboxItem;
import java.time.Duration;

/**
 * A delivery channel other than email that the outbox dispatcher serves (the bridges). The dispatcher claims, leases, retries with
 * backoff and dead-letters; the channel only delivers one row. It throws {@link ItemRenderer.Skip} to cancel the row,
 * {@link Permanent} to make it a dead letter at once, {@link Deferred} to try again later without counting an attempt, and any other
 * runtime exception for an ordinary failure (retried with backoff).
 */
public interface OutboxChannel {

    /** A failure retrying cannot cure. The message goes to the dead letter list, so it must never hold a secret. */
    final class Permanent extends RuntimeException {
        public Permanent(String reason) {
            super(reason, null, false, false);
        }
    }

    /** Not now: the row stays pending and is tried again after the delay (a rate limit), with no attempt counted. */
    final class Deferred extends RuntimeException {
        private final transient Duration delay;

        public Deferred(String reason, Duration delay) {
            super(reason, null, false, false);
            this.delay = delay;
        }

        public Duration delay() {
            return delay;
        }
    }

    /** The {@link OutboxItem#channel()} this serves. */
    String channel();

    /** Delivers the row, or throws as described above. */
    void deliver(OutboxItem item);

    /** Called once after the row was sent (for the audit trail). */
    default void delivered(OutboxItem item) {}

    /** Called when the row became a dead letter. */
    default void gaveUp(OutboxItem item, String reason) {}
}
