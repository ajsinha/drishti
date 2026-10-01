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

import com.ash.drishti.identity.db.AlertEntity;
import com.ash.drishti.identity.db.IdentityRepositories;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every user's fired alerts, kept in the identity database so a restart keeps them. Each user keeps the newest
 * {@code keep} (older ones are pruned every few inserts); the row id numbers alerts across restarts.
 */
public final class AlertHistory {

    /** One fired alert; {@code seq} is its row id (it only grows). */
    public record Alert(long seq, Instant at, String user, String rule, String kind, String id, String severity, String message,
            long generation) {}

    private static final int PRUNE_EVERY = 50;
    private final IdentityRepositories.Alerts alerts;
    private final TransactionTemplate tx;
    private final int keep;
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> sincePrune = new ConcurrentHashMap<>();

    public AlertHistory(IdentityRepositories.Alerts alerts, TransactionTemplate tx, int keep) {
        this.alerts = alerts;
        this.tx = tx;
        this.keep = Math.max(1, keep);
    }

    /** Stores a fired alert and returns it with its sequence number. */
    public Alert record(Instant at, String user, String rule, String kind, String id, String severity, String message, long generation) {
        AlertEntity e = new AlertEntity();
        e.username = user;
        e.at = at;
        e.rule = rule;
        e.kind = kind;
        e.entityId = id;
        e.severity = severity;
        e.message = message == null ? "" : message;
        e.generation = generation;
        ReentrantLock lock = locks.computeIfAbsent(user, u -> new ReentrantLock());
        lock.lock();
        try {
            AlertEntity saved = tx.execute(s -> alerts.save(e));
            if (sincePrune.computeIfAbsent(user, u -> new AtomicInteger()).incrementAndGet() >= PRUNE_EVERY) {
                sincePrune.get(user).set(0);
                prune(user);
            }
            return toAlert(saved);
        } finally {
            lock.unlock();
        }
    }

    private void prune(String user) {
        tx.executeWithoutResult(s -> {
            List<AlertEntity> page = alerts.findByUsernameOrderByIdDesc(user, PageRequest.of(keep, 1));   // the first one too old
            if (!page.isEmpty()) {
                alerts.deleteByUsernameAndIdLessThan(user, page.get(0).id + 1);
            }
        });
    }

    /** Newest first, at most {@code limit}. */
    public List<Alert> recent(String user, int limit) {
        return alerts.findByUsernameOrderByIdDesc(user, PageRequest.of(0, Math.max(1, Math.min(limit, keep)))).stream()
                .map(AlertHistory::toAlert).toList();
    }

    /** Removes a user's alerts (called when the user is deleted). */
    public void forget(String user) {
        tx.executeWithoutResult(s -> alerts.deleteByUsername(user));
    }

    private static Alert toAlert(AlertEntity e) {
        return new Alert(e.id, e.at, e.username, e.rule, e.kind, e.entityId, e.severity, e.message, e.generation);
    }
}
