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
package com.ash.drishti.compute.reactive;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * The events of each compute request, delivered to its owner only (REACTIVE_CONNECTORS.md section 9.1, rule 1): {@code progress} while it
 * runs, {@code state} when it changes (stale, failed), {@code done} when its result is cached, and, while somebody keeps listening,
 * {@code updated} each time a trigger recomputes what it showed. A listener that arrives late is first given the events so far. There is
 * no broadcast: a request's events go to the listeners of that request id, opened by its owner.
 */
public final class RequestEvents {

    /** One event of a request. */
    public record Event(long seq, String requestId, String type, Map<String, Object> data, Instant at) {}

    private static final int BACKLOG = 64;

    private final class Channel {
        final String id;
        final String owner;
        final Runnable onIdle;
        final ArrayDeque<Event> backlog = new ArrayDeque<>();
        final List<Consumer<Event>> listeners = new CopyOnWriteArrayList<>();
        final ReentrantLock lock = new ReentrantLock();
        volatile Instant touched;

        Channel(String id, String owner, Runnable onIdle, Instant at) {
            this.id = id;
            this.owner = owner;
            this.onIdle = onIdle;
            this.touched = at;
        }
    }

    private final Map<String, Channel> channels = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();
    private final Duration retention;
    private final Clock clock;

    public RequestEvents(Duration retention, Clock clock) {
        this.retention = retention;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** Opens the events of a request of {@code owner}; {@code onIdle} runs when its last listener goes (cancel, stop watching). */
    public void open(String requestId, String owner, Runnable onIdle) {
        channels.putIfAbsent(requestId, new Channel(requestId, owner, onIdle, clock.instant()));
    }

    public boolean known(String requestId) {
        return channels.containsKey(requestId);
    }

    /** The owner of a request, if it is known here. */
    public Optional<String> owner(String requestId) {
        Channel c = channels.get(requestId);
        return c == null ? Optional.empty() : Optional.of(c.owner);
    }

    /** Sends an event to the request's listeners and keeps it for those who come later. Unknown ids are ignored. */
    public void publish(String requestId, String type, Map<String, Object> data) {
        Channel c = channels.get(requestId);
        if (c == null) {
            return;
        }
        Event e = new Event(seq.incrementAndGet(), requestId, type, data == null ? Map.of() : data, clock.instant());
        c.lock.lock();
        try {
            c.backlog.addLast(e);
            if (c.backlog.size() > BACKLOG) {
                c.backlog.removeFirst();
            }
            c.touched = e.at();
        } finally {
            c.lock.unlock();
        }
        for (Consumer<Event> l : c.listeners) {
            try {
                l.accept(e);
            } catch (RuntimeException ex) {
                c.listeners.remove(l);
            }
        }
    }

    /**
     * Listens to a request of {@code user}: the events so far, then each new one, until the returned handle is closed. Refused as
     * unknown for a request of another user (it does not say whether it exists).
     */
    public AutoCloseable listen(String requestId, String user, Consumer<Event> listener) {
        Channel c = channels.get(requestId);
        if (c == null || !c.owner.equals(user)) {
            throw new java.util.NoSuchElementException("no compute request " + requestId);
        }
        List<Event> past;
        c.lock.lock();
        try {
            past = new ArrayList<>(c.backlog);
            c.listeners.add(listener);
            c.touched = clock.instant();
        } finally {
            c.lock.unlock();
        }
        past.forEach(listener);
        return () -> {
            c.listeners.remove(listener);
            c.touched = clock.instant();
            if (c.listeners.isEmpty() && c.onIdle != null) {
                c.onIdle.run();
            }
        };
    }

    /** How many listeners a request has now. */
    public int listeners(String requestId) {
        Channel c = channels.get(requestId);
        return c == null ? 0 : c.listeners.size();
    }

    /** Forgets requests nobody has listened to for the retention; returns how many. */
    public int sweep() {
        Instant limit = clock.instant().minus(retention);
        int n = 0;
        for (Iterator<Channel> it = channels.values().iterator(); it.hasNext(); ) {
            Channel c = it.next();
            if (c.listeners.isEmpty() && c.touched.isBefore(limit)) {
                it.remove();
                n++;
            }
        }
        return n;
    }

    public int size() {
        return channels.size();
    }
}
