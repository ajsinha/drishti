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
import com.ash.drishti.identity.db.UserEntity;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.support.TransactionTemplate;

/** Users in the identity database. {@link UserService} serialises changes per user; each call is one transaction. */
public final class JpaUserStore implements UserStore {

    private final IdentityRepositories.Users users;
    private final TransactionTemplate tx;

    /**
     * Told of every user written or deleted through this store, after the write: Drishti*net copies users (their roles, enabled flag and
     * password hash) to the other nodes of a cluster, so a person known to one node stays signed in on another (CLUSTER_GUIDE.md › Drishti*net: many servers, one Drishti, section 11).
     */
    public interface Listener {
        void written(User user);

        void deleted(String username);
    }

    private final List<Listener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public void listen(Listener l) {
        listeners.add(l);
    }

    public JpaUserStore(IdentityRepositories.Users users, TransactionTemplate tx) {
        this.users = users;
        this.tx = tx;
    }

    /**
     * Users read in the last few seconds: every request checks its user (enabled, roles), and the identity database takes one
     * access at a time, so under load the reads queued (measured 2026-10-18). A write through this store forgets the user at
     * once; a change made by another server of a cluster shows within {@link #RECENT_NANOS}.
     */
    private record Hit(Optional<User> user, long at) {}
    static final long RECENT_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
    private final java.util.concurrent.ConcurrentHashMap<String, Hit> recent = new java.util.concurrent.ConcurrentHashMap<>();
    /** Writes committed through this store, counted after each commit and before its user is forgotten (see {@link #find}). */
    private final java.util.concurrent.atomic.AtomicLong writes = new java.util.concurrent.atomic.AtomicLong();

    @Override
    public Optional<User> find(String username) {
        long now = System.nanoTime();
        Hit h = recent.get(username);
        if (h != null && now - h.at() < RECENT_NANOS) {
            return h.user();
        }
        long began = writes.get();
        Optional<User> u = tx.execute(s -> users.findById(username).map(JpaUserStore::toUser));
        if (recent.size() > 100_000) {
            recent.clear();
        }
        // a write that committed while this read was in flight may have forgotten the user before the read is kept: the read then
        // put back what the database held before the write, for RECENT_NANOS (a node that had just seeded its administrator read it
        // as absent and took a peer's newer one over it, NetLastAdminTest). The read is kept only if no write was counted since it began
        Hit hit = new Hit(u, now);
        recent.put(username, hit);
        if (writes.get() != began) {
            recent.remove(username, hit);
        }
        return u;
    }

    @Override
    public List<User> all() {
        return tx.execute(s -> users.findAll().stream().map(JpaUserStore::toUser)
                .sorted(java.util.Comparator.comparing(User::username)).toList());
    }

    /**
     * Writes of users here one at a time: {@link #putIfEmpty}'s look and its write are one step against every other write of this server
     * (a lock, not a monitor: a virtual thread waiting on the database while holding a monitor pins its carrier on Java 21).
     */
    private final java.util.concurrent.locks.ReentrantLock writing = new java.util.concurrent.locks.ReentrantLock();

    @Override
    public boolean putIfEmpty(User u) {
        boolean wrote;
        writing.lock();
        try {
            wrote = Boolean.TRUE.equals(tx.execute(s -> {
                if (users.count() != 0) {
                    return false;
                }
                users.save(entity(u, new UserEntity()));
                return true;
            }));
        } finally {
            writing.unlock();
        }
        if (wrote) {
            writes.incrementAndGet();
            recent.remove(u.username());
            for (Listener l : listeners) {
                l.written(u);
            }
        }
        return wrote;
    }

    @Override
    public void put(User u) {
        writing.lock();
        try {
            tx.executeWithoutResult(s -> users.save(entity(u, users.findById(u.username()).orElseGet(UserEntity::new))));
        } finally {
            writing.unlock();
        }
        writes.incrementAndGet();
        recent.remove(u.username());
        for (Listener l : listeners) {
            l.written(u);
        }
    }

    private static UserEntity entity(User u, UserEntity e) {
        e.username = u.username();
        e.displayName = u.displayName();
        e.email = u.email() == null ? "" : u.email();
        e.desk = u.desk() == null ? "" : u.desk();
        e.enabled = u.enabled();
        e.mustChangePassword = u.mustChangePassword();
        e.passwordHash = u.passwordHash();
        e.failedAttempts = u.failedAttempts();
        e.lockedUntil = u.lockedUntil();
        e.createdAt = u.createdAt();
        e.updatedAt = u.updatedAt();
        e.lastLoginAt = u.lastLoginAt();
        e.passwordChangedAt = u.passwordChangedAt();
        e.roles.clear();
        e.roles.addAll(u.roles());
        e.packsAssigned = u.packs() != null;
        e.packs.clear();
        if (u.packs() != null) {
            e.packs.addAll(u.packs());
        }
        return e;
    }

    @Override
    public boolean delete(String username) {
        boolean gone = Boolean.TRUE.equals(tx.execute(s -> {
            if (!users.existsById(username)) {
                return false;
            }
            users.deleteById(username);
            return true;
        }));
        writes.incrementAndGet();
        recent.remove(username);
        if (gone) {
            for (Listener l : listeners) {
                l.deleted(username);
            }
        }
        return gone;
    }

    /** Whether the store holds no user at all (first start: seed or import). */
    public boolean isEmpty() {
        return users.count() == 0;
    }

    static User toUser(UserEntity e) {
        return new User(e.username, e.displayName, e.email, e.desk, new LinkedHashSet<>(e.roles), e.enabled, e.mustChangePassword,
                e.passwordHash, e.failedAttempts, e.lockedUntil, e.createdAt, e.updatedAt, e.lastLoginAt, e.passwordChangedAt,
                e.packsAssigned ? new LinkedHashSet<>(e.packs) : null);
    }
}
