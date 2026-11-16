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

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The drain before a stop (CD2, CLUSTER_GUIDE.md section 4.6): one action drains every {@link Drainable} part of this node at once
 * (Drishti*net's forwarding, the VaR shards), makes readiness {@code OUT_OF_SERVICE}, and says {@code safeToStop} once every part has
 * handed its work over. The flag is in memory: a restarted node takes work again (the rolling upgrade relies on it). Requests in flight
 * finish either way: the stop itself is Spring's graceful shutdown.
 */
public final class NodeDrain {

    private static final Logger LOG = LoggerFactory.getLogger(NodeDrain.class);

    private final Supplier<List<Drainable>> parts;
    private volatile boolean drained;
    private volatile Instant since;

    /** @param parts the parts of this node that hand work over (looked up when asked: an engine switched on later is included) */
    public NodeDrain(Supplier<List<Drainable>> parts) {
        this.parts = parts;
    }

    /** Drains this node (true) or takes it back (false); the status after. */
    public synchronized Map<String, Object> drain(boolean on) {
        drained = on;
        since = on ? Instant.now() : null;
        for (Drainable p : parts.get()) {
            try {
                p.drain(on);
            } catch (RuntimeException e) {
                LOG.warn("node drain: {} could not be {}: {}", p.name(), on ? "drained" : "undrained", e.getMessage());
            }
        }
        LOG.info("node drain: {}", on ? "draining: forwarded work and shards go to other members; ready for a stop once safeToStop"
                : "undrained: takes work again");
        return status();
    }

    /** True while this node's drain is on. */
    public boolean drained() {
        return drained;
    }

    /** True while this node or any of its parts is drained (readiness is then {@code OUT_OF_SERVICE}). */
    public boolean outOfService() {
        if (drained) {
            return true;
        }
        for (Drainable p : parts.get()) {
            try {
                if (p.draining()) {
                    return true;
                }
            } catch (RuntimeException e) {
                // a part that cannot say is not drained
            }
        }
        return false;
    }

    /** True when this node is drained and every part has handed its work over. */
    public boolean safeToStop() {
        if (!drained) {
            return false;
        }
        for (Drainable p : parts.get()) {
            if (!safe(p)) {
                return false;
            }
        }
        return true;
    }

    private static boolean safe(Drainable p) {
        try {
            return p.safeToStop();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** {@code drained}, {@code since}, {@code safeToStop} and each part's state. */
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("drained", drained);
        out.put("since", since == null ? null : since.toString());
        boolean safe = drained;
        List<Map<String, Object>> ps = new ArrayList<>();
        for (Drainable p : parts.get()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.name());
            boolean s = safe(p);
            try {
                m.put("draining", p.draining());
                m.put("detail", p.detail());
            } catch (RuntimeException e) {
                m.put("draining", false);
                m.put("detail", "cannot say: " + e.getMessage());
            }
            m.put("safeToStop", s);
            safe &= s;
            ps.add(m);
        }
        out.put("safeToStop", safe);
        out.put("parts", ps);
        return out;
    }
}
