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
 * One immutable step in a comment's life; the record that survives edits and moderation. {@code body} is the text after the action
 * (null for actions that do not change it). {@code prevHash} and {@code hash} chain the steps of a thread (see {@link HashChain}).
 */
public record Revision(String commentId, int revision, Instant at, String actor, String action, String body, String reason, String prevHash,
        String hash) {

    public static final String CREATED = "created";
    public static final String EDITED = "edited";
    public static final String RETRACTED = "retracted";
    public static final String HIDDEN = "hidden";
    public static final String UNHIDDEN = "unhidden";

    /** A step not yet chained (the store seals it). */
    public static Revision draft(String commentId, int revision, Instant at, String actor, String action, String body, String reason) {
        return new Revision(commentId, revision, at, actor, action, body, reason, "", "");
    }
}
