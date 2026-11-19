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
package com.ash.drishti.server.api;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.context.request.async.DeferredResultProcessingInterceptor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Ends every open long-lived response when the server stops (SIGTERM, a service stop, the launcher's stop), so the web server's graceful
 * shutdown is not held for its whole phase timeout by streams that never end on their own.
 *
 * <p>Every server-sent-events stream ({@code SseEmitter}: live views, monitors, alerts, live sets, pivot live, BI reports, runs, message
 * queries, reactive compute) and every {@code DeferredResult} is asynchronous request processing; this interceptor, registered once for the
 * whole web layer, sees each one start and end. A live stream ends only when its client leaves, so with a ticking source (the built-in
 * samples) a connected console kept the graceful shutdown waiting and the process lingered long after SIGTERM. On stop this bean completes
 * each open one (the client sees the stream end and reconnects to another node or later) and any that starts afterwards is completed at
 * once. Its phase is below the node's drain ({@code NodeDrainOnStop}: streams still answer while the node hands its work over) and above the
 * web server's graceful shutdown (DEFAULT_PHASE - 1024), which then finds no request open.
 */
@Component
public class LiveStreamsOnStop implements SmartLifecycle, DeferredResultProcessingInterceptor, WebMvcConfigurer {

    private static final Logger LOG = LoggerFactory.getLogger(LiveStreamsOnStop.class);
    /** Between NodeDrainOnStop (DEFAULT_PHASE - 512) and WebServerGracefulShutdownLifecycle (DEFAULT_PHASE - 1024). */
    static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 768;

    private final Set<DeferredResult<?>> open = ConcurrentHashMap.newKeySet();
    private volatile boolean running;
    private volatile boolean stopped;

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.registerDeferredResultInterceptors(this);
    }

    @Override
    public <T> void preProcess(NativeWebRequest request, DeferredResult<T> result) {
        open.add(result);
        if (stopped) {
            end(result);                               // a stream opened while stopping ends at once
        }
    }

    @Override
    public <T> void afterCompletion(NativeWebRequest request, DeferredResult<T> result) {
        open.remove(result);
    }

    /** The open asynchronous responses (for Health and tests). */
    public int open() {
        return open.size();
    }

    @Override
    public void start() {
        stopped = false;
        running = true;
    }

    @Override
    public void stop() {
        stopped = true;
        running = false;
        int n = 0;
        for (DeferredResult<?> r : open) {
            if (end(r)) {
                n++;
            }
        }
        if (n > 0) {
            LOG.info("stopping: ended {} open stream(s) so the graceful shutdown need not wait for them", n);
        }
    }

    /** Completes one: the same as the producer's own {@code complete()}, its completion callbacks run and its resources are released. */
    private static boolean end(DeferredResult<?> r) {
        try {
            return r.setResult(null);
        } catch (RuntimeException e) {
            return false;                              // already completed by its producer or its client
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
