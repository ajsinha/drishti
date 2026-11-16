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
package com.ash.drishti.server.connectors;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import com.ash.drishti.common.SharedWatchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Calls a task when a folder's files change: file events where the platform offers them ({@code WATCHING}), and in any
 * case every poll interval ({@code POLLING} when events are unavailable or switched off), so an edit is never missed
 * because an event was. The same pattern as the Sutra registry's hot reload. The folder need not exist yet: it is picked
 * up when it appears. One task at a time, on one virtual thread; a task that throws is logged and watching goes on.
 */
public final class DirectoryWatcher implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(DirectoryWatcher.class);
    private static final long DEBOUNCE_MS = 250;

    private final Path dir;
    private final String mode;
    private final long pollMs;
    private final Runnable task;
    private final String threadName;
    private volatile String state = "OFF";
    private final BlockingQueue<Boolean> signal = new ArrayBlockingQueue<>(1);   // an event happened (collapses to one)
    private volatile SharedWatchService.Registration registration;
    private Thread thread;

    public DirectoryWatcher(Path dir, String mode, long pollMs, Runnable task) {
        this(dir, mode, pollMs, task, "drishti-connectors-watch");
    }

    public DirectoryWatcher(Path dir, String mode, long pollMs, Runnable task, String threadName) {
        this.threadName = threadName;
        this.dir = dir;
        this.mode = mode == null ? "auto" : mode;
        this.pollMs = Math.max(50, pollMs);
        this.task = task;
    }

    public String state() {
        return state;
    }

    public void start() {
        if ("off".equalsIgnoreCase(mode)) {
            state = "OFF";
            return;
        }
        state = "poll".equalsIgnoreCase(mode) ? "POLLING" : tryRegister() ? "WATCHING" : "POLLING";
        thread = Thread.ofVirtual().name(threadName).start(this::loop);
    }

    private boolean tryRegister() {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try {
            registration = SharedWatchService.defaultInstance().register(dir, () -> signal.offer(Boolean.TRUE));
            return true;
        } catch (IOException | RuntimeException e) {
            LOG.warn("file events are unavailable for {} ({}); looking at the files every {} ms instead", dir, e.getMessage(), pollMs);
            return false;
        }
    }

    private void loop() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                SharedWatchService.Registration reg = registration;
                if (reg != null && !reg.valid()) {            // the folder was deleted: register again when it is back
                    reg.close();
                    registration = null;
                    state = "POLLING";
                }
                if (registration == null && !"poll".equalsIgnoreCase(mode) && tryRegister()) {
                    state = "WATCHING";                      // the folder appeared (or events became available)
                }
                if (registration != null) {
                    if (signal.poll(pollMs, TimeUnit.MILLISECONDS) != null) {
                        while (signal.poll(DEBOUNCE_MS, TimeUnit.MILLISECONDS) != null) {
                            // a burst of events: wait for it to go quiet
                        }
                    }
                } else {
                    Thread.sleep(pollMs);
                }
                try {
                    task.run();
                } catch (RuntimeException | StackOverflowError e) {
                    LOG.error("connector file pass failed; watching goes on", e);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            state = "OFF";
        } catch (RuntimeException | Error e) {
            state = "STOPPED: " + e;
            LOG.error("connector file watching stopped: changes are not picked up until a restart", e);
        }
    }

    @Override
    public void close() {
        SharedWatchService.Registration reg = registration;
        if (reg != null) {
            reg.close();
        }
        if (thread != null) {
            thread.interrupt();
        }
        state = "OFF";
    }
}
