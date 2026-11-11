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
package com.ash.drishti.identity;

import java.time.Instant;
import java.util.List;

/** The audit trail: who did what, to whom, and when. Kept in the identity database ({@link JpaAuditLog}). */
public interface AuditLog {

    /**
     * @param at when
     * @param actor who did it ({@code system} for the seeder)
     * @param action what ({@code login}, {@code login-failed}, {@code user-created}, ...)
     * @param subject the user (or role, Sutra, cache) it concerns
     * @param detail extra context, never a password
     */
    record Event(Instant at, String actor, String action, String subject, String detail) {}

    void record(String actor, String action, String subject, String detail);

    /** Newest first, at most {@code limit}, optionally only events about or by {@code subject}. */
    List<Event> recent(int limit, String subject);
}
