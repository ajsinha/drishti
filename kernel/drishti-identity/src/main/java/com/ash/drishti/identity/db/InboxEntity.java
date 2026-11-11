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

/** One inbox row ({@code drishti_inbox}): a pointer to a share or comment, never its content. */
@Entity
@Table(name = "drishti_inbox")
public class InboxEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "seq")
    public Long seq;

    @Column(name = "username", nullable = false)
    public String username;

    @Column(name = "at", nullable = false)
    public Instant at;

    @Column(name = "type", nullable = false)
    public String type;

    @Column(name = "kind", nullable = false)
    public String kind;

    @Column(name = "entity_id", nullable = false)
    public String entityId;

    @Column(name = "panel_id")
    public String panelId;

    @Column(name = "share_id")
    public String shareId;

    @Column(name = "thread_id")
    public String threadId;

    @Column(name = "comment_id")
    public String commentId;

    @Column(name = "actor", nullable = false)
    public String actor;

    @Column(name = "read_at")
    public Instant readAt;
}
