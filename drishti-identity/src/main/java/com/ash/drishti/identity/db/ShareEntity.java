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
import java.time.LocalDate;

/** One share with a note ({@code drishti_share}): the record of sending. Recipients are in {@link ShareRecipientEntity}. */
@Entity
@Table(name = "drishti_share")
public class ShareEntity {

    @Id
    @Column(name = "id")
    public String id;

    @Column(name = "sender", nullable = false)
    public String sender;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "kind", nullable = false)
    public String kind;

    @Column(name = "entity_id", nullable = false)
    public String entityId;

    @Column(name = "panel_id")
    public String panelId;

    @Column(name = "gate_kind")
    public String gateKind;

    @Column(name = "pin_date")
    public LocalDate pinDate;

    @Column(name = "pin_live", nullable = false)
    public boolean pinLive;

    @Column(name = "pin_known_at")
    public Instant pinKnownAt;

    @Column(name = "pin_generation", nullable = false)
    public long pinGeneration;

    @Column(name = "pin_source")
    public String pinSource;

    @Column(name = "body", nullable = false)
    public String body;

    @Column(name = "masked_spans", nullable = false)
    public String maskedSpans;

    @Column(name = "channels", nullable = false)
    public String channels;

    @Column(name = "thread_id")
    public String threadId;

    @Column(name = "hash", nullable = false)
    public String hash;
}
