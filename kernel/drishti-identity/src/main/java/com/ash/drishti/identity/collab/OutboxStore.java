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
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The outbox: deliveries to leave the server, claimed under a lease so several servers may dispatch. JPA and file implementations. */
public interface OutboxStore {

    /** Stores the delivery and returns it with its sequence number. */
    OutboxItem add(OutboxItem item);

    Optional<OutboxItem> find(long seq);

    /**
     * Claims up to {@code limit} deliveries that are due (pending with {@code nextAt <= now}, or sending with an expired lease), oldest
     * first, marking them sending until {@code leaseUntil} for {@code owner}. A delivery is returned to one caller only.
     */
    List<OutboxItem> claim(Instant now, int limit, Instant leaseUntil, String owner);

    void sent(long seq, Instant at);

    /** Back to pending, to be tried again at {@code nextAt}. */
    void retry(long seq, int attempts, Instant nextAt, String error);

    /** Gives up: a dead letter, visible to administrators. */
    void dead(long seq, int attempts, String error);

    /** Will not be sent (the recipient can no longer see it, or has no address, or turned it off). */
    void cancel(long seq, String reason);

    /** A dead (or cancelled) delivery goes back to pending with its attempts reset; false when it is not in such a state. */
    boolean requeue(long seq, Instant at);

    /** Newest first, {@code state} null = all. */
    List<OutboxItem> list(String state, int limit);

    /** Deliveries with a sequence number above {@code afterSeq}, oldest first, at most {@code limit} (for the compliance export). */
    List<OutboxItem> after(long afterSeq, int limit);

    /** Deliveries per state. */
    Map<String, Long> counts();

    /** Deliveries created for the recipient since the instant (the per-recipient hourly cap). */
    long countSince(String recipient, Instant since);

    /** Removes sent deliveries older than the instant; returns how many. */
    int purgeSent(Instant before);
}
