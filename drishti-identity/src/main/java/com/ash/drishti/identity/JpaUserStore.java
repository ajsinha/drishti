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

    public JpaUserStore(IdentityRepositories.Users users, TransactionTemplate tx) {
        this.users = users;
        this.tx = tx;
    }

    @Override
    public Optional<User> find(String username) {
        return tx.execute(s -> users.findById(username).map(JpaUserStore::toUser));
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
    }

    @Override
    public boolean delete(String username) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            if (!users.existsById(username)) {
                return false;
            }
            users.deleteById(username);
            return true;
        }));
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
