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

import com.ash.drishti.compute.ComputeException;
import com.ash.drishti.compute.ComputeRequest;
import com.ash.drishti.compute.ComputeResult;
import com.ash.drishti.compute.cluster.PeerTransport;
import com.ash.drishti.net.transport.PeerHttp;
import com.ash.drishti.server.net.RunCluster;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fan-out over Drishti*net (docs/architecture/PYTHON_COMPUTE.md section 7.6): the other members alive, each asked for its load on a pool
 * (cached two seconds), and chunks run there on behalf of the request's viewer: signed, one hop, an on-behalf-of token naming the viewer, as
 * BI runs are forwarded ({@link RunCluster}). A member that cannot be reached answers {@code unavailable}, and fan-out runs the chunk here.
 */
final class NetComputePeers implements PeerTransport {

    private static final Logger LOG = LoggerFactory.getLogger(NetComputePeers.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Principal SYSTEM = new Principal("drishti-compute", List.of());
    private static final long CACHE_MS = 2000;

    private record Cached(long at, List<PeerLoad> loads) {}

    private final RunCluster cluster;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    NetComputePeers(RunCluster cluster) {
        this.cluster = cluster;
    }

    @Override
    public List<PeerLoad> peers(String pool) {
        if (!cluster.on()) {
            return List.of();
        }
        Cached c = cache.get(pool);
        if (c != null && System.currentTimeMillis() - c.at() < CACHE_MS) {
            return c.loads();
        }
        List<PeerLoad> out = new ArrayList<>();
        for (String node : cluster.peers()) {
            try {
                PeerHttp.Answer a = cluster.call(node, "GET", "/api/v1/compute/peer/load?pool=" + URLEncoder.encode(pool, StandardCharsets.UTF_8), null,
                        SYSTEM, Duration.ofSeconds(2));
                if (a.status() == 200) {
                    Map<String, Object> m = JSON.readValue(a.body(), new TypeReference<Map<String, Object>>() {});
                    int workers = ((Number) m.getOrDefault("workers", 0)).intValue();
                    if (workers > 0) {
                        out.add(new PeerLoad(node, workers, ((Number) m.getOrDefault("busy", 0)).intValue(), ((Number) m.getOrDefault("queued", 0)).intValue()));
                    }
                }
            } catch (IOException | RuntimeException e) {
                LOG.debug("compute: node {} did not say its load on {}: {}", node, pool, e.getMessage());
            }
        }
        cache.put(pool, new Cached(System.currentTimeMillis(), List.copyOf(out)));
        return out;
    }

    @Override
    public CompletableFuture<ComputeResult> run(String node, ComputeRequest request) {
        CompletableFuture<ComputeResult> f = new CompletableFuture<>();
        Thread.ofVirtual().name("compute-peer-" + node).start(() -> {
            try {
                byte[] body = JSON.writeValueAsBytes(ComputeWire.request(request));
                Duration budget = (request.timeout() == null ? Duration.ofMinutes(10) : request.timeout()).plusSeconds(15);
                PeerHttp.Answer a = cluster.call(node, "POST", "/api/v1/compute/peer/run", body,
                        new Principal(request.viewer().user(), request.viewer().roles()), budget);
                if (a.status() != 200) {
                    throw new ComputeException(a.status() >= 500 ? ComputeException.Code.UNAVAILABLE : ComputeException.Code.REFUSED,
                            "node " + node + " answered " + a.status());
                }
                f.complete(ComputeWire.result(JSON.readValue(a.body(), new TypeReference<Map<String, Object>>() {})));
            } catch (ComputeException e) {
                f.completeExceptionally(e);
            } catch (IOException e) {
                f.completeExceptionally(new ComputeException(ComputeException.Code.UNAVAILABLE, "node " + node + " cannot be reached: " + e.getMessage()));
            } catch (RuntimeException e) {
                f.completeExceptionally(new ComputeException(ComputeException.Code.FAILED, "node " + node + ": " + e.getMessage()));
            }
        });
        return f;
    }

    @Override
    public void cancel(String node, String requestId) {
        Thread.ofVirtual().start(() -> {
            try {
                cluster.call(node, "POST", "/api/v1/compute/peer/cancel/" + URLEncoder.encode(requestId, StandardCharsets.UTF_8), new byte[0], SYSTEM,
                        Duration.ofSeconds(5));
            } catch (IOException | RuntimeException e) {
                LOG.debug("compute: could not cancel {} on {}: {}", requestId, node, e.getMessage());
            }
        });
    }
}
