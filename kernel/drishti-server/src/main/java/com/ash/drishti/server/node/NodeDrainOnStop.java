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
package com.ash.drishti.server.node;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * The server's own drain when it is stopped (CD2): on the stop of the application context (SIGTERM, a service stop, docker stop, Ctrl+C)
 * it drains this node ({@link NodeDrain}: forwarded work and VaR shards handed to other members, readiness out of service) and waits until
 * it is safe to stop, at most {@code waitSeconds}, then lets the graceful shutdown go on. Its phase is above the web server's graceful
 * shutdown, so the node still answers while it drains. A node with nothing to hand over is safe to stop at once.
 */
public final class NodeDrainOnStop implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(NodeDrainOnStop.class);
    /** Above WebServerGracefulShutdownLifecycle (DEFAULT_PHASE - 1024): stopped first. */
    public static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 512;

    private final NodeDrain drain;
    private final boolean enabled;
    private final int waitSeconds;
    private volatile boolean running;

    public NodeDrainOnStop(NodeDrain drain, boolean enabled, int waitSeconds) {
        this.drain = drain;
        this.enabled = enabled;
        this.waitSeconds = waitSeconds;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        if (!enabled) {
            return;
        }
        try {
            if (!drain.drained()) {
                drain.drain(true);
            }
            long end = System.nanoTime() + waitSeconds * 1_000_000_000L;
            while (!drain.safeToStop()) {
                if (System.nanoTime() >= end) {
                    LOG.warn("node drain on stop: not safe to stop after {} s: stopping anyway (the shards' replicas answer; their next owners build them)", waitSeconds);
                    return;
                }
                Thread.sleep(500);
            }
            LOG.info("node drain on stop: safe to stop");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            LOG.warn("node drain on stop failed ({}): stopping without it", e.toString());
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
