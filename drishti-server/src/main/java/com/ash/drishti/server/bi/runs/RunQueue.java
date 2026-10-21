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
package com.ash.drishti.server.bi.runs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The queue of background runs on this node (docs/architecture/BI_RUNS.md section 10.1): at most {@code workers} run at once, and the next one
 * started is the oldest of the person with the fewest runs running (fair share), so one person's burst cannot hold everyone else's runs back.
 * Interactive questions never pass here. Thread-safe.
 */
final class RunQueue {

    private record Entry(RunService.Flight flight, String user, long seq) {}

    private final int workers;
    private final int max;
    private final Consumer<RunService.Flight> run;
    private final List<Entry> waiting = new ArrayList<>();
    private final Map<String, Integer> runningByUser = new HashMap<>();
    private int running;
    private long seq;
    private boolean closed;

    RunQueue(int workers, int max, Consumer<RunService.Flight> run) {
        this.workers = Math.max(1, workers);
        this.max = Math.max(1, max);
        this.run = run;
    }

    /** Adds a run; false when the node's queue is full ({@code queue.max}). */
    synchronized boolean offer(RunService.Flight f, String user) {
        if (closed || waiting.size() >= max) {
            return false;
        }
        waiting.add(new Entry(f, user, seq++));
        startMore();
        return true;
    }

    /** Takes a run out of the queue (cancelled while it waited); false when it was not waiting. */
    synchronized boolean remove(RunService.Flight f) {
        return waiting.removeIf(e -> e.flight() == f);
    }

    private void startMore() {
        while (!closed && running < workers && !waiting.isEmpty()) {
            Entry next = waiting.stream().min((a, b) -> {
                int ra = runningByUser.getOrDefault(a.user(), 0);
                int rb = runningByUser.getOrDefault(b.user(), 0);
                return ra != rb ? Integer.compare(ra, rb) : Long.compare(a.seq(), b.seq());
            }).orElseThrow();
            waiting.remove(next);
            running++;
            runningByUser.merge(next.user(), 1, Integer::sum);
            Thread.ofVirtual().name("bi-run-worker").start(() -> {
                try {
                    run.accept(next.flight());
                } finally {
                    done(next.user());
                }
            });
        }
    }

    private synchronized void done(String user) {
        running--;
        runningByUser.merge(user, -1, Integer::sum);
        startMore();
    }

    synchronized Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("workers", workers);
        m.put("running", running);
        m.put("queued", waiting.size());
        Map<String, Integer> byUser = new LinkedHashMap<>();
        waiting.forEach(e -> byUser.merge(e.user(), 1, Integer::sum));
        m.put("byUser", byUser);
        m.put("byClass", Map.of("background", waiting.size()));
        return m;
    }

    synchronized void close() {
        closed = true;
        waiting.clear();
    }
}
