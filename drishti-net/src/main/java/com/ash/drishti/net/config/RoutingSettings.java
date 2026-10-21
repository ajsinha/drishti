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
package com.ash.drishti.net.config;

import java.time.Duration;
import java.util.Map;

/**
 * How requests travel ({@code config/net/routing.yaml}); applied to the next request.
 *
 * @param forwardTimeout longest a forwarded view or a peer fetch may take (5 s)
 * @param scatterBudget longest a peer's share of a scattered search may take before the answer is partial without it (3 s)
 * @param suggestBudget the same for type-ahead (150 ms)
 * @param linkBudget longest a view waits for a linked entity that only a peer holds (1 s); the linked entities this node's own
 *     sources hold wait {@code drishti.graph.link-budget} (40 ms) as on a single server
 * @param maxHops 1: a forwarded request is never forwarded again; 0 switches forwarding off (gossip and digests only)
 * @param weights peer choice weights: {@code latency} (how much a peer's latency counts against the stable choice by entity id)
 * @param userState {@code replicate} (saved searches, layouts and pivots written on one node are copied to the others) or {@code off}
 *     (state stays on the node it was written on, as with one shared identity database)
 * @param joinWait longest a read that no source here holds waits, on a node still joining, for its peers' first digests (2 s); then it is
 *     answered from the peers, or {@code 502 DRS-5052} "still joining", never {@code 404 DRS-1001} (SOAK3-01)
 * @param joinGrace longest a node counts as joining after it joined (30 s): when its peers' digests have not all come by then (every peer
 *     down), it answers as a node alone does
 */
public record RoutingSettings(Duration forwardTimeout, Duration scatterBudget, Duration suggestBudget, int maxHops, Map<String, Double> weights,
        String userState, Duration linkBudget, Duration joinWait, Duration joinGrace) {

    public static final RoutingSettings DEFAULTS = new RoutingSettings(Duration.ofSeconds(5), Duration.ofSeconds(3), Duration.ofMillis(150), 1,
            Map.of("latency", 1.0), "replicate", Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(30));

    public RoutingSettings {
        weights = weights == null ? Map.of() : Map.copyOf(weights);
        userState = userState == null || userState.isBlank() ? "replicate" : userState;
        linkBudget = linkBudget == null ? Duration.ofSeconds(1) : linkBudget;
        joinWait = joinWait == null ? Duration.ofSeconds(2) : joinWait;
        joinGrace = joinGrace == null ? Duration.ofSeconds(30) : joinGrace;
    }

    public RoutingSettings(Duration forwardTimeout, Duration scatterBudget, Duration suggestBudget, int maxHops, Map<String, Double> weights,
            String userState, Duration linkBudget) {
        this(forwardTimeout, scatterBudget, suggestBudget, maxHops, weights, userState, linkBudget, null, null);
    }

    public RoutingSettings(Duration forwardTimeout, Duration scatterBudget, Duration suggestBudget, int maxHops, Map<String, Double> weights,
            String userState) {
        this(forwardTimeout, scatterBudget, suggestBudget, maxHops, weights, userState, null, null, null);
    }

    public boolean forwarding() {
        return maxHops > 0;
    }

    public boolean replicateUserState() {
        return "replicate".equals(userState);
    }
}
