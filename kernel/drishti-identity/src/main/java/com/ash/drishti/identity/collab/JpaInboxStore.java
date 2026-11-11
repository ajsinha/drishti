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
import com.ash.drishti.identity.db.InboxEntity;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.support.TransactionTemplate;

/** The inbox in the identity database ({@code drishti_inbox}); the row id is the sequence number. */
public final class JpaInboxStore implements InboxStore {

    private final IdentityRepositories.Inbox inbox;
    private final TransactionTemplate tx;

    public JpaInboxStore(IdentityRepositories.Inbox inbox, TransactionTemplate tx) {
        this.inbox = inbox;
        this.tx = tx;
    }

    @Override
    public Notice add(Notice n) {
        InboxEntity e = new InboxEntity();
        e.username = n.username();
        e.at = n.at();
        e.type = n.type();
        e.kind = n.kind();
        e.entityId = n.entityId();
        e.panelId = n.panelId();
        e.shareId = n.shareId();
        e.threadId = n.threadId();
        e.commentId = n.commentId();
        e.actor = n.actor();
        e.readAt = n.readAt();
        return toNotice(tx.execute(t -> inbox.save(e)));
    }

    @Override
    public List<Notice> list(String username, String type, boolean unreadOnly, int limit, long beforeSeq) {
        Specification<InboxEntity> spec = (root, q, cb) -> {
            List<Predicate> p = new ArrayList<>();
            p.add(cb.equal(root.get("username"), username));
            if (type != null && !type.isBlank()) {
                p.add(cb.equal(root.get("type"), type));
            }
            if (unreadOnly) {
                p.add(cb.isNull(root.get("readAt")));
            }
            if (beforeSeq > 0) {
                p.add(cb.lessThan(root.get("seq"), beforeSeq));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
        return tx.execute(t -> inbox.findAll(spec, PageRequest.of(0, Math.max(1, limit), Sort.by(Sort.Direction.DESC, "seq"))).stream()
                .map(JpaInboxStore::toNotice).toList());
    }

    @Override
    public long unread(String username) {
        return inbox.countByUsernameAndReadAtIsNull(username);
    }

    @Override
    public int markRead(String username, Collection<Long> seqs, Instant at) {
        if (seqs == null || seqs.isEmpty()) {
            return 0;
        }
        Integer n = tx.execute(t -> inbox.markRead(username, seqs, at));
        return n == null ? 0 : n;
    }

    @Override
    public int markReadUpTo(String username, long upTo, Instant at) {
        Integer n = tx.execute(t -> inbox.markReadUpTo(username, upTo, at));
        return n == null ? 0 : n;
    }

    @Override
    public List<Notice> after(long seq, int limit) {
        return tx.execute(t -> inbox.findBySeqGreaterThanOrderBySeq(seq, PageRequest.of(0, Math.max(1, limit))).stream()
                .map(JpaInboxStore::toNotice).toList());
    }

    @Override
    public long maxSeq() {
        return inbox.maxSeq();
    }

    @Override
    public void prune(String username, int keep) {
        tx.executeWithoutResult(t -> {
            List<InboxEntity> page = inbox.findByUsernameOrderBySeqDesc(username, PageRequest.of(Math.max(1, keep), 1));
            if (!page.isEmpty()) {
                inbox.deleteByUsernameAndSeqLessThan(username, page.get(0).seq + 1);
            }
        });
    }

    @Override
    public int purgeBefore(Instant before) {
        Integer n = tx.execute(t -> inbox.deleteOlderThan(before));
        return n == null ? 0 : n;
    }

    @Override
    public void forget(String username) {
        tx.executeWithoutResult(t -> inbox.deleteByUsername(username));
    }

    private static Notice toNotice(InboxEntity e) {
        return new Notice(e.seq, e.username, e.at, e.type, e.kind, e.entityId, e.panelId, e.shareId, e.threadId, e.commentId, e.actor, e.readAt);
    }
}
