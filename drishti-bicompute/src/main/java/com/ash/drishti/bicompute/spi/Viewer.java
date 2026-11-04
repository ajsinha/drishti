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
package com.ash.drishti.bicompute.spi;

/**
 * Who asks (PIVOT_ENGINE.md section 19): the module knows only the user's name; {@code principal} is the host's own identity (the server's
 * {@code Principal}), passed through to its providers so they apply the person's entitlements and masks before any aggregation.
 *
 * @param user the user's name (audit, per-person limits, saved views)
 * @param principal the host's identity of the user, or null in tests
 */
public record Viewer(String user, Object principal) {

    public Viewer {
        user = user == null ? "" : user;
    }

    /** Someone with no identity of the host (tests, the harness). */
    public static Viewer of(String user) {
        return new Viewer(user, null);
    }
}
