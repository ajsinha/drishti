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
package com.ash.drishti.server.collab;

import com.ash.drishti.identity.User;
import com.ash.drishti.identity.UserService;
import com.ash.drishti.server.security.Principal;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * The principal of any user, not only the caller: the current roles of an enabled user, none otherwise, looked up at most once a
 * minute per user. What a person receives is computed for that person from what they may see now (COLLABORATION.md, Security),
 * so delivery, the inbox and the alert engine decide rights through this one place.
 */
public final class Principals {

    private final UserService users;
    private final com.ash.drishti.server.security.Entitlements entitlements;
    private final LoadingCache<String, Principal> cache;

    public Principals(UserService users, com.ash.drishti.server.security.Entitlements entitlements) {
        this(users, entitlements, Duration.ofMinutes(1));
    }

    public Principals(UserService users, com.ash.drishti.server.security.Entitlements entitlements, Duration ttl) {
        this.users = users;
        this.entitlements = entitlements;
        this.cache = Caffeine.newBuilder().maximumSize(10_000).expireAfterWrite(ttl).build(this::load);
    }

    private Principal load(String user) {
        return new Principal(user, users.find(user).filter(User::enabled).map(u -> List.copyOf(u.roles())).orElse(List.of()));
    }

    /** The user as they are now (no roles for an unknown or disabled user). */
    public Principal of(String user) {
        return cache.get(user);
    }

    /** The user's field masks as a function on documents. */
    public UnaryOperator<com.ash.drishti.api.DataNode> redactor(String user) {
        return entitlements.redactor(of(user));
    }

    /** The directory entry, when the user exists. */
    public Optional<User> user(String name) {
        return users.find(name);
    }

    /** Forgets what is cached for a user (after their roles change). */
    public void invalidate(String user) {
        cache.invalidate(user);
    }
}
