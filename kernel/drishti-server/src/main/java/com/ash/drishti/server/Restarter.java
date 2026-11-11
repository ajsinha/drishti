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
package com.ash.drishti.server;

import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Restarts the server inside its own process: the Spring context is closed and built again from configuration, as at
 * start-up (packs, connectors, Sutras). Used when an administrator loads or unloads a pack. Only a server started by
 * {@link DrishtiApplication#main} can restart this way; elsewhere (tests, an embedding) {@link #available()} is false.
 */
public final class Restarter {

    private static final Logger LOG = LoggerFactory.getLogger(Restarter.class);
    private static final SynchronousQueue<String> REQUESTS = new SynchronousQueue<>();
    private static volatile boolean available;
    private static volatile Runnable undo;

    private Restarter() {}

    static void enable() {
        available = true;
    }

    public static boolean available() {
        return available;
    }

    /**
     * Asks for a restart after {@code delayMs} (so the answer to the request that asked for it is sent first).
     *
     * @param undoIfFails puts the configuration back if the server cannot start with the new one, so it starts as it
     *     was rather than not at all
     */
    public static void request(String reason, long delayMs, Runnable undoIfFails) {
        if (!available) {
            return;
        }
        undo = undoIfFails;
        Thread.ofPlatform().name("drishti-restart").daemon(false).start(() -> {
            try {
                Thread.sleep(delayMs);
                LOG.warn("restarting the server in place: {}", reason);
                REQUESTS.offer(reason, 30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /** Waits for a restart request; blocks the caller (the main thread) until one comes. */
    static String await() throws InterruptedException {
        return REQUESTS.take();
    }

    /** The undo for the last restart, once: null when there is none (the first start, or already used). */
    static Runnable takeUndo() {
        Runnable u = undo;
        undo = null;
        return u;
    }
}
