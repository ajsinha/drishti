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
package com.ash.drishti.engine.source;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityHit;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.common.JsonCodec;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Tries a connector's settings without touching the running server: a fresh instance of the plugin is started on the
 * given settings, asked what it holds for each of the connector's kinds (business dates and how many entities each),
 * and closed. Nothing it reads is kept. A plugin that cannot count (no {@code inventory}) is asked to search and the
 * count is "at least" what one search returns.
 */
public final class ConnectionProbe {

    /** One business date of a kind and how many entities the source holds for it. */
    public record DateRows(LocalDate date, long rows) {}

    /** What a source holds for one kind; {@code exact} is false when the count is a search's size (a lower bound). */
    public record KindRows(String kind, List<DateRows> dates, boolean exact, String note) {}

    /** The outcome: {@code ok} when the plugin started and answered; {@code error} says why not. */
    public record Result(boolean ok, String health, long ms, String error, List<KindRows> kinds) {}

    private static final int SEARCH_CAP = 10_000;

    private final List<SourcePlugin> discovered;
    private final JsonCodec codec;

    public ConnectionProbe(List<SourcePlugin> discovered, JsonCodec codec) {
        this.discovered = List.copyOf(discovered);
        this.codec = codec;
    }

    /** The plugin names this server can probe. */
    public Set<String> plugins() {
        return discovered.stream().map(p -> p.manifest().name()).collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
    }

    public Result probe(String plugin, List<String> kinds, Map<String, String> settings, int maxDates, long timeoutMs) {
        long t0 = System.nanoTime();
        SourcePlugin proto = discovered.stream().filter(p -> p.manifest().name().equals(plugin)).findFirst().orElse(null);
        if (proto == null) {
            return new Result(false, "DOWN", 0, "no plugin named '" + plugin + "' (installed: " + String.join(", ", plugins()) + ")", List.of());
        }
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("drishti-probe-", 0).factory());
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Result> f = exec.submit(() -> run(proto, kinds, settings, maxDates, scheduler, t0));
            try {
                return f.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                f.cancel(true);
                return new Result(false, "DOWN", ms(t0), "no answer within " + timeoutMs / 1000 + " seconds", List.of());
            } catch (ExecutionException e) {
                Throwable c = e.getCause() == null ? e : e.getCause();
                return new Result(false, "DOWN", ms(t0), c instanceof com.ash.drishti.api.PluginNotConfigured ? "not configured: " + c.getMessage()
                        : String.valueOf(c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage()), List.of());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Result(false, "DOWN", ms(t0), "interrupted", List.of());
            }
        } finally {
            scheduler.shutdownNow();
        }
    }

    private Result run(SourcePlugin proto, List<String> kinds, Map<String, String> settings, int maxDates, ScheduledExecutorService scheduler, long t0)
            throws Exception {
        SourcePlugin fresh = proto.getClass().getDeclaredConstructor().newInstance();
        SourcePlugin instance = new ConnectorInstance("probe", new java.util.HashSet<>(kinds), fresh);
        Map<String, String> own = new LinkedHashMap<>(settings);
        own.putIfAbsent("source-name", "probe");
        own.putIfAbsent("refresh-seconds", "3600");
        own.putIfAbsent("warm-dates", "0");
        try {
            instance.start(new EngineSourceContext(own, codec, scheduler, null));
            List<KindRows> rows = new ArrayList<>();
            for (String kind : kinds) {
                rows.add(rowsOf(instance, kind, maxDates));
            }
            String health = String.valueOf(instance.health());
            boolean up = "UP".equalsIgnoreCase(health) || health.startsWith("UP");
            return new Result(up, health, ms(t0), up ? null : health, rows);
        } finally {
            try {
                instance.close();
            } catch (Exception ignored) {
                // a probe that cannot close has nothing more to say
            }
        }
    }

    private static KindRows rowsOf(SourcePlugin p, String kind, int maxDates) {
        try {
            Map<LocalDate, Long> inv = p.inventory(kind, maxDates);
            if (!inv.isEmpty()) {
                return new KindRows(kind, inv.entrySet().stream().map(e -> new DateRows(LocalDate.MIN.equals(e.getKey()) ? null : e.getKey(), e.getValue())).toList(), true, "");
            }
            List<EntityHit> hits = p.search(kind, "", SEARCH_CAP, AsOf.LATEST);
            if (!hits.isEmpty()) {
                return new KindRows(kind, List.of(new DateRows(null, hits.size())), hits.size() < SEARCH_CAP,
                        hits.size() < SEARCH_CAP ? "latest data only; this connector does not list its dates" : "at least " + SEARCH_CAP + "; this connector does not list its dates");
            }
            return new KindRows(kind, List.of(), true, "nothing found for this kind");
        } catch (RuntimeException e) {
            return new KindRows(kind, List.of(), true, "cannot read: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }
}
