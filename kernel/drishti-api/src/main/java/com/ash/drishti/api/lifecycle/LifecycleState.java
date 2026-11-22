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

import java.util.Locale;

/**
 * Where a module, pack or connector stands (docs/architecture/LIFECYCLE.md section 2; the names are the engine specification's, section 7.1).
 *
 * <table>
 *   <caption>The states and what may follow</caption>
 *   <tr><th>State</th><th>Entered by</th><th>Allowed next</th></tr>
 *   <tr><td>{@code NEW}</td><td>construction</td><td>init; shutdown</td></tr>
 *   <tr><td>{@code INITIALIZED}</td><td>init returned: read and validated, nothing served, no I/O</td><td>start; shutdown</td></tr>
 *   <tr><td>{@code RUNNING}</td><td>start or resume returned: serving</td><td>suspend; shutdown</td></tr>
 *   <tr><td>{@code SUSPENDED}</td><td>suspend returned: no new request, state and caches kept</td><td>resume; shutdown</td></tr>
 *   <tr><td>{@code FAILED}</td><td>init or start threw</td><td>shutdown</td></tr>
 *   <tr><td>{@code SHUTDOWN}</td><td>shutdown returned: nothing registered</td><td>none; a further shutdown is harmless</td></tr>
 * </table>
 */
public enum LifecycleState {
    NEW, INITIALIZED, RUNNING, SUSPENDED, FAILED, SHUTDOWN;

    /** The state {@code t} leads to from this one, or null when the table does not allow it. A shutdown of a shut-down part stays. */
    public LifecycleState after(Transition t) {
        return switch (t) {
            case INIT -> this == NEW ? INITIALIZED : null;
            case START -> this == INITIALIZED ? RUNNING : null;
            case SUSPEND -> this == RUNNING ? SUSPENDED : null;
            case RESUME -> this == SUSPENDED ? RUNNING : null;
            case SHUTDOWN -> SHUTDOWN;
        };
    }

    /** True when {@code t} is allowed from this state. */
    public boolean allows(Transition t) {
        return after(t) != null;
    }

    /** True while the part answers requests. */
    public boolean serving() {
        return this == RUNNING;
    }

    /** The lower-case word Admin and the digest show ({@code running}, {@code suspended}). */
    public String word() {
        return name().toLowerCase(Locale.ROOT);
    }
}
