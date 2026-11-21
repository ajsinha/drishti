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
package com.ash.drishti.engine.rachana;

import com.ash.drishti.api.compute.ComputeRef;
import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.rachana.bind.AsOf;
import com.ash.rachana.bind.Entity;
import com.ash.rachana.bind.EntityHit;
import com.ash.rachana.bind.EntityRef;
import com.ash.rachana.bind.SourceFailures;
import com.ash.rachana.bind.TableProvider;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Rachana's {@link TableProvider} over Drishti's {@link SourceRouter} (SPLIT_PLAN 8.3e): every read the binder, the controls, the map
 * panels and the event wiring make goes to the sources as before: {@code search}, {@code list(...).hits()}, {@code listAcross(...).hits()},
 * {@code fetchAll} with the failures handed back, {@code freshness}, the caller's {@code partition()}, and a computed kind's id as
 * {@link ComputeRef#format} writes it.
 */
public final class DrishtiTables implements TableProvider {

    private final SourceRouter router;

    public DrishtiTables(SourceRouter router) {
        this.router = router;
    }

    public SourceRouter router() {
        return router;
    }

    @Override
    public List<EntityHit> search(String kind, String text, int limit, Duration budget, AsOf at) {
        return router.search(kind, text, limit, budget, Hosted.asOf(at)).stream().map(Hosted::hit).toList();
    }

    @Override
    public List<EntityHit> list(String kind, String text, int limit, Duration budget, AsOf at, SourceFailures failures) {
        com.ash.drishti.engine.source.SourceFailures f = new com.ash.drishti.engine.source.SourceFailures();
        try {
            return router.list(kind, text, limit, budget, Hosted.asOf(at), f).hits().stream().map(Hosted::hit).toList();
        } finally {
            copy(f, failures);
        }
    }

    @Override
    public List<EntityHit> listAcross(String kind, int max, Duration budget, AsOf at, SourceFailures failures) {
        com.ash.drishti.engine.source.SourceFailures f = new com.ash.drishti.engine.source.SourceFailures();
        try {
            return router.listAcross(kind, max, budget, Hosted.asOf(at), f).hits().stream().map(Hosted::hit).toList();
        } finally {
            copy(f, failures);
        }
    }

    @Override
    public Map<EntityRef, Entity> fetchAll(Collection<EntityRef> refs, Duration budget, AsOf at, SourceFailures failures) {
        com.ash.drishti.engine.source.SourceFailures f = new com.ash.drishti.engine.source.SourceFailures();
        try {
            return Hosted.entities(router.fetchAll(Hosted.refs(refs), budget, Hosted.asOf(at), f));
        } finally {
            copy(f, failures);
        }
    }

    @Override
    public Freshness freshness(String kind, String source) {
        SourceRegistry.Freshness f = router.freshness(kind, source);
        return f == null ? null : new Freshness(f.source(), f.lastUpdate(), f.staleAfter(), f.stale());
    }

    @Override
    public String partition() {
        return router.partition();
    }

    @Override
    public String computedId(String id, Map<String, ?> params) {
        return ComputeRef.format(id, params);
    }

    private static void copy(com.ash.drishti.engine.source.SourceFailures from, SourceFailures to) {
        if (to != null && !from.isEmpty()) {
            from.asMap().forEach(to::add);
        }
    }
}
