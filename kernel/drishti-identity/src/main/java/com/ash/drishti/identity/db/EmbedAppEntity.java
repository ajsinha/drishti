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

/** A host application registered to show Drishti views in its pages ({@code drishti_embed_app}); only hashes of its secret are kept. */
@Entity
@Table(name = "drishti_embed_app")
public class EmbedAppEntity {

    @Id
    @Column(name = "id", length = 32)
    public String id;

    @Column(name = "name", nullable = false)
    public String name;

    @Column(name = "contact", nullable = false)
    public String contact = "";

    /** Exact origins, one per line. */
    @Column(name = "origins", nullable = false)
    public String origins = "";

    /** Kinds the app may show, one per line; empty: every kind its users' roles open. */
    @Column(name = "kinds", nullable = false)
    public String kinds = "";

    /** Comma-separated embed scopes. */
    @Column(name = "scopes", nullable = false)
    public String scopes = "";

    /** Comma-separated: {@code id_token}, {@code jwt}. */
    @Column(name = "subject_types", nullable = false)
    public String subjectTypes = "";

    /** Extra accepted audiences of the user's ID tokens (the client ids the identity provider gave the app), one per line. */
    @Column(name = "subject_audiences", nullable = false)
    public String subjectAudiences = "";

    /** The app's public keys as a JWKS document, or null. */
    @Column(name = "jwks")
    public String jwks;

    /** SHA-256 (hex) of the client secret, or null when the app authenticates with a key only. */
    @Column(name = "secret_hash")
    public String secretHash;

    @Column(name = "prev_secret_hash")
    public String prevSecretHash;

    /** The previous secret still works until this instant (rotation grace). */
    @Column(name = "prev_secret_until")
    public Instant prevSecretUntil;

    @Column(name = "token_seconds", nullable = false)
    public int tokenSeconds;

    @Column(name = "calls_per_minute", nullable = false)
    public int callsPerMinute;

    @Column(name = "user_calls_per_minute", nullable = false)
    public int userCallsPerMinute;

    @Column(name = "enabled", nullable = false)
    public boolean enabled;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "created_by", nullable = false)
    public String createdBy = "";

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @Column(name = "updated_by", nullable = false)
    public String updatedBy = "";

    @Column(name = "last_used_at")
    public Instant lastUsedAt;
}
