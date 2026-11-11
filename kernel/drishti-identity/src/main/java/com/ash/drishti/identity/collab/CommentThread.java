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
 * A comment thread on an entity: on the whole view ({@code entity}), on a panel ({@code panel}) or on a labelled field
 * ({@code field}). {@code gateKind} is the kind a panel's source names: readers must be able to open it too.
 */
public record CommentThread(String id, String kind, String entityId, String anchor, String panelId, String path, String gateKind,
        String anchorLabel, String state, String createdBy, Instant createdAt, Instant lastAt, int comments) {

    public static final String ENTITY = "entity";
    public static final String PANEL = "panel";
    public static final String FIELD = "field";
    /** Replies to a share live in a thread of this anchor: private to the share's parties, never listed with the discussion. */
    public static final String SHARE = "share";
    public static final String OPEN = "open";
    public static final String RESOLVED = "resolved";
    public static final String LOCKED = "locked";

    public CommentThread withState(String s) {
        return new CommentThread(id, kind, entityId, anchor, panelId, path, gateKind, anchorLabel, s, createdBy, createdAt, lastAt, comments);
    }

    public CommentThread withActivity(Instant at, int count) {
        return new CommentThread(id, kind, entityId, anchor, panelId, path, gateKind, anchorLabel, state, createdBy, createdAt, at, count);
    }
}
