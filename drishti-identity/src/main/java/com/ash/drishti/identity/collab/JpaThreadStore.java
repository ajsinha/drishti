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

import com.ash.drishti.identity.db.IdentityRepositories;
import com.ash.drishti.identity.db.ThreadEntities;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;

/** Comment threads in the identity database (SQLite or PostgreSQL). */
public final class JpaThreadStore implements ThreadStore {

    private final IdentityRepositories.Threads threads;
    private final IdentityRepositories.Comments comments;
    private final IdentityRepositories.Revisions revisions;
    private final IdentityRepositories.Mentions mentions;
    private final IdentityRepositories.Follows follows;
    private final IdentityRepositories.NoteLinks links;
    private final TransactionTemplate tx;

    @SuppressWarnings("java:S107")
    public JpaThreadStore(IdentityRepositories.Threads threads, IdentityRepositories.Comments comments, IdentityRepositories.Revisions revisions,
            IdentityRepositories.Mentions mentions, IdentityRepositories.Follows follows, IdentityRepositories.NoteLinks links,
            TransactionTemplate tx) {
        this.threads = threads;
        this.comments = comments;
        this.revisions = revisions;
        this.mentions = mentions;
        this.follows = follows;
        this.links = links;
        this.tx = tx;
    }

    @Override
    public void saveThread(CommentThread t) {
        tx.executeWithoutResult(s -> {
            ThreadEntities.Thread e = new ThreadEntities.Thread();
            e.id = t.id();
            e.kind = t.kind();
            e.entityId = t.entityId();
            e.anchor = t.anchor();
            e.panelId = t.panelId();
            e.path = t.path();
            e.gateKind = t.gateKind();
            e.anchorLabel = t.anchorLabel();
            e.state = t.state();
            e.createdBy = t.createdBy();
            e.createdAt = t.createdAt();
            e.lastAt = t.lastAt();
            e.comments = t.comments();
            threads.save(e);
        });
    }

    @Override
    public Revision append(Comment c, Revision draft, Collection<String> targets) {
        return tx.execute(s -> {
            List<ThreadEntities.Revision> last = revisions.latest(c.threadId(), PageRequest.of(0, 1));
            String prev = last.isEmpty() ? HashChain.genesis(c.threadId()) : last.get(0).hash;
            Instant at = HashChain.after(draft.at(), last.isEmpty() ? null : last.get(0).at);
            Revision sealed = HashChain.seal(prev, new Revision(draft.commentId(), draft.revision(), at, draft.actor(), draft.action(),
                    draft.body(), draft.reason(), "", ""));
            comments.save(toEntity(c));
            ThreadEntities.Revision r = new ThreadEntities.Revision();
            r.key = new ThreadEntities.RevisionKey(sealed.commentId(), sealed.revision());
            r.at = sealed.at();
            r.actor = sealed.actor();
            r.action = sealed.action();
            r.body = sealed.body();
            r.reason = sealed.reason();
            r.prevHash = sealed.prevHash();
            r.hash = sealed.hash();
            revisions.save(r);
            Set<String> have = new HashSet<>();
            mentions.findByKeyCommentId(c.id()).forEach(m -> have.add(m.key.target));
            for (String target : targets) {
                if (have.add(target)) {
                    ThreadEntities.Mention m = new ThreadEntities.Mention();
                    m.key = new ThreadEntities.MentionKey(c.id(), target);
                    m.createdAt = at;
                    mentions.save(m);
                }
            }
            return sealed;
        });
    }

    @Override
    public Optional<CommentThread> thread(String id) {
        return tx.execute(s -> threads.findById(id).map(JpaThreadStore::toThread));
    }

    @Override
    public List<CommentThread> threads(String kind, String entityId) {
        return tx.execute(s -> threads.findByKindAndEntityIdOrderByLastAtDesc(kind, entityId).stream().map(JpaThreadStore::toThread).toList());
    }

    @Override
    public List<CommentThread> page(String afterId, int limit) {
        return tx.execute(s -> threads.findByIdGreaterThanOrderByIdAsc(afterId == null ? "" : afterId, PageRequest.of(0, Math.max(1, limit)))
                .stream().map(JpaThreadStore::toThread).toList());
    }

    @Override
    public void deleteThread(String id) {
        tx.executeWithoutResult(s -> {
            List<String> ids = comments.findByThreadIdOrderByCreatedAtAscIdAsc(id).stream().map(c -> c.id).toList();
            if (!ids.isEmpty()) {
                revisions.deleteByKeyCommentIdIn(ids);
                mentions.deleteByKeyCommentIdIn(ids);
                links.deleteByCommentIdIn(ids);
            }
            comments.deleteByThreadId(id);
            follows.deleteByKeyThreadId(id);
            threads.deleteById(id);
        });
    }

    @Override
    public Optional<Comment> comment(String id) {
        return tx.execute(s -> comments.findById(id).map(JpaThreadStore::toComment));
    }

    @Override
    public List<Comment> comments(String threadId) {
        return tx.execute(s -> comments.findByThreadIdOrderByCreatedAtAscIdAsc(threadId).stream().map(JpaThreadStore::toComment).toList());
    }

    @Override
    public List<Revision> revisions(String commentId) {
        return tx.execute(s -> revisions.findByKeyCommentIdOrderByKeyRevisionAsc(commentId).stream().map(JpaThreadStore::toRevision).toList());
    }

    @Override
    public List<Revision> chain(String threadId) {
        return tx.execute(s -> revisions.chain(threadId).stream().map(JpaThreadStore::toRevision).toList());
    }

    @Override
    public void follow(Follow f) {
        tx.executeWithoutResult(s -> {
            ThreadEntities.Follow e = new ThreadEntities.Follow();
            e.key = new ThreadEntities.FollowKey(f.threadId(), f.username());
            e.muted = f.muted();
            e.since = f.since();
            follows.save(e);
        });
    }

    @Override
    public boolean unfollow(String threadId, String username) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            ThreadEntities.FollowKey k = new ThreadEntities.FollowKey(threadId, username);
            if (!follows.existsById(k)) {
                return false;
            }
            follows.deleteById(k);
            return true;
        }));
    }

    @Override
    public Optional<Follow> following(String threadId, String username) {
        return tx.execute(s -> follows.findById(new ThreadEntities.FollowKey(threadId, username)).map(JpaThreadStore::toFollow));
    }

    @Override
    public List<Follow> followers(String threadId) {
        return tx.execute(s -> follows.findByKeyThreadId(threadId).stream().map(JpaThreadStore::toFollow).toList());
    }

    @Override
    public List<Mention> mentionsOf(Collection<String> targets, int limit, String before) {
        if (targets.isEmpty()) {
            return List.of();
        }
        PageRequest page = PageRequest.of(0, Math.max(1, limit));
        return tx.execute(s -> (before == null || before.isBlank() ? mentions.findByKeyTargetInOrderByKeyCommentIdDesc(targets, page)
                : mentions.findByKeyTargetInAndKeyCommentIdLessThanOrderByKeyCommentIdDesc(targets, before, page)).stream()
                .map(JpaThreadStore::toMention).toList());
    }

    @Override
    public List<Mention> mentions(String commentId) {
        return tx.execute(s -> mentions.findByKeyCommentId(commentId).stream().map(JpaThreadStore::toMention).toList());
    }

    @Override
    public void linkNote(long noteId, String commentId) {
        tx.executeWithoutResult(s -> {
            ThreadEntities.NoteLink e = new ThreadEntities.NoteLink();
            e.noteId = noteId;
            e.commentId = commentId;
            links.save(e);
        });
    }

    @Override
    public Optional<String> commentOfNote(long noteId) {
        return tx.execute(s -> links.findById(noteId).map(l -> l.commentId));
    }

    @Override
    public Optional<Long> noteOfComment(String commentId) {
        return tx.execute(s -> links.findByCommentId(commentId).map(l -> l.noteId));
    }

    @Override
    public Set<Long> linkedNotes() {
        return tx.execute(s -> {
            Set<Long> out = new HashSet<>();
            links.findAll().forEach(l -> out.add(l.noteId));
            return out;
        });
    }

    private static CommentThread toThread(ThreadEntities.Thread e) {
        return new CommentThread(e.id, e.kind, e.entityId, e.anchor, e.panelId, e.path, e.gateKind, e.anchorLabel, e.state, e.createdBy,
                e.createdAt, e.lastAt, e.comments);
    }

    private static ThreadEntities.Comment toEntity(Comment c) {
        ThreadEntities.Comment e = new ThreadEntities.Comment();
        e.id = c.id();
        e.threadId = c.threadId();
        e.author = c.author();
        e.createdAt = c.createdAt();
        e.editedAt = c.editedAt();
        e.revision = c.revision();
        Pin p = c.pin();
        e.pinDate = p == null ? null : p.businessDate();
        e.pinLive = p != null && p.live();
        e.pinKnownAt = p == null ? null : p.knownAt();
        e.pinGeneration = p == null ? 0 : p.generation();
        e.pinSource = p == null ? null : p.source();
        e.body = c.body();
        e.maskedSpans = Share.spansToJson(c.maskedSpans());
        e.state = c.state();
        e.stateReason = c.stateReason();
        return e;
    }

    private static Comment toComment(ThreadEntities.Comment e) {
        return new Comment(e.id, e.threadId, e.author, e.createdAt, e.editedAt, e.revision,
                new Pin(e.pinDate, e.pinLive, e.pinKnownAt, e.pinGeneration, e.pinSource), e.body, Share.spansFromJson(e.maskedSpans), e.state,
                e.stateReason);
    }

    private static Revision toRevision(ThreadEntities.Revision e) {
        return new Revision(e.key.commentId, e.key.revision, e.at, e.actor, e.action, e.body, e.reason, e.prevHash, e.hash);
    }

    private static Follow toFollow(ThreadEntities.Follow e) {
        return new Follow(e.key.threadId, e.key.username, e.muted, e.since);
    }

    private static Mention toMention(ThreadEntities.Mention e) {
        return new Mention(e.key.commentId, e.key.target, e.createdAt);
    }
}
