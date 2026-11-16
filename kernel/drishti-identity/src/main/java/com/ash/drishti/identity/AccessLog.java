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

import com.ash.drishti.identity.db.AccessEntity;
import com.ash.drishti.identity.db.IdentityRepositories;
import jakarta.persistence.criteria.Predicate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Who looked at what, when (the access log, {@code drishti_access}). Recording never slows a read: events go to a
 * bounded queue and are written in batches every second; when the queue is full (the database is down or far behind)
 * events are dropped and counted, never blocked on. Kept {@code keepDays} days, pruned once a day.
 */
public final class AccessLog implements AutoCloseable {

    /** One read. {@code kind}/{@code entityId} are null for what has none; {@code businessDate} null for live. */
    public record Event(Instant at, String user, String action, String kind, String entityId, String detail, String businessDate) {}

    /** What to look for; null fields match everything. */
    public record Filter(String user, String action, String kind, String entityId, Instant from, Instant to, int limit) {}

    private static final Logger LOG = LoggerFactory.getLogger(AccessLog.class);
    private static final int BATCH = 500;
    private final IdentityRepositories.Access repo;
    private final TransactionTemplate tx;
    private final int keepDays;
    private final BlockingQueue<Event> queue;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong written = new AtomicLong();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("drishti-access-log").factory());

    public AccessLog(IdentityRepositories.Access repo, TransactionTemplate tx, int keepDays, int queueSize) {
        this.repo = repo;
        this.tx = tx;
        this.keepDays = keepDays;
        this.queue = new ArrayBlockingQueue<>(Math.max(100, queueSize));
        worker.scheduleWithFixedDelay(this::flush, 1, 1, TimeUnit.SECONDS);
        worker.scheduleWithFixedDelay(this::prune, 1, 24 * 60, TimeUnit.MINUTES);
    }

    /** Records a read; never blocks. */
    public void record(Event e) {
        if (!queue.offer(e)) {
            dropped.incrementAndGet();
        }
    }

    /**
     * Records an event now, in the caller's transaction when there is one, never queued and never dropped: for what must not
     * be unrecorded (a share). A failure propagates, so the caller's transaction rolls back with it.
     */
    public void recordNow(Event e) {
        tx.executeWithoutResult(s -> repo.save(entity(e)));
        written.incrementAndGet();
    }

    /** Writes what is queued (also called at shutdown and by tests). */
    public void flush() {
        try {
            List<Event> batch = new ArrayList<>(BATCH);
            while (queue.drainTo(batch, BATCH) > 0) {
                List<AccessEntity> rows = batch.stream().map(AccessLog::entity).toList();
                tx.executeWithoutResult(s -> repo.saveAll(rows));
                written.addAndGet(rows.size());
                batch.clear();
            }
        } catch (RuntimeException e) {
            LOG.warn("access log: could not write ({}); later events wait in the queue", e.toString());
        }
    }

    /** Newest first. */
    public List<Event> find(Filter f) {
        Specification<AccessEntity> spec = (root, q, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (f.user() != null) {
                p.add(cb.equal(root.get("username"), f.user()));
            }
            if (f.action() != null) {
                p.add(cb.equal(root.get("action"), f.action()));
            }
            if (f.kind() != null) {
                p.add(cb.equal(root.get("kind"), f.kind()));
            }
            if (f.entityId() != null) {
                p.add(cb.equal(root.get("entityId"), f.entityId()));
            }
            if (f.from() != null) {
                p.add(cb.greaterThanOrEqualTo(root.get("at"), f.from()));
            }
            if (f.to() != null) {
                p.add(cb.lessThan(root.get("at"), f.to()));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
        int limit = Math.max(1, Math.min(5000, f.limit()));
        return repo.findAll(spec, PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "at", "id"))).stream().map(AccessLog::event).toList();
    }

    /** Removes what is older than the retention; returns how many. */
    public int prune() {
        try {
            Instant before = Instant.now().minus(Duration.ofDays(keepDays));
            Integer n = tx.execute(s -> repo.deleteOlderThan(before));
            return n == null ? 0 : n;
        } catch (RuntimeException e) {
            LOG.warn("access log: pruning failed ({})", e.toString());
            return 0;
        }
    }

    /** Written, dropped and waiting, for health. */
    public java.util.Map<String, Object> stats() {
        return java.util.Map.of("written", written.get(), "dropped", dropped.get(), "queued", queue.size(), "keepDays", keepDays);
    }

    private static AccessEntity entity(Event e) {
        AccessEntity a = new AccessEntity();
        a.at = e.at();
        a.username = cut(e.user(), 64);
        a.action = cut(e.action(), 16);
        a.kind = cut(e.kind(), 64);
        a.entityId = cut(e.entityId(), 200);
        a.detail = cut(e.detail(), 500);
        a.businessDate = cut(e.businessDate(), 10);
        return a;
    }

    private static Event event(AccessEntity a) {
        return new Event(a.at, a.username, a.action, a.kind, a.entityId, a.detail, a.businessDate);
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    @Override
    public void close() {
        worker.shutdown();
        flush();
    }
}
