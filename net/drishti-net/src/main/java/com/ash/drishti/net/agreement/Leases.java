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

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Leases with a term (CLUSTER_I5_DESIGN.md sections 2.3 and 3): the one way the product holds a one-runner part. A node names the
 * candidates of a lease (from the placement); while it is one of them and runs, it asks for the lease every {@code lease-renew}. A free
 * lease goes to the <b>first live candidate in the given order</b> (a candidate is live while its asks are younger than the lease's
 * time-to-live); a held lease stays with its holder while the holder renews (no hand-over to an earlier candidate, so no ping-pong), and
 * expires {@code lease-ttl} after its last renewal. Every change of holder raises the term.
 *
 * <p>The term fences: a holder checks {@link #stillHeld} before a side effect that is not idempotent by itself, and an official record
 * stores the term, so a late writer of an older term is refused.
 */
public interface Leases {

    /** One lease as it stands: its holder, the holder's term and when it expires unless renewed (epoch milliseconds, the referee's clock). */
    record Lease(String name, String node, long term, long expiresAt) {}

    /**
     * Names the candidates of a lease, in order (from the placement); the holder is always one of them. While this node is among them it
     * asks for the lease every {@code lease-renew}; when it is no longer among them it stops asking and releases the lease if it holds it.
     * An empty list forgets the lease here.
     */
    void candidates(String lease, List<String> nodesInOrder);

    /** The lease's holder as last known here, or empty when it is free or unknown. */
    Optional<Lease> holder(String lease);

    /** True when this node holds the lease under {@code term} now: the fencing check before a side effect. */
    boolean stillHeld(String lease, long term);

    /** Gives the lease up now (a drain, a clean stop) when this node holds it, so the next candidate takes it without waiting for expiry. */
    void release(String lease);

    /** Told the lease's name whenever its holder or term changes. */
    void onChange(Consumer<String> listener);
}
