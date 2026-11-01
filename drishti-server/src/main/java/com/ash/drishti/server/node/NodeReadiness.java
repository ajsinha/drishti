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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;

/**
 * Readiness that means ready (CD1, CLUSTER_DEPLOYMENT.md section 9.1): a member of the {@code readiness} health group only (never of
 * {@code /actuator/health} itself, nor of liveness: {@link NodeConfiguration#readinessOnly}). {@code OUT_OF_SERVICE} while this node is
 * drained (its drain, Drishti*net's drain or the VaR shards' drain); {@code DOWN} while any {@link ReadinessGate} waits (joining, an engine's
 * newest date opening, an owned shard not ready); {@code UP} otherwise. Each gate can be switched off in {@code drishti.node.readiness}.
 */
public final class NodeReadiness implements HealthIndicator {

    /** The name of this indicator in the health registry (from its bean name, {@code nodeReadinessHealthIndicator}). */
    public static final String NAME = "nodeReadiness";

    private final Supplier<NodeProperties.Readiness> settings;
    private final Supplier<List<ReadinessGate>> gates;
    private final NodeDrain drain;

    public NodeReadiness(Supplier<NodeProperties.Readiness> settings, Supplier<List<ReadinessGate>> gates, NodeDrain drain) {
        this.settings = settings;
        this.gates = gates;
        this.drain = drain;
    }

    /** What readiness waits for now, gate by gate (only the gates that wait). */
    public Map<String, List<String>> waiting() {
        NodeProperties.Readiness r = settings.get();
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (!r.enabled()) {
            return out;
        }
        for (ReadinessGate g : gates.get()) {
            boolean on = switch (g.kind()) {
                case "joining" -> r.joining();
                case "engines" -> r.engines();
                case "shards" -> r.shards();
                default -> true;
            };
            if (!on) {
                continue;
            }
            List<String> w;
            try {
                w = g.waitingFor();
            } catch (RuntimeException e) {
                w = List.of(g.name() + ": cannot say (" + e.getMessage() + ")");
            }
            if (!w.isEmpty()) {
                out.put(g.name(), w);
            }
        }
        return out;
    }

    /** UP, DOWN or OUT_OF_SERVICE, as the probe answers. */
    public Status status() {
        if (!settings.get().enabled()) {
            return Status.UP;
        }
        if (drain.outOfService()) {
            return Status.OUT_OF_SERVICE;
        }
        return waiting().isEmpty() ? Status.UP : Status.DOWN;
    }

    @Override
    public Health health() {
        Status s = status();
        Health.Builder b = Health.status(s);
        if (s == Status.OUT_OF_SERVICE) {
            b.withDetail("drained", true);
        }
        if (s == Status.DOWN) {
            List<String> all = new ArrayList<>();
            waiting().values().forEach(all::addAll);
            b.withDetail("waitingFor", all);
        }
        return b.build();
    }

    /** The status for Admin and the drain endpoint: the status, and what it waits for. */
    public Map<String, Object> describe() {
        Map<String, Object> out = new LinkedHashMap<>();
        NodeProperties.Readiness r = settings.get();
        out.put("status", status().getCode());
        out.put("enabled", r.enabled());
        out.put("waitingFor", waiting());
        return out;
    }
}
