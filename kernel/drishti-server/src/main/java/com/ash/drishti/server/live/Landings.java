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
package com.ash.drishti.server.live;

import com.ash.drishti.common.live.Landing;
import com.ash.drishti.kernel.api.data.KindLanding;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Where the live connectors' landed kinds go (docs/architecture/LIVE_CONNECTORS_DESIGN.md sections 2.4 and 7.5): the engine that owns a
 * kind provides its {@link KindLanding} (the VaR engine's for scenario PVs and P&amp;L vectors: its checks, the lake writer, the data-load
 * signal), and the kernel's live lander hands each window of that kind to it. A kind no engine takes is not landed, said in the
 * connector's reject log. The landings are looked up at every window, so an engine switched on or off is followed at once.
 */
@Component
public final class Landings implements Landing {

    private final ObjectProvider<KindLanding> engines;

    public Landings(com.ash.drishti.engine.source.SourceRegistry registry, ObjectProvider<KindLanding> engines) {
        this.engines = engines;
        registry.landing(this);
    }

    /** The landing of {@code kind}, if an engine takes it. */
    public Optional<KindLanding> of(String kind) {
        return engines.orderedStream().filter(e -> e.kinds().contains(kind)).findFirst();
    }

    @Override
    public Landed land(Batch batch) {
        Optional<KindLanding> l = of(batch.kind());
        if (l.isEmpty()) {
            return new Landed(0, List.of(), "kind " + batch.kind() + " has no landing on this server (its engine is off or not installed)");
        }
        try {
            KindLanding.Landed r = l.get().land(new KindLanding.Batch(batch.connector(), batch.kind(), batch.date(), batch.batchId(),
                    batch.rows().stream().map(ObjectNode::toString).toList(), batch.settings()));
            return new Landed(r.written(), r.rejects().stream().map(x -> new Reject(x.key(), x.code(), x.why())).toList(), r.error());
        } catch (RuntimeException e) {
            return new Landed(0, List.of(), e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
