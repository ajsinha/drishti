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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.db.IdentityRepositories;
import com.ash.drishti.identity.db.RoleEntity;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Roles administrators define, kept in the identity database. Every permission check reads an immutable snapshot
 * (lock-free; replaced after each change), so checks never touch the database. Changes are serialised.
 */
public final class RoleStore {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{1,63}");
    private final IdentityRepositories.Roles roles;
    private final TransactionTemplate tx;
    private final AuditLog audit;
    private final ReentrantLock writes = new ReentrantLock();
    private volatile Map<String, RoleDefinition> snapshot;

    public RoleStore(IdentityRepositories.Roles roles, TransactionTemplate tx, AuditLog audit) {
        this.roles = roles;
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
        snapshot = tx.execute(s -> roles.findAll().stream().map(RoleStore::toDefinition)
                .collect(Collectors.toUnmodifiableMap(RoleDefinition::name, Function.identity())));
    }

    public Optional<RoleDefinition> find(String name) {
        return Optional.ofNullable(snapshot.get(name));
    }

    public List<RoleDefinition> all() {
        return snapshot.values().stream().sorted(Comparator.comparing(RoleDefinition::name)).toList();
    }

    /**
     * Creates or replaces a role.
     *
     * @param reserved names that may not be used (built-in roles)
     */
    public RoleDefinition save(RoleDefinition role, String actor, java.util.Set<String> reserved) {
        if (role.name() == null || !NAME.matcher(role.name()).matches()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "role names are 2-64 of a-z, 0-9 and -, starting with a letter");
        }
        if (reserved.contains(role.name())) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "'" + role.name() + "' is a built-in role; choose another name");
        }
        if (role.kinds().isEmpty()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a role opens at least one kind (or * for all)");
        }
        writes.lock();
        try {
            boolean created = tx.execute(s -> {
                RoleEntity e = roles.findById(role.name()).orElse(null);
                boolean isNew = e == null;
                Instant now = Instant.now();
                if (isNew) {
                    e = new RoleEntity();
                    e.name = role.name();
                    e.createdAt = now;
                }
                e.description = role.description();
                e.raw = role.raw();
                e.author = role.author();
                e.approve = role.approve();
                e.admin = role.admin();
                e.updatedAt = now;
                e.updatedBy = actor;
                e.kinds.clear();
                e.kinds.addAll(new TreeSet<>(role.kinds()));
                roles.save(e);
                return isNew;
            });
            reload();
            audit.record(actor, created ? "role-created" : "role-updated", role.name(), describe(role));
            return snapshot.get(role.name());
        } finally {
            writes.unlock();
        }
    }

    public boolean delete(String name, String actor) {
        writes.lock();
        try {
            boolean gone = Boolean.TRUE.equals(tx.execute(s -> {
                if (!roles.existsById(name)) {
                    return false;
                }
                roles.deleteById(name);
                return true;
            }));
            if (gone) {
                reload();
                audit.record(actor, "role-deleted", name, "");
            }
            return gone;
        } finally {
            writes.unlock();
        }
    }

    private static String describe(RoleDefinition r) {
        return "kinds=" + r.kinds() + (r.raw() ? " raw" : "") + (r.author() ? " author" : "") + (r.approve() ? " approve" : "")
                + (r.admin() ? " admin" : "");
    }

    private static RoleDefinition toDefinition(RoleEntity e) {
        return new RoleDefinition(e.name, e.description, List.copyOf(new TreeSet<>(e.kinds)), e.raw, e.author, e.approve, e.admin, false,
                e.updatedAt, e.updatedBy);
    }
}
