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

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The generation bus (docs/architecture/BI_DESIGN.md sections 4.6 and 6.5): every change of the data a model reads is published once, in order, as a
 * {@link Notice}: a live batch (with its change set: the rows written, what they were before, the rows removed), a data load or refresh for a business date
 * ({@code backdated} when the date is before the current business date: only that date's answers move), a history sample. Standing queries listen (push, no
 * polling of generations); with several Java nodes a {@link Bridge} carries the notices between nodes. Delivery is asynchronous on one virtual thread, so a
 * publisher (a live feed, a load) never waits for a listener; a listener that throws is logged and kept. Thread-safe.
 */
public final class GenerationBus implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(GenerationBus.class);

    /**
     * The rows a live batch changed, by entity id: each row is {@code [businessDate, column values in the table's physical order]}.
     *
     * @param before for each upserted id, its row before the batch (null: it is new)
     * @param deletes the removed rows (deleted or evicted), as they were
     */
    public record Change(List<String> columns, Map<String, Object[]> upserts, Map<String, Object[]> before, Map<String, Object[]> deletes) {
        public int size() {
            return upserts.size() + deletes.size();
        }
    }

    /**
     * One change of a model's (or dataset's) data.
     *
     * @param key the layer key of the model or dataset whose data moved
     * @param table its table, or null for every table
     * @param cause {@code live}, {@code load}, {@code refresh}, {@code backdated}, {@code history}
     * @param date the business date a load or refresh was for, or null
     * @param generation the generation after the change (as {@code meta.generation} reports it for the key)
     * @param change the rows of a live batch, or null
     * @param origin the node the notice came from (empty: this node)
     */
    public record Notice(String key, String table, String cause, LocalDate date, long generation, Change change, String origin) {
        public Notice(String key, String table, String cause, LocalDate date, long generation, Change change) {
            this(key, table, cause, date, generation, change, "");
        }
    }

    /** A listener of the bus. */
    @FunctionalInterface
    public interface Listener {
        void on(Notice n);
    }

    /** Carries notices to the other Java nodes and gives this node theirs (wave B scale); the in-process bus needs none. */
    public interface Bridge {
        void send(Notice n);
    }

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final LinkedBlockingQueue<Notice> queue = new LinkedBlockingQueue<>();
    private final AtomicLong published = new AtomicLong();
    private final AtomicLong delivered = new AtomicLong();
    private final Thread worker;
    private volatile Bridge bridge;
    private volatile boolean closed;

    public GenerationBus() {
        this.worker = Thread.ofVirtual().name("drishti-bi-bus").start(this::drain);
    }

    /** Adds a listener; closing the returned handle removes it. */
    public AutoCloseable listen(Listener l) {
        listeners.add(l);
        return () -> listeners.remove(l);
    }

    /** Publishes a change of this node's data (and sends it to the other nodes when bridged). */
    public void publish(Notice n) {
        if (closed) {
            return;
        }
        published.incrementAndGet();
        queue.add(n);
        Bridge b = bridge;
        if (b != null && n.origin().isEmpty()) {
            try {
                b.send(n);
            } catch (RuntimeException e) {
                LOG.warn("generation bus: cannot send a notice of {} to the other nodes: {}", n.key(), e.getMessage());
            }
        }
    }

    /** A notice from another node: delivered here, never sent on. */
    public void receive(Notice n) {
        if (!closed) {
            published.incrementAndGet();
            queue.add(n);
        }
    }

    public void bridge(Bridge b) {
        this.bridge = b;
    }

    public long published() {
        return published.get();
    }

    public long delivered() {
        return delivered.get();
    }

    /** Waits until every notice published so far has been delivered (tests, and an orderly shutdown). */
    public boolean drained(long millis) {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (delivered.get() < published.get() && System.nanoTime() < until) {
            Thread.onSpinWait();
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return delivered.get() >= published.get();
    }

    private void drain() {
        while (!closed) {
            Notice n;
            try {
                n = queue.poll(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (n == null) {
                continue;
            }
            for (Listener l : listeners) {
                try {
                    l.on(n);
                } catch (RuntimeException e) {
                    LOG.warn("generation bus: a listener failed on {} {}: {}", n.key(), n.cause(), e.getMessage());
                }
            }
            delivered.incrementAndGet();
        }
    }

    @Override
    public void close() {
        closed = true;
        worker.interrupt();
    }
}
