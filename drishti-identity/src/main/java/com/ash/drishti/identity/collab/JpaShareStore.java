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
import com.ash.drishti.identity.db.ShareEntity;
import com.ash.drishti.identity.db.ShareRecipientEntity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;

/** Shares in the identity database ({@code drishti_share}, {@code drishti_share_recipient}). */
public final class JpaShareStore implements ShareStore {

    private final IdentityRepositories.Shares shares;
    private final IdentityRepositories.ShareRecipients recipients;
    private final TransactionTemplate tx;

    public JpaShareStore(IdentityRepositories.Shares shares, IdentityRepositories.ShareRecipients recipients, TransactionTemplate tx) {
        this.shares = shares;
        this.recipients = recipients;
        this.tx = tx;
    }

    @Override
    public void save(Share s, List<Recipient> rs) {
        tx.executeWithoutResult(t -> {
            shares.save(toEntity(s));
            List<ShareRecipientEntity> rows = new ArrayList<>();
            for (int i = 0; i < rs.size(); i++) {
                Recipient r = rs.get(i);
                ShareRecipientEntity e = new ShareRecipientEntity();
                e.key = new ShareRecipientEntity.Key(s.id(), i);
                e.addressed = r.addressed();
                e.username = r.username();
                e.state = r.state();
                e.openedAt = r.openedAt();
                rows.add(e);
            }
            recipients.saveAll(rows);
        });
    }

    @Override
    public Optional<Share> find(String id) {
        return tx.execute(t -> shares.findById(id).map(JpaShareStore::toShare));
    }

    @Override
    public List<Recipient> recipients(String shareId) {
        return tx.execute(t -> recipients.findByKeyShareIdOrderByKeySeq(shareId).stream()
                .map(e -> new Recipient(e.addressed, e.username, e.state, e.openedAt)).toList());
    }

    @Override
    public boolean markOpened(String shareId, String username, Instant at) {
        return Boolean.TRUE.equals(tx.execute(t -> {
            boolean set = false;
            for (ShareRecipientEntity e : recipients.findByKeyShareIdOrderByKeySeq(shareId)) {
                if (e.username.equals(username) && e.openedAt == null) {
                    e.openedAt = at;
                    recipients.save(e);
                    set = true;
                }
            }
            return set;
        }));
    }

    @Override
    public List<Share> sent(String sender, int limit, String beforeId) {
        PageRequest page = PageRequest.of(0, Math.max(1, limit));
        return tx.execute(t -> (beforeId == null || beforeId.isBlank() ? shares.findBySenderOrderByIdDesc(sender, page)
                : shares.findBySenderAndIdLessThanOrderByIdDesc(sender, beforeId, page)).stream().map(JpaShareStore::toShare).toList());
    }

    @Override
    public List<Share> received(String username, int limit, String beforeId) {
        PageRequest page = PageRequest.of(0, Math.max(1, limit));
        return tx.execute(t -> {
            List<ShareRecipientEntity> rows = beforeId == null || beforeId.isBlank()
                    ? recipients.findByUsernameAndStateOrderByKeyShareIdDesc(username, Recipient.NOTIFIED, page)
                    : recipients.findByUsernameAndStateAndKeyShareIdLessThanOrderByKeyShareIdDesc(username, Recipient.NOTIFIED, beforeId, page);
            List<Share> out = new ArrayList<>();
            for (ShareRecipientEntity r : rows) {
                shares.findById(r.key.shareId).ifPresent(e -> out.add(toShare(e)));
            }
            return out;
        });
    }

    @Override
    public long countSentSince(String sender, Instant since) {
        return shares.countBySenderAndCreatedAtGreaterThanEqual(sender, since);
    }

    private static ShareEntity toEntity(Share s) {
        ShareEntity e = new ShareEntity();
        e.id = s.id();
        e.sender = s.sender();
        e.createdAt = s.createdAt();
        e.kind = s.kind();
        e.entityId = s.entityId();
        e.panelId = s.panelId();
        e.gateKind = s.gateKind();
        Pin p = s.pin();
        e.pinDate = p == null ? null : p.businessDate();
        e.pinLive = p != null && p.live();
        e.pinKnownAt = p == null ? null : p.knownAt();
        e.pinGeneration = p == null ? 0 : p.generation();
        e.pinSource = p == null ? null : p.source();
        e.body = s.body();
        e.maskedSpans = Share.spansToJson(s.maskedSpans());
        e.channels = s.channels();
        e.threadId = s.threadId();
        e.hash = s.hash();
        return e;
    }

    private static Share toShare(ShareEntity e) {
        return new Share(e.id, e.sender, e.createdAt, e.kind, e.entityId, e.panelId, e.gateKind,
                new Pin(e.pinDate, e.pinLive, e.pinKnownAt, e.pinGeneration, e.pinSource), e.body, Share.spansFromJson(e.maskedSpans),
                e.channels, e.threadId, e.hash);
    }
}
