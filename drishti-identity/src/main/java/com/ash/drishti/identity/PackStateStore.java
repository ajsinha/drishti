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
 *
 * <p>With the switches ledger attached ({@link #ledger}, the server's default since 2.8.0), a pack's state is the ledger's {@code pack}
 * entry: the same table as the commands' switches, spread to every node of a cluster by Drishti*net, so nodes with databases of their own
 * agree. The states kept before (this table or the content store document) are imported into the ledger once, when it has no entry for
 * the pack.
 */
public final class PackStateStore {

    /** One pack's state as last set by an administrator. */
    public record State(String name, boolean enabled, Instant updatedAt, String updatedBy) {}

    private final IdentityRepositories.PackStates states;
    private final TransactionTemplate tx;
    private final AuditLog audit;
    private final ReentrantLock writes = new ReentrantLock();
    private volatile Map<String, State> snapshot;
    private volatile SwitchLedger ledger;

    /** The content store document of every pack's state ({@code drishti.packs.state-store=content}, the default). */
    public static final class Doc {
        public java.util.TreeMap<String, State> packs = new java.util.TreeMap<>();
    }

    static final String KEY = "admin/pack-state.json";
    private final com.ash.drishti.identity.content.ContentDocs content;

    public PackStateStore(IdentityRepositories.PackStates states, TransactionTemplate tx, AuditLog audit) {
        this(states, tx, audit, null);
    }

    /**
     * With {@code content}, the states are one document in the content store, {@code admin/pack-state.json} (CONTENT_STORE.md section 9),
     * so every node of a cluster sees a switch within the refresh interval; the table's rows are imported once (the document's existence
     * is the marker), and left in place.
     */
    public PackStateStore(IdentityRepositories.PackStates states, TransactionTemplate tx, AuditLog audit,
            com.ash.drishti.identity.content.ContentStore content) {
        this.states = states;
        this.tx = tx;
        this.audit = audit;
        this.content = content == null ? null : new com.ash.drishti.identity.content.ContentDocs(content);
        if (this.content != null && content.get(KEY).isEmpty()) {
            Doc d = new Doc();
            tx.execute(s -> states.findAll()).forEach(e -> d.packs.put(e.name, new State(e.name, e.enabled, e.updatedAt, e.updatedBy)));
            this.content.create(KEY, d);
        }
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

    /** Moves whenever the states are read again (a pack switched on or off): callers that cache what follows from them compare it. */
    public long version() {
        SwitchLedger l = ledger;
        return version.get() + (l == null ? 0 : l.version());
    }

    /**
     * Keeps the packs' states in the switches ledger from now on; the states held here that the ledger has no entry for are imported into
     * it, stamped with the time they were set.
     */
    public void ledger(SwitchLedger l) {
        writes.lock();
        try {
            java.util.List<SwitchLedger.Entry> old = new java.util.ArrayList<>();
            snapshot.values().stream().filter(st -> l.find(SwitchLedger.PACK, st.name()).isEmpty()).forEach(st -> old.add(new SwitchLedger.Entry(
                    SwitchLedger.PACK, st.name(), SwitchLedger.EVERYONE, st.enabled(), "imported from the pack states", st.updatedBy(),
                    (st.updatedAt() == null ? 0 : st.updatedAt().toEpochMilli()) << 16, l.node(), st.updatedAt())));
            l.merge(old);
            this.ledger = l;
        } finally {
            writes.unlock();
        }
    }

    private final java.util.concurrent.atomic.AtomicLong version = new java.util.concurrent.atomic.AtomicLong();

    private void reload() {
        version.incrementAndGet();
        if (content != null) {
            snapshot = Map.copyOf(content.value(KEY, Doc.class).map(d -> d.packs).orElse(new java.util.TreeMap<>()));
            return;
        }
        snapshot = tx.execute(s -> states.findAll().stream().map(e -> new State(e.name, e.enabled, e.updatedAt, e.updatedBy))
                .collect(Collectors.toUnmodifiableMap(State::name, Function.identity())));
    }

    /** True unless an administrator switched the pack off. */
    public boolean enabled(String pack) {
        SwitchLedger l = ledger;
        if (l != null) {
            return l.on(SwitchLedger.PACK, pack);
        }
        State s = snapshot.get(pack);
        return s == null || s.enabled();
    }

    public Optional<State> find(String pack) {
        SwitchLedger l = ledger;
        if (l != null) {
            return l.find(SwitchLedger.PACK, pack).map(e -> new State(pack, e.on(), e.at(), e.actor()));
        }
        return Optional.ofNullable(snapshot.get(pack));
    }

    public State set(String pack, boolean enabled, String actor) {
        return set(pack, enabled, actor, "");
    }

    /** Switches the pack on or off for everyone, with the reason the audit log keeps. */
    public State set(String pack, boolean enabled, String actor, String reason) {
        SwitchLedger l = ledger;
        if (l != null) {
            l.set(java.util.List.of(new SwitchLedger.Change(SwitchLedger.PACK, pack, enabled)), reason, actor);
            audit.record(actor, enabled ? "pack-enabled" : "pack-disabled", pack, reason == null ? "" : reason);
            return find(pack).orElseThrow();
        }
        writes.lock();
        try {
            if (content != null) {
                content.update(KEY, Doc.class, d -> {
                    Doc out = d == null ? new Doc() : d;
                    out.packs.put(pack, new State(pack, enabled, Instant.now(), actor));
                    return out;
                });
            } else {
                tx.executeWithoutResult(s -> {
                    PackStateEntity e = states.findById(pack).orElseGet(PackStateEntity::new);
                    e.name = pack;
                    e.enabled = enabled;
                    e.updatedAt = Instant.now();
                    e.updatedBy = actor;
                    states.save(e);
                });
            }
            reload();
            audit.record(actor, enabled ? "pack-enabled" : "pack-disabled", pack, "");
            return snapshot.get(pack);
        } finally {
            writes.unlock();
        }
    }
}
