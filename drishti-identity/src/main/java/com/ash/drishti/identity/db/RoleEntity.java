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

/**
 * A role defined by an administrator ({@code drishti_role}): the kinds it may open and what else it may do. Roles from
 * configuration and packs are not stored; they are built in and read-only.
 */
@Entity
@Table(name = "drishti_role")
public class RoleEntity {

    @Id
    @Column(name = "name", length = 64)
    public String name;

    @Column(name = "description", nullable = false)
    public String description = "";

    /** May see unredacted raw JSON. */
    @Column(name = "raw_json", nullable = false)
    public boolean raw;

    @Column(name = "author", nullable = false)
    public boolean author;

    @Column(name = "approve", nullable = false)
    public boolean approve;

    @Column(name = "admin", nullable = false)
    public boolean admin;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @Column(name = "updated_by", nullable = false)
    public String updatedBy = "";

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "drishti_role_kind", joinColumns = @JoinColumn(name = "role"))
    @Column(name = "kind")
    public Set<String> kinds = new LinkedHashSet<>();
}
