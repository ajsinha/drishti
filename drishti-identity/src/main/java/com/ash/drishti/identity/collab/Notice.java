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
 * One inbox row: a pointer to a share or comment, never its content or any data value. The text a reader sees is rendered
 * when read, for the reader's current rights.
 *
 * @param seq monotonic row number (the SSE event id); 0 before it is stored
 * @param username the recipient
 * @param type {@code share}, {@code mention}, {@code reply}, {@code thread} or {@code moderation}
 * @param kind entity kind
 * @param entityId entity id
 * @param panelId a panel, or null
 * @param shareId the share, or null
 * @param threadId the thread, or null
 * @param commentId the comment, or null
 * @param actor who caused it
 * @param readAt when it was read, or null
 */
public record Notice(long seq, String username, Instant at, String type, String kind, String entityId, String panelId, String shareId,
        String threadId, String commentId, String actor, Instant readAt) {

    public static final String SHARE = "share";

    public Notice withSeq(long s) {
        return new Notice(s, username, at, type, kind, entityId, panelId, shareId, threadId, commentId, actor, readAt);
    }

    public Notice withRead(Instant when) {
        return new Notice(seq, username, at, type, kind, entityId, panelId, shareId, threadId, commentId, actor, when);
    }
}
