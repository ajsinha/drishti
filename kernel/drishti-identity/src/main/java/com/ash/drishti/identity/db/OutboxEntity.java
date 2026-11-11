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

/** One delivery ({@code drishti_outbox}). */
@Entity
@Table(name = "drishti_outbox")
public class OutboxEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "seq")
    public Long seq;

    @Column(name = "channel", nullable = false)
    public String channel;

    @Column(name = "recipient", nullable = false)
    public String recipient;

    @Column(name = "template", nullable = false)
    public String template;

    @Column(name = "ref_id", nullable = false)
    public String refId;

    @Column(name = "state", nullable = false)
    public String state;

    @Column(name = "attempts", nullable = false)
    public int attempts;

    @Column(name = "next_at", nullable = false)
    public Instant nextAt;

    @Column(name = "lease_until")
    public Instant leaseUntil;

    @Column(name = "leased_by")
    public String leasedBy;

    @Column(name = "last_error")
    public String lastError;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "sent_at")
    public Instant sentAt;
}
