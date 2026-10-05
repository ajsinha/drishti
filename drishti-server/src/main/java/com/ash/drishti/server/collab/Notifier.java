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
package com.ash.drishti.server.collab;

import com.ash.drishti.identity.collab.Comment;
import com.ash.drishti.identity.collab.CommentThread;
import com.ash.drishti.identity.collab.Notice;
import com.ash.drishti.identity.collab.Recipient;
import com.ash.drishti.identity.collab.Share;
import java.util.List;

/**
 * A channel a share is delivered through. {@code ShareService} hands the audience to every {@code Notifier} bean, so a new channel
 * (email in step 4, webhooks in phase 2) adds a bean and touches no service. Delivery runs inside the share's transaction: what a
 * notifier writes commits with the share, or not at all.
 */
public interface Notifier {

    /** The share and the recipients it reached (state {@code notified}). */
    record ShareEvent(Share share, List<Recipient> reached) {}

    /**
     * A comment's audience: {@code type} is {@code mention} (addressed: cannot be muted) or {@code reply} (followers); {@code shareId} is set
     * for a reply to a share. {@code recipients} are usernames already checked with {@code mayReach} and never the author.
     */
    record CommentEvent(String type, CommentThread thread, Comment comment, String shareId, List<String> recipients) {}

    /**
     * A comment as it was posted, whoever is in its audience: {@code mentions} says it addressed someone. For channels that carry
     * the activity itself (bridges); never called for a reply inside a share's private thread. Runs in the comment's transaction.
     */
    record CommentPosted(CommentThread thread, Comment comment, boolean mentions) {}

    /** The channel's name: {@code in-app}, {@code email}, {@code bridge}. A share may ask for {@code email} only when a notifier of that channel is on. */
    String channel();

    /** True when the channel can deliver now (email: configured and enabled). */
    default boolean available() {
        return true;
    }

    /** Delivers; returns the inbox rows written, which the caller pushes to open streams after the commit. */
    List<Notice> onShare(ShareEvent event);

    /** Delivers a mention or reply notice; channels that do not carry comments leave this alone. Returns the inbox rows written. */
    default List<Notice> onComment(CommentEvent event) {
        return List.of();
    }

    /** Called once for every comment posted to a discussion thread, with or without an audience; channels that route by event use it. */
    default void onCommentPosted(CommentPosted event) {}
}
