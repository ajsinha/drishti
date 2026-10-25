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
package com.ash.drishti.compute;

import com.ash.drishti.compute.arrow.ArrowTable;
import com.ash.drishti.compute.sched.FairQueue;
import com.ash.drishti.compute.sched.Latencies;
import com.ash.drishti.compute.worker.Scratch;
import com.ash.drishti.compute.worker.WorkerProcess;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One pool on this node (docs/architecture/PYTHON_COMPUTE.md section 7): its worker processes, its fair queue, and the dispatch between
 * them. Concurrency is the number of workers ({@code workers.max}, configuration only): each worker runs one request at a time, on its own
 * virtual thread on the server side. Workers start on demand up to the maximum, idle ones beyond the minimum stop after {@code idle-stop},
 * a worker is replaced after {@code recycle-after} requests, and a dead one is replaced by the next request that needs it.
 */
final class Pool implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Pool.class);

    /** One request in this pool. */
    final class Job implements FairQueue.Item, ComputeHandle {
        final ComputeRequest req;
        final CompletableFuture<ComputeResult> future = new CompletableFuture<>();
        final ProgressSink progress;
        final long enqueued = System.nanoTime();
        final long deadline;
        volatile boolean cancelled;
        volatile String state = "queued";
        volatile String worker;

        Job(ComputeRequest req, ProgressSink progress, long deadline) {
            this.req = req;
            this.progress = progress;
            this.deadline = deadline;
        }

        @Override
        public RequestClass cls() {
            return req.cls();
        }

        @Override
        public String user() {
            return req.viewer().user();
        }

        @Override
        public String id() {
            return req.id();
        }

        @Override
        public CompletableFuture<ComputeResult> result() {
            return future;
        }

        @Override
        public String state() {
            return state;
        }

        @Override
        public void cancel() {
            cancelled = true;
            boolean removed;
            lock.lock();
            try {
                removed = queue.remove(this);
            } finally {
                lock.unlock();
            }
            if (removed) {
                fail(this, new ComputeException(ComputeException.Code.CANCELLED, "the computation was cancelled before it started", req.id(), null));
            }
        }
    }

    private final PoolSettings settings;
    private final ComputeSettings compute;
    private final String node;
    private final IntFunction<WorkerProcess.Spec> specs;
    private final Consumer<Finished> finished;
    private final ReentrantLock lock = new ReentrantLock();
    private final FairQueue<Job> queue;
    private final ArrayDeque<WorkerProcess> idle = new ArrayDeque<>();
    private final List<Job> running = new ArrayList<>();
    private final AtomicLong seq = new AtomicLong();
    private int active;                                     // requests holding a slot (running or starting their worker)
    private int warming;                                    // workers being started to reach workers.min
    private volatile boolean closed;
    private final Latencies queueMs = new Latencies(4096);
    private final Latencies runMs = new Latencies(4096);
    private final AtomicLong started = new AtomicLong();
    private final AtomicLong startFailures = new AtomicLong();
    private final AtomicLong recycled = new AtomicLong();
    private final AtomicLong crashed = new AtomicLong();
    private final AtomicLong stopped = new AtomicLong();
    private final Map<ComputeException.Code, AtomicLong> failures = new EnumMap<>(ComputeException.Code.class);
    private final AtomicLong served = new AtomicLong();

    /** What a finished request reports to the service (its recent list and audit). */
    record Finished(ComputeRequest req, String outcome, long queuedMs, long runMs, String worker) {}

    Pool(PoolSettings settings, ComputeSettings compute, String node, IntFunction<WorkerProcess.Spec> specs, Consumer<Finished> finished) {
        this.settings = settings;
        this.compute = compute;
        this.node = node;
        this.specs = specs;
        this.finished = finished;
        this.queue = new FairQueue<>(compute.queueInteractive(), compute.queueEvent(), compute.queueBatch(), compute.maxInflightPerUser(),
                settings.batchSlots());
        for (ComputeException.Code c : ComputeException.Code.values()) {
            failures.put(c, new AtomicLong());
        }
    }

    PoolSettings settings() {
        return settings;
    }

    /** Queues the request (or refuses it at once when its class's queue is full: back-pressure). */
    ComputeHandle submit(ComputeRequest req, ProgressSink progress) {
        java.time.Duration budget = req.timeout() != null ? req.timeout() : compute.timeout(settings, req.cls());
        Job job = new Job(req, progress == null ? ProgressSink.NONE : progress, System.nanoTime() + budget.toNanos());
        lock.lock();
        try {
            if (closed) {
                throw new ComputeException(ComputeException.Code.OFF, "compute pool " + settings.name() + " is stopping");
            }
            if (!queue.offer(job)) {
                failures.get(ComputeException.Code.BUSY).incrementAndGet();
                throw new ComputeException(ComputeException.Code.BUSY, "compute pool " + settings.name() + " is busy (" + queue.size(req.cls())
                        + " " + req.cls().label() + " requests waiting): try again in a moment", req.id(), null);
            }
        } finally {
            lock.unlock();
        }
        pump();
        return job;
    }

    /** Starts as many waiting requests as there are free worker slots. */
    void pump() {
        List<Runnable> launches = new ArrayList<>();
        lock.lock();
        try {
            while (!closed && active < settings.workersMax()) {
                Job job = queue.next();
                if (job == null) {
                    break;
                }
                active++;
                job.state = "running";
                running.add(job);
                WorkerProcess w = null;
                while (!idle.isEmpty() && w == null) {
                    WorkerProcess c = idle.pollFirst();
                    if (c.alive()) {
                        w = c;
                    } else {
                        c.kill();
                    }
                }
                WorkerProcess worker = w;
                launches.add(() -> execute(job, worker));
            }
        } finally {
            lock.unlock();
        }
        for (Runnable r : launches) {
            Thread.ofVirtual().name("compute-" + settings.name()).start(r);
        }
    }

    private WorkerProcess newWorker() {
        WorkerProcess.Spec spec = specs.apply((int) seq.incrementAndGet());
        try {
            WorkerProcess w = WorkerProcess.start(spec);
            started.incrementAndGet();
            return w;
        } catch (RuntimeException e) {
            startFailures.incrementAndGet();
            throw e;
        }
    }

    private void execute(Job job, WorkerProcess given) {
        long queued = (System.nanoTime() - job.enqueued) / 1_000_000;
        queueMs.add(queued);
        long t0 = System.nanoTime();
        WorkerProcess worker = given;
        boolean reusable = false;
        String outcome = "done";
        try {
            if (job.cancelled) {
                throw new ComputeException(ComputeException.Code.CANCELLED, "the computation was cancelled before it started", job.id(), null);
            }
            if (System.nanoTime() >= job.deadline) {
                throw new ComputeException(ComputeException.Code.TIMEOUT, "the computation waited longer than its time budget for a worker", job.id(), null);
            }
            if (worker == null) {
                worker = newWorker();
            }
            if (job.cancelled) {
                reusable = true;                                            // a fresh worker, never used: keep it
                throw new ComputeException(ComputeException.Code.CANCELLED, "the computation was cancelled before it started", job.id(), null);
            }
            job.worker = worker.id();
            ComputeResult r;
            try (Scratch scratch = Scratch.create(compute.scratch(), job.id())) {
                Map<String, String> files = scratch.writeInputs(job.req.inputs());
                WorkerProcess.Outcome o = worker.run(message(job, scratch.dir(), files), job.id(), job.deadline, () -> job.cancelled,
                        data -> job.progress.progress(job.id(), data));
                reusable = o.reusable();
                if (o.error() != null) {
                    throw o.error();
                }
                r = result(job, worker, o.result(), scratch, queued, (System.nanoTime() - t0) / 1_000_000);
            }                                                               // the scratch folder is gone before anyone hears the answer
            served.incrementAndGet();
            runMs.add(r.runMs());
            job.state = "done";
            job.future.complete(r);
        } catch (ComputeException e) {
            outcome = e.code().label();
            if (e.code() == ComputeException.Code.CRASHED) {
                crashed.incrementAndGet();
            }
            fail(job, e.forRequest(job.id()));
        } catch (RuntimeException e) {
            outcome = ComputeException.Code.FAILED.label();
            LOG.warn("compute {}: request {} failed in the supervisor", settings.name(), job.id(), e);
            fail(job, new ComputeException(ComputeException.Code.FAILED, "the computation failed (request " + job.id() + ")", job.id(), e));
        } finally {
            release(job, worker, reusable);
            finished.accept(new Finished(job.req, outcome, queued, (System.nanoTime() - t0) / 1_000_000, job.worker));
        }
    }

    private void fail(Job job, ComputeException e) {
        failures.get(e.code()).incrementAndGet();
        job.state = e.code() == ComputeException.Code.CANCELLED ? "cancelled" : "failed";
        job.future.completeExceptionally(e);
    }

    private void release(Job job, WorkerProcess worker, boolean reusable) {
        WorkerProcess toClose = null;
        lock.lock();
        try {
            active--;
            running.remove(job);
            queue.finished(job);
            if (worker != null) {
                if (!closed && reusable && worker.alive() && worker.served() < settings.recycleAfter()) {
                    idle.addFirst(worker);                                  // the hottest worker is reused first; cold ones idle out
                } else {
                    toClose = worker;
                    if (worker.alive() && worker.served() >= settings.recycleAfter()) {
                        recycled.incrementAndGet();
                    }
                }
            }
        } finally {
            lock.unlock();
        }
        if (toClose != null) {
            toClose.close();
        }
        pump();
    }

    private Map<String, Object> message(Job job, Path scratch, Map<String, String> files) {
        ComputeRequest r = job.req;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "run");
        m.put("id", r.id());
        m.put("engine", r.engine());
        m.put("kind", r.kind());
        m.put("params", r.params());
        m.put("user", r.viewer().user());
        m.put("roles", r.viewer().roles());
        m.put("viewSignature", r.viewer().viewSignature());
        m.put("cls", r.cls().label());
        m.put("seed", r.seed());
        m.put("scratch", scratch.toAbsolutePath().toString());
        m.put("inputs", files);
        m.put("cpuSeconds", settings.cpuSeconds());
        if (r.chunk() != null) {
            m.put("chunk", Map.of("parent", r.chunk().parent(), "index", r.chunk().index(), "of", r.chunk().of()));
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private ComputeResult result(Job job, WorkerProcess worker, Map<String, Object> msg, Scratch scratch, long queued, long run) {
        Object rows = msg.get("rows");
        Object tables = msg.get("tables");
        Object meta = msg.get("meta");
        if (rows != null && !(rows instanceof List<?>) || tables != null && !(tables instanceof Map<?, ?>) || meta != null && !(meta instanceof Map<?, ?>)) {
            throw new ComputeException(ComputeException.Code.INVALID, "the computation returned an invalid result", job.id(), null);
        }
        List<Map<String, Object>> rs = new ArrayList<>();
        if (rows != null) {
            for (Object o : (List<Object>) rows) {
                rs.add(o instanceof Map<?, ?> mm ? (Map<String, Object>) mm : Map.of("value", o));
            }
        }
        Map<String, String> names = new LinkedHashMap<>();
        if (tables != null) {
            ((Map<String, Object>) tables).forEach((k, v) -> names.put(k, String.valueOf(v)));
        }
        Map<String, ArrowTable> read = scratch.readResults(names, compute.maxResultBytes());
        return new ComputeResult(job.id(), rs, read, meta == null ? Map.of() : (Map<String, Object>) meta, worker.id(), node, queued, run);
    }

    /** Housekeeping, every second: expired waiting requests time out; workers are kept between min and max; idle ones stop. */
    void tick() {
        List<Job> expired = new ArrayList<>();
        List<WorkerProcess> stop = new ArrayList<>();
        int startN = 0;
        long now = System.nanoTime();
        long idleCut = System.currentTimeMillis() - settings.idleStop().toMillis();
        lock.lock();
        try {
            if (closed) {
                return;
            }
            for (Job j : queue.waiting()) {
                if (now >= j.deadline && queue.remove(j)) {
                    expired.add(j);
                }
            }
            idle.removeIf(w -> {
                if (!w.alive()) {
                    stop.add(w);
                    return true;
                }
                return false;
            });
            int live = idle.size() + active + warming;
            var it = idle.descendingIterator();                            // the coldest first
            while (it.hasNext() && live > settings.workersMin()) {
                WorkerProcess w = it.next();
                if (w.lastUsed() < idleCut) {
                    it.remove();
                    stop.add(w);
                    stopped.incrementAndGet();
                    live--;
                }
            }
            if (live < settings.workersMin()) {
                startN = settings.workersMin() - live;
                warming += startN;
            }
        } finally {
            lock.unlock();
        }
        expired.forEach(j -> fail(j, new ComputeException(ComputeException.Code.TIMEOUT, "the computation waited longer than its time budget for a worker",
                j.id(), null)));
        stop.forEach(WorkerProcess::close);
        for (int i = 0; i < startN; i++) {
            Thread.ofVirtual().name("compute-warm-" + settings.name()).start(this::warmOne);
        }
    }

    private void warmOne() {
        WorkerProcess w = null;
        try {
            w = newWorker();
        } catch (RuntimeException e) {
            LOG.warn("compute {}: a worker could not be started: {}", settings.name(), e.getMessage());
        }
        lock.lock();
        try {
            warming--;
            if (w != null) {
                if (closed) {
                    w.close();
                } else {
                    idle.addLast(w);
                }
            }
        } finally {
            lock.unlock();
        }
        pump();
    }

    /** The pool's state for Admin → Compute and for other nodes deciding where to send work. */
    Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> workers = new ArrayList<>();
        int waiting;
        Map<String, Object> queues = new LinkedHashMap<>();
        lock.lock();
        try {
            m.put("name", settings.name());
            m.put("node", node);
            m.put("mode", settings.mode().resolved().name().toLowerCase(java.util.Locale.ROOT));
            m.put("environment", settings.environment());
            m.put("engines", settings.engines());
            m.put("workersMin", settings.workersMin());
            m.put("workersMax", settings.workersMax());
            m.put("busy", active);
            m.put("idle", idle.size());
            m.put("starting", warming);
            for (RequestClass c : RequestClass.values()) {
                queues.put(c.label(), queue.size(c));
            }
            waiting = queue.size();
            for (WorkerProcess w : idle) {
                workers.add(Map.of("id", w.id(), "pid", w.pid(), "state", "idle", "served", w.served()));
            }
            for (Job j : running) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("id", j.worker == null ? "(starting)" : j.worker);
                r.put("state", "busy");
                r.put("request", j.id());
                r.put("user", j.user());
                r.put("class", j.cls().label());
                r.put("kind", j.req.kind());
                r.put("forMs", (System.nanoTime() - j.enqueued) / 1_000_000);
                workers.add(r);
            }
        } finally {
            lock.unlock();
        }
        m.put("queued", waiting);
        m.put("queues", queues);
        m.put("batchSlots", settings.batchSlots());
        m.put("served", served.get());
        m.put("started", started.get());
        m.put("startFailures", startFailures.get());
        m.put("recycled", recycled.get());
        m.put("crashed", crashed.get());
        m.put("idleStopped", stopped.get());
        Map<String, Long> f = new LinkedHashMap<>();
        failures.forEach((k, v) -> {
            if (v.get() > 0) {
                f.put(k.label(), v.get());
            }
        });
        m.put("failures", f);
        m.put("queueMs", queueMs.summary());
        m.put("runMs", runMs.summary());
        m.put("workers", workers);
        return m;
    }

    /** Free capacity for a fan-out decision: max workers, busy and waiting. */
    int[] load() {
        lock.lock();
        try {
            return new int[] {settings.workersMax(), active, queue.size()};
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        List<WorkerProcess> all;
        List<Job> waiting;
        List<Job> busy;
        lock.lock();
        try {
            closed = true;
            all = new ArrayList<>(idle);
            idle.clear();
            waiting = queue.waiting();
            waiting.forEach(queue::remove);
            busy = new ArrayList<>(running);
        } finally {
            lock.unlock();
        }
        waiting.forEach(j -> fail(j, new ComputeException(ComputeException.Code.OFF, "compute pool " + settings.name() + " stopped", j.id(), null)));
        busy.forEach(j -> j.cancelled = true);
        all.forEach(WorkerProcess::close);
    }
}
