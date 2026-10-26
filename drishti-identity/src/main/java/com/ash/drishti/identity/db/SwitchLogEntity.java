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
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One applied change of a switch ({@code drishti_switch_log}), the audit trail Admin → Commands shows: who, when, why, and the node it
 * was made on. A change learned from another node is written here too, once ({@code scope, name, grantee, stamp, node} is unique).
 */
@Entity
@Table(name = "drishti_switch_log")
public class SwitchLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    public Long id;

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

    @Column(name = "stamp", nullable = false)
    public long stamp;

    @Column(name = "node", nullable = false)
    public String node = "";

    /** When the change was made (on its node). */
    @Column(name = "at", nullable = false)
    public Instant at;

    /** When this node applied it: {@code at} for its own changes, later for one learned from a peer. */
    @Column(name = "applied_at", nullable = false)
    public Instant appliedAt;
}
