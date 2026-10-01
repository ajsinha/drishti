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
package com.ash.drishti.identity;

import com.ash.drishti.identity.db.AuditEventEntity;
import com.ash.drishti.identity.db.IdentityRepositories;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;

/** The audit trail in the identity database: one row per event, newest first by id. */
public final class JpaAuditLog implements AuditLog {

    private static final Logger LOG = LoggerFactory.getLogger(JpaAuditLog.class);
    private final IdentityRepositories.Audit audit;

    public JpaAuditLog(IdentityRepositories.Audit audit) {
        this.audit = audit;
    }

    @Override
    public void record(String actor, String action, String subject, String detail) {
        AuditEventEntity e = new AuditEventEntity();
        e.at = Instant.now();
        e.actor = actor == null ? "" : actor;
        e.action = action;
        e.subject = subject == null ? "" : subject;
        e.detail = detail == null ? "" : detail;
        try {
            audit.save(e);
        } catch (RuntimeException ex) {
            LOG.error("audit write failed for {} {}", action, subject, ex);   // never lose the action itself over its record
        }
    }

    /** Records an event that happened earlier (importing an older audit file). */
    void recordAt(Event ev) {
        AuditEventEntity e = new AuditEventEntity();
        e.at = ev.at();
        e.actor = ev.actor();
        e.action = ev.action();
        e.subject = ev.subject() == null ? "" : ev.subject();
        e.detail = ev.detail() == null ? "" : ev.detail();
        audit.save(e);
    }

    @Override
    public List<Event> recent(int limit, String subject) {
        PageRequest page = PageRequest.of(0, Math.max(1, limit));
        List<AuditEventEntity> rows = subject == null || subject.isBlank() ? audit.findAllByOrderByIdDesc(page)
                : audit.findBySubjectOrActorOrderByIdDesc(subject, subject, page);
        return rows.stream().map(r -> new Event(r.at, r.actor, r.action, r.subject, r.detail)).toList();
    }
}
