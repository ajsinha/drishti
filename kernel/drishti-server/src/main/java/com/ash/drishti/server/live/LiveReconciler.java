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

import com.ash.drishti.api.EntityRef;
import com.ash.drishti.live.LiveDates;
import com.ash.drishti.live.LiveRules;
import com.ash.drishti.live.LiveSource;
import com.ash.drishti.live.LiveState;
import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.engine.time.BusinessDates;
import com.ash.drishti.server.loads.LoadRecord;
import com.ash.drishti.server.loads.LoadService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The next lake load absorbs the live state (docs/architecture/LIVE_CONNECTORS_DESIGN.md section 6). When a load of a kind finishes ready,
 * each live connector of the kind reconciles its rows of that business date (and before) against the load, by the kind's {@code absorb:}
 * rule, off the request threads: the lake's row of every key the live state holds is read once (the load's date, live connectors left
 * out), then the state swaps row by row, so reads are never blocked. Every reconcile leaves a record (the feed's reconcile file, an audit
 * entry, the counts in Health). A store that fails while it is read leaves the live state as it was: no row is absorbed on a guess.
 *
 * <p>Also tells the live connectors the server's business dates: the live date is the current business date, the next one its calendar's.
 */
@Component
public final class LiveReconciler implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(LiveReconciler.class);

    private final SourceRouter router;
    private final SourceRegistry registry;
    private final ObjectProvider<com.ash.drishti.identity.AuditLog> audit;
    private final Duration settle;
    private final ExecutorService runs = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("drishti-live-reconcile").factory());

    public LiveReconciler(LoadService loads, SourceRouter router, SourceRegistry registry, BusinessDates dates,
            ObjectProvider<com.ash.drishti.identity.AuditLog> audit, Environment env) {
        this.router = router;
        this.registry = registry;
        this.audit = audit;
        this.settle = env.getProperty("drishti.live.reconcile.settle", Duration.class, Duration.ofMinutes(5));
        registry.liveDates(LiveDates.of(dates::current, d -> dates.calendar().next(d)));
        loads.onReady(this::loaded);
    }

    private void loaded(LoadRecord load) {
        if (load.kind() == null || load.businessDate() == null || router.liveSources(load.kind()).isEmpty()) {
            return;
        }
        if (load.source() != null && load.source().startsWith(Landings.SOURCE)) {
            return;                 // a window the live lander wrote (apply land or both): the live rows it came from are not absorbed by it
        }
        // the load's cut-off: when the load was received, less the settle time (the extract stated by the load comes with its manifest)
        Instant cutoff = (load.receivedAt() == null ? Instant.now() : load.receivedAt()).minus(settle);
        runs.submit(() -> reconcile(load.kind(), load.businessDate(), cutoff, load.id()));
    }

    /** Reconciles every live connector of {@code kind} against the load of {@code date}; returns the records, by connector. */
    public Map<String, LiveState.Reconcile> reconcile(String kind, LocalDate date, Instant cutoff, String loadId) {
        Map<String, LiveState.Reconcile> out = new HashMap<>();
        LiveRules rules = registry.liveRules(kind);
        router.liveSources(kind).forEach((name, live) -> {
            if (live.feed() == null) {
                return;
            }
            Map<String, Optional<List<Object>>> lake = new HashMap<>();
            try {
                live.feed().state().entries(kind).forEach(e -> lake.put(e.key(),
                        router.storeOrder(EntityRef.of(kind, e.key()), date, rules.order())));
            } catch (IllegalStateException e) {
                LOG.warn("live reconcile of {} for {} by {} not run: {} (the live rows stay until the next load)", kind, date, name, e.getMessage());
                return;
            }
            LiveState.Reconcile r = live.feed().reconcile(kind, date, cutoff, key -> lake.getOrDefault(key, Optional.empty()));
            out.put(name, r);
            String words = "load " + loadId + " of " + date + " (" + r.rule().name().toLowerCase(java.util.Locale.ROOT).replace('_', '-') + "): "
                    + r.absorbed() + " absorbed, " + r.carried() + " carried, " + r.orphans() + " orphans, " + r.deletesCarried() + " deletes carried";
            LOG.info("live {} of {}: {}", name, kind, words);
            com.ash.drishti.identity.AuditLog a = audit.getIfAvailable();
            if (a != null) {
                a.record("system", "live-reconcile", name + "/" + kind, words);
            }
        });
        return out;
    }

    /** The live sources of a kind, for Admin. */
    public Map<String, LiveSource> sources(String kind) {
        return router.liveSources(kind);
    }

    @Override
    public void close() {
        runs.shutdownNow();
    }
}
