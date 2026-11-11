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
package com.ash.drishti.compute;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A submitted request: its id, its eventual result (only ever this request's), its state, and a way to cancel it. A request still waiting
 * is taken out of the queue; one running is stopped (in fork mode only its child process).
 */
public interface ComputeHandle {

    String id();

    /** Completes with the result, or exceptionally with a {@link ComputeException}. */
    CompletableFuture<ComputeResult> result();

    /** {@code queued}, {@code running}, {@code done}, {@code failed} or {@code cancelled}. */
    String state();

    void cancel();

    /** Waits for the result; a failure is thrown as the {@link ComputeException} it was. */
    default ComputeResult await(Duration max) {
        try {
            return result().get(max.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ComputeException ce) {
                throw ce;
            }
            throw new ComputeException(ComputeException.Code.FAILED, String.valueOf(e.getCause()), id(), e.getCause());
        } catch (TimeoutException e) {
            throw new ComputeException(ComputeException.Code.TIMEOUT, "no result within " + max.toMillis() + " ms", id(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ComputeException(ComputeException.Code.CANCELLED, "interrupted", id(), null);
        }
    }

    /** A handle that has already failed (refused before it was queued). */
    static ComputeHandle failed(String id, ComputeException e) {
        CompletableFuture<ComputeResult> f = CompletableFuture.failedFuture(e.forRequest(id));
        return new ComputeHandle() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public CompletableFuture<ComputeResult> result() {
                return f;
            }

            @Override
            public String state() {
                return "failed";
            }

            @Override
            public void cancel() {
                // nothing to cancel
            }
        };
    }
}
