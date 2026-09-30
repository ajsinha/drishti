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
package com.ash.drishti.engine.live;

import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.Subscription;
import com.ash.drishti.engine.source.SourceRouter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * One topic per live entity, shared by every view that shows it. A topic holds a single source
 * subscription; ticks land in a latest-wins slot and are delivered at most once per frame (leading edge:
 * a tick after a quiet frame goes out at once; ticks inside a busy frame coalesce), so a source ticking
 * faster than the frame rate costs one delivery per frame, not one per tick. Each topic has a single
 * writer: at most one flush runs at a time. Topics close their source subscription when the last
 * listener leaves.
 */
public final class TopicHub implements AutoCloseable {

    /** A topic that has been disconnected; never connected again (a new subscriber gets a new topic). */
    private static final Subscription CLOSED = () -> {};

    private final class Topic {
        final EntityRef ref;
        final CopyOnWriteArrayList<Consumer<EntityDocument>> listeners = new CopyOnWriteArrayList<>();
        final AtomicReference<EntityDocument> latest = new AtomicReference<>();
        final AtomicBoolean scheduled = new AtomicBoolean();
        final ReentrantLock lock = new ReentrantLock();   // guards connecting and disconnecting the source
        volatile Subscription source = Subscription.NONE;
        volatile long lastFlush;

        Topic(EntityRef ref) {
            this.ref = ref;
        }

        void onTick(EntityDocument d) {
            latest.set(d);
            if (scheduled.compareAndSet(false, true)) {
                long sinceLast = (System.nanoTime() - lastFlush) / 1_000_000;
                long delay = Math.max(0, frameMillis - sinceLast);
                frames.schedule(this::flush, delay, TimeUnit.MILLISECONDS);
            }
        }

        void flush() {
            lastFlush = System.nanoTime();
            scheduled.set(false);
            EntityDocument d = latest.getAndSet(null);
            if (d != null) {
                for (Consumer<EntityDocument> l : listeners) {
                    try {
                        l.accept(d);
                    } catch (RuntimeException ignored) {
                        // one slow or broken view must not starve the others
                    }
                }
            }
        }
    }

    private final SourceRouter router;
    private final ScheduledExecutorService frames;
    private final long frameMillis;
    private final Map<EntityRef, Topic> topics = new ConcurrentHashMap<>();

    public TopicHub(SourceRouter router, ScheduledExecutorService frames, LiveProperties props) {
        this.router = router;
        this.frames = frames;
        this.frameMillis = props.frame().toMillis();
    }

    /** Receives each new generation of {@code ref}, at most once per frame, until the returned handle is closed. */
    public Subscription subscribe(EntityRef ref, Consumer<EntityDocument> listener) {
        Topic t = topics.compute(ref, (r, existing) -> {
            Topic topic = existing == null ? new Topic(r) : existing;
            topic.listeners.add(listener);
            return topic;
        });
        // Subscribe upstream once per topic, whoever gets here first. Not "when I am the only listener": two
        // subscribers arriving together both see two listeners, and the topic would then never be fed.
        if (t.source == Subscription.NONE) {
            t.lock.lock();                    // a ReentrantLock: the upstream subscribe may do I/O, and synchronized would pin
            try {
                if (t.source == Subscription.NONE && topics.get(ref) == t) {
                    t.source = router.subscribe(ref, t::onTick);
                }
            } finally {
                t.lock.unlock();
            }
        }
        return () -> release(ref, listener);
    }

    private void release(EntityRef ref, Consumer<EntityDocument> listener) {
        topics.computeIfPresent(ref, (r, t) -> {
            t.listeners.remove(listener);
            if (t.listeners.isEmpty()) {
                t.lock.lock();                // not while a subscriber is still connecting it upstream
                try {
                    t.source.close();
                    t.source = CLOSED;
                } finally {
                    t.lock.unlock();
                }
                return null;
            }
            return t;
        });
    }

    public int topicCount() {
        return topics.size();
    }

    @Override
    public void close() {
        topics.values().forEach(t -> t.source.close());
        topics.clear();
    }
}
