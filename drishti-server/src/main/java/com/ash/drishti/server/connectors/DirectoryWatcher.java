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
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Calls a task when a folder's files change: file events where the platform offers them ({@code WATCHING}), and in any
 * case every poll interval ({@code POLLING} when events are unavailable or switched off), so an edit is never missed
 * because an event was. The same pattern as the Sutra registry's hot reload. The folder need not exist yet: it is picked
 * up when it appears. One task at a time, on one virtual thread; a task that throws is logged and watching goes on.
 */
final class DirectoryWatcher implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(DirectoryWatcher.class);
    private static final long DEBOUNCE_MS = 250;

    private final Path dir;
    private final String mode;
    private final long pollMs;
    private final Runnable task;
    private volatile String state = "OFF";
    private volatile WatchService service;
    private Thread thread;

    DirectoryWatcher(Path dir, String mode, long pollMs, Runnable task) {
        this.dir = dir;
        this.mode = mode == null ? "auto" : mode;
        this.pollMs = Math.max(50, pollMs);
        this.task = task;
    }

    String state() {
        return state;
    }

    void start() {
        if ("off".equalsIgnoreCase(mode)) {
            state = "OFF";
            return;
        }
        state = "poll".equalsIgnoreCase(mode) ? "POLLING" : tryRegister() ? "WATCHING" : "POLLING";
        thread = Thread.ofVirtual().name("drishti-connectors-watch").start(this::loop);
    }

    private boolean tryRegister() {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try {
            WatchService ws = FileSystems.getDefault().newWatchService();
            dir.register(ws, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
            service = ws;
            return true;
        } catch (IOException | RuntimeException e) {
            LOG.warn("file events are unavailable for {} ({}); looking at the files every {} ms instead", dir, e.getMessage(), pollMs);
            return false;
        }
    }

    private void loop() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                if (service == null && !"poll".equalsIgnoreCase(mode) && tryRegister()) {
                    state = "WATCHING";                      // the folder appeared (or events became available)
                }
                WatchService ws = service;
                if (ws != null) {
                    WatchKey key = ws.poll(pollMs, TimeUnit.MILLISECONDS);
                    if (key != null) {
                        key.pollEvents();
                        key.reset();
                        WatchKey more;
                        while ((more = ws.poll(DEBOUNCE_MS, TimeUnit.MILLISECONDS)) != null) {
                            more.pollEvents();
                            more.reset();
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
        } catch (InterruptedException | java.nio.file.ClosedWatchServiceException e) {
            Thread.currentThread().interrupt();
            state = "OFF";
        } catch (RuntimeException | Error e) {
            state = "STOPPED: " + e;
            LOG.error("connector file watching stopped: changes are not picked up until a restart", e);
        }
    }

    @Override
    public void close() {
        try {
            WatchService ws = service;
            if (ws != null) {
                ws.close();
            }
        } catch (IOException ignored) {
            // closing on shutdown
        }
        if (thread != null) {
            thread.interrupt();
        }
        state = "OFF";
    }
}
