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
import com.ash.drishti.engine.ViewPipeline;
import com.ash.drishti.engine.view.ViewModel;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * One live view for one client. It listens to the entity's topic and to the topics of the entities its
 * charts read from; on any change it rebuilds the view (the layout is cached, so this is binding only),
 * diffs it against what the client has, and hands the patches to the sink. Rebuilds never overlap: a
 * change during a rebuild triggers exactly one more.
 */
public final class ViewStream implements AutoCloseable {

    private final EntityRef ref;
    private final ViewPipeline pipeline;
    private final PatchDiffer differ = new PatchDiffer();
    private final ExecutorService executor;
    private final LiveMetrics metrics;
    private final Consumer<Frame> sink;
    private final List<Subscription> subscriptions = new ArrayList<>();
    private final AtomicReference<ViewModel> shown = new AtomicReference<>();
    private final AtomicReference<EntityDocument> latestMain = new AtomicReference<>();
    private final AtomicBoolean dirty = new AtomicBoolean();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong seq = new AtomicLong();
    private volatile Instant tickAt = Instant.now();
    private volatile boolean closed;

    public ViewStream(EntityRef ref, ViewModel initial, List<EntityRef> sources, TopicHub hub, ViewPipeline pipeline,
            ExecutorService executor, LiveMetrics metrics, Consumer<Frame> sink) {
        this.ref = ref;
        this.pipeline = pipeline;
        this.executor = executor;
        this.metrics = metrics;
        this.sink = sink;
        this.shown.set(initial);
        subscriptions.add(hub.subscribe(ref, d -> {
            latestMain.set(d);
            changed(d.provenance().fetchedAt());
        }));
        for (EntityRef s : sources) {
            if (!s.equals(ref)) {
                subscriptions.add(hub.subscribe(s, d -> changed(d.provenance().fetchedAt())));
            }
        }
    }

    public EntityRef ref() {
        return ref;
    }

    private void changed(Instant at) {
        tickAt = at;
        dirty.set(true);
        if (running.compareAndSet(false, true)) {
            executor.execute(this::drain);
        }
    }

    private void drain() {
        try {
            while (!closed && dirty.getAndSet(false)) {
                Instant at = tickAt;
                EntityDocument main = latestMain.get();
                ViewModel next = main != null ? pipeline.build(main, System.nanoTime(), System.nanoTime()) : pipeline.view(ref);
                ViewModel before = shown.getAndSet(next);
                List<Patch> patches = differ.diff(before, next);
                if (!patches.isEmpty()) {
                    double latency = Duration.between(at, Instant.now()).toNanos() / 1e6;
                    metrics.record(latency);
                    sink.accept(new Frame(seq.incrementAndGet(), next.provenance().generation(), patches, latency, metrics.percentile(99)));
                }
            }
        } catch (RuntimeException e) {
            dirty.set(true);
        } finally {
            running.set(false);
            if (dirty.get() && !closed && running.compareAndSet(false, true)) {
                executor.execute(this::drain);
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        subscriptions.forEach(Subscription::close);
    }
}
