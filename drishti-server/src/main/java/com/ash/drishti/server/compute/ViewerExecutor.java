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
package com.ash.drishti.server.compute;

import com.ash.drishti.api.compute.Viewer;
import com.ash.drishti.server.security.Principal;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * An executor whose tasks run on behalf of one viewer: a live view's rebuilds happen on the live hub's threads, long after the request
 * that opened it, and a panel whose source is a computed kind must still be computed (and cached) for that viewer and no one else
 * (REACTIVE_CONNECTORS.md section 9). Shutting it down does not shut the shared executor down.
 */
public final class ViewerExecutor extends AbstractExecutorService {

    private final ExecutorService delegate;
    private final Viewer viewer;

    private ViewerExecutor(ExecutorService delegate, Viewer viewer) {
        this.delegate = delegate;
        this.viewer = viewer;
    }

    /** {@code delegate}'s threads acting for {@code principal}. */
    public static ExecutorService of(ExecutorService delegate, Principal principal) {
        return principal == null ? delegate : new ViewerExecutor(delegate, ReactiveHostImpl.viewerOf(principal));
    }

    @Override
    public void execute(Runnable command) {
        delegate.execute(() -> {
            Viewer before = Viewer.current().orElse(null);
            Viewer.set(viewer);
            try {
                command.run();
            } finally {
                Viewer.set(before);
            }
        });
    }

    @Override
    public void shutdown() {
        // the shared executor is not this view's to stop
    }

    @Override
    public List<Runnable> shutdownNow() {
        return List.of();
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }
}
