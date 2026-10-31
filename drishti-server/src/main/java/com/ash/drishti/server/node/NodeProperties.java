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

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * This server as one node of a cluster (CLUSTER_DEPLOYMENT.md sections 9.1 and 4.6, CD1 and CD2, 2.9.7), {@code drishti.node.*}: what its
 * readiness waits for, and how the drain before a stop decides that the node is safe to stop.
 *
 * @param readiness what {@code /actuator/health/readiness} waits for
 * @param drain the drain before a stop
 */
@ConfigurationProperties("drishti.node")
public record NodeProperties(Readiness readiness, Drain drain) {

    public NodeProperties {
        readiness = readiness == null ? new Readiness(null, null, null, null) : readiness;
        drain = drain == null ? new Drain(null) : drain;
    }

    /** The defaults (tests). */
    public static NodeProperties defaults() {
        return new NodeProperties(null, null);
    }

    /**
     * What readiness waits for (each true by default). Liveness never waits for any of it.
     *
     * @param enabled false: readiness is Spring's alone (up once the server has started), as before 2.9.7
     * @param joining DOWN while this node joins Drishti*net (it has not had its peers' digests yet; at most routing.yaml's join-grace)
     * @param engines DOWN while an enabled engine's (VaR, exposure) newest business date is not open here; the check starts opening it.
     *     A date that cannot be opened (refused, or its load failed) is not waited for: the engine answers from the date before or says why
     * @param shards DOWN while a shard this member owns of a date in use is not ready here (VaR sharding)
     */
    public record Readiness(Boolean enabled, Boolean joining, Boolean engines, Boolean shards) {
        public Readiness {
            enabled = enabled == null || enabled;
            joining = joining == null || joining;
            engines = engines == null || engines;
            shards = shards == null || shards;
        }
    }

    /**
     * The drain before a stop.
     *
     * @param settleSeconds after its drain, how long Drishti*net needs before no peer forwards work here any more (the drained flag reaches
     *     every peer within a few gossip rounds; 5 s): only then is the forwarding part safe to stop
     */
    public record Drain(Integer settleSeconds) {
        public Drain {
            settleSeconds = settleSeconds == null || settleSeconds < 0 ? 5 : settleSeconds;
        }
    }
}
