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

import com.ash.drishti.identity.db.HoldEntity;
import com.ash.drishti.identity.db.IdentityRepositories;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.support.TransactionTemplate;

/** Legal holds in the identity database. */
public final class JpaHoldStore implements HoldStore {

    private final IdentityRepositories.Holds holds;
    private final TransactionTemplate tx;

    public JpaHoldStore(IdentityRepositories.Holds holds, TransactionTemplate tx) {
        this.holds = holds;
        this.tx = tx;
    }

    @Override
    public Hold place(Hold h) {
        return tx.execute(s -> {
            HoldEntity e = new HoldEntity();
            e.scope = h.scope();
            e.kind = h.kind();
            e.entityId = h.entityId();
            e.username = h.username();
            e.threadId = h.threadId();
            e.dateFrom = h.from();
            e.dateTo = h.to();
            e.reason = h.reason();
            e.placedBy = h.placedBy();
            e.placedAt = h.placedAt();
            return toHold(holds.save(e));
        });
    }

    @Override
    public Optional<Hold> find(long id) {
        return tx.execute(s -> holds.findById(id).map(JpaHoldStore::toHold));
    }

    @Override
    public List<Hold> list(boolean activeOnly) {
        return tx.execute(s -> (activeOnly ? holds.findByReleasedAtIsNullOrderByIdDesc() : holds.findAllByOrderByIdDesc()).stream()
                .map(JpaHoldStore::toHold).toList());
    }

    @Override
    public boolean release(long id, String by, Instant at) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            Optional<HoldEntity> e = holds.findById(id);
            if (e.isEmpty() || e.get().releasedAt != null) {
                return false;
            }
            e.get().releasedBy = by;
            e.get().releasedAt = at;
            holds.save(e.get());
            return true;
        }));
    }

    private static Hold toHold(HoldEntity e) {
        return new Hold(e.id, e.scope, e.kind, e.entityId, e.username, e.threadId, e.dateFrom, e.dateTo, e.reason, e.placedBy, e.placedAt,
                e.releasedBy, e.releasedAt);
    }
}
