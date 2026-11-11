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
package com.ash.drishti.identity.content;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One poller of {@link ContentStore#changes(long)} for the whole node: every interval it asks the store what changed and hands the
 * keys to the listeners whose prefix they start with (the shared configuration overlays, the pack state, DATA_ARCHITECTURE.md
 * section 11). One walk of the store per interval, however many areas listen. At least once: a key may be handed over twice.
 */
public final class ContentWatch implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ContentWatch.class);
    /** Clock skew allowed between nodes writing one store: the cursor passed back is this much earlier. */
    static final long SKEW_MS = 2_000;

    private record Listener(String prefix, Consumer<List<String>> onChange) {}

    private final ContentStore store;
    private final long intervalMs;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ReentrantLock lock = new ReentrantLock();
    private volatile long cursor;
    private volatile Thread thread;
    private Map<String, String> delivered = new HashMap<>();   // key to version handed over in the last window

    public ContentWatch(ContentStore store, long intervalMs) {
        this.store = store;
        this.intervalMs = Math.max(50, intervalMs);
        this.cursor = System.currentTimeMillis() - SKEW_MS;
    }

    public ContentStore store() {
        return store;
    }

    public long intervalMs() {
        return intervalMs;
    }

    /** Calls {@code onChange} with the changed keys under {@code prefix} after each poll that saw any. */
    public void subscribe(String prefix, Consumer<List<String>> onChange) {
        listeners.add(new Listener(prefix, onChange));
    }

    /** Starts the background poller (a virtual thread); idempotent. */
    public synchronized ContentWatch start() {
        if (thread == null) {
            thread = Thread.ofVirtual().name("drishti-content-watch").start(this::loop);
        }
        return this;
    }

    private void loop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                poll();
            } catch (RuntimeException e) {
                LOG.warn("content store changes could not be read ({}); trying again in {} ms", e.getMessage(), intervalMs);
            }
        }
    }

    /** One pass now (the poller's step; tests call it directly). Returns the keys seen. */
    public List<String> poll() {
        lock.lock();
        try {
            ContentStore.Changes c = store.changes(cursor);
            cursor = Math.max(cursor, c.cursor() - SKEW_MS);
            // the window overlaps the last one (clock skew), so a key is handed over only when its version moved
            Map<String, String> now = new HashMap<>();
            List<String> fresh = new ArrayList<>();
            for (String k : c.keys()) {
                String v = store.get(k).map(ContentStore.Item::version).orElse("");
                now.put(k, v);
                if (!v.equals(delivered.get(k))) {
                    fresh.add(k);
                }
            }
            delivered = now;
            if (!fresh.isEmpty()) {
                for (Listener l : listeners) {
                    List<String> mine = new ArrayList<>();
                    for (String k : fresh) {
                        if (k.startsWith(l.prefix())) {
                            mine.add(k);
                        }
                    }
                    if (!mine.isEmpty()) {
                        try {
                            l.onChange().accept(mine);
                        } catch (RuntimeException e) {
                            LOG.warn("a content change under {} could not be applied: {}", l.prefix(), e.getMessage(), e);
                        }
                    }
                }
            }
            return fresh;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public synchronized void close() {
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }
}
