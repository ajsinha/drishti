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
package com.ash.drishti.dataload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The data inbox as the server runs it (DATALOAD_INBOX.md sections 4, 6 and 10): a cycle every {@code interval} on its own thread
 * ({@code dataload-cycle}), one at a time; Load now; Retry; and the status Admin and {@code drishti.py dataload status} show (the files
 * waiting, the last cycles, the failures with their reasons, the lock's holder, the next cycle's time). The server makes one, wires its
 * signal, packs and busy check, and starts and stops it with the engine's switch.
 */
public final class DataInbox {

    private static final Logger LOG = LoggerFactory.getLogger(DataInbox.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int HISTORY = 20;

    private final DataLoadSettings settings;
    private final String node;
    private final Clock clock;
    private final ZoneId zone;
    private final Routes.Packs packs;
    private final LoadRunner runner;
    private final Signal signal;
    private final Supplier<String> busy;
    private final Deque<Cycle.Report> history = new ArrayDeque<>();
    private volatile ScheduledExecutorService ticker;
    private volatile java.util.concurrent.ExecutorService worker;
    /** Start, stop and Load now one at a time (a lock, not synchronized: stopping waits on the loader, which would pin a virtual thread). */
    private final java.util.concurrent.locks.ReentrantLock guard = new java.util.concurrent.locks.ReentrantLock();
    private final java.util.concurrent.atomic.AtomicBoolean busyRun = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile Instant next;
    private volatile Instant current;
    private volatile String currentTrigger;

    public DataInbox(DataLoadSettings settings, String node, Clock clock, ZoneId zone, Routes.Packs packs, LoadRunner runner, Signal signal,
            Supplier<String> busy) {
        this.settings = settings;
        this.node = node;
        this.clock = clock;
        this.zone = zone;
        this.packs = packs;
        this.runner = runner;
        this.signal = signal;
        this.busy = busy;
    }

    public DataLoadSettings settings() {
        return settings;
    }

    /**
     * Starts the schedule (the engine turned on): the first cycle a minute after (or one interval, if shorter), then every interval. A
     * cycle due while a run still loads is not started: it is recorded as skipped, with the run and how long it has loaded.
     */
    public void start() {
        guard.lock();
        try {
            startLocked();
        } finally {
            guard.unlock();
        }
    }

    private void startLocked() {
        if (ticker != null) {
            return;
        }
        ticker = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("dataload-clock").daemon(true).factory());
        worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("dataload-cycle").daemon(true).factory());
        long first = Math.min(settings.interval().toMillis(), Duration.ofMinutes(1).toMillis());
        next = clock.instant().plusMillis(first);
        ticker.scheduleAtFixedRate(() -> {
            next = clock.instant().plus(settings.interval());
            startOrSkip("schedule");
        }, first, settings.interval().toMillis(), TimeUnit.MILLISECONDS);
        LOG.info("data inbox: on ({}), a cycle every {}{}", Path.of(settings.dir()).toAbsolutePath().normalize(), settings.interval(),
                settings.owner() ? "" : "; this node is not the owner, so cycles are skipped");
    }

    /** Stops the schedule and a load that runs now (the engine turned off, the server stopping). Files claimed go back at the next start. */
    public void stop() {
        guard.lock();
        try {
            stopLocked();
        } finally {
            guard.unlock();
        }
    }

    private void stopLocked() {
        if (ticker == null) {
            return;
        }
        ticker.shutdownNow();
        worker.shutdownNow();
        if (runner instanceof LoaderProcess p) {
            p.stopRunning();
        }
        ticker = null;
        worker = null;
        next = null;
    }

    public boolean started() {
        return ticker != null;
    }

    /**
     * Load now: a cycle started at once on the inbox's thread. Answers null when it started, else why not: the inbox is off, or a run
     * still loads ({@code skipped: run <id> still loading (12 min)}, recorded as a skipped cycle).
     */
    public String runNow(String by) {
        return startOrSkip("by " + by);
    }

    /** Starts a cycle on the worker, or records it as skipped while a run loads (runs never overlap); null when it started. */
    private String startOrSkip(String trigger) {
        guard.lock();
        try {
            return startOrSkipLocked(trigger);
        } finally {
            guard.unlock();
        }
    }

    private String startOrSkipLocked(String trigger) {
        if (worker == null) {
            return "the data inbox is off on this server";
        }
        if (!busyRun.compareAndSet(false, true)) {
            String why = loadingNow();
            record(new Cycle.Report("-", trigger, clock.instant(), clock.instant(), "skipped", why, List.of(), List.of(), 0));
            LOG.info("data inbox: cycle skipped: {}", why);
            return "skipped: " + why;
        }
        try {
            worker.execute(() -> {
                try {
                    cycle(trigger);
                } finally {
                    busyRun.set(false);
                }
            });
        } catch (RuntimeException e) {
            busyRun.set(false);
            throw e;
        }
        return null;
    }

    /** The run that loads now, in words: from the inbox lock (its run id and start), else from this server's own clock. */
    private String loadingNow() {
        JsonNode h = new Claims(Path.of(settings.dir()), clock).holder();
        if (h != null && !h.path("run").asText("").isEmpty()) {
            return Cycle.stillLoading(h, clock.instant());
        }
        Instant since = current;
        long min = since == null ? 0 : Math.max(0, Duration.between(since, clock.instant()).toMinutes());
        return "a run started at " + (since == null ? "?" : since.toString()) + " still loading (" + min + " min)";
    }

    /** Runs one cycle on the calling thread (the worker; a test). */
    public Cycle.Report cycle(String trigger) {
        current = clock.instant();
        currentTrigger = trigger;
        Cycle.Report r;
        try {
            r = new Cycle(settings, node, clock, zone, packs, runner, signal, busy, Cycle.Faults.NONE).run(trigger);
        } catch (RuntimeException e) {
            LOG.warn("data inbox: the cycle failed: {}", e.toString());
            r = new Cycle.Report("-", trigger, current, clock.instant(), "failed", e.toString(), List.of(), List.of(), 0);
        } finally {
            current = null;
            currentTrigger = null;
        }
        record(r);
        return r;
    }

    private void record(Cycle.Report r) {
        synchronized (history) {
            history.addFirst(r);
            while (history.size() > HISTORY) {
                history.removeLast();
            }
        }
    }

    /** Retry: a failed file and its marker back into the inbox (the next cycle loads it); recorded in the ledger. */
    public Map<String, Object> retry(String name, String by) throws IOException {
        Path root = Path.of(settings.dir());
        Path from = new Outbox(root, clock, zone).retry(name);
        Path rel = root.resolve("failed").relativize(from);
        new Ledger(root.resolve("ledger"), clock).write(rel.getName(1).toString(), name, "retried",
                JSON.createObjectNode().put("by", by).put("reason", "moved back to the inbox by " + by));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("file", name);
        out.put("from", root.relativize(from).toString().replace('\\', '/'));
        out.put("to", "inbox/" + name);
        out.put("next", started() ? "the next cycle loads it (or Load now)" : "the inbox is off: turn it on to load it");
        return out;
    }

    /** What Admin and the command line show. */
    public Map<String, Object> status() {
        Path root = Path.of(settings.dir());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("on", started());
        out.put("owner", settings.owner());
        out.put("node", node);
        out.put("dir", root.toAbsolutePath().normalize().toString());
        out.put("interval", settings.interval().toString());
        out.put("nextCycleAt", next == null ? null : next.toString());
        Map<String, Object> run = null;
        if (current != null) {
            run = new LinkedHashMap<>();
            run.put("startedAt", current.toString());
            run.put("trigger", currentTrigger);
        }
        out.put("running", run);
        JsonNode holder = new Claims(root, clock).holder();
        out.put("lock", holder == null ? null : JSON.convertValue(holder, Map.class));
        try {
            Inbox.Found f = new Inbox(root.resolve("inbox")).look(clock.instant(), Duration.ofSeconds(settings.stableSeconds()), settings.maxFiles(),
                    settings.maxBytes());
            List<Map<String, Object>> waiting = new ArrayList<>();
            f.ready().forEach(r -> waiting.add(entry(r.name(), r.size(), r.modified(), "ready: the next cycle takes it")));
            f.waiting().forEach(w -> waiting.add(entry(w.name(), w.size(), w.modified(), w.why())));
            f.refused().forEach(r -> waiting.add(entry(r.file().getFileName().toString(), -1, null, "refused at the next cycle: " + r.reason())));
            out.put("waiting", waiting);
            out.put("markersWithoutFile", f.orphanMarkers());
            out.put("readyAll", f.readyAll() != null);
            Outbox ob = new Outbox(root, clock, zone);
            List<Path> failed = ob.failedCopies();
            out.put("failed", failed.subList(Math.max(0, failed.size() - 50), failed.size()).reversed().stream().map(ob::describeFailed).toList());
            out.put("ledger", new Ledger(root.resolve("ledger"), clock).recent(20));
        } catch (IOException e) {
            out.put("error", "the inbox's folders cannot be read: " + e.getMessage());
        }
        synchronized (history) {
            out.put("cycles", List.copyOf(history));
        }
        return out;
    }

    private static Map<String, Object> entry(String name, long size, Instant modified, String state) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("file", name);
        m.put("size", size < 0 ? null : size);
        m.put("modified", modified == null ? null : modified.toString());
        m.put("state", state);
        return m;
    }
}
