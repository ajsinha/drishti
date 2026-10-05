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
package com.ash.drishti.server.collab.compliance;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.collab.CollabProperties;
import com.ash.drishti.identity.collab.Hold;
import com.ash.drishti.identity.collab.HoldStore;
import com.ash.drishti.identity.collab.ThreadStore;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Legal holds: placed and released by the {@code compliance} power, audited, never deleted. A hold stops retention and permanent
 * removal for what it covers; it does not change how anything is read or written (COLLABORATION.md, Retention, legal hold and export).
 */
public final class HoldService {

    /** What a hold covers; strings are ISO instants or dates (see {@code ComplianceDates}). */
    public record Request(String scope, String kind, String id, String user, String thread, String from, String to, String reason) {}

    private final HoldStore store;
    private final ThreadStore threads;
    private final Entitlements entitlements;
    private final AuditLog audit;
    private final CollabProperties props;
    private final Clock clock;

    public HoldService(HoldStore store, ThreadStore threads, Entitlements entitlements, AuditLog audit, CollabProperties props, Clock clock) {
        this.store = store;
        this.threads = threads;
        this.entitlements = entitlements;
        this.audit = audit;
        this.props = props;
        this.clock = clock;
    }

    public Hold place(Principal p, Request r) {
        requireOn();
        entitlements.requireCompliance(p);
        String scope = r.scope() == null ? "" : r.scope().strip().toLowerCase();
        String reason = r.reason() == null ? "" : r.reason().strip();
        if (reason.isEmpty() || reason.length() > 400) {
            throw new DrishtiException(ErrorCode.TEXT_REFUSED, "say why the hold is placed (at most 400 characters)");
        }
        Instant from = ComplianceDates.from(r.from());
        Instant to = ComplianceDates.to(r.to());
        ComplianceDates.requireOrdered(from, to);
        String kind = blank(r.kind());
        String id = blank(r.id());
        String user = blank(r.user());
        String thread = blank(r.thread());
        switch (scope) {
            case Hold.ENTITY -> need(kind != null && id != null, "an entity hold needs kind and id");
            case Hold.KIND -> need(kind != null, "a kind hold needs kind");
            case Hold.USER -> need(user != null, "a user hold needs user");
            case Hold.THREAD -> {
                need(thread != null, "a thread hold needs thread");
                if (threads.thread(thread).isEmpty()) {
                    throw new DrishtiException(ErrorCode.THREAD_NOT_FOUND, "no thread " + thread);
                }
            }
            case Hold.ALL -> { }
            default -> throw new DrishtiException(ErrorCode.BAD_REQUEST, "scope is entity, kind, user, thread or all");
        }
        Hold saved = store.place(new Hold(0, scope, Hold.ENTITY.equals(scope) || Hold.KIND.equals(scope) ? kind : null,
                Hold.ENTITY.equals(scope) ? id : null, Hold.USER.equals(scope) ? user : null, Hold.THREAD.equals(scope) ? thread : null, from, to,
                reason, p.user(), clock.instant(), null, null));
        audit.record(p.user(), "collab.hold.place", describe(saved), "hold " + saved.id() + ": " + reason);
        return saved;
    }

    public Hold release(Principal p, long id) {
        requireOn();
        entitlements.requireCompliance(p);
        Hold h = store.find(id).orElseThrow(() -> new DrishtiException(ErrorCode.BAD_REQUEST, "no hold " + id));
        if (!store.release(id, p.user(), clock.instant())) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "hold " + id + " is released already");
        }
        audit.record(p.user(), "collab.hold.release", describe(h), "hold " + id);
        return store.find(id).orElse(h);
    }

    public List<Hold> list(Principal p, boolean activeOnly) {
        requireOn();
        entitlements.requireCompliance(p);
        return store.list(activeOnly);
    }

    /** The active holds, for retention and permanent removal (no caller check: the system's own question). */
    List<Hold> active() {
        return store.list(true);
    }

    static String describe(Hold h) {
        return switch (h.scope()) {
            case Hold.ENTITY -> h.kind() + "/" + h.entityId();
            case Hold.KIND -> h.kind();
            case Hold.USER -> "user:" + h.username();
            case Hold.THREAD -> h.threadId();
            default -> "all";
        };
    }

    private void requireOn() {
        if (!props.enabled()) {
            throw new DrishtiException(ErrorCode.SHARING_OFF, "collaboration is off (drishti.collab.enabled)");
        }
    }

    private static void need(boolean ok, String message) {
        if (!ok) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, message);
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
