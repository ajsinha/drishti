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
package com.ash.drishti.engine.command;

import com.ash.drishti.api.EntityHit;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** The entities each user opened most recently, newest first. Bounded per user and in user count. */
public final class RecentEntities {

    private final int perUser;
    private final Cache<String, Deque<EntityHit>> byUser = Caffeine.newBuilder().maximumSize(10_000).build();

    public RecentEntities(int perUser) {
        this.perUser = perUser;
    }

    public void touch(String user, EntityHit hit) {
        Deque<EntityHit> d = byUser.get(user, u -> new ArrayDeque<>());
        synchronized (d) {
            d.removeIf(h -> h.ref().equals(hit.ref()));
            d.addFirst(hit);
            while (d.size() > perUser) {
                d.removeLast();
            }
        }
    }

    public List<EntityHit> of(String user) {
        Deque<EntityHit> d = byUser.getIfPresent(user);
        if (d == null) {
            return List.of();
        }
        synchronized (d) {
            return List.copyOf(d);
        }
    }
}
