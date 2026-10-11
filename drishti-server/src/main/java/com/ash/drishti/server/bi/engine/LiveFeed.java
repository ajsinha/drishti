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
package com.ash.drishti.server.bi.engine;

import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.Subscription;
import com.ash.drishti.server.bi.dataset.Dataset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The feed of one live table: it lists the kind, reads the documents once, subscribes to each entity through the same live machinery the terminal's live views
 * use (the connector's {@code subscribe}, by the router or by the connector the table names), and applies what arrives in batches. Updates of one entity inside a
 * batch interval are merged, so a source ticking faster than {@code drishti.bi.live.batch} costs one row write per batch. New entities are found by listing again
 * every {@code resync}; a connector that cannot push is read again at the same interval (rows whose values did not change are skipped by the table).
 */
final class LiveFeed implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(LiveFeed.class);
    private static final int CHUNK = 200;

    private final LiveTable table;
    private final Dataset.Table def;
    private final SourceGateway sources;
    private final BiLiveProperties props;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService io;
    private final Map<String, EntityDocument> pending = new ConcurrentHashMap<>();
    private final Set<String> known = ConcurrentHashMap.newKeySet();
    private final List<Subscription> subscriptions = new ArrayList<>();
    private final AtomicBoolean resyncing = new AtomicBoolean();
    private volatile ScheduledFuture<?> flusher;
    private volatile ScheduledFuture<?> resyncer;
    private volatile boolean closed;
    private volatile int pushing;
    private volatile String note;

    LiveFeed(LiveTable table, Dataset.Table def, SourceGateway sources, BiLiveProperties props, ScheduledExecutorService scheduler, ExecutorService io) {
        this.table = table;
        this.def = def;
        this.sources = sources;
        this.props = props;
        this.scheduler = scheduler;
        this.io = io;
    }

    /** Reads the table's first state (so the first query has rows), subscribes, and starts the timers. Never throws: a failing source is the table's state. */
    void start() {
        String problem = null;
        try {
            table.setFallbackDate(sources.currentDate());
            List<EntityRef> ids = list();
            readAndApply(ids);
            ids.forEach(r -> follow(r));
        } catch (RuntimeException e) {
            problem = describe(e);
            LOG.warn("live table {} could not load {}: {}", def.name(), def.kind(), problem);
        }
        publish(problem);
        long batch = props.batch().toMillis();
        long again = props.resync().toMillis();
        flusher = scheduler.scheduleWithFixedDelay(this::flush, batch, batch, TimeUnit.MILLISECONDS);
        resyncer = scheduler.scheduleWithFixedDelay(() -> io.execute(this::resync), again, again, TimeUnit.MILLISECONDS);
    }

    private void publish(String problem) {
        boolean push;
        int count;
        synchronized (subscriptions) {
            count = pushing;
            push = count > 0;
        }
        String shown = problem != null ? problem : note;
        if (!table.isStarted()) {
            table.markStarted(push, count, known.size(), shown);
        } else {
            table.setFeedState(push, count, known.size(), shown);
        }
    }

    private List<EntityRef> list() {
        List<EntityRef> ids = new ArrayList<>(sources.ids(def.kind(), null, props.followMax() + 1));
        if (ids.size() > props.followMax()) {
            ids = new ArrayList<>(ids.subList(0, props.followMax()));
            note = "following the first " + props.followMax() + " " + def.kind() + " entities of more (drishti.bi.live.follow-max)";
        } else {
            note = null;
        }
        return ids;
    }

    private void readAndApply(List<EntityRef> refs) {
        for (int i = 0; i < refs.size(); i += CHUNK) {
            List<EntityRef> chunk = refs.subList(i, Math.min(refs.size(), i + CHUNK));
            Map<EntityRef, EntityDocument> docs = sources.read(chunk, null);
            table.apply(new ArrayList<>(docs.values()));
        }
    }

    private void follow(EntityRef ref) {
        if (!known.add(ref.id())) {
            return;
        }
        try {
            Subscription s = sources.subscribe(def.connector(), ref, d -> {
                if (!closed) {
                    pending.put(d.ref().id(), d);
                    table.seen();
                }
            });
            if (s != null && s != Subscription.NONE) {
                synchronized (subscriptions) {
                    subscriptions.add(s);
                    pushing++;
                }
            }
        } catch (RuntimeException e) {
            LOG.debug("live table {} cannot follow {}: {}", def.name(), ref, e.getMessage());
        }
    }

    /** Applies what arrived since the last batch. */
    private void flush() {
        if (closed || pending.isEmpty()) {
            return;
        }
        List<EntityDocument> batch = new ArrayList<>();
        for (String id : new ArrayList<>(pending.keySet())) {
            EntityDocument d = pending.remove(id);
            if (d != null) {
                batch.add(d);
            }
        }
        try {
            table.apply(batch);
        } catch (RuntimeException e) {
            LOG.warn("live table {} could not apply {} updates: {}", def.name(), batch.size(), describe(e));
            publish(describe(e));
        }
        table.sweep();
    }

    /** Lists the kind again: new entities are read and followed; a feed that cannot push reads every entity again. */
    private void resync() {
        if (closed || !resyncing.compareAndSet(false, true)) {
            return;
        }
        try {
            table.setFallbackDate(sources.currentDate());
            List<EntityRef> ids = list();
            List<EntityRef> fresh = ids.stream().filter(r -> !known.contains(r.id())).toList();
            if (pushing == 0) {
                readAndApply(ids);                                    // nothing pushes: read it all again (the table skips rows that did not change)
            } else {
                readAndApply(fresh);
            }
            fresh.forEach(this::follow);
            table.sweep();
            publish(null);
        } catch (RuntimeException e) {
            LOG.warn("live table {} could not list {}: {}", def.name(), def.kind(), describe(e));
            publish(describe(e));
        } finally {
            resyncing.set(false);
        }
    }

    private static String describe(RuntimeException e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return m.lines().findFirst().orElse(m);
    }

    @Override
    public void close() {
        closed = true;
        ScheduledFuture<?> f = flusher;
        if (f != null) {
            f.cancel(false);
        }
        ScheduledFuture<?> r = resyncer;
        if (r != null) {
            r.cancel(false);
        }
        List<Subscription> toClose;
        synchronized (subscriptions) {
            toClose = new ArrayList<>(subscriptions);
            subscriptions.clear();
            pushing = 0;
        }
        toClose.forEach(s -> {
            try {
                s.close();
            } catch (RuntimeException ignored) {
                // already closed
            }
        });
        pending.clear();
    }
}
