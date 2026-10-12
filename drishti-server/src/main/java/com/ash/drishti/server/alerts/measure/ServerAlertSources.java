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
package com.ash.drishti.server.alerts.measure;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.Subscription;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.live.TopicHub;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.server.bi.BiService;
import com.ash.drishti.server.bi.DatasetService;
import com.ash.drishti.server.bi.board.BiBoardService;
import com.ash.drishti.server.bi.board.Board;
import com.ash.drishti.server.bi.report.BiReportService;
import com.ash.drishti.server.bi.report.Report;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * What the alert service needs to know about its sources: whether a person can use one ({@link AlertEnvironment.Checker}) and whether it changes by itself
 * ({@link AlertEnvironment.Live}). A BI source is live when the dataset behind it has a live table (the report service and the dataset engine answer); a
 * Sutra entity is live when its source pushes changes (a hub subscription counts them).
 */
public final class ServerAlertSources implements AlertEnvironment.Checker, AlertEnvironment.Live, AutoCloseable {

    private static final Duration WATCH_TTL = Duration.ofMinutes(5);

    private record Cached(Supplier<Map<String, Object>> watch, Instant at) {}

    private record FieldWatch(AtomicLong generation, Subscription subscription, boolean pushes) {}

    private final BiReportService reports;
    private final DatasetService datasets;
    private final BiBoardService boards;
    private final BiService bi;
    private final Entitlements entitlements;
    private final FieldValueReader fields;
    private final SourceRouter router;
    private final TopicHub hub;
    private final Clock clock;
    private final Map<String, Cached> watches = new ConcurrentHashMap<>();
    private final Map<EntityRef, FieldWatch> fieldWatches = new ConcurrentHashMap<>();
    private volatile com.ash.drishti.server.bi.mq.ModelCatalog models;
    private volatile com.ash.drishti.server.bi.mq.MqService mq;

    @SuppressWarnings("java:S107")
    public ServerAlertSources(BiReportService reports, DatasetService datasets, BiBoardService boards, BiService bi, Entitlements entitlements, FieldValueReader fields,
            SourceRouter router, TopicHub hub, Clock clock) {
        this.reports = reports;
        this.datasets = datasets;
        this.boards = boards;
        this.bi = bi;
        this.entitlements = entitlements;
        this.fields = fields;
        this.router = router;
        this.hub = hub;
        this.clock = clock;
    }

    /** The model layer, for {@code type: model} sources (set once at start-up). */
    public ServerAlertSources withModels(com.ash.drishti.server.bi.mq.ModelCatalog models, com.ash.drishti.server.bi.mq.MqService mq) {
        this.models = models;
        this.mq = mq;
        return this;
    }

    // ---- may this person watch it? -------------------------------------------------------------------------------------------

    @Override
    public void check(AlertSpec spec, Principal who) {
        AlertSpec.Source s = spec.source();
        switch (s.type()) {
            case "visual" -> {
                Report r = reports.open(who, s.report()).report();
                boolean found = false;
                for (Report.Page p : r.pages()) {
                    found |= (s.page().isBlank() || p.id().equals(s.page())) && p.visual(s.visual()) != null;
                }
                if (!found) {
                    throw new DrishtiException(ErrorCode.REPORT_STATE, "the report has no visual '" + s.visual() + "'" + (s.page().isBlank() ? "" : " on page '" + s.page() + "'"));
                }
            }
            case "dataset" -> {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> measures = (List<Map<String, Object>>) datasets.model(who, s.dataset()).get("measures");
                if (measures == null || measures.stream().noneMatch(m -> s.measure().equals(m.get("name")))) {
                    throw new DrishtiException(ErrorCode.QUERY_INVALID, "dataset '" + s.dataset() + "' has no measure '" + s.measure() + "'");
                }
            }
            case "model" -> {
                if (models == null) {
                    throw new DrishtiException(ErrorCode.ALERT_INVALID, "the model layer is not running on this server");
                }
                mq.validate(models.bind(who, s.model()), BiValueReader.query(s, null));       // the query's refusal, with its code, path and fix
            }
            case "field" -> {
                entitlements.requireOpen(who, s.kind());
                try {
                    fields.check(s.field());
                } catch (com.ash.drishti.rachana.el.ElException e) {
                    throw new DrishtiException(ErrorCode.EL_SYNTAX, "field: " + e.getMessage());
                }
            }
            default -> throw new DrishtiException(ErrorCode.ALERT_INVALID, "source.type is visual, dataset, model or field");
        }
    }

    // ---- does it change by itself? -------------------------------------------------------------------------------------------

    @Override
    public long generation(AlertSpec spec, Principal who) {
        try {
            AlertSpec.Source s = spec.source();
            return switch (s.type()) {
                case "field" -> fieldGeneration(s, who);
                case "dataset" -> generationOf(spec.key() + "|" + who.user(), () -> datasets.liveWatch(who, bi.readableItem(who, s.dataset())));
                case "model" -> models == null ? -1 : models.generation(who, models.bind(who, s.model()).view());
                case "tile" -> {
                    Optional<BiBoardService.TileTarget> t = boards.target(who, new Board.Tile("t", s.report(), s.page(), s.visual(), "", new Report.Grid(0, 0, 1, 1), s.tile()));
                    yield t.isEmpty() ? -1 : generationOf(spec.key() + "|" + who.user(), () -> reports.liveWatch(who, t.get().report()));
                }
                default -> generationOf(spec.key() + "|" + who.user(), () -> reports.liveWatch(who, s.report()));
            };
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private long generationOf(String key, Supplier<Supplier<Map<String, Object>>> open) {
        Cached c = watches.get(key);
        if (c == null || Duration.between(c.at(), clock.instant()).compareTo(WATCH_TTL) > 0) {
            Supplier<Map<String, Object>> w = open.get();
            if (w == null) {
                watches.remove(key);
                return -1;
            }
            c = new Cached(w, clock.instant());
            watches.put(key, c);
        }
        try {
            Map<String, Object> tick = c.watch().get();
            if ("none".equals(tick.get("state"))) {
                return -1;
            }
            return tick.get("generation") instanceof Number n ? n.longValue() : -1;
        } catch (RuntimeException e) {
            watches.remove(key);
            return -1;
        }
    }

    private long fieldGeneration(AlertSpec.Source s, Principal who) {
        EntityRef ref = EntityRef.of(s.kind(), s.id());
        FieldWatch w = fieldWatches.computeIfAbsent(ref, r -> {
            AtomicLong g = new AtomicLong();
            boolean pushes;
            try {
                pushes = router.fetch(r, AsOf.LATEST).get(30, TimeUnit.SECONDS).provenance().live();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pushes = false;
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
                pushes = false;
            }
            return new FieldWatch(g, pushes ? hub.subscribe(r, d -> g.incrementAndGet()) : () -> { }, pushes);
        });
        return w.pushes() && entitlements.mayOpen(who, s.kind()) ? w.generation().get() : -1;
    }

    @Override
    public void close() {
        fieldWatches.values().forEach(w -> w.subscription().close());
        fieldWatches.clear();
    }
}
