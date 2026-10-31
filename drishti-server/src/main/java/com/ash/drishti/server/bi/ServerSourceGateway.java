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
package com.ash.drishti.server.bi;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityHit;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.engine.time.BusinessDates;
import com.ash.drishti.server.bi.engine.QueryProperties;
import com.ash.drishti.server.bi.engine.SourceGateway;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The engine's way to the data on the server: the connectors by their logical names ({@code config/connectors/<name>.yaml}), through the
 * connector registry, and the router for the slow path of reading documents. There is no BI-only route to a store (BI_DESIGN.md › Rūpaka: the original design and every phase as built, section 6.1).
 */
public final class ServerSourceGateway implements SourceGateway {

    private final SourceRegistry registry;
    private final SourceRouter router;
    private final BusinessDates dates;
    private final QueryProperties props;
    private final com.ash.drishti.engine.live.TopicHub hub;

    public ServerSourceGateway(SourceRegistry registry, SourceRouter router, BusinessDates dates, QueryProperties props) {
        this(registry, router, dates, props, null);
    }

    /** @param hub the live views' topics: with it, a live table shares each entity's one source subscription with the views that show it (null: the router is asked directly) */
    public ServerSourceGateway(SourceRegistry registry, SourceRouter router, BusinessDates dates, QueryProperties props, com.ash.drishti.engine.live.TopicHub hub) {
        this.registry = registry;
        this.router = router;
        this.dates = dates;
        this.props = props;
        this.hub = hub;
    }

    /** How long a query waits for a named connector that is being (re)started: its drain and its start. */
    static final java.time.Duration RESTART_WAIT = java.time.Duration.ofSeconds(30);

    @Override
    public Optional<SourcePlugin> plugin(String connector, String kind) {
        if (connector != null && !connector.isBlank()) {
            Optional<SourcePlugin> named = registry.connector(connector, RESTART_WAIT);       // a connector restarting is waited for, not reported as not running
            if (named.isEmpty()) {
                String why = registry.connectorStatuses().containsKey(connector) ? " (" + registry.connectorStatuses().get(connector).state().toLowerCase() + ")" : " (no such connector)";
                throw new DrishtiException(ErrorCode.QUERY_SOURCE, "connector '" + connector + "' is not running" + why + ": check Admin, Connectors");
            }
            return named;
        }
        return registry.plugins().stream().filter(p -> p.manifest().serves(kind)).filter(p -> !p.manifest().kinds().isEmpty())
                .max(Comparator.comparingInt((SourcePlugin p) -> p.columnar(kind).size())).or(() -> registry.plugins().stream().filter(p -> p.manifest().serves(kind)).findFirst());
    }

    @Override
    public List<SourcePlugin> plugins(String connector, String kind) {
        if (connector == null || connector.isBlank()) {
            List<SourcePlugin> union = router.unionOf(kind);
            if (!union.isEmpty()) {
                return union;
            }
        }
        return SourceGateway.super.plugins(connector, kind);
    }

    @Override
    public List<EntityRef> ids(String kind, LocalDate date, int limit) {
        List<EntityHit> hits = router.search(kind, "", limit, props.recordReadBudget(), asOf(date));
        return hits.stream().map(EntityHit::ref).toList();
    }

    @Override
    public Map<EntityRef, EntityDocument> read(Collection<EntityRef> refs, LocalDate date) {
        return router.fetchAll(refs, props.recordReadBudget(), asOf(date));
    }

    @Override
    public List<EntityRef> ids(List<SourcePlugin> plugins, String kind, LocalDate date, int limit) {
        if (plugins == null || plugins.isEmpty()) {
            return ids(kind, date, limit);
        }
        AsOf at = asOf(date);
        java.util.LinkedHashSet<EntityRef> out = new java.util.LinkedHashSet<>();
        for (SourcePlugin p : plugins) {
            int left = limit - out.size();
            if (left <= 0) {
                break;
            }
            List<EntityHit> hits = p.manifest().capabilities().dated() ? p.search(kind, "", left, at) : p.search(kind, "", left);
            hits.forEach(h -> out.add(h.ref()));
        }
        return List.copyOf(out);
    }

    @Override
    public Map<EntityRef, EntityDocument> read(List<SourcePlugin> plugins, Collection<EntityRef> refs, LocalDate date) {
        if (plugins == null || plugins.isEmpty()) {
            return read(refs, date);
        }
        AsOf at = asOf(date);
        Map<EntityRef, EntityDocument> out = new java.util.LinkedHashMap<>();
        for (EntityRef r : refs) {
            for (SourcePlugin p : plugins) {
                Optional<EntityDocument> d;
                try {
                    d = p.manifest().capabilities().dated() ? p.fetch(r, at) : p.fetch(r);
                } catch (Exception e) {                // this connector does not hold it (or failed): as the router, the row is left out
                    d = Optional.empty();
                }
                if (d.isPresent()) {
                    out.put(r, d.get());
                    break;
                }
            }
        }
        return out;
    }

    @Override
    public com.ash.drishti.api.Subscription subscribe(String connector, EntityRef ref, java.util.function.Consumer<EntityDocument> listener) {
        if (connector != null && !connector.isBlank()) {
            Optional<SourcePlugin> named = registry.connector(connector, RESTART_WAIT);
            if (named.isPresent()) {
                try {
                    return named.get().subscribe(ref, listener);
                } catch (UnsupportedOperationException e) {
                    return com.ash.drishti.api.Subscription.NONE;                      // the connector cannot push: the table is read again on a timer
                }
            }
        }
        return hub != null ? hub.subscribe(ref, listener) : router.subscribe(ref, listener);
    }

    private AsOf asOf(LocalDate date) {
        return dates.resolve(date == null ? AsOf.LATEST : AsOf.of(date));
    }

    @Override
    public LocalDate currentDate() {
        return dates.current();
    }

    @Override
    public com.ash.drishti.common.BusinessCalendar calendar() {
        return dates.calendar();
    }
}
