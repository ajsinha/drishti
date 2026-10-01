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

/** One fired alert ({@code drishti_alert}): a user's rule became true for an entity. */
@Entity
@Table(name = "drishti_alert")
public class AlertEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    public Long id;

    @Column(name = "username", nullable = false)
    public String username;

    @Column(name = "at", nullable = false)
    public Instant at;

    @Column(name = "rule", nullable = false)
    public String rule;

    @Column(name = "kind", nullable = false)
    public String kind;

    @Column(name = "entity_id", nullable = false)
    public String entityId;

    @Column(name = "severity", nullable = false)
    public String severity;

    @Column(name = "message", nullable = false)
    public String message;

    @Column(name = "generation", nullable = false)
    public long generation;
}
