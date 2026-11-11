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

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Where comment threads are kept: the thread, its comments (current state), the immutable revisions, mentions, followers, and the
 * link from a legacy note to the comment it became. A JPA and a file implementation exist ({@code drishti.collab.store}); one
 * contract runs against both. Callers serialise a write with {@link CollabTx}.
 */
public interface ThreadStore {

    /** Inserts or updates the thread row. */
    void saveThread(CommentThread thread);

    /**
     * Inserts or updates the comment's current state and appends the revision, chained after the thread's last one (its time is moved
     * forward if needed, see {@link HashChain#after}); {@code mentions} are added. Returns the revision as stored.
     */
    Revision append(Comment comment, Revision draft, Collection<String> mentions);

    Optional<CommentThread> thread(String id);

    /** The entity's threads, most recent activity first. */
    List<CommentThread> threads(String kind, String entityId);

    /** Threads in id (creation) order after {@code afterId} (null = from the start), at most {@code limit}: how a scan walks them in bounded memory. */
    List<CommentThread> page(String afterId, int limit);

    /**
     * Removes the whole thread: its comments, revisions, mentions, followers and note links. Only retention and the administrator's
     * permanent removal call this, and neither when a legal hold covers it.
     */
    void deleteThread(String id);

    Optional<Comment> comment(String id);

    /** A thread's comments, oldest first. */
    List<Comment> comments(String threadId);

    /** A comment's steps, oldest first. */
    List<Revision> revisions(String commentId);

    /** Every step of the thread, in chain order (for verification). */
    List<Revision> chain(String threadId);

    void follow(Follow follow);

    boolean unfollow(String threadId, String username);

    Optional<Follow> following(String threadId, String username);

    List<Follow> followers(String threadId);

    /** Mentions of any of {@code targets}, newest first, strictly older than {@code beforeCommentId} when given. */
    List<Mention> mentionsOf(Collection<String> targets, int limit, String beforeCommentId);

    List<Mention> mentions(String commentId);

    /** Records that legacy note {@code noteId} is (now) {@code commentId}. */
    void linkNote(long noteId, String commentId);

    Optional<String> commentOfNote(long noteId);

    Optional<Long> noteOfComment(String commentId);

    /** Notes already linked (the import skips them). */
    Set<Long> linkedNotes();
}
