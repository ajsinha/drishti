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
package com.ash.drishti.server.collab.thread;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.User;
import com.ash.drishti.identity.collab.CollabProperties;
import com.ash.drishti.identity.collab.CollabTx;
import com.ash.drishti.identity.collab.Comment;
import com.ash.drishti.identity.collab.CommentThread;
import com.ash.drishti.identity.collab.Follow;
import com.ash.drishti.identity.collab.Mention;
import com.ash.drishti.identity.collab.Notice;
import com.ash.drishti.identity.collab.Pin;
import com.ash.drishti.identity.collab.Revision;
import com.ash.drishti.identity.collab.Share;
import com.ash.drishti.identity.collab.ThreadStore;
import com.ash.drishti.identity.collab.Ulid;
import com.ash.drishti.server.collab.InboxHub;
import com.ash.drishti.server.collab.NoteText;
import com.ash.drishti.server.collab.Notifier;
import com.ash.drishti.server.collab.Principals;
import com.ash.drishti.server.collab.RateLimits;
import com.ash.drishti.server.collab.ShareService;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Comment threads on an entity, a panel or a field (COLLABORATION.md). What is stored is the author's words, a pin (business date,
 * generation) and the ranges that copy a masked field's value; what a reader gets is computed for that reader, when they look
 * ({@link CommentRenderer}). A thread is visible to whoever may open its kind (and a panel's gate kind). Comments are edited within the
 * edit window, retracted by their author, hidden by an administrator; every step is an immutable, hash-chained revision. A mention
 * reaches only people who {@code mayReach} the kind ({@link Audience}); the audience is handed to every {@link Notifier}, inside the
 * comment's transaction ({@link CollabTx}), and pushed to open streams after the commit.
 */
public final class ThreadService {

    /** The first comment of a new thread and where it is anchored. {@code label} names a panel or field for readers. */
    public record NewThread(String anchor, String panel, String path, String gateKind, String label, Long generation, String body) {}

    /** A reply. */
    public record NewComment(Long generation, String body) {}

    /** One comment as the reader sees it; {@code body} and {@code parts} are null when the reader may not see the text. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CommentView(String id, String threadId, String author, String authorName, Instant createdAt, Instant editedAt, int revision,
            Pin pin, String state, String stateReason, String body, List<CommentRenderer.Part> parts, boolean edited, boolean mine,
            boolean editable) {}

    /** A thread with its first page of comments. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ThreadView(String id, String kind, String entityId, String anchor, String panel, String path, String label, String state,
            String createdBy, String createdByName, Instant createdAt, Instant lastAt, int comments, boolean following, boolean muted,
            List<CommentView> items, boolean more) {}

    /** The answer to a post: where it went, and who was (not) told. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Posted(String threadId, CommentView comment, int notified, List<ShareService.Skipped> skipped, List<String> warnings) {}

    /** One step of a comment's history; {@code body} is null where the text is not for this reader. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RevisionView(int revision, Instant at, String actor, String action, String body, String reason) {}

    /** A comment that mentions the caller (or one of their roles). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MentionRow(String commentId, String threadId, String kind, String entityId, String panel, String author, String authorName,
            Instant at, String excerpt) {}

    /** What an inbox row shows for a comment notice: whether the reader may still open the entity, and the text for them. */
    public record NoticeText(boolean access, String excerpt) {}

    /** What is written before the transaction: the text, the pin, the scrub ranges and the warnings. */
    record Draft(String body, Pin pin, List<Share.Span> spans, List<String> warnings) {}

    private static final int EXCERPT = 140;
    private final ThreadStore store;
    private final CollabTx tx;
    private final CollabProperties props;
    private final Entitlements entitlements;
    private final Principals principals;
    private final SourceRouter router;
    private final CommentRenderer renderer;
    private final PinnedDocs pinned;
    private final Audience audience;
    private final List<Notifier> notifiers;
    private final InboxHub hub;
    private final RateLimits limits;
    private final AuditLog audit;
    private final Clock clock;
    private final List<Pattern> deny = new ArrayList<>();

    @SuppressWarnings("java:S107")
    public ThreadService(ThreadStore store, CollabTx tx, CollabProperties props, Entitlements entitlements, Principals principals,
            SourceRouter router, CommentRenderer renderer, PinnedDocs pinned, Audience audience, List<Notifier> notifiers, InboxHub hub,
            RateLimits limits, AuditLog audit, Clock clock) {
        this.store = store;
        this.tx = tx;
        this.props = props;
        this.entitlements = entitlements;
        this.principals = principals;
        this.router = router;
        this.renderer = renderer;
        this.pinned = pinned;
        this.audience = audience;
        this.notifiers = List.copyOf(notifiers);
        this.hub = hub;
        this.limits = limits;
        this.audit = audit;
        this.clock = clock;
        for (String p : props.text().denyPatterns()) {
            try {
                deny.add(Pattern.compile(p));
            } catch (PatternSyntaxException e) {
                throw new IllegalStateException("drishti.collab.text.deny-patterns: not a regular expression: " + p, e);
            }
        }
    }

    // ---- rights --------------------------------------------------------------------------------------------------------

    private void requireOn() {
        if (!props.enabled()) {
            throw new DrishtiException(ErrorCode.SHARING_OFF, "collaboration is switched off (drishti.collab.enabled)");
        }
    }

    private void requireCollaborate(Principal p) {
        requireOn();
        if (!entitlements.mayCollaborate(p)) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, p.user() + " may not comment: ask an administrator for a role with collaborate");
        }
    }

    /** True when the caller may open the thread's kind and (for a panel) the kind its source names; share threads are never listed. */
    private boolean visible(Principal p, CommentThread t) {
        return !CommentThread.SHARE.equals(t.anchor()) && entitlements.mayOpen(p, t.kind()) && (t.gateKind() == null || entitlements.mayOpen(p, t.gateKind()));
    }

    private CommentThread visibleThread(Principal p, String id) {
        requireOn();
        CommentThread t = store.thread(id).orElse(null);
        if (t == null || !visible(p, t)) {
            throw new DrishtiException(ErrorCode.THREAD_NOT_FOUND, "no thread '" + shorten(id) + "'");
        }
        return t;
    }

    /** The comment's thread, when the caller may see it; else the comment is not there for them (DRS-7006). */
    private CommentThread threadOf(Principal p, Comment c) {
        CommentThread t = store.thread(c.threadId()).orElse(null);
        if (t == null || !visible(p, t)) {
            throw new DrishtiException(ErrorCode.COMMENT_NOT_FOUND, "no comment '" + shorten(c.id()) + "'");
        }
        return t;
    }

    private Comment comment(String id) {
        return store.comment(id).orElseThrow(() -> new DrishtiException(ErrorCode.COMMENT_NOT_FOUND, "no comment '" + shorten(id) + "'"));
    }

    private static String shorten(String s) {
        return s == null ? "" : s.length() > 40 ? s.substring(0, 40) : s;
    }

    private boolean moderator(Principal p) {
        return entitlements.isAdmin(p) || entitlements.mayCompliance(p);
    }

    // ---- writing -------------------------------------------------------------------------------------------------------

    /** A new thread on {@code kind/id} with its first comment. {@code lenient}: an entity that does not exist is pinned with generation 0. */
    public Posted start(Principal p, String kind, String id, NewThread req, AsOf asOf, boolean lenient) {
        requireCollaborate(p);
        entitlements.requireOpen(p, kind);
        if (req == null) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a thread needs an anchor and a first comment");
        }
        String anchor = req.anchor() == null || req.anchor().isBlank() ? (req.panel() != null ? CommentThread.PANEL : CommentThread.ENTITY) : req.anchor();
        String panel = blank(req.panel()) ? null : req.panel().trim();
        String path = blank(req.path()) ? null : req.path().trim();
        String gate = blank(req.gateKind()) ? null : req.gateKind().trim();
        switch (anchor) {
            case CommentThread.ENTITY -> {
                panel = null;
                path = null;
                gate = null;
            }
            case CommentThread.PANEL -> {
                if (panel == null) {
                    throw new DrishtiException(ErrorCode.BAD_REQUEST, "a panel thread needs the panel id");
                }
                path = null;
            }
            case CommentThread.FIELD -> {
                if (path == null || !path.startsWith("$") || path.length() > 200) {
                    throw new DrishtiException(ErrorCode.BAD_REQUEST, "a field thread needs a path such as $.mtm");
                }
                panel = null;
                gate = null;
            }
            default -> throw new DrishtiException(ErrorCode.BAD_REQUEST, "anchor is entity, panel or field");
        }
        if (gate != null) {
            entitlements.requireOpen(p, gate);
        }
        String text = clean(req.body());
        Draft draft = draft(p, kind, id, text, req.generation(), asOf, lenient);
        Audience.Mentioned who = audience.resolve(p, text, kind, gate);
        if (store.threads(kind, id).size() >= props.threads().maxPerEntity()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, kind + " " + id + " already has " + props.threads().maxPerEntity() + " threads");
        }
        rateLimit(p.user());
        Instant now = now();
        String label = !blank(req.label()) ? shorten200(req.label()) : panel != null ? panel : path != null ? path : "whole view";
        CommentThread thread = new CommentThread(Ulid.next("th_", now.toEpochMilli()), kind, id, anchor, panel, path, gate, label,
                CommentThread.OPEN, p.user(), now, now, 0);
        Comment c = new Comment(Ulid.next("cm_", now.toEpochMilli()), thread.id(), p.user(), now, null, 1, draft.pin(), text, draft.spans(),
                Comment.LIVE, null);
        List<Notice> written = tx.run(() -> {
            store.saveThread(thread);
            return write(thread, c, who, now);
        });
        written.forEach(hub::publish);
        audit.record(p.user(), "collab.comment.add", kind + "/" + id, thread.id() + " " + c.id());
        return new Posted(thread.id(), view(p, thread, c), who.reached().size(), who.skipped(), draft.warnings());
    }

    /** A reply on a thread. */
    public Posted reply(Principal p, String threadId, NewComment req, AsOf asOf) {
        requireCollaborate(p);
        CommentThread thread = visibleThread(p, threadId);
        if (CommentThread.LOCKED.equals(thread.state())) {
            throw new DrishtiException(ErrorCode.THREAD_LOCKED, "this thread is locked: an administrator must unlock it before anyone replies");
        }
        if (req == null) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a reply needs a body");
        }
        String text = clean(req.body());
        Draft draft = draft(p, thread.kind(), thread.entityId(), text, req.generation(), asOf, false);
        Audience.Mentioned who = audience.resolve(p, text, thread.kind(), thread.gateKind());
        rateLimit(p.user());
        Instant now = now();
        Comment c = new Comment(Ulid.next("cm_", now.toEpochMilli()), thread.id(), p.user(), now, null, 1, draft.pin(), text, draft.spans(),
                Comment.LIVE, null);
        List<Notice> written = tx.run(() -> {
            CommentThread fresh = store.thread(threadId).orElseThrow();
            if (CommentThread.LOCKED.equals(fresh.state())) {
                throw new DrishtiException(ErrorCode.THREAD_LOCKED, "this thread is locked");
            }
            return write(fresh, c, who, now);
        });
        written.forEach(hub::publish);
        audit.record(p.user(), "collab.comment.add", thread.kind() + "/" + thread.entityId(), thread.id() + " " + c.id());
        return new Posted(thread.id(), view(p, thread, c), who.reached().size(), who.skipped(), draft.warnings());
    }

    /** Stores the comment, bumps the thread, makes the author a follower, and delivers the audience (mentions, then followers). */
    private List<Notice> write(CommentThread thread, Comment c, Audience.Mentioned who, Instant now) {
        store.append(c, Revision.draft(c.id(), 1, now, c.author(), Revision.CREATED, c.body(), null), who.targets());
        store.saveThread(thread.withActivity(now, thread.comments() + 1));
        if (store.following(thread.id(), c.author()).isEmpty()) {
            store.follow(new Follow(thread.id(), c.author(), false, now));
        }
        Set<String> told = new LinkedHashSet<>(who.reached());
        List<String> repliers = new ArrayList<>();
        for (Follow f : store.followers(thread.id())) {
            if (!f.muted() && !f.username().equals(c.author()) && !told.contains(f.username())
                    && audience.reaches(f.username(), thread.kind(), thread.gateKind())) {
                repliers.add(f.username());
            }
        }
        List<Notice> out = new ArrayList<>();
        deliver(out, "mention", thread, c, null, new ArrayList<>(told));
        deliver(out, "reply", thread, c, null, repliers);
        return out;
    }

    private void deliver(List<Notice> out, String type, CommentThread thread, Comment c, String shareId, List<String> people) {
        if (people.isEmpty()) {
            return;
        }
        Notifier.CommentEvent event = new Notifier.CommentEvent(type, thread, c, shareId, people);
        for (Notifier n : notifiers) {
            if (n.available()) {
                out.addAll(n.onComment(event));
            }
        }
    }

    /**
     * Edits the text. Only the author, only while the comment is live and within the edit window ({@code 403 DRS-7008} otherwise),
     * and only on the current revision ({@code 409 DRS-7009}). The earlier text stays in the revisions.
     */
    public CommentView edit(Principal p, String commentId, String body, Integer revision) {
        requireCollaborate(p);
        Comment c = comment(commentId);
        CommentThread thread = threadOf(p, c);
        if (!c.author().equals(p.user())) {
            throw new DrishtiException(ErrorCode.NOT_EDITABLE, "only " + c.author() + " may edit this comment");
        }
        if (!Comment.LIVE.equals(c.state())) {
            throw new DrishtiException(ErrorCode.NOT_EDITABLE, "a " + c.state() + " comment cannot be edited");
        }
        Instant now = now();
        if (Duration.between(c.createdAt(), now).compareTo(props.threads().editWindow()) > 0) {
            throw new DrishtiException(ErrorCode.NOT_EDITABLE, "the edit window (" + props.threads().editWindow() + ") has passed: retract the comment and write another");
        }
        String text = clean(body);
        List<Share.Span> spans = spansAt(thread, c.pin(), text);
        Audience.Mentioned who = audience.resolve(p, text, thread.kind(), thread.gateKind());
        rateLimit(p.user());
        List<Notice> written = new ArrayList<>();
        Comment[] saved = new Comment[1];
        tx.run(() -> {
            Comment cur = comment(commentId);
            if (revision != null && revision != cur.revision()) {
                throw new DrishtiException(ErrorCode.STALE_COMMENT, "someone changed this comment since you opened it (revision " + cur.revision() + ")");
            }
            if (!Comment.LIVE.equals(cur.state())) {
                throw new DrishtiException(ErrorCode.NOT_EDITABLE, "a " + cur.state() + " comment cannot be edited");
            }
            Set<String> before = new LinkedHashSet<>();
            store.mentions(commentId).forEach(m -> before.add(m.target()));
            Comment next = cur.edited(text, spans, now, cur.revision() + 1);
            store.append(next, Revision.draft(commentId, next.revision(), now, p.user(), Revision.EDITED, text, null), who.targets());
            saved[0] = next;
            List<String> fresh = who.reached().stream().filter(u -> !before.contains("user:" + u)).toList();
            deliver(written, "mention", thread, next, null, fresh);
            return null;
        });
        written.forEach(hub::publish);
        audit.record(p.user(), "collab.comment.edit", thread.kind() + "/" + thread.entityId(), commentId);
        return view(p, thread, saved[0]);
    }

    /** The author withdraws a comment at any time: its text is hidden from readers and kept in the record. */
    public CommentView retract(Principal p, String commentId) {
        requireCollaborate(p);
        Comment c = comment(commentId);
        CommentThread thread = threadOf(p, c);
        if (!c.author().equals(p.user())) {
            throw new DrishtiException(ErrorCode.NOT_EDITABLE, "only " + c.author() + " may retract this comment (an administrator may hide it)");
        }
        return transition(p, thread, commentId, Comment.RETRACTED, Revision.RETRACTED, null, "collab.comment.retract");
    }

    /** An administrator hides a comment, with a reason readers are shown ("Hidden by a moderator: client name"). */
    public CommentView hide(Principal p, String commentId, String reason) {
        requireOn();
        entitlements.requireAdmin(p);
        String why = reason == null ? "" : reason.strip();
        if (why.isEmpty() || why.length() > 400) {
            throw new DrishtiException(ErrorCode.TEXT_REFUSED, "say why the comment is hidden (at most 400 characters)");
        }
        Comment c = comment(commentId);
        CommentThread thread = store.thread(c.threadId()).orElseThrow(() -> new DrishtiException(ErrorCode.THREAD_NOT_FOUND, "no thread"));
        return transition(p, thread, commentId, Comment.HIDDEN, Revision.HIDDEN, why, "collab.comment.hide");
    }

    public CommentView unhide(Principal p, String commentId) {
        requireOn();
        entitlements.requireAdmin(p);
        Comment c = comment(commentId);
        CommentThread thread = store.thread(c.threadId()).orElseThrow(() -> new DrishtiException(ErrorCode.THREAD_NOT_FOUND, "no thread"));
        if (!Comment.HIDDEN.equals(c.state())) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the comment is not hidden");
        }
        return transition(p, thread, commentId, Comment.LIVE, Revision.UNHIDDEN, null, "collab.comment.unhide");
    }

    private CommentView transition(Principal p, CommentThread thread, String commentId, String state, String action, String reason, String audited) {
        Instant now = now();
        Comment[] saved = new Comment[1];
        tx.run(() -> {
            Comment cur = comment(commentId);
            if (Comment.RETRACTED.equals(cur.state()) && !Comment.RETRACTED.equals(state)) {
                throw new DrishtiException(ErrorCode.NOT_EDITABLE, "a retracted comment stays retracted");
            }
            Comment next = cur.inState(state, reason, cur.revision() + 1);
            store.append(next, Revision.draft(commentId, next.revision(), now, p.user(), action, null, reason), List.of());
            saved[0] = next;
            return null;
        });
        audit.record(p.user(), audited, thread.kind() + "/" + thread.entityId(), commentId + (reason == null ? "" : ": " + reason));
        return view(p, thread, saved[0]);
    }

    /** Resolve and reopen (the thread's participants and administrators); lock and unlock (administrators). */
    public ThreadView state(Principal p, String threadId, String state) {
        requireCollaborate(p);
        CommentThread t = visibleThread(p, threadId);
        if (!CommentThread.OPEN.equals(state) && !CommentThread.RESOLVED.equals(state) && !CommentThread.LOCKED.equals(state)) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "state is open, resolved or locked");
        }
        boolean admin = entitlements.isAdmin(p);
        if (CommentThread.LOCKED.equals(state) || CommentThread.LOCKED.equals(t.state())) {
            entitlements.requireAdmin(p);
        } else if (!admin && !participant(p.user(), t)) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "only people who took part in this thread (or an administrator) change its state");
        }
        if (!t.state().equals(state)) {
            store.saveThread(t.withState(state));
            audit.record(p.user(), "collab.thread." + state, t.kind() + "/" + t.entityId(), t.id());
        }
        return threadView(p, store.thread(threadId).orElse(t), props.threads().pageSize());
    }

    private boolean participant(String user, CommentThread t) {
        return t.createdBy().equals(user) || store.comments(t.id()).stream().anyMatch(c -> c.author().equals(user));
    }

    public void follow(Principal p, String threadId, boolean muted) {
        requireOn();
        CommentThread t = visibleThread(p, threadId);
        Follow old = store.following(t.id(), p.user()).orElse(null);
        store.follow(new Follow(t.id(), p.user(), muted, old == null ? now() : old.since()));
    }

    public void unfollow(Principal p, String threadId) {
        requireOn();
        store.unfollow(visibleThread(p, threadId).id(), p.user());
    }

    // ---- reading -------------------------------------------------------------------------------------------------------

    /** The threads the caller may see on {@code kind/id}, most recent activity first, each with its first page of comments. */
    public List<ThreadView> list(Principal p, String kind, String id, String anchor, String panel, String path, String state, Integer limit,
            String before) {
        requireOn();
        entitlements.requireOpen(p, kind);
        int n = limit == null ? props.threads().pageSize() : Math.max(1, Math.min(limit, 200));
        List<CommentThread> all = store.threads(kind, id).stream().filter(t -> visible(p, t))
                .filter(t -> blank(anchor) || anchor.equals(t.anchor())).filter(t -> blank(panel) || panel.equals(t.panelId()))
                .filter(t -> blank(path) || path.equals(t.path())).filter(t -> blank(state) || state.equals(t.state())).toList();
        int from = 0;
        if (!blank(before)) {
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).id().equals(before)) {
                    from = i + 1;
                }
            }
        }
        List<ThreadView> out = new ArrayList<>();
        for (CommentThread t : all.subList(Math.min(from, all.size()), Math.min(all.size(), from + n))) {
            out.add(threadView(p, t, props.threads().pageSize()));
        }
        return out;
    }

    /** {@code {entity: n, panels: {id: n}, fields: {path: n}}}: the badges, from the threads the caller may see. */
    public Map<String, Object> counts(Principal p, String kind, String id) {
        requireOn();
        entitlements.requireOpen(p, kind);
        int entity = 0;
        Map<String, Integer> panels = new LinkedHashMap<>();
        Map<String, Integer> fields = new LinkedHashMap<>();
        for (CommentThread t : store.threads(kind, id)) {
            if (!visible(p, t)) {
                continue;
            }
            switch (t.anchor()) {
                case CommentThread.PANEL -> panels.merge(t.panelId(), t.comments(), Integer::sum);
                case CommentThread.FIELD -> fields.merge(t.path(), t.comments(), Integer::sum);
                default -> {
                }
            }
            if (CommentThread.ENTITY.equals(t.anchor())) {
                entity += t.comments();
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("entity", entity);
        m.put("panels", panels);
        m.put("fields", fields);
        return m;
    }

    /** A comment's steps. Bodies are for readers of a live comment (scrubbed for this reader) and for moderators; others see the steps only. */
    public List<RevisionView> revisions(Principal p, String commentId) {
        requireOn();
        Comment c = comment(commentId);
        CommentThread t = threadOf(p, c);
        boolean text = Comment.LIVE.equals(c.state()) || moderator(p);
        List<String> secrets = text && entitlements.masks(p) ? pinned.at(t.kind(), t.entityId(), c.pin()).map(entitlements::maskedValues).orElse(null) : List.of();
        List<RevisionView> out = new ArrayList<>();
        for (Revision r : store.revisions(commentId)) {
            String body = null;
            if (text && r.body() != null && secrets != null) {
                body = NoteText.render(r.body(), NoteText.spans(r.body(), secrets), entitlements.masks(p));
            }
            out.add(new RevisionView(r.revision(), r.at(), r.actor(), r.action(), body, r.reason()));
        }
        return out;
    }

    /** Comments that mention the caller or one of their roles, newest first, only where the caller may open the entity. */
    public List<MentionRow> mentions(Principal p, Integer limit, String before) {
        requireOn();
        int n = limit == null ? 50 : Math.max(1, Math.min(limit, 200));
        List<String> targets = new ArrayList<>(List.of("user:" + p.user()));
        p.roles().stream().filter(r -> !"*".equals(r)).forEach(r -> targets.add("role:" + r));
        List<MentionRow> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String cursor = blank(before) ? null : before;
        for (int round = 0; round < 10 && out.size() < n; round++) {
            List<Mention> page = store.mentionsOf(targets, n, cursor);
            if (page.isEmpty()) {
                break;
            }
            for (Mention m : page) {
                cursor = m.commentId();
                Comment c = seen.add(m.commentId()) ? store.comment(m.commentId()).orElse(null) : null;
                CommentThread t = c == null ? null : store.thread(c.threadId()).orElse(null);
                if (t == null || !visible(p, t) || !Comment.LIVE.equals(c.state()) || c.author().equals(p.user()) || out.size() >= n) {
                    continue;
                }
                Set<String> have = new LinkedHashSet<>();
                store.mentions(c.id()).forEach(x -> have.add(x.target()));
                String text = CommentRenderer.text(renderer.parts(t.kind(), t.entityId(), c.pin(), c.body(), spans(t, c), have, p));
                out.add(new MentionRow(c.id(), t.id(), t.kind(), t.entityId(), t.panelId(), c.author(), displayName(c.author()), c.createdAt(),
                        NoteText.excerpt(text, EXCERPT)));
            }
        }
        return out;
    }

    // ---- views ---------------------------------------------------------------------------------------------------------

    private ThreadView threadView(Principal p, CommentThread t, int commentLimit) {
        List<Comment> all = store.comments(t.id());
        List<CommentView> items = new ArrayList<>();
        for (Comment c : all.subList(0, Math.min(all.size(), commentLimit))) {
            items.add(view(p, t, c));
        }
        Follow f = store.following(t.id(), p.user()).orElse(null);
        return new ThreadView(t.id(), t.kind(), t.entityId(), t.anchor(), t.panelId(), t.path(), t.anchorLabel(), t.state(), t.createdBy(),
                displayName(t.createdBy()), t.createdAt(), t.lastAt(), t.comments(), f != null, f != null && f.muted(), items, all.size() > commentLimit);
    }

    /** One comment for one reader: text only while live (or for moderators), scrubbed and with quotes filled for that reader. */
    CommentView view(Principal reader, CommentThread t, Comment c) {
        boolean live = Comment.LIVE.equals(c.state());
        boolean text = live || moderator(reader);
        String body = null;
        List<CommentRenderer.Part> parts = null;
        if (text) {
            Set<String> targets = new LinkedHashSet<>();
            if (c.body().indexOf('@') >= 0) {
                store.mentions(c.id()).forEach(m -> targets.add(m.target()));
            }
            parts = renderer.parts(t.kind(), t.entityId(), c.pin(), c.body(), spans(t, c), targets, reader);
            body = CommentRenderer.text(parts);
        }
        boolean mine = c.author().equals(reader.user());
        boolean editable = mine && live && Duration.between(c.createdAt(), now()).compareTo(props.threads().editWindow()) <= 0;
        return new CommentView(c.id(), c.threadId(), c.author(), displayName(c.author()), c.createdAt(), c.editedAt(), c.revision(), c.pin(),
                c.state(), c.stateReason(), body, parts, c.editedAt() != null, mine, editable);
    }

    private String displayName(String user) {
        return principals.user(user).map(User::displayName).filter(n -> n != null && !n.isBlank()).orElse(user);
    }

    // ---- text, pin, scrub ----------------------------------------------------------------------------------------------

    /**
     * The ranges of a comment's text that copy a masked value. A comment written here carries them from when it was written; a
     * note imported from the old Notes was never checked, so its text is checked now against the entity as it is (an old note
     * may hold a trader's name that a reader without raw must not see). When the entity cannot be read the whole text counts as
     * masked: a reader who sees masks then sees none of it rather than a value.
     */
    private List<Share.Span> spans(CommentThread t, Comment c) {
        boolean imported = c.pin() == null || c.pin().generation() == 0 && c.pin().businessDate() == null;
        if (!imported || !c.maskedSpans().isEmpty() || c.body() == null || c.body().isEmpty()) {
            return c.maskedSpans();
        }
        try {
            EntityDocument doc = fetch(t.kind(), t.entityId(), AsOf.LATEST, true);
            return doc == null ? List.of() : NoteText.spans(c.body(), entitlements.maskedValues(doc.data()));
        } catch (RuntimeException e) {
            return List.of(new Share.Span(0, c.body().length()));
        }
    }

    private String clean(String raw) {
        String text = raw == null ? "" : raw.strip();
        if (text.isEmpty()) {
            throw new DrishtiException(ErrorCode.TEXT_REFUSED, "write something: a comment needs some text");
        }
        if (text.length() > props.threads().maxText()) {
            throw new DrishtiException(ErrorCode.TEXT_REFUSED, "the comment is " + text.length() + " characters; the limit is " + props.threads().maxText());
        }
        if (text.chars().anyMatch(c -> c < 0x20 && c != '\n' && c != '\t')) {
            throw new DrishtiException(ErrorCode.TEXT_REFUSED, "the comment has control characters");
        }
        for (Pattern d : deny) {
            if (d.matcher(text).find()) {
                throw new DrishtiException(ErrorCode.TEXT_REFUSED, "the comment matches a pattern that may not be written (drishti.collab.text.deny-patterns)");
            }
        }
        return text;
    }

    /** Reads the document the author is looking at, checks the pin, and finds the copies of masked values in the text. */
    Draft draft(Principal author, String kind, String id, String text, Long generation, AsOf asOf, boolean lenient) {
        EntityDocument doc = fetch(kind, id, asOf, lenient);
        long held = doc == null ? 0 : doc.provenance().generation();
        long gen = generation == null ? 0 : generation;
        if (gen < 0 || gen > held) {
            throw new DrishtiException(ErrorCode.TEXT_REFUSED, "bad pin: generation " + gen + " is newer than the " + held + " the server holds");
        }
        List<String> warnings = new ArrayList<>();
        List<Share.Span> spans = doc == null ? List.of() : NoteText.spans(text, entitlements.maskedValues(doc.data()));
        if (!spans.isEmpty()) {
            if ("reject".equals(props.text().onMaskedCopy())) {
                throw new DrishtiException(ErrorCode.TEXT_REFUSED, "the comment contains the value of a field that is hidden from some readers; remove it or quote it with {$.path}");
            }
            if ("warn".equals(props.text().onMaskedCopy())) {
                warnings.add("The comment contains the value of a field hidden from some readers; people without full access will see "
                        + com.ash.drishti.api.DataNode.MASK + ". Quote it with {$.path} instead.");
            }
        }
        Instant now = now();
        Pin pin = new Pin(asOf.live() ? null : asOf.businessDate(), asOf.live(), asOf.knownAt() != null && !asOf.live() ? asOf.knownAt() : now, gen,
                doc == null ? null : doc.provenance().source());
        return new Draft(text, pin, spans, warnings);
    }

    /** The masked-value ranges of {@code text} against the document at {@code pin}; refuses when that document cannot be read. */
    private List<Share.Span> spansAt(CommentThread t, Pin pin, String text) {
        var doc = pinned.at(t.kind(), t.entityId(), pin);
        if (doc.isEmpty()) {
            if (pin != null && pin.generation() == 0) {
                return List.of();                                       // an imported note: nothing was pinned
            }
            throw new DrishtiException(ErrorCode.SOURCE_FAILED, "could not read " + t.kind() + " " + t.entityId() + " at the comment's date to check the new text");
        }
        List<Share.Span> spans = NoteText.spans(text, entitlements.maskedValues(doc.get()));
        if (!spans.isEmpty() && "reject".equals(props.text().onMaskedCopy())) {
            throw new DrishtiException(ErrorCode.TEXT_REFUSED, "the comment contains the value of a field that is hidden from some readers");
        }
        return spans;
    }

    private EntityDocument fetch(String kind, String id, AsOf asOf, boolean lenient) {
        try {
            return router.fetch(EntityRef.of(kind, id), asOf).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof DrishtiException de) {
                if (lenient && de.errorCode() == ErrorCode.ENTITY_NOT_FOUND) {
                    return null;
                }
                throw de;
            }
            throw new DrishtiException(ErrorCode.SOURCE_FAILED, "could not read " + kind + " " + id + " to pin the comment", e.getCause());
        }
    }

    private void rateLimit(String user) {
        limits.hit("comment", user, props.limits().commentsPerMinute(), Duration.ofMinutes(1), "comments");
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String shorten200(String s) {
        String t = s.strip();
        return t.length() > 200 ? t.substring(0, 200) : t;
    }

    // ---- the share's own threads ---------------------------------------------------------------------------------------

    /**
     * Posts a share's note to the discussion, as its sender: a new thread (on the panel when one was shared, else on the whole view) with
     * the note as its first comment, under the id the share already carries. Called inside the share's transaction; the recipients get the
     * share's notice, so no second one is written.
     */
    public void postShare(String threadId, Share share, Instant now) {
        if (store.threads(share.kind(), share.entityId()).size() >= props.threads().maxPerEntity()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, share.kind() + " " + share.entityId() + " already has " + props.threads().maxPerEntity() + " threads");
        }
        boolean panel = share.panelId() != null;
        CommentThread t = new CommentThread(threadId, share.kind(), share.entityId(), panel ? CommentThread.PANEL : CommentThread.ENTITY,
                share.panelId(), null, share.gateKind(), panel ? share.panelId() : "whole view", CommentThread.OPEN, share.sender(), now, now, 1);
        store.saveThread(t);
        Comment c = new Comment(Ulid.next("cm_", now.toEpochMilli()), threadId, share.sender(), now, null, 1, share.pin(), share.body(),
                share.maskedSpans(), Comment.LIVE, null);
        store.append(c, Revision.draft(c.id(), 1, now, share.sender(), Revision.CREATED, c.body(), null), List.of());
        store.follow(new Follow(threadId, share.sender(), false, now));
    }

    /** The replies to a share, oldest first, for this reader (the caller has already been checked as a party). */
    public List<CommentView> replies(Principal reader, Share share) {
        List<CommentView> out = new ArrayList<>();
        for (CommentThread t : shareThreads(share)) {
            for (Comment c : store.comments(t.id())) {
                out.add(view(reader, t, c));
            }
        }
        out.sort(java.util.Comparator.comparing(CommentView::createdAt).thenComparing(CommentView::id));
        return out;
    }

    /** One reply to a share, from the sender or a notified recipient; the other party is told. */
    public CommentView replyToShare(Principal p, Share share, List<String> notified, String note, AsOf asOf) {
        requireCollaborate(p);
        String text = clean(note);
        Draft draft = draft(p, share.kind(), share.entityId(), text, share.pin().generation(), asOf, false);
        rateLimit(p.user());
        Instant now = now();
        List<String> others = new ArrayList<>();
        if (p.user().equals(share.sender())) {
            notified.stream().filter(u -> !u.equals(p.user())).forEach(others::add);
        } else {
            others.add(share.sender());
        }
        List<String> reach = others.stream().filter(u -> audience.reaches(u, share.kind(), share.gateKind())).toList();
        Comment[] saved = new Comment[1];
        CommentThread[] where = new CommentThread[1];
        List<Notice> written = tx.run(() -> {
            CommentThread t = shareThreads(share).stream().findFirst().orElse(null);
            if (t == null) {
                t = new CommentThread(Ulid.next("th_", now.toEpochMilli()), share.kind(), share.entityId(), CommentThread.SHARE, share.panelId(),
                        share.id(), share.gateKind(), "share", CommentThread.OPEN, share.sender(), now, now, 0);
                store.saveThread(t);
            }
            Comment c = new Comment(Ulid.next("cm_", now.toEpochMilli()), t.id(), p.user(), now, null, 1, share.pin(), text, draft.spans(),
                    Comment.LIVE, null);
            store.append(c, Revision.draft(c.id(), 1, now, p.user(), Revision.CREATED, text, null), List.of());
            CommentThread bumped = t.withActivity(now, t.comments() + 1);
            store.saveThread(bumped);
            saved[0] = c;
            where[0] = bumped;
            List<Notice> rows = new ArrayList<>();
            deliver(rows, "reply", bumped, c, share.id(), reach);
            return rows;
        });
        written.forEach(hub::publish);
        audit.record(p.user(), "collab.share.reply", share.kind() + "/" + share.entityId(), share.id() + " " + saved[0].id());
        return view(p, where[0], saved[0]);
    }

    private List<CommentThread> shareThreads(Share share) {
        return store.threads(share.kind(), share.entityId()).stream().filter(t -> CommentThread.SHARE.equals(t.anchor()) && share.id().equals(t.path()))
                .sorted(java.util.Comparator.comparing(CommentThread::id)).toList();
    }

    /** The inbox's view of a mention or reply notice for this reader, now: no text once they may no longer open the entity. */
    public NoticeText noticeText(Principal reader, String commentId) {
        Comment c = store.comment(commentId).orElse(null);
        CommentThread t = c == null ? null : store.thread(c.threadId()).orElse(null);
        if (t == null || !entitlements.mayOpen(reader, t.kind()) || (t.gateKind() != null && !entitlements.mayOpen(reader, t.gateKind()))) {
            return new NoticeText(false, null);
        }
        if (!Comment.LIVE.equals(c.state())) {
            return new NoticeText(true, null);
        }
        return new NoticeText(true, NoteText.excerpt(CommentRenderer.text(renderer.parts(t.kind(), t.entityId(), c.pin(), c.body(),
                spans(t, c), mentionTargets(c), reader)), EXCERPT));
    }

    private Set<String> mentionTargets(Comment c) {
        Set<String> targets = new LinkedHashSet<>();
        if (c.body().indexOf('@') >= 0) {
            store.mentions(c.id()).forEach(m -> targets.add(m.target()));
        }
        return targets;
    }

    // ---- notes facade support ------------------------------------------------------------------------------------------

    /** The store, for the notes facade and the import (same package family; not part of the API). */
    public ThreadStore store() {
        return store;
    }

    /** The comment as this reader sees it; the thread must be one the reader may see. */
    public CommentView viewOf(Principal reader, String commentId) {
        Comment c = comment(commentId);
        return view(reader, threadOf(reader, c), c);
    }
}
