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
package com.ash.drishti.compute.approval;

import com.ash.drishti.compute.ComputeException;
import com.ash.drishti.compute.env.LockFile;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The approval of compute requirements (docs/architecture/PYTHON_COMPUTE.md section 6). Nothing a connector or pool requires is fetched,
 * built or run until every item of it is approved. A subject whose items are not all approved files a request; an administrator approves it
 * (with a reason) or rejects it; an approval can be revoked, after which new requests are refused at once and in-flight ones finish.
 * <b>Four eyes:</b> the person who asked can neither approve nor reject their own request. Every step is an audit entry (who, when, what,
 * why). The approved set is read from the store at most every {@code refresh} so a cluster's nodes converge on the same approvals.
 */
public final class Approvals {

    /** Receives audit entries: actor, action, subject, detail. */
    @FunctionalInterface
    public interface Audit {
        void record(String actor, String action, String subject, String detail);
    }

    /** Where a subject stands. {@code missing} lists the items not approved; {@code request} is the open (or last) request about them. */
    public record Status(String state, List<ApprovalItem> missing, ApprovalRequest request) {
        public boolean approved() {
            return "approved".equals(state);
        }

        /** In words, for a connector's health and a panel. */
        public String words() {
            return switch (state) {
                case "approved" -> "approved";
                case "rejected" -> "requirements rejected" + (request == null ? "" : " by " + request.decidedBy() + ": " + request.reason());
                default -> "waiting for approval of " + missing.size() + " requirement" + (missing.size() == 1 ? "" : "s")
                        + (request == null ? "" : " (request " + request.id() + ", Admin → Compute)");
            };
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApprovalStore store;
    private final Audit audit;
    private final Clock clock;
    private final long refreshMs;
    private final List<Consumer<ApprovalRequest>> listeners = new CopyOnWriteArrayList<>();
    private volatile Set<String> approved;
    private volatile long readAt;

    public Approvals(ApprovalStore store, Audit audit, Clock clock, long refreshMs) {
        this.store = store;
        this.audit = audit == null ? (a, b, c, d) -> { } : audit;
        this.clock = clock;
        this.refreshMs = refreshMs;
    }

    /** Called after every decision (approve, reject, revoke): connectors re-check, environments build. */
    public void onDecision(Consumer<ApprovalRequest> l) {
        listeners.add(l);
    }

    private Set<String> approvedKeys() {
        long now = System.currentTimeMillis();
        Set<String> a = approved;
        if (a == null || now - readAt > refreshMs) {
            a = store.approvedKeys();
            approved = a;
            readAt = now;
        }
        return a;
    }

    private void invalidate() {
        approved = null;
    }

    /** Where the subject's items stand, without filing anything. */
    public Status status(String subject, List<ApprovalItem> items) {
        Set<String> ok = approvedKeys();
        List<ApprovalItem> missing = items.stream().filter(i -> !ok.contains(i.key())).toList();
        if (missing.isEmpty()) {
            return new Status("approved", List.of(), null);
        }
        String fp = fingerprint(missing);
        for (ApprovalRequest r : store.of(subject, ApprovalRequest.REJECTED)) {
            if (r.fingerprint().equals(fp)) {
                return new Status("rejected", missing, r);
            }
        }
        List<ApprovalRequest> pending = store.of(subject, ApprovalRequest.PENDING);
        ApprovalRequest open = pending.stream().filter(r -> r.fingerprint().equals(fp)).findFirst().orElse(null);
        return new Status("pending", missing, open);
    }

    /**
     * Files a request for whatever of the subject's items is not approved (none when all are; the existing one when the same set is already
     * pending; an older pending request of the subject for another set is superseded). Returns the subject's status afterwards.
     */
    public Status request(String subject, List<ApprovalItem> items, String requestedBy) {
        Status s = status(subject, items);
        if (s.approved() || s.request() != null) {
            return s;
        }
        String fp = fingerprint(s.missing());
        for (ApprovalRequest old : store.of(subject, ApprovalRequest.PENDING)) {
            store.save(old.superseded());
        }
        ApprovalRequest r = new ApprovalRequest(newId(), subject, fp, s.missing(), ApprovalRequest.PENDING,
                requestedBy == null || requestedBy.isBlank() ? "unknown" : requestedBy, clock.instant(), null, null, null, null, null, null);
        store.save(r);
        audit.record(r.requestedBy(), "compute-request", subject, r.id() + ": " + describe(r.items()));
        return new Status("pending", s.missing(), r);
    }

    /** Approves a pending request. The approver must not be the person who asked (four eyes); a reason is required. */
    public ApprovalRequest approve(String id, String approver, String reason) {
        ApprovalRequest r = pending(id, approver, reason, "approve");
        Instant now = clock.instant();
        store.approve(r, approver, now);
        ApprovalRequest done = r.decided(ApprovalRequest.APPROVED, approver, now, reason.strip());
        store.save(done);
        invalidate();
        audit.record(approver, "compute-approve", r.subject(), id + ": " + reason.strip() + " — " + describe(r.items()));
        listeners.forEach(l -> l.accept(done));
        return done;
    }

    /** Rejects a pending request (four eyes, a reason required). */
    public ApprovalRequest reject(String id, String approver, String reason) {
        ApprovalRequest r = pending(id, approver, reason, "reject");
        ApprovalRequest done = r.decided(ApprovalRequest.REJECTED, approver, clock.instant(), reason.strip());
        store.save(done);
        audit.record(approver, "compute-reject", r.subject(), id + ": " + reason.strip());
        listeners.forEach(l -> l.accept(done));
        return done;
    }

    /** Revokes an approval: its items are approved no longer (unless another live approval covers them). */
    public ApprovalRequest revoke(String id, String by, String reason) {
        ApprovalRequest r = store.get(id).orElseThrow(() -> new ComputeException(ComputeException.Code.REFUSED, "no approval request " + id));
        if (!ApprovalRequest.APPROVED.equals(r.state())) {
            throw new ComputeException(ComputeException.Code.REFUSED, id + " is " + r.state() + ", not approved: nothing to revoke");
        }
        if (reason == null || reason.isBlank()) {
            throw new ComputeException(ComputeException.Code.REFUSED, "say why the approval is revoked");
        }
        Instant now = clock.instant();
        store.revoke(id, now);
        ApprovalRequest done = r.revoked(by, now, reason.strip());
        store.save(done);
        invalidate();
        audit.record(by, "compute-revoke", r.subject(), id + ": " + reason.strip());
        listeners.forEach(l -> l.accept(done));
        return done;
    }

    private ApprovalRequest pending(String id, String approver, String reason, String verb) {
        ApprovalRequest r = store.get(id).orElseThrow(() -> new ComputeException(ComputeException.Code.REFUSED, "no approval request " + id));
        if (!ApprovalRequest.PENDING.equals(r.state())) {
            throw new ComputeException(ComputeException.Code.REFUSED, id + " is " + r.state() + ", not pending");
        }
        if (approver == null || approver.isBlank()) {
            throw new ComputeException(ComputeException.Code.REFUSED, "who decides is required");
        }
        if (approver.equalsIgnoreCase(r.requestedBy())) {
            audit.record(approver, "compute-" + verb + "-refused", r.subject(), id + ": four eyes (the requester cannot decide)");
            throw new ComputeException(ComputeException.Code.REFUSED, "four eyes: " + approver + " asked for " + id
                    + " and cannot " + verb + " it; another administrator must");
        }
        if (reason == null || reason.isBlank()) {
            throw new ComputeException(ComputeException.Code.REFUSED, "a reason is required to " + verb + " " + id);
        }
        return r;
    }

    public List<ApprovalRequest> recent(int limit) {
        return store.recent(limit);
    }

    public java.util.Optional<ApprovalRequest> get(String id) {
        return store.get(id);
    }

    /** The live approved items as rows (for Admin). */
    public int approvedCount() {
        return approvedKeys().size();
    }

    static String fingerprint(List<ApprovalItem> items) {
        List<String> keys = new ArrayList<>(items.stream().map(ApprovalItem::key).toList());
        keys.sort(Comparator.naturalOrder());
        return LockFile.sha256(String.join("\n", keys)).substring(0, 24);
    }

    private static String newId() {
        byte[] b = new byte[5];
        RANDOM.nextBytes(b);
        return "cr-" + Long.toString(System.currentTimeMillis(), 36) + "-" + HexFormat.of().formatHex(b);
    }

    static String describe(List<ApprovalItem> items) {
        Map<String, Integer> byKind = new LinkedHashMap<>();
        items.forEach(i -> byKind.merge(i.kind(), 1, Integer::sum));
        List<String> parts = new ArrayList<>();
        byKind.forEach((k, n) -> parts.add(n + " " + k));
        String names = String.join(", ", items.stream().limit(6).map(i -> i.name() + (i.version().isEmpty() ? "" : "==" + i.version())).toList());
        return String.join(", ", parts) + " (" + names + (items.size() > 6 ? ", …" : "") + ")";
    }
}
