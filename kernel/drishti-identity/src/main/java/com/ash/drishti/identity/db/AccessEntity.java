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

/** One read of data: who looked at what, when ({@code drishti_access}). */
@Entity
@Table(name = "drishti_access")
public class AccessEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    public Long id;

    @Column(name = "at", nullable = false)
    public Instant at;

    @Column(name = "username", nullable = false)
    public String username;

    /** view, raw, history, search, export. */
    @Column(name = "action", nullable = false)
    public String action;

    /** The entity's kind, or the searched kind; null when unknown. */
    @Column(name = "kind")
    public String kind;

    /** The entity's id, or null for a search. */
    @Column(name = "entity_id")
    public String entityId;

    /** The search text, or the field of a history read. */
    @Column(name = "detail", length = 500)
    public String detail;

    /** The business date asked for, or null for live. */
    @Column(name = "business_date")
    public String businessDate;
}
