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
 * One person a share was addressed to, after expansion of roles.
 *
 * @param addressed as typed: {@code user:ravi} or {@code role:risk}
 * @param username the person
 * @param state {@code notified}, {@code no-access}, {@code no-pack}, {@code disabled} or {@code over-limit}
 * @param openedAt when the recipient first opened the link, or null
 */
public record Recipient(String addressed, String username, String state, Instant openedAt) {

    public static final String NOTIFIED = "notified";
    public static final String NO_ACCESS = "no-access";
    public static final String NO_PACK = "no-pack";
    public static final String DISABLED = "disabled";
    public static final String OVER_LIMIT = "over-limit";

    public Recipient withOpened(Instant at) {
        return new Recipient(addressed, username, state, at);
    }
}
