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

/**
 * The one lifecycle contract of the kernel (docs/architecture/LIFECYCLE.md): a module, a pack and a connector each implement it, with the
 * states of {@link LifecycleState} and the transitions of {@link Transition}. Every transition is atomic for its part (one at a time),
 * audited by the host, and refused with {@link LifecycleRefused} ({@code 409 DRS-1036}) when the state does not allow it, changing nothing.
 *
 * <ul>
 *   <li>{@link #init}: read and validate (files, settings, secrets resolved); no I/O to the outside, nothing registered, nothing served.</li>
 *   <li>{@link #start}: register what the part offers and begin serving (open pools and consumers, register kinds and mnemonics).</li>
 *   <li>{@link #suspend}: stop taking new requests (they answer {@code 503 DRS-1037} in words); requests in flight finish; pulling stops;
 *       state, offsets, caches and pools are kept, idle.</li>
 *   <li>{@link #resume}: serve again from the kept state; cheap, nothing is reloaded or re-read.</li>
 *   <li>{@link #shutdown}: unregister everything, release every resource; idempotent; the part is done (a new start is a new instance).</li>
 * </ul>
 * {@link #health} and {@link #stats} are cheap snapshots: they never block and never do I/O.
 */
public interface Lifecycle {

    /** What sort of part this is. */
    enum Type {
        MODULE, PACK, CONNECTOR;

        public String word() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    Type type();

    /** The part's name: the module id, the pack name, the connector name. */
    String name();

    LifecycleState state();

    void init(String actor);

    void start(String actor);

    void suspend(String actor);

    void resume(String actor);

    void shutdown(String actor);

    /** Runs {@code t} (the REST and Admin entry). */
    default void apply(Transition t, String actor) {
        switch (t) {
            case INIT -> init(actor);
            case START -> start(actor);
            case SUSPEND -> suspend(actor);
            case RESUME -> resume(actor);
            case SHUTDOWN -> shutdown(actor);
        }
    }

    /** UP, DEGRADED, DOWN or SUSPENDED with the reasons in words and when it was checked; cheap, never blocks. */
    ComponentHealth health();

    /** Activity since start and over the recent window; a cheap snapshot. */
    ComponentStats stats();
}
