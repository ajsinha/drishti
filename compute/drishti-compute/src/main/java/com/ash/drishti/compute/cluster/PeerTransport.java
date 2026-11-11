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
package com.ash.drishti.compute.cluster;

import com.ash.drishti.compute.ComputeRequest;
import com.ash.drishti.compute.ComputeResult;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The other Drishti*net nodes that serve a pool, as fan-out sees them: their capacity, and a way to run one chunk there on behalf of the
 * request's viewer (signed, one hop: the receiving node runs it on its own workers and never forwards it again). The server implements it
 * over Drishti*net; off a cluster it is {@link #NONE}, and all work runs on this node's workers.
 */
public interface PeerTransport {

    /** A node's load for one pool: its workers there, how many are busy, how many requests wait. */
    record PeerLoad(String node, int workers, int busy, int queued) {}

    /** The other nodes serving the pool now (alive, with at least one worker for it). */
    List<PeerLoad> peers(String pool);

    /** Runs the request on {@code node}; completes with its result or a {@link com.ash.drishti.compute.ComputeException}. */
    CompletableFuture<ComputeResult> run(String node, ComputeRequest request);

    /** Asks {@code node} to stop a request it is running for this node (best effort). */
    default void cancel(String node, String requestId) {
        // best effort; a transport may not support it
    }

    /** No other nodes: everything runs here. */
    PeerTransport NONE = new PeerTransport() {
        @Override
        public List<PeerLoad> peers(String pool) {
            return List.of();
        }

        @Override
        public CompletableFuture<ComputeResult> run(String node, ComputeRequest request) {
            return CompletableFuture.failedFuture(new com.ash.drishti.compute.ComputeException(
                    com.ash.drishti.compute.ComputeException.Code.UNAVAILABLE, "no other nodes"));
        }
    };
}
