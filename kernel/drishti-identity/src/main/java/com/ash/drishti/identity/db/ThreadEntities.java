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
package com.ash.drishti.identity.db;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/** The rows of comment threads ({@code drishti_thread}, {@code _comment}, {@code _comment_revision}, {@code _mention}, {@code _follow}, {@code _note_link}). */
public final class ThreadEntities {

    private ThreadEntities() {}

    @Entity(name = "CollabThread")
    @Table(name = "drishti_thread")
    public static class Thread {
        @Id
        @Column(name = "id")
        public String id;

        @Column(name = "kind", nullable = false)
        public String kind;

        @Column(name = "entity_id", nullable = false)
        public String entityId;

        @Column(name = "anchor", nullable = false)
        public String anchor;

        @Column(name = "panel_id")
        public String panelId;

        @Column(name = "path")
        public String path;

        @Column(name = "gate_kind")
        public String gateKind;

        @Column(name = "anchor_label", nullable = false)
        public String anchorLabel;

        @Column(name = "state", nullable = false)
        public String state;

        @Column(name = "created_by", nullable = false)
        public String createdBy;

        @Column(name = "created_at", nullable = false)
        public Instant createdAt;

        @Column(name = "last_at", nullable = false)
        public Instant lastAt;

        @Column(name = "comments", nullable = false)
        public int comments;
    }

    @Entity(name = "CollabComment")
    @Table(name = "drishti_comment")
    public static class Comment {
        @Id
        @Column(name = "id")
        public String id;

        @Column(name = "thread_id", nullable = false)
        public String threadId;

        @Column(name = "author", nullable = false)
        public String author;

        @Column(name = "created_at", nullable = false)
        public Instant createdAt;

        @Column(name = "edited_at")
        public Instant editedAt;

        @Column(name = "revision", nullable = false)
        public int revision;

        @Column(name = "pin_date")
        public LocalDate pinDate;

        @Column(name = "pin_live", nullable = false)
        public boolean pinLive;

        @Column(name = "pin_known_at")
        public Instant pinKnownAt;

        @Column(name = "pin_generation", nullable = false)
        public long pinGeneration;

        @Column(name = "pin_source")
        public String pinSource;

        @Column(name = "body", nullable = false)
        public String body;

        @Column(name = "masked_spans", nullable = false)
        public String maskedSpans;

        @Column(name = "state", nullable = false)
        public String state;

        @Column(name = "state_reason")
        public String stateReason;
    }

    /** Two-part keys share one shape: a first and a second column. */
    @Embeddable
    public static class RevisionKey implements Serializable {
        @Column(name = "comment_id")
        public String commentId;

        @Column(name = "revision")
        public int revision;

        public RevisionKey() {}

        public RevisionKey(String commentId, int revision) {
            this.commentId = commentId;
            this.revision = revision;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof RevisionKey k && Objects.equals(commentId, k.commentId) && revision == k.revision;
        }

        @Override
        public int hashCode() {
            return Objects.hash(commentId, revision);
        }
    }

    @Entity(name = "CollabRevision")
    @Table(name = "drishti_comment_revision")
    public static class Revision {
        @EmbeddedId
        public RevisionKey key;

        @Column(name = "at", nullable = false)
        public Instant at;

        @Column(name = "actor", nullable = false)
        public String actor;

        @Column(name = "action", nullable = false)
        public String action;

        @Column(name = "body")
        public String body;

        @Column(name = "reason")
        public String reason;

        @Column(name = "prev_hash", nullable = false)
        public String prevHash;

        @Column(name = "hash", nullable = false)
        public String hash;
    }

    @Embeddable
    public static class MentionKey implements Serializable {
        @Column(name = "comment_id")
        public String commentId;

        @Column(name = "target")
        public String target;

        public MentionKey() {}

        public MentionKey(String commentId, String target) {
            this.commentId = commentId;
            this.target = target;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof MentionKey k && Objects.equals(commentId, k.commentId) && Objects.equals(target, k.target);
        }

        @Override
        public int hashCode() {
            return Objects.hash(commentId, target);
        }
    }

    @Entity(name = "CollabMention")
    @Table(name = "drishti_mention")
    public static class Mention {
        @EmbeddedId
        public MentionKey key;

        @Column(name = "created_at", nullable = false)
        public Instant createdAt;
    }

    @Embeddable
    public static class FollowKey implements Serializable {
        @Column(name = "thread_id")
        public String threadId;

        @Column(name = "username")
        public String username;

        public FollowKey() {}

        public FollowKey(String threadId, String username) {
            this.threadId = threadId;
            this.username = username;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof FollowKey k && Objects.equals(threadId, k.threadId) && Objects.equals(username, k.username);
        }

        @Override
        public int hashCode() {
            return Objects.hash(threadId, username);
        }
    }

    @Entity(name = "CollabFollow")
    @Table(name = "drishti_follow")
    public static class Follow {
        @EmbeddedId
        public FollowKey key;

        @Column(name = "muted", nullable = false)
        public boolean muted;

        @Column(name = "since", nullable = false)
        public Instant since;
    }

    @Entity(name = "CollabNoteLink")
    @Table(name = "drishti_note_link")
    public static class NoteLink {
        @Id
        @Column(name = "note_id")
        public long noteId;

        @Column(name = "comment_id", nullable = false)
        public String commentId;
    }
}
