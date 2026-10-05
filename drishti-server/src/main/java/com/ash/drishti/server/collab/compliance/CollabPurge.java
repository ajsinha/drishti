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
import com.ash.drishti.identity.collab.CollabTx;
import com.ash.drishti.identity.collab.CommentThread;
import com.ash.drishti.identity.collab.Hold;
import com.ash.drishti.identity.collab.Revision;
import com.ash.drishti.identity.collab.Share;
import com.ash.drishti.identity.collab.ShareStore;
import com.ash.drishti.identity.collab.ThreadStore;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.PackAccess;
import com.ash.drishti.server.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Retention: removes whole threads (last activity) and shares (creation) older than their kind's retention, never what an active legal
 * hold covers. Days come from configuration, most specific first: {@code retention.kinds.<kind>}, then
 * {@code packs.<pack>.retention-days} for the pack that owns the kind, then {@code retention.keep-days}; 0 keeps forever (the default).
 * Every removal is audited with the thread's final hash. Also the administrator's permanent removal of one thread (refused under a hold).
 * A purge never touches the middle of a chain: a thread goes whole or not at all.
 */
public final class CollabPurge implements AutoCloseable {

    /** What one run did. {@code held} counts items past their retention that a hold kept. */
    public record Result(boolean dryRun, int threadsPurged, int sharesPurged, int threadsHeld, int sharesHeld) {}

    private static final Logger LOG = LoggerFactory.getLogger(CollabPurge.class);
    private static final int PAGE = 200;

    private final CollabProperties props;
    private final ThreadStore threads;
    private final ShareStore shares;
    private final HoldService holds;
    private final PackAccess packs;
    private final Entitlements entitlements;
    private final AuditLog audit;
    private final CollabTx tx;
    private final Clock clock;
    private final ReentrantLock running = new ReentrantLock();
    private ScheduledExecutorService loop;

    @SuppressWarnings("java:S107")
    public CollabPurge(CollabProperties props, ThreadStore threads, ShareStore shares, HoldService holds, PackAccess packs,
            Entitlements entitlements, AuditLog audit, CollabTx tx, Clock clock) {
        this.props = props;
        this.threads = threads;
        this.shares = shares;
        this.holds = holds;
        this.packs = packs;
        this.entitlements = entitlements;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
    }

    /** Retention days for an entity kind: the kind's, else its pack's, else the default; 0 keeps forever. */
    public int days(String kind) {
        Integer byKind = props.retention().kinds().get(kind);
        if (byKind != null) {
            return Math.max(0, byKind);
        }
        String owner = kind == null ? null : packs.ownerOf(kind);
        Object byPack = owner == null ? null : props.packs().getOrDefault(owner, Map.of()).get("retention-days");
        if (byPack instanceof Number n) {
            return Math.max(0, n.intValue());
        }
        if (byPack != null) {
            try {
                return Math.max(0, Integer.parseInt(byPack.toString().strip()));
            } catch (NumberFormatException e) {
                LOG.warn("packs.{}.retention-days is not a number: {}", owner, byPack);
            }
        }
        return props.retention().keepDays();
    }

    /** One pass, now. {@code dryRun} only counts. Passes never overlap; a second caller waits. */
    public Result run(boolean dryRun) {
        running.lock();
        try {
            Instant now = clock.instant();
            int[] n = new int[4];
            for (String after = null; ; ) {
                List<CommentThread> page = threads.page(after, PAGE);
                if (page.isEmpty()) {
                    break;
                }
                for (CommentThread t : page) {
                    int d = days(t.kind());
                    if (d > 0 && t.lastAt().isBefore(now.minus(Duration.ofDays(d)))) {
                        purgeThread(t, dryRun, n);
                    }
                    after = t.id();
                }
            }
            for (String after = null; ; ) {
                List<Share> page = shares.page(after, PAGE);
                if (page.isEmpty()) {
                    break;
                }
                for (Share s : page) {
                    int d = days(s.kind());
                    if (d > 0 && s.createdAt().isBefore(now.minus(Duration.ofDays(d)))) {
                        HoldMatcher m = new HoldMatcher(holds.active(), threads, shares);
                        if (m.covering(s) != null) {
                            n[3]++;
                        } else if (dryRun) {
                            n[1]++;
                        } else {
                            tx.run(() -> {
                                shares.delete(s.id());
                                return null;
                            });
                            audit.record("system", "collab.purge.share", s.kind() + "/" + s.entityId(), s.id() + " hash " + s.hash());
                            n[1]++;
                        }
                    }
                    after = s.id();
                }
            }
            Result r = new Result(dryRun, n[0], n[1], n[2], n[3]);
            if (!dryRun && (r.threadsPurged() > 0 || r.sharesPurged() > 0)) {
                LOG.info("collaboration retention: removed {} threads and {} shares; {} threads and {} shares kept by legal hold",
                        r.threadsPurged(), r.sharesPurged(), r.threadsHeld(), r.sharesHeld());
            }
            return r;
        } finally {
            running.unlock();
        }
    }

    private void purgeThread(CommentThread t, boolean dryRun, int[] n) {
        HoldMatcher m = new HoldMatcher(holds.active(), threads, shares);
        if (m.covering(t) != null) {
            n[2]++;
            return;
        }
        if (!dryRun) {
            String last = lastHash(t.id());
            tx.run(() -> {
                threads.deleteThread(t.id());
                return null;
            });
            audit.record("system", "collab.purge.thread", t.kind() + "/" + t.entityId(), t.id() + " last hash " + last);
        }
        n[0]++;
    }

    private String lastHash(String threadId) {
        List<Revision> chain = threads.chain(threadId);
        return chain.isEmpty() ? "none" : chain.get(chain.size() - 1).hash();
    }

    /** An administrator removes a whole thread for good: audited, and {@code 423 DRS-7010} while a legal hold covers it. */
    public void removeThread(Principal p, String threadId) {
        entitlements.requireAdmin(p);
        CommentThread t = threads.thread(threadId).orElseThrow(() -> new DrishtiException(ErrorCode.THREAD_NOT_FOUND, "no thread " + threadId));
        Hold held = new HoldMatcher(holds.active(), threads, shares).covering(t);
        if (held != null) {
            throw new DrishtiException(ErrorCode.ON_HOLD, "thread " + threadId + " is under legal hold " + held.id() + " (" + held.reason() + ")");
        }
        String last = lastHash(threadId);
        tx.run(() -> {
            threads.deleteThread(threadId);
            return null;
        });
        audit.record(p.user(), "collab.thread.delete", t.kind() + "/" + t.entityId(), threadId + " last hash " + last);
    }

    /** Starts the schedule ({@code retention.interval}); a no-op while no retention is configured, so a default install never runs it. */
    public synchronized void start() {
        if (loop != null || !props.enabled() || !anyRetention()) {
            return;
        }
        loop = Executors.newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().daemon().name("drishti-collab-purge").unstarted(r));
        long ms = props.retention().interval().toMillis();
        loop.scheduleWithFixedDelay(() -> {
            try {
                run(false);
            } catch (RuntimeException e) {
                LOG.warn("collaboration retention failed: {}", e.toString());
            }
        }, Math.min(ms, 60_000), ms, TimeUnit.MILLISECONDS);
    }

    private boolean anyRetention() {
        if (props.retention().keepDays() > 0 || props.retention().kinds().values().stream().anyMatch(d -> d != null && d > 0)) {
            return true;
        }
        return props.packs().values().stream().anyMatch(m -> m.get("retention-days") instanceof Number d && d.intValue() > 0
                || m.get("retention-days") instanceof String s && !s.isBlank() && !"0".equals(s.strip()));
    }

    @Override
    public synchronized void close() {
        if (loop != null) {
            loop.shutdownNow();
            loop = null;
        }
    }
}
