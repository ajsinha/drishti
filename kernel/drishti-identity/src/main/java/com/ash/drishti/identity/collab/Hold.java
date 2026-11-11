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
import java.util.Collection;

/**
 * A legal hold: while it is active, retention and permanent removal skip what it covers (ordinary use goes on). The scope says what
 * it covers ({@code entity}, {@code kind}, {@code user}, {@code thread} or {@code all}); an optional date range narrows it further
 * (a thread is covered when its activity overlaps the range, a share when it was sent inside it).
 *
 * @param id assigned when placed (0 before)
 * @param scope {@code entity} (kind and entity id), {@code kind}, {@code user} (everything the person wrote, sent or received),
 *     {@code thread} or {@code all}
 * @param from start of the date range (inclusive), or null
 * @param to end of the date range (inclusive), or null
 * @param releasedBy who released it, or null while active
 */
public record Hold(long id, String scope, String kind, String entityId, String username, String threadId, Instant from, Instant to,
        String reason, String placedBy, Instant placedAt, String releasedBy, Instant releasedAt) {

    public static final String ENTITY = "entity";
    public static final String KIND = "kind";
    public static final String USER = "user";
    public static final String THREAD = "thread";
    public static final String ALL = "all";

    public boolean active() {
        return releasedAt == null;
    }

    public Hold released(String by, Instant at) {
        return new Hold(id, scope, kind, entityId, username, threadId, from, to, reason, placedBy, placedAt, by, at);
    }

    public Hold withId(long newId) {
        return new Hold(newId, scope, kind, entityId, username, threadId, from, to, reason, placedBy, placedAt, releasedBy, releasedAt);
    }

    /**
     * Whether this (active) hold covers the thread. {@code people} are everyone who wrote in it (the thread's creator included);
     * the caller supplies them only for a {@code user} hold.
     */
    public boolean coversThread(CommentThread t, Collection<String> people) {
        if (!active() || !scopeMatches(t.kind(), t.entityId(), t.id(), people)) {
            return false;
        }
        boolean beforeRange = to != null && t.createdAt().isAfter(to);
        boolean afterRange = from != null && t.lastAt().isBefore(from);
        return !beforeRange && !afterRange;
    }

    /** Whether this (active) hold covers the share; {@code people} are its sender and recipients (for a {@code user} hold). */
    public boolean coversShare(Share s, Collection<String> people) {
        if (!active() || !scopeMatches(s.kind(), s.entityId(), s.threadId(), people)) {
            return false;
        }
        return (from == null || !s.createdAt().isBefore(from)) && (to == null || !s.createdAt().isAfter(to));
    }

    private boolean scopeMatches(String k, String e, String thread, Collection<String> people) {
        return switch (scope) {
            case ALL -> true;
            case KIND -> kind != null && kind.equals(k);
            case ENTITY -> kind != null && kind.equals(k) && entityId != null && entityId.equals(e);
            case THREAD -> threadId != null && threadId.equals(thread);
            case USER -> username != null && people != null && people.contains(username);
            default -> false;
        };
    }
}
