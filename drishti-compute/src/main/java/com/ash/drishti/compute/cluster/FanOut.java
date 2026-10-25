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
package com.ash.drishti.compute.cluster;

import com.ash.drishti.compute.ComputeException;
import com.ash.drishti.compute.ComputeHandle;
import com.ash.drishti.compute.ComputeRequest;
import com.ash.drishti.compute.ComputeResult;
import com.ash.drishti.compute.ProgressSink;
import com.ash.drishti.compute.arrow.ArrowTable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;

/**
 * Splits one request into chunks and runs them on as many workers as there are, on this node and on the other nodes serving the pool, then
 * gathers the answers in chunk order (docs/architecture/PYTHON_COMPUTE.md section 7.6). This is how a batch of trades is priced over 509
 * scenario days by N workers: the input named {@code split} is cut into chunks of {@code chunkRows} rows; every other input goes whole with
 * each chunk. Each chunk is an ordinary request (its own id, {@code <parent>-c<i>}, the parent's viewer, class and budget), so fairness,
 * limits and isolation apply to every chunk.
 *
 * <p>Where a chunk goes: the target with the lowest {@code (outstanding + 1) / workers}, so work spreads in proportion to capacity and
 * follows whichever node frees up first. At most {@code 2 x total workers} chunks are outstanding at once (back-pressure: the queues are
 * never flooded). A chunk sent to a node that cannot be reached is run here instead, once; any other failure fails the whole request and
 * cancels what is left.
 */
public final class FanOut implements ComputeHandle {

    private static final int BUSY_TRIES = 600;
    private static final long BUSY_WAIT_MS = 100;

    private final ComputeRequest parent;
    private final String split;
    private final int chunkRows;
    private final BiFunction<ComputeRequest, ProgressSink, ComputeHandle> local;
    private final int localWorkers;
    private final PeerTransport peers;
    private final List<PeerTransport.PeerLoad> targets;
    private final ProgressSink progress;
    private final CompletableFuture<ComputeResult> future = new CompletableFuture<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final int chunks;
    private final ComputeResult[] results;
    private final String[] where;
    private final int[] outstanding;
    private final Map<String, ComputeHandle> localHandles = new LinkedHashMap<>();
    private final Map<String, String> remote = new LinkedHashMap<>();
    private final long started = System.nanoTime();
    private int next;
    private int inFlight;
    private int done;
    private volatile String state = "running";

    private FanOut(ComputeRequest parent, String split, int chunkRows, BiFunction<ComputeRequest, ProgressSink, ComputeHandle> local, int localWorkers,
            PeerTransport peers, List<PeerTransport.PeerLoad> targets, ProgressSink progress) {
        this.parent = parent;
        this.split = split;
        this.chunkRows = Math.max(1, chunkRows);
        this.local = local;
        this.localWorkers = Math.max(1, localWorkers);
        this.peers = peers;
        this.targets = targets;
        this.progress = progress == null ? ProgressSink.NONE : progress;
        ArrowTable t = parent.inputs().get(split);
        if (t == null) {
            throw new ComputeException(ComputeException.Code.REFUSED, "the request has no input '" + split + "' to split", parent.id(), null);
        }
        this.chunks = Math.max(1, (t.rows() + this.chunkRows - 1) / this.chunkRows);
        this.results = new ComputeResult[chunks];
        this.where = new String[chunks];
        this.outstanding = new int[targets.size() + 1];                    // [0] is this node
    }

    /**
     * Starts the fan-out. {@code local} submits a chunk to this node's pool; {@code localWorkers} is this node's worker count for the pool;
     * {@code peers} the other nodes (their loads are read once, at the start).
     */
    public static FanOut start(ComputeRequest parent, String split, int chunkRows, BiFunction<ComputeRequest, ProgressSink, ComputeHandle> local,
            int localWorkers, PeerTransport peers, boolean usePeers, ProgressSink progress) {
        List<PeerTransport.PeerLoad> targets = usePeers && peers != null ? peers.peers(parent.pool()).stream().filter(p -> p.workers() > 0).toList()
                : List.of();
        FanOut f = new FanOut(parent, split, chunkRows, local, localWorkers, peers, targets, progress);
        f.launchMore();
        return f;
    }

    private int capacity(int target) {
        return target == 0 ? localWorkers : targets.get(target - 1).workers();
    }

    private String nodeOf(int target) {
        return target == 0 ? "local" : targets.get(target - 1).node();
    }

    private int window() {
        int total = localWorkers;
        for (PeerTransport.PeerLoad p : targets) {
            total += p.workers();
        }
        return Math.max(1, total * 2);
    }

    private int pick() {
        int best = 0;
        double bestScore = Double.MAX_VALUE;
        for (int i = 0; i < outstanding.length; i++) {
            double score = (outstanding[i] + 1.0) / capacity(i);
            if (score < bestScore) {
                best = i;
                bestScore = score;
            }
        }
        return best;
    }

    private ComputeRequest chunk(int index) {
        ArrowTable whole = parent.inputs().get(split);
        int from = index * chunkRows;
        int to = Math.min(whole.rows(), from + chunkRows);
        Map<String, ArrowTable> in = new LinkedHashMap<>(parent.inputs());
        in.put(split, whole.slice(Math.min(from, whole.rows()), to));
        return parent.chunk(in, index, chunks);
    }

    private void launchMore() {
        List<Runnable> launches = new ArrayList<>();
        lock.lock();
        try {
            while (!future.isDone() && next < chunks && inFlight < window()) {
                int index = next++;
                int target = pick();
                outstanding[target]++;
                inFlight++;
                launches.add(() -> send(index, target, chunk(index), false));
            }
        } finally {
            lock.unlock();
        }
        launches.forEach(Runnable::run);
    }

    private void send(int index, int target, ComputeRequest req, boolean retried) {
        send(index, target, req, retried, 0);
    }

    private void send(int index, int target, ComputeRequest req, boolean retried, int busyTries) {
        if (future.isDone()) {
            return;
        }
        CompletableFuture<ComputeResult> f;
        if (target == 0) {
            ComputeHandle h;
            try {
                h = local.apply(req, ProgressSink.NONE);
            } catch (ComputeException e) {
                if (e.code() == ComputeException.Code.BUSY && busyTries < BUSY_TRIES) {
                    // this node's queue is full of other people's work: wait a moment rather than fail the whole fan-out
                    CompletableFuture.delayedExecutor(BUSY_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                            .execute(() -> send(index, target, req, retried, busyTries + 1));
                    return;
                }
                landed(index, target, req, null, e, true);
                return;
            }
            lock.lock();
            try {
                localHandles.put(req.id(), h);
            } finally {
                lock.unlock();
            }
            f = h.result();
        } else {
            String node = nodeOf(target);
            lock.lock();
            try {
                remote.put(req.id(), node);
            } finally {
                lock.unlock();
            }
            try {
                f = peers.run(node, req);
            } catch (RuntimeException e) {
                f = CompletableFuture.failedFuture(new ComputeException(ComputeException.Code.UNAVAILABLE, "node " + node + ": " + e.getMessage()));
            }
        }
        f.whenComplete((r, e) -> landed(index, target, req, r, e, retried));
    }

    private void landed(int index, int target, ComputeRequest req, ComputeResult r, Throwable e, boolean retried) {
        Throwable cause = e instanceof java.util.concurrent.CompletionException && e.getCause() != null ? e.getCause() : e;
        boolean retryHere = false;
        lock.lock();
        try {
            localHandles.remove(req.id());
            remote.remove(req.id());
            outstanding[target]--;
            if (cause == null) {
                results[index] = r;
                where[index] = target == 0 ? "local" : nodeOf(target);
                inFlight--;
                done++;
            } else if (target != 0 && !retried && cause instanceof ComputeException ce && ce.code() == ComputeException.Code.UNAVAILABLE) {
                outstanding[0]++;
                retryHere = true;                                           // the node went away: run the chunk here, once
            } else {
                inFlight--;
            }
        } finally {
            lock.unlock();
        }
        if (retryHere) {
            send(index, 0, req, true);
            return;
        }
        if (cause != null) {
            ComputeException ce = cause instanceof ComputeException x ? x
                    : new ComputeException(ComputeException.Code.FAILED, "a chunk failed: " + cause.getMessage(), parent.id(), cause);
            if (future.completeExceptionally(new ComputeException(ce.code(), "chunk " + (index + 1) + " of " + chunks + ": " + ce.getMessage(),
                    parent.id(), ce))) {
                state = ce.code() == ComputeException.Code.CANCELLED ? "cancelled" : "failed";
                cancelRest();
            }
            return;
        }
        progress.progress(parent.id(), Map.of("done", done, "of", chunks, "node", where[index]));
        if (done == chunks) {
            gather();
        } else {
            launchMore();
        }
    }

    private void gather() {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, List<ArrowTable>> tables = new LinkedHashMap<>();
        Map<String, Integer> nodes = new LinkedHashMap<>();
        Set<String> workers = new TreeSet<>();
        long queued = 0;
        long run = 0;
        for (int i = 0; i < chunks; i++) {
            ComputeResult r = results[i];
            rows.addAll(r.rows());
            r.tables().forEach((k, v) -> tables.computeIfAbsent(k, x -> new ArrayList<>()).add(v));
            nodes.merge(where[i], 1, Integer::sum);
            workers.add(r.worker());
            queued = Math.max(queued, r.queuedMs());
            run += r.runMs();
        }
        Map<String, ArrowTable> merged = new LinkedHashMap<>();
        tables.forEach((k, v) -> merged.put(k, ArrowTable.concat(v)));
        Map<String, Object> meta = new LinkedHashMap<>(results[0].meta());
        meta.put("chunks", chunks);
        meta.put("chunkRows", chunkRows);
        meta.put("nodes", nodes);
        meta.put("workers", workers.size());
        meta.put("workerMs", run);
        state = "done";
        future.complete(new ComputeResult(parent.id(), rows, merged, meta, workers.size() + " workers", String.join(",", nodes.keySet()), queued,
                (System.nanoTime() - started) / 1_000_000));
    }

    private void cancelRest() {
        List<ComputeHandle> hs;
        Map<String, String> rs;
        lock.lock();
        try {
            next = chunks;
            hs = new ArrayList<>(localHandles.values());
            rs = new LinkedHashMap<>(remote);
        } finally {
            lock.unlock();
        }
        hs.forEach(ComputeHandle::cancel);
        rs.forEach((id, node) -> peers.cancel(node, id));
    }

    public int chunks() {
        return chunks;
    }

    @Override
    public String id() {
        return parent.id();
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
        if (future.completeExceptionally(new ComputeException(ComputeException.Code.CANCELLED, "the computation was cancelled", parent.id(), null))) {
            state = "cancelled";
            cancelRest();
        }
    }
}
