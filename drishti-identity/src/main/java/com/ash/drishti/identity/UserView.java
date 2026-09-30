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
 * A user as the API shows it: everything except the password hash.
 *
 * @param username user name
 * @param displayName display name
 * @param email email
 * @param desk desk
 * @param roles roles
 * @param enabled may sign in
 * @param mustChangePassword asked to change the password
 * @param locked currently locked out after failed attempts
 * @param createdAt created
 * @param updatedAt updated
 * @param lastLoginAt last sign-in
 * @param passwordChangedAt last password change
 */
public record UserView(String username, String displayName, String email, String desk, Set<String> roles, boolean enabled,
        boolean mustChangePassword, boolean locked, Instant createdAt, Instant updatedAt, Instant lastLoginAt,
        Instant passwordChangedAt) {

    public static UserView of(User u, Instant now) {
        return new UserView(u.username(), u.displayName(), u.email(), u.desk(), u.roles(), u.enabled(), u.mustChangePassword(),
                u.locked(now), u.createdAt(), u.updatedAt(), u.lastLoginAt(), u.passwordChangedAt());
    }
}
