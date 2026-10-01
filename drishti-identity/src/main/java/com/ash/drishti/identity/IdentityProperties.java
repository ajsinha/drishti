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
 * @param usersFile the users file of releases before 1.10, imported into the database once (when it holds no users)
 * @param auditFile the audit file of releases before 1.10, imported with the users
 * @param iterations PBKDF2 iterations for new hashes
 * @param minPasswordLength shortest accepted password
 * @param maxFailedAttempts failed sign-ins before a lockout
 * @param lockout how long a lockout lasts
 * @param seedAdmin create the development admin when the store is empty
 * @param seedUsername development admin user name
 * @param seedPassword development admin password (development only; change it after first sign-in)
 * @param seedRoles development admin roles
 * @param seedDisplayName development admin display name
 * @param forcePasswordChangeOnCreate new users must change their password at first sign-in, unless the admin
 *     says otherwise for that user (default false)
 * @param forcePasswordChangeOnReset users must change a password an admin reset for them (default false)
 * @param preferencesDir per-user documents of releases before 1.10, imported with the users
 * @param databaseUrl the identity database: {@code jdbc:sqlite:<file>} (default) or {@code jdbc:postgresql://...}
 * @param databaseUser database user (PostgreSQL)
 * @param databasePassword database password (PostgreSQL)
 * @param databasePoolSize connections in the pool (SQLite allows one writer at a time whatever the size)
 */
@ConfigurationProperties("drishti.identity")
public record IdentityProperties(
        String usersFile, String auditFile, Integer iterations, Integer minPasswordLength, Integer maxFailedAttempts,
        Duration lockout, Boolean seedAdmin, String seedUsername, String seedPassword, List<String> seedRoles,
        Boolean forcePasswordChangeOnCreate, Boolean forcePasswordChangeOnReset, String preferencesDir,
        String databaseUrl, String databaseUser, String databasePassword, Integer databasePoolSize, String seedDisplayName) {

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
        preferencesDir = preferencesDir == null ? "./data/identity/preferences" : preferencesDir;
        databaseUrl = databaseUrl == null || databaseUrl.isBlank() ? "jdbc:sqlite:./data/identity/drishti.db" : databaseUrl;
        databaseUser = databaseUser == null ? "" : databaseUser;
        databasePassword = databasePassword == null ? "" : databasePassword;
        databasePoolSize = databasePoolSize == null ? 8 : databasePoolSize;
        seedDisplayName = seedDisplayName == null || seedDisplayName.isBlank() ? "Development admin" : seedDisplayName;
    }

    /** True for SQLite, false for PostgreSQL. */
    public boolean sqlite() {
        return databaseUrl.startsWith("jdbc:sqlite:");
    }
}
