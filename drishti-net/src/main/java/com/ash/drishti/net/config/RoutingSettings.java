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
 * @param maxHops 1: a forwarded request is never forwarded again; 0 switches forwarding off (gossip and digests only)
 * @param weights peer choice weights: {@code latency} (how much a peer's latency counts against the stable choice by entity id)
 * @param userState {@code replicate} (saved searches, layouts and pivots written on one node are copied to the others) or {@code off}
 *     (state stays on the node it was written on, as with one shared identity database)
 */
public record RoutingSettings(Duration forwardTimeout, Duration scatterBudget, Duration suggestBudget, int maxHops, Map<String, Double> weights,
        String userState) {

    public static final RoutingSettings DEFAULTS = new RoutingSettings(Duration.ofSeconds(5), Duration.ofSeconds(3), Duration.ofMillis(150), 1,
            Map.of("latency", 1.0), "replicate");

    public RoutingSettings {
        weights = weights == null ? Map.of() : Map.copyOf(weights);
        userState = userState == null || userState.isBlank() ? "replicate" : userState;
    }

    public boolean forwarding() {
        return maxHops > 0;
    }

    public boolean replicateUserState() {
        return "replicate".equals(userState);
    }
}
