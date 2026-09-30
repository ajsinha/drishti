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

import java.time.Instant;
import java.util.Set;

/**
 * A Drishti user. Immutable; changes produce a new record. The password hash never leaves the server
 * (see {@link UserView}).
 *
 * @param username unique, lower-case, 3-64 of {@code a-z 0-9 . _ -}
 * @param displayName shown in the top bar
 * @param email contact address, optional
 * @param desk the desk shown beside the name ({@code Rates desk})
 * @param roles role names from {@code drishti.security.roles}
 * @param enabled disabled users cannot sign in
 * @param mustChangePassword the user is asked to change the password at next sign-in
 * @param passwordHash {@code pbkdf2_sha256$iterations$salt$hash}
 * @param failedAttempts consecutive failed sign-ins
 * @param lockedUntil sign-in refused until then, or null
 * @param createdAt created
 * @param updatedAt last changed
 * @param lastLoginAt last successful sign-in, or null
 * @param passwordChangedAt last password change
 */
public record User(
        String username,
        String displayName,
        String email,
        String desk,
        Set<String> roles,
        boolean enabled,
        boolean mustChangePassword,
        String passwordHash,
        int failedAttempts,
        Instant lockedUntil,
        Instant createdAt,
        Instant updatedAt,
        Instant lastLoginAt,
        Instant passwordChangedAt) {

    public User {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    public boolean locked(Instant now) {
        return lockedUntil != null && now.isBefore(lockedUntil);
    }

    User with(String displayName, String email, String desk, Set<String> roles, boolean enabled, Instant now) {
        return new User(username, displayName, email, desk, roles, enabled, mustChangePassword, passwordHash, failedAttempts,
                lockedUntil, createdAt, now, lastLoginAt, passwordChangedAt);
    }

    User withPassword(String hash, boolean mustChange, Instant now) {
        return new User(username, displayName, email, desk, roles, enabled, mustChange, hash, 0, null, createdAt, now, lastLoginAt, now);
    }

    User withLogin(Instant now) {
        return new User(username, displayName, email, desk, roles, enabled, mustChangePassword, passwordHash, 0, null, createdAt,
                updatedAt, now, passwordChangedAt);
    }

    User withFailure(int attempts, Instant lockUntil) {
        return new User(username, displayName, email, desk, roles, enabled, mustChangePassword, passwordHash, attempts, lockUntil,
                createdAt, updatedAt, lastLoginAt, passwordChangedAt);
    }
}
