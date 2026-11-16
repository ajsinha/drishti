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
package com.ash.drishti.server.collab.compliance;

import com.ash.drishti.identity.collab.Comment;
import com.ash.drishti.identity.collab.CommentThread;
import com.ash.drishti.identity.collab.Hold;
import com.ash.drishti.identity.collab.Recipient;
import com.ash.drishti.identity.collab.Share;
import com.ash.drishti.identity.collab.ShareStore;
import com.ash.drishti.identity.collab.ThreadStore;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Which active hold, if any, covers a thread or a share. The people of an item are looked up only when a {@code user} hold exists. */
final class HoldMatcher {

    private final List<Hold> holds;
    private final boolean needPeople;
    private final ThreadStore threads;
    private final ShareStore shares;

    HoldMatcher(List<Hold> active, ThreadStore threads, ShareStore shares) {
        this.holds = List.copyOf(active);
        this.needPeople = holds.stream().anyMatch(h -> Hold.USER.equals(h.scope()));
        this.threads = threads;
        this.shares = shares;
    }

    boolean isEmpty() {
        return holds.isEmpty();
    }

    /** The first active hold covering the thread, or null. */
    Hold covering(CommentThread t) {
        Set<String> people = needPeople ? peopleOf(t) : Set.of();
        for (Hold h : holds) {
            if (h.coversThread(t, people)) {
                return h;
            }
        }
        return null;
    }

    /** The first active hold covering the share, or null. */
    Hold covering(Share s) {
        Set<String> people = needPeople ? peopleOf(s) : Set.of();
        for (Hold h : holds) {
            if (h.coversShare(s, people)) {
                return h;
            }
        }
        return null;
    }

    private Set<String> peopleOf(CommentThread t) {
        Set<String> out = new HashSet<>();
        out.add(t.createdBy());
        for (Comment c : threads.comments(t.id())) {
            out.add(c.author());
        }
        return out;
    }

    private Set<String> peopleOf(Share s) {
        Set<String> out = new HashSet<>();
        out.add(s.sender());
        for (Recipient r : shares.recipients(s.id())) {
            out.add(r.username());
        }
        return out;
    }
}
