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
package com.ash.drishti.server.collab;

import com.ash.drishti.api.Subscription;
import com.ash.drishti.identity.collab.InboxStore;
import com.ash.drishti.identity.collab.Notice;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Live delivery of inbox rows to open streams. A row written on this server is pushed at once ({@link #publish}); a row written
 * on another server sharing the identity database is found by a poll of {@code drishti_inbox} every {@code inbox.poll}, made
 * <em>only while a stream is open</em>, so a notice reaches a browser on any server within seconds. A bounded set of delivered
 * sequence numbers keeps the two paths from delivering a row twice, and the poll re-reads a short window below its high-water
 * mark so a row committed late with a lower number is not missed.
 */
public final class InboxHub implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(InboxHub.class);
    private static final int WINDOW = 100;
    private static final int REMEMBER = 5000;

    private final InboxStore store;
    private final Map<String, CopyOnWriteArrayList<Consumer<Notice>>> listeners = new ConcurrentHashMap<>();
    private final AtomicInteger open = new AtomicInteger();
    private final ReentrantLock state = new ReentrantLock();
    private final ArrayDeque<Long> order = new ArrayDeque<>();
    private final Set<Long> delivered = new HashSet<>();
    private long highWater;
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("drishti-inbox-poll").factory());

    public InboxHub(InboxStore store, Duration poll) {
        this.store = store;
        long ms = Math.max(50, poll.toMillis());
        poller.scheduleWithFixedDelay(this::poll, ms, ms, TimeUnit.MILLISECONDS);
    }

    /** Streams of {@code user} receive each new row until the subscription is closed. */
    public Subscription listen(String user, Consumer<Notice> listener) {
        if (open.getAndIncrement() == 0) {
            state.lock();
            try {
                delivered.clear();
                order.clear();
                highWater = store.maxSeq();      // rows written while nothing was listening are in the inbox, not in a stream
            } finally {
                state.unlock();
            }
        }
        listeners.computeIfAbsent(user, u -> new CopyOnWriteArrayList<>()).add(listener);
        return () -> {
            CopyOnWriteArrayList<Consumer<Notice>> l = listeners.get(user);
            if (l != null && l.remove(listener)) {
                open.decrementAndGet();
            }
        };
    }

    /** Streams currently open on this server. */
    public int streams() {
        return open.get();
    }

    /** Pushes a row written on this server to the recipient's open streams. */
    public void publish(Notice n) {
        if (open.get() == 0 || !firstDelivery(n.seq())) {
            return;
        }
        dispatch(n);
    }

    private boolean firstDelivery(long seq) {
        state.lock();
        try {
            highWater = Math.max(highWater, seq);
            if (!delivered.add(seq)) {
                return false;
            }
            order.addLast(seq);
            while (order.size() > REMEMBER) {
                delivered.remove(order.pollFirst());
            }
            return true;
        } finally {
            state.unlock();
        }
    }

    private void dispatch(Notice n) {
        List<Consumer<Notice>> l = listeners.get(n.username());
        if (l == null) {
            return;
        }
        for (Consumer<Notice> c : l) {
            try {
                c.accept(n);
            } catch (RuntimeException e) {
                LOG.debug("inbox listener failed: {}", e.toString());
            }
        }
    }

    private void poll() {
        if (open.get() == 0) {
            return;
        }
        try {
            long from;
            state.lock();
            try {
                from = Math.max(0, highWater - WINDOW);
            } finally {
                state.unlock();
            }
            for (Notice n : store.after(from, 500)) {
                if (firstDelivery(n.seq())) {
                    dispatch(n);
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("inbox poll failed: {}", e.toString());
        }
    }

    @Override
    public void close() {
        poller.shutdownNow();
    }
}
