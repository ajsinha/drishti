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

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code drishti.identity.*}.
 *
 * @param usersFile where users are kept (JSON, written atomically)
 * @param auditFile append-only audit log (JSON lines)
 * @param iterations PBKDF2 iterations for new hashes
 * @param minPasswordLength shortest accepted password
 * @param maxFailedAttempts failed sign-ins before a lockout
 * @param lockout how long a lockout lasts
 * @param seedAdmin create the development admin when the store is empty
 * @param seedUsername development admin user name
 * @param seedPassword development admin password (development only; change it after first sign-in)
 * @param seedRoles development admin roles
 * @param forcePasswordChangeOnCreate new users must change their password at first sign-in, unless the admin
 *     says otherwise for that user (default false)
 * @param forcePasswordChangeOnReset users must change a password an admin reset for them (default false)
 */
@ConfigurationProperties("drishti.identity")
public record IdentityProperties(
        String usersFile, String auditFile, Integer iterations, Integer minPasswordLength, Integer maxFailedAttempts,
        Duration lockout, Boolean seedAdmin, String seedUsername, String seedPassword, List<String> seedRoles,
        Boolean forcePasswordChangeOnCreate, Boolean forcePasswordChangeOnReset) {

    public IdentityProperties {
        usersFile = usersFile == null ? "./data/identity/users.json" : usersFile;
        auditFile = auditFile == null ? "./data/identity/audit.jsonl" : auditFile;
        iterations = iterations == null ? 240_000 : iterations;
        minPasswordLength = minPasswordLength == null ? 10 : minPasswordLength;
        maxFailedAttempts = maxFailedAttempts == null ? 5 : maxFailedAttempts;
        lockout = lockout == null ? Duration.ofMinutes(15) : lockout;
        seedAdmin = seedAdmin == null || seedAdmin;
        seedUsername = seedUsername == null ? "drishti-dev-admin" : seedUsername;
        seedPassword = seedPassword == null ? "drishti-dev-admin123" : seedPassword;
        seedRoles = seedRoles == null ? List.of("admin") : List.copyOf(seedRoles);
        forcePasswordChangeOnCreate = forcePasswordChangeOnCreate != null && forcePasswordChangeOnCreate;
        forcePasswordChangeOnReset = forcePasswordChangeOnReset != null && forcePasswordChangeOnReset;
    }
}
