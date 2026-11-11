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
import java.util.List;

/**
 * The current state of a comment. {@code revision} is the number of the latest revision (every action, not only edits, takes the
 * next number). The text is kept whatever the state; readers are shown it only while {@code live}.
 */
public record Comment(String id, String threadId, String author, Instant createdAt, Instant editedAt, int revision, Pin pin, String body,
        List<Share.Span> maskedSpans, String state, String stateReason) {

    public static final String LIVE = "live";
    public static final String RETRACTED = "retracted";
    public static final String HIDDEN = "hidden";

    public Comment {
        maskedSpans = maskedSpans == null ? List.of() : List.copyOf(maskedSpans);
    }

    public Comment edited(String text, List<Share.Span> spans, Instant at, int rev) {
        return new Comment(id, threadId, author, createdAt, at, rev, pin, text, spans, state, stateReason);
    }

    public Comment inState(String s, String reason, int rev) {
        return new Comment(id, threadId, author, createdAt, editedAt, rev, pin, body, maskedSpans, s, reason);
    }
}
