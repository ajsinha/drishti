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

/** One legal hold ({@code drishti_collab_hold}). */
@Entity
@Table(name = "drishti_collab_hold")
public class HoldEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    public Long id;

    @Column(name = "scope", nullable = false)
    public String scope;

    @Column(name = "kind")
    public String kind;

    @Column(name = "entity_id")
    public String entityId;

    @Column(name = "username")
    public String username;

    @Column(name = "thread_id")
    public String threadId;

    @Column(name = "date_from")
    public Instant dateFrom;

    @Column(name = "date_to")
    public Instant dateTo;

    @Column(name = "reason", nullable = false)
    public String reason;

    @Column(name = "placed_by", nullable = false)
    public String placedBy;

    @Column(name = "placed_at", nullable = false)
    public Instant placedAt;

    @Column(name = "released_by")
    public String releasedBy;

    @Column(name = "released_at")
    public Instant releasedAt;
}
