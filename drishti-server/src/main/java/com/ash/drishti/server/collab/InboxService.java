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

import com.ash.drishti.identity.User;
import com.ash.drishti.identity.collab.InboxStore;
import com.ash.drishti.identity.collab.Notice;
import com.ash.drishti.identity.collab.Share;
import com.ash.drishti.identity.collab.ShareStore;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The inbox: rows are pointers, never content. What a reader sees is rendered <em>when read</em>, for the reader's current rights:
 * a share on a kind the reader can no longer open reads "(no access) a trade view" with no entity id, and the note's excerpt shows a
 * masked field's value as the mask for a reader without {@code raw}. No data value is ever put in a row or a notice.
 */
public final class InboxService {

    /** One row as a reader sees it. {@code id} and {@code excerpt} are null when {@code access} is false. */
    public record Row(long seq, Instant at, String type, String actor, String actorName, String kind, String id, String panel, String shareId,
            String threadId, String commentId, boolean read, boolean access, String title, String excerpt) {}

    private static final int PRUNE_EVERY = 20;
    private static final int EXCERPT = 140;

    private final InboxStore store;
    private final ShareStore shares;
    private final Entitlements entitlements;
    private final Principals principals;
    private final int keep;
    private final java.util.function.Supplier<com.ash.drishti.server.collab.thread.ThreadService> threads;
    private final Map<String, AtomicInteger> sincePrune = new ConcurrentHashMap<>();

    public InboxService(InboxStore store, ShareStore shares, Entitlements entitlements, Principals principals, int keep,
            java.util.function.Supplier<com.ash.drishti.server.collab.thread.ThreadService> threads) {
        this.store = store;
        this.shares = shares;
        this.entitlements = entitlements;
        this.principals = principals;
        this.keep = keep;
        this.threads = threads;
    }

    /** Stores a row (call inside the share's transaction); the newest {@code inbox.keep} per user are kept. */
    public Notice add(Notice n) {
        Notice stored = store.add(n);
        if (sincePrune.computeIfAbsent(n.username(), u -> new AtomicInteger()).incrementAndGet() >= PRUNE_EVERY) {
            sincePrune.get(n.username()).set(0);
            store.prune(n.username(), keep);
        }
        return stored;
    }

    public List<Row> list(Principal p, String type, boolean unreadOnly, int limit, long before) {
        return store.list(p.user(), type, unreadOnly, Math.max(1, Math.min(limit, 200)), before).stream().map(n -> render(n, p)).toList();
    }

    public long unread(String user) {
        return store.unread(user);
    }

    public int markRead(String user, Collection<Long> seqs, Long upTo) {
        Instant now = Instant.now();
        return upTo != null ? store.markReadUpTo(user, upTo, now) : store.markRead(user, seqs == null ? List.of() : seqs, now);
    }

    /** The row for this reader, now. */
    public Row render(Notice n, Principal reader) {
        Share share = n.shareId() == null ? null : shares.find(n.shareId()).orElse(null);
        boolean access = entitlements.mayOpen(reader, n.kind()) && (share == null || share.gateKind() == null
                || entitlements.mayOpen(reader, share.gateKind()));
        String actorName = principals.user(n.actor()).map(User::displayName).filter(s -> !s.isBlank()).orElse(n.actor());
        String verb = switch (n.type()) {
            case Notice.SHARE -> "shared";
            case "mention" -> "mentioned you on";
            case "reply" -> "replied on";
            default -> "wrote about";
        };
        String title = access ? actorName + " " + verb + " " + n.kind() + " " + n.entityId() : "(no access) " + actorName + " " + verb + " a " + n.kind() + " view";
        String excerpt = null;
        com.ash.drishti.server.collab.thread.ThreadService ts = n.commentId() == null ? null : threads.get();
        if (ts != null) {
            var text = ts.noticeText(reader, n.commentId());
            access = text.access();
            excerpt = text.excerpt();
            title = access ? actorName + " " + verb + " " + n.kind() + " " + n.entityId() : "(no access) " + actorName + " " + verb + " a " + n.kind() + " view";
        } else if (access && share != null && !share.body().isBlank()) {
            excerpt = NoteText.excerpt(NoteText.render(share.body(), share.maskedSpans(), entitlements.masks(reader)), EXCERPT);
        }
        return new Row(n.seq(), n.at(), n.type(), n.actor(), actorName, n.kind(), access ? n.entityId() : null, access ? n.panelId() : null,
                n.shareId(), n.threadId(), n.commentId(), n.readAt() != null, access, title, excerpt);
    }
}
