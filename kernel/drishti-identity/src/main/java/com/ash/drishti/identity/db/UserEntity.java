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

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/** A user row ({@code drishti_user}) with its roles and assigned packs; see db/schema-*.sql. */
@Entity
@Table(name = "drishti_user")
public class UserEntity {

    @Id
    @Column(name = "username", length = 64)
    public String username;

    @Column(name = "display_name", nullable = false)
    public String displayName;

    @Column(name = "email", nullable = false)
    public String email = "";

    @Column(name = "desk", nullable = false)
    public String desk = "";

    @Column(name = "enabled", nullable = false)
    public boolean enabled;

    @Column(name = "must_change_password", nullable = false)
    public boolean mustChangePassword;

    @Column(name = "password_hash", nullable = false)
    public String passwordHash;

    @Column(name = "failed_attempts", nullable = false)
    public int failedAttempts;

    @Column(name = "locked_until")
    public Instant lockedUntil;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @Column(name = "last_login_at")
    public Instant lastLoginAt;

    @Column(name = "password_changed_at")
    public Instant passwordChangedAt;

    /** False: the user sees the default packs; true: exactly {@link #packs} (possibly none). */
    @Column(name = "packs_assigned", nullable = false)
    public boolean packsAssigned;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "drishti_user_role", joinColumns = @JoinColumn(name = "username"))
    @Column(name = "role")
    public Set<String> roles = new LinkedHashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "drishti_user_pack", joinColumns = @JoinColumn(name = "username"))
    @Column(name = "pack")
    public Set<String> packs = new LinkedHashSet<>();
}
