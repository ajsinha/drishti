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
package com.ash.drishti.api.write;

/**
 * What the source said to a command (WRITE_BACK.md section 5.3).
 *
 * @param status {@link Status#APPLIED} (the guard held and the write was made), {@link Status#ALREADY} (the idempotency clause found
 *     this change id: a resend, nothing written), {@link Status#CONFLICTED} (the guard failed: the record moved at the source),
 *     {@link Status#REFUSED} (the source said no, with its reason) or {@link Status#SENT} (sent; the confirmation comes later: an ack
 *     topic or the next load)
 * @param reason the source's words, or empty
 */
public record WriteResult(Status status, String reason) {

    public enum Status {
        APPLIED, ALREADY, CONFLICTED, REFUSED, SENT
    }

    public WriteResult {
        reason = reason == null ? "" : reason;
    }

    public static WriteResult applied() {
        return new WriteResult(Status.APPLIED, "");
    }

    public static WriteResult already() {
        return new WriteResult(Status.ALREADY, "the source already holds this change");
    }

    public static WriteResult conflicted(String why) {
        return new WriteResult(Status.CONFLICTED, why);
    }

    public static WriteResult refused(String why) {
        return new WriteResult(Status.REFUSED, why);
    }
}
