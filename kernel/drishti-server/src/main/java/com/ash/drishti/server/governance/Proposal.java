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
package com.ash.drishti.server.governance;

import java.time.Instant;

/**
 * A Sutra proposed from Studio, and what became of it.
 *
 * @param id {@code P-000042}
 * @param name the Sutra's name
 * @param version its version
 * @param text the proposed Sutra (Markdown or YAML)
 * @param baseText the live text of {@code name@version} when proposed (empty for a new version); approval refuses
 *     when the live text has changed since, so no one approves against a stale comparison
 * @param note the author's note
 * @param author who proposed it
 * @param createdAt when
 * @param status {@code pending}, {@code approved}, {@code rejected} or {@code withdrawn}
 * @param reviewer who decided
 * @param reviewedAt when
 * @param comment the reviewer's comment
 * @param evidence what the author attached from the workbench (check matrix, sample names, notes, the design's id), or null
 */
public record Proposal(String id, String name, int version, String text, String baseText, String note, String author, Instant createdAt,
        String status, String reviewer, Instant reviewedAt, String comment, com.fasterxml.jackson.databind.JsonNode evidence) {

    public static final String PENDING = "pending";
    public static final String APPROVED = "approved";
    public static final String REJECTED = "rejected";
    public static final String WITHDRAWN = "withdrawn";

    public boolean pending() {
        return PENDING.equals(status);
    }

    Proposal decided(String newStatus, String by, Instant at, String why) {
        return new Proposal(id, name, version, text, baseText, note, author, createdAt, newStatus, by, at, why, evidence);
    }
}
