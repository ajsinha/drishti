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
     * password hash) to the other nodes of a cluster, so a person known to one node stays signed in on another (DRISHTI_NET.md section 11).
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

    @Override
    public Optional<User> find(String username) {
        long now = System.nanoTime();
        Hit h = recent.get(username);
        if (h != null && now - h.at() < RECENT_NANOS) {
            return h.user();
        }
        Optional<User> u = tx.execute(s -> users.findById(username).map(JpaUserStore::toUser));
        if (recent.size() > 100_000) {
            recent.clear();
        }
        recent.put(username, new Hit(u, now));
        return u;
    }

    @Override
    public List<User> all() {
        return tx.execute(s -> users.findAll().stream().map(JpaUserStore::toUser)
                .sorted(java.util.Comparator.comparing(User::username)).toList());
    }

    @Override
    public void put(User u) {
        tx.executeWithoutResult(s -> {
            UserEntity e = users.findById(u.username()).orElseGet(UserEntity::new);
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
            users.save(e);
        });
        recent.remove(u.username());
        for (Listener l : listeners) {
            l.written(u);
        }
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
