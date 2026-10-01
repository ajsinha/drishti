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
package com.ash.drishti.identity;

import com.ash.drishti.identity.db.IdentityRepositories;
import com.ash.drishti.identity.db.PackStateEntity;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Which packs administrators switched off (Admin → Packs), kept in the identity database. Every access check reads an
 * immutable snapshot (lock-free); changes are serialised, audited, and apply at the next request.
 */
public final class PackStateStore {

    /** One pack's state as last set by an administrator. */
    public record State(String name, boolean enabled, Instant updatedAt, String updatedBy) {}

    private final IdentityRepositories.PackStates states;
    private final TransactionTemplate tx;
    private final AuditLog audit;
    private final ReentrantLock writes = new ReentrantLock();
    private volatile Map<String, State> snapshot;

    public PackStateStore(IdentityRepositories.PackStates states, TransactionTemplate tx, AuditLog audit) {
        this.states = states;
        this.tx = tx;
        this.audit = audit;
        reload();
    }

    /** Re-reads the table: another server sharing the database may have changed it. */
    public void refresh() {
        writes.lock();
        try {
            reload();
        } finally {
            writes.unlock();
        }
    }

    private void reload() {
        snapshot = tx.execute(s -> states.findAll().stream().map(e -> new State(e.name, e.enabled, e.updatedAt, e.updatedBy))
                .collect(Collectors.toUnmodifiableMap(State::name, Function.identity())));
    }

    /** True unless an administrator switched the pack off. */
    public boolean enabled(String pack) {
        State s = snapshot.get(pack);
        return s == null || s.enabled();
    }

    public Optional<State> find(String pack) {
        return Optional.ofNullable(snapshot.get(pack));
    }

    public State set(String pack, boolean enabled, String actor) {
        writes.lock();
        try {
            tx.executeWithoutResult(s -> {
                PackStateEntity e = states.findById(pack).orElseGet(PackStateEntity::new);
                e.name = pack;
                e.enabled = enabled;
                e.updatedAt = Instant.now();
                e.updatedBy = actor;
                states.save(e);
            });
            reload();
            audit.record(actor, enabled ? "pack-enabled" : "pack-disabled", pack, "");
            return snapshot.get(pack);
        } finally {
            writes.unlock();
        }
    }
}
