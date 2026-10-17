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
package com.ash.drishti.server.bi.mq;

import com.ash.drishti.server.bi.plan.SecuritySignature;
import com.ash.drishti.server.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The subscription directory of a site with several Java nodes (docs/architecture/BI_DESIGN.md section 6.5): each model's shard (its live tables and its
 * standing queries) is owned by one node, chosen by rendezvous (highest random weight) hashing of the model's key over the nodes that are alive, so a node
 * that leaves moves only its own shards and every node agrees on the owner without talking. Asked queries stay stateless (any node answers any query).
 */
public final class ShardDirectory {

    /** Another Java node, as the standing queries reach it (in process in the tests; over HTTP between servers). */
    public interface Peer {
        String name();

        boolean alive();

        /** Holds a subscription there under the given id (the forwarding node keeps the id, so it survives a move). */
        Map<String, Object> subscribe(Principal p, ModelQuery q, Duration throttle, String deliver, String id);

        List<StandingQueries.Event> poll(List<String> ids);

        List<StandingQueries.Event> status(List<String> ids);

        void unsubscribe(Principal p, String id);
    }

    private final String self;
    private final List<String> nodes;
    private final Predicate<String> alive;

    /**
     * @param self this node's name
     * @param nodes every node's name (this one included)
     * @param alive whether a node is up now (this one always is)
     */
    public ShardDirectory(String self, List<String> nodes, Predicate<String> alive) {
        this.self = self;
        this.nodes = List.copyOf(nodes);
        this.alive = alive;
    }

    public String self() {
        return self;
    }

    public List<String> nodes() {
        return nodes;
    }

    /** The node that owns a model's shard now. */
    public String owner(String modelKey) {
        String best = self;
        long top = Long.MIN_VALUE;
        for (String n : nodes) {
            if (!n.equals(self) && !alive.test(n)) {
                continue;
            }
            long w = weight(n, modelKey);
            if (w > top || w == top && n.compareTo(best) < 0) {
                top = w;
                best = n;
            }
        }
        return best;
    }

    public boolean owns(String modelKey) {
        return self.equals(owner(modelKey));
    }

    static long weight(String node, String key) {
        String h = SecuritySignature.sha(node + "|" + key);
        return Long.parseUnsignedLong(h.substring(0, 15), 16);
    }
}
