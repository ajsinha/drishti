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
    private ScheduledExecutorService exec;
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

    /** Starts the schedule (the engine turned on): the first cycle a minute after (or one interval, if shorter), then every interval. */
    public synchronized void start() {
        if (exec != null) {
            return;
        }
        exec = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("dataload-cycle").daemon(true).factory());
        long first = Math.min(settings.interval().toMillis(), Duration.ofMinutes(1).toMillis());
        next = clock.instant().plusMillis(first);
        exec.scheduleWithFixedDelay(() -> cycle("schedule"), first, settings.interval().toMillis(), TimeUnit.MILLISECONDS);
        LOG.info("data inbox: on ({}), a cycle every {}{}", Path.of(settings.dir()).toAbsolutePath().normalize(), settings.interval(),
                settings.owner() ? "" : "; this node is not the owner, so cycles are skipped");
    }

    /** Stops the schedule and a load that runs now (the engine turned off, the server stopping). Files claimed go back at the next start. */
    public synchronized void stop() {
        if (exec == null) {
            return;
        }
        exec.shutdownNow();
        if (runner instanceof LoaderProcess p) {
            p.stopRunning();
        }
        exec = null;
        next = null;
    }

    public synchronized boolean started() {
        return exec != null;
    }

    /** Load now: a cycle queued on the inbox's thread (after the one running, if any); false when the inbox is not started. */
    public synchronized boolean runNow(String by) {
        if (exec == null) {
            return false;
        }
        exec.execute(() -> cycle("by " + by));
        return true;
    }

    /** Runs one cycle on the calling thread (the schedule's thread; a test). */
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
            if (trigger.equals("schedule")) {
                next = clock.instant().plus(settings.interval());
            }
        }
        synchronized (history) {
            history.addFirst(r);
            while (history.size() > HISTORY) {
                history.removeLast();
            }
        }
        return r;
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
