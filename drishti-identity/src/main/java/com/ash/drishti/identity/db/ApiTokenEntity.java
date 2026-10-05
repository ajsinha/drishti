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
package com.ash.drishti.identity.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** A personal API token ({@code drishti_api_token}): only a hash of its secret is kept. */
@Entity
@Table(name = "drishti_api_token")
public class ApiTokenEntity {

    @Id
    @Column(name = "id", length = 24)
    public String id;

    @Column(name = "username", nullable = false)
    public String username;

    @Column(name = "name", nullable = false)
    public String name;

    /** SHA-256 of the secret, hex. */
    @Column(name = "secret_hash", nullable = false)
    public String secretHash;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "expires_at")
    public Instant expiresAt;

    @Column(name = "last_used_at")
    public Instant lastUsedAt;

    @Column(name = "revoked_at")
    public Instant revokedAt;

    /** Comma-separated scopes; null for a token made before scopes existed (it reads). */
    @Column(name = "scopes", length = 200)
    public String scopes;
}
