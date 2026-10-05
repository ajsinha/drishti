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

    private final com.ash.drishti.identity.collab.Seal.Store seals;

    public JpaAuditLog(IdentityRepositories.Audit audit) {
        this(audit, null);
    }

    /** With {@code seals}, every row is chained to the one before it (tamper evidence: {@link #verify}). */
    public JpaAuditLog(IdentityRepositories.Audit audit, com.ash.drishti.identity.collab.Seal.Store seals) {
        this.audit = audit;
        this.seals = seals;
    }

    private static String rowHash(String prev, AuditEventEntity e) {
        return com.ash.drishti.identity.collab.Seals.sha(prev + '\u001e' + com.ash.drishti.identity.collab.Seals.join(e.id, e.at, e.actor, e.action,
                e.subject, e.detail));
    }

    /** Chains the saved row after the last sealed one. */
    private synchronized void seal(AuditEventEntity e) {
        if (seals == null) {
            return;
        }
        var head = seals.get("audit:head");
        String prev = head.map(com.ash.drishti.identity.collab.Seal::hash).orElse("");
        String hash = rowHash(prev, e);
        seals.put("audit:" + e.id, new com.ash.drishti.identity.collab.Seal(e.id, hash));
        seals.put("audit:head", new com.ash.drishti.identity.collab.Seal(head.map(com.ash.drishti.identity.collab.Seal::count).orElse(0L) + 1, hash));
    }

    /** What the audit trail's seals say is wrong (empty when it holds); rows from before sealing began are not judged. */
    public List<String> verify(int max) {
        List<String> problems = new java.util.ArrayList<>();
        if (seals == null) {
            return problems;
        }
        String prev = "";
        long rows = 0;
        boolean sealedYet = false;
        long after = 0;
        for (;;) {
            List<AuditEventEntity> page = audit.findByIdGreaterThanOrderByIdAsc(after, PageRequest.of(0, 500));
            if (page.isEmpty()) {
                break;
            }
            for (AuditEventEntity e : page) {
                after = e.id;
                var seal = seals.get("audit:" + e.id);
                if (seal.isEmpty()) {
                    if (sealedYet && problems.size() < max) {
                        problems.add("audit row " + e.id + " has no seal (added outside Drishti)");
                    }
                    continue;
                }
                sealedYet = true;
                rows++;
                String expect = rowHash(prev, e);
                if (!expect.equals(seal.get().hash()) && problems.size() < max) {
                    problems.add("audit row " + e.id + " was changed, or a row before it was removed");
                }
                prev = seal.get().hash();
            }
        }
        var head = seals.get("audit:head");
        if (head.isPresent() && (head.get().count() != rows || !head.get().hash().equals(prev)) && problems.size() < max) {
            problems.add("the audit trail's newest rows were removed (sealed " + head.get().count() + ", found " + rows + ")");
        }
        return problems;
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
            seal(e);
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
