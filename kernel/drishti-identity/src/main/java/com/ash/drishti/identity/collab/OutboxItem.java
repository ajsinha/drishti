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
package com.ash.drishti.identity.collab;

import java.time.Instant;

/**
 * One delivery waiting to leave the server ({@code drishti_outbox}): a pointer (the recipient's user name, the template, the
 * share or comment id), never the message; the message is rendered at send time for the recipient's rights then.
 */
public record OutboxItem(long seq, String channel, String recipient, String template, String refId, String state, int attempts,
        Instant nextAt, Instant leaseUntil, String leasedBy, String lastError, Instant createdAt, Instant sentAt) {

    public static final String PENDING = "pending";
    public static final String SENDING = "sending";
    public static final String SENT = "sent";
    public static final String DEAD = "dead";
    public static final String CANCELLED = "cancelled";

    /** A new pending delivery, due now. */
    public static OutboxItem pending(String channel, String recipient, String template, String refId, Instant now) {
        return new OutboxItem(0, channel, recipient, template, refId, PENDING, 0, now, null, null, null, now, null);
    }

    public OutboxItem withSeq(long s) {
        return new OutboxItem(s, channel, recipient, template, refId, state, attempts, nextAt, leaseUntil, leasedBy, lastError, createdAt, sentAt);
    }

    public OutboxItem with(String newState, int newAttempts, Instant newNextAt, Instant lease, String owner, String error, Instant sent) {
        return new OutboxItem(seq, channel, recipient, template, refId, newState, newAttempts, newNextAt, lease, owner, error, createdAt, sent);
    }
}
