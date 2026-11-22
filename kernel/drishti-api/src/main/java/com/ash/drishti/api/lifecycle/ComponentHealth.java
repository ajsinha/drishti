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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A part's health (docs/architecture/LIFECYCLE.md section 7): one status, the reasons in words, and when it was checked. Built from what the
 * part already knows (its state, its plugin's own health line, the health of what it depends on): never blocks, never does I/O.
 *
 * @param status UP, DEGRADED, DOWN or SUSPENDED
 * @param reasons why it is not UP, in words a person reads (empty when UP)
 * @param checkedAt when this was put together
 */
public record ComponentHealth(Status status, List<String> reasons, Instant checkedAt) {

    /** The four statuses; {@link #worst} of several is the most serious one. */
    public enum Status {
        UP, DEGRADED, SUSPENDED, DOWN;

        /** The more serious of two: DOWN over SUSPENDED over DEGRADED over UP. */
        public Status worst(Status other) {
            return other.ordinal() > ordinal() ? other : this;
        }
    }

    public ComponentHealth {
        reasons = List.copyOf(reasons);
    }

    public static ComponentHealth up(Instant at) {
        return new ComponentHealth(Status.UP, List.of(), at);
    }

    public static ComponentHealth of(Status s, String reason, Instant at) {
        return new ComponentHealth(s, reason == null || reason.isBlank() ? List.of() : List.of(reason), at);
    }

    /** The health a lifecycle state implies before anything else is known: RUNNING is UP, SUSPENDED is SUSPENDED, the rest DOWN with the state. */
    public static ComponentHealth ofState(LifecycleState s, String failure, Instant at) {
        return switch (s) {
            case RUNNING -> up(at);
            case SUSPENDED -> of(Status.SUSPENDED, "suspended by an administrator", at);
            case FAILED -> of(Status.DOWN, failure == null || failure.isBlank() ? "failed to start" : failure, at);
            default -> of(Status.DOWN, "not running (" + s.word() + ")", at);
        };
    }

    /** This health with another's folded in: the worse status, both lists of reasons ({@code prefix} before each of the other's). */
    public ComponentHealth with(ComponentHealth other, String prefix) {
        if (other.status == Status.UP) {
            return this;
        }
        List<String> r = new ArrayList<>(reasons);
        other.reasons.forEach(x -> r.add(prefix == null || prefix.isBlank() ? x : prefix + ": " + x));
        Status s = other.status == Status.SUSPENDED && status == Status.UP ? Status.DEGRADED : status.worst(other.status);
        return new ComponentHealth(s, r, checkedAt);
    }
}
