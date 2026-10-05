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
import com.ash.drishti.identity.db.OutboxEntity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;

/** The outbox in the identity database. A claim is one conditional {@code UPDATE} per row, so two servers never take the same delivery. */
public final class JpaOutboxStore implements OutboxStore {

    private final IdentityRepositories.Outbox repo;
    private final TransactionTemplate tx;

    public JpaOutboxStore(IdentityRepositories.Outbox repo, TransactionTemplate tx) {
        this.repo = repo;
        this.tx = tx;
    }

    @Override
    public OutboxItem add(OutboxItem i) {
        OutboxEntity e = new OutboxEntity();
        e.channel = i.channel();
        e.recipient = i.recipient();
        e.template = i.template();
        e.refId = i.refId();
        e.state = i.state();
        e.attempts = i.attempts();
        e.nextAt = i.nextAt();
        e.leaseUntil = i.leaseUntil();
        e.leasedBy = i.leasedBy();
        e.lastError = cut(i.lastError());
        e.createdAt = i.createdAt();
        e.sentAt = i.sentAt();
        return item(tx.execute(t -> repo.save(e)));
    }

    @Override
    public Optional<OutboxItem> find(long seq) {
        return tx.execute(t -> repo.findById(seq).map(JpaOutboxStore::item));
    }

    @Override
    public List<OutboxItem> claim(Instant now, int limit, Instant leaseUntil, String owner) {
        return tx.execute(t -> {
            List<OutboxItem> won = new ArrayList<>();
            for (OutboxEntity e : repo.due(now, PageRequest.of(0, Math.max(1, limit)))) {
                if (repo.claim(e.seq, now, leaseUntil, owner) == 1) {
                    e.state = OutboxItem.SENDING;
                    e.leaseUntil = leaseUntil;
                    e.leasedBy = owner;
                    won.add(item(e));
                }
            }
            return won;
        });
    }

    private void change(long seq, Consumer<OutboxEntity> edit) {
        tx.executeWithoutResult(t -> repo.findById(seq).ifPresent(e -> {
            edit.accept(e);
            repo.save(e);
        }));
    }

    @Override
    public void sent(long seq, Instant at) {
        change(seq, e -> {
            e.state = OutboxItem.SENT;
            e.sentAt = at;
            e.leaseUntil = null;
            e.leasedBy = null;
            e.lastError = null;
        });
    }

    @Override
    public void retry(long seq, int attempts, Instant nextAt, String error) {
        change(seq, e -> {
            e.state = OutboxItem.PENDING;
            e.attempts = attempts;
            e.nextAt = nextAt;
            e.leaseUntil = null;
            e.leasedBy = null;
            e.lastError = cut(error);
        });
    }

    @Override
    public void dead(long seq, int attempts, String error) {
        change(seq, e -> {
            e.state = OutboxItem.DEAD;
            e.attempts = attempts;
            e.leaseUntil = null;
            e.leasedBy = null;
            e.lastError = cut(error);
        });
    }

    @Override
    public void cancel(long seq, String reason) {
        change(seq, e -> {
            e.state = OutboxItem.CANCELLED;
            e.leaseUntil = null;
            e.leasedBy = null;
            e.lastError = cut(reason);
        });
    }

    @Override
    public boolean requeue(long seq, Instant at) {
        Boolean ok = tx.execute(t -> repo.findById(seq)
                .filter(e -> OutboxItem.DEAD.equals(e.state) || OutboxItem.CANCELLED.equals(e.state)).map(e -> {
                    e.state = OutboxItem.PENDING;
                    e.attempts = 0;
                    e.nextAt = at;
                    repo.save(e);
                    return true;
                }).orElse(false));
        return Boolean.TRUE.equals(ok);
    }

    @Override
    public List<OutboxItem> list(String state, int limit) {
        PageRequest page = PageRequest.of(0, Math.max(1, limit));
        return tx.execute(t -> (state == null || state.isBlank() ? repo.findAllByOrderBySeqDesc(page)
                : repo.findByStateOrderBySeqDesc(state, page)).stream().map(JpaOutboxStore::item).toList());
    }

    @Override
    public List<OutboxItem> after(long afterSeq, int limit) {
        return tx.execute(t -> repo.findBySeqGreaterThanOrderBySeq(afterSeq, PageRequest.of(0, Math.max(1, limit))).stream()
                .map(JpaOutboxStore::item).toList());
    }

    @Override
    public Map<String, Long> counts() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String s : List.of(OutboxItem.PENDING, OutboxItem.SENDING, OutboxItem.SENT, OutboxItem.DEAD, OutboxItem.CANCELLED)) {
            out.put(s, repo.countByState(s));
        }
        return out;
    }

    @Override
    public long countSince(String recipient, Instant since) {
        return repo.countByRecipientAndCreatedAtGreaterThanEqual(recipient, since);
    }

    @Override
    public int purgeSent(Instant before) {
        Integer n = tx.execute(t -> repo.purgeSent(before));
        return n == null ? 0 : n;
    }

    static String cut(String s) {
        return s == null ? null : s.length() > 400 ? s.substring(0, 400) : s;
    }

    private static OutboxItem item(OutboxEntity e) {
        return new OutboxItem(e.seq, e.channel, e.recipient, e.template, e.refId, e.state, e.attempts, e.nextAt, e.leaseUntil, e.leasedBy,
                e.lastError, e.createdAt, e.sentAt);
    }
}
