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
package com.ash.drishti.compute.approval;

import java.time.Instant;
import java.util.List;

/**
 * A request to approve what a connector or pool requires and is not approved yet (docs/architecture/PYTHON_COMPUTE.md section 6): a row of
 * {@code drishti_compute_request}. Its items become rows of {@code drishti_compute_approval} when it is approved.
 *
 * @param id {@code cr-...}
 * @param subject what asks ({@code connector:<name>} or {@code pool:<name>})
 * @param fingerprint the hash of its items: the same unapproved set is one request
 * @param items what is to be approved (only what is not approved already)
 * @param state {@code pending}, {@code approved}, {@code rejected}, {@code revoked} or {@code superseded}
 * @param requestedBy who asked (never the approver: four eyes)
 * @param requestedAt when
 * @param decidedBy who approved or rejected it
 * @param decidedAt when
 * @param reason the decision's reason
 * @param revokedBy who revoked the approval
 * @param revokedAt when
 * @param revokeReason why
 */
public record ApprovalRequest(String id, String subject, String fingerprint, List<ApprovalItem> items, String state, String requestedBy,
        Instant requestedAt, String decidedBy, Instant decidedAt, String reason, String revokedBy, Instant revokedAt, String revokeReason) {

    public static final String PENDING = "pending";
    public static final String APPROVED = "approved";
    public static final String REJECTED = "rejected";
    public static final String REVOKED = "revoked";
    public static final String SUPERSEDED = "superseded";

    public ApprovalRequest {
        items = List.copyOf(items);
    }

    public ApprovalRequest decided(String newState, String by, Instant at, String why) {
        return new ApprovalRequest(id, subject, fingerprint, items, newState, requestedBy, requestedAt, by, at, why, revokedBy, revokedAt, revokeReason);
    }

    public ApprovalRequest revoked(String by, Instant at, String why) {
        return new ApprovalRequest(id, subject, fingerprint, items, REVOKED, requestedBy, requestedAt, decidedBy, decidedAt, reason, by, at, why);
    }

    public ApprovalRequest superseded() {
        return new ApprovalRequest(id, subject, fingerprint, items, SUPERSEDED, requestedBy, requestedAt, decidedBy, decidedAt, reason, revokedBy,
                revokedAt, revokeReason);
    }
}
