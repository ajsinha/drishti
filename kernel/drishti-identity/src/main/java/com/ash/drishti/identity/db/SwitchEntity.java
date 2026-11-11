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

/**
 * The newest state of one administrator's switch ({@code drishti_switch}): a command or a pack, on or off, for a grantee ({@code *}
 * is everyone; a role name once commands are granted per role). No row: on. The id is {@code scope:name:grantee}.
 */
@Entity
@Table(name = "drishti_switch")
public class SwitchEntity {

    @Id
    @Column(name = "id", length = 200)
    public String id;

    @Column(name = "scope", nullable = false)
    public String scope;

    @Column(name = "name", nullable = false)
    public String name;

    @Column(name = "grantee", nullable = false)
    public String grantee = "*";

    @Column(name = "enabled", nullable = false)
    public boolean enabled;

    @Column(name = "reason", nullable = false)
    public String reason = "";

    @Column(name = "actor", nullable = false)
    public String actor = "";

    /** The hybrid-logical-clock stamp of the change: wall milliseconds shifted left 16 bits, plus a counter. */
    @Column(name = "stamp", nullable = false)
    public long stamp;

    @Column(name = "node", nullable = false)
    public String node = "";

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;
}
