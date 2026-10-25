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
package com.ash.drishti.compute.reactive;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.compute.ComputeEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A reactive connector's triggers (REACTIVE_CONNECTORS.md section 7): from an event to the base keys it affects ({@code affects: all},
 * {@code subscribed}, id templates over the event, or an expression the engine evaluates), the schedules ({@code cron} or {@code every}),
 * the data versions of the kinds a {@code data-version} trigger watches, and the live entities a {@code live} trigger follows (the
 * entities of its kinds the watched computations read).
 */
final class Triggers implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Triggers.class);
    private static final int MAX_LIVE = 2_000;
    private static final Duration VERSION_CHECK = Duration.ofSeconds(30);

    private final ReactiveConnector connector;
    private final ReactiveSpec spec;
    private final EngineRunner runner;
    private final ScheduledExecutorService scheduler;
    private final Clock clock;
    private final List<ScheduledFuture<?>> timers = new ArrayList<>();
    private final Map<String, String> versions = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> liveBases = new ConcurrentHashMap<>();
    private final Map<String, AutoCloseable> liveSubs = new ConcurrentHashMap<>();
    private volatile boolean closed;

    Triggers(ReactiveConnector connector, ReactiveSpec spec, EngineRunner runner, ScheduledExecutorService scheduler, Clock clock) {
        this.connector = connector;
        this.spec = spec;
        this.runner = runner;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    void start() {
        for (ReactiveSpec.TriggerSpec t : spec.triggers()) {
            if ("schedule".equals(t.on())) {
                next(t, clock.instant());
            }
        }
        if (spec.triggers().stream().anyMatch(t -> "data-version".equals(t.on())) && scheduler != null) {
            checkVersions();                                               // the versions now are the baseline
            timers.add(scheduler.scheduleWithFixedDelay(this::checkVersions, VERSION_CHECK.toMillis(), VERSION_CHECK.toMillis(), TimeUnit.MILLISECONDS));
        }
    }

    private void next(ReactiveSpec.TriggerSpec t, Instant after) {
        if (closed || scheduler == null) {
            return;
        }
        Instant at;
        if (t.every() != null) {
            at = after.plus(t.every());
        } else {
            Optional<Instant> n = connector.host().nextCron(t.cron(), t.zone(), after);
            if (n.isEmpty()) {
                LOG.warn("reactive: {}: the schedule '{}' ({}) never fires on this server", connector.name(), t.cron(), t.zone());
                return;
            }
            at = n.get();
        }
        long delay = Math.max(0, Duration.between(clock.instant(), at).toMillis());
        synchronized (timers) {
            timers.add(scheduler.schedule(() -> {
                connector.tick(new ComputeEvent("schedule/" + connector.name() + "/" + at, ComputeEvent.SCHEDULE, "", "", null, Map.of(), at, "",
                        List.of(), Map.of("tick", at.toString())));
                next(t, at);
            }, delay, TimeUnit.MILLISECONDS));
        }
    }

    /** Looks at the data versions of the kinds {@code data-version} triggers name; an event for each that moved. */
    void checkVersions() {
        Set<String> kinds = new LinkedHashSet<>();
        spec.triggers().stream().filter(t -> "data-version".equals(t.on())).forEach(t -> kinds.addAll(t.kinds().isEmpty() ? spec.readKinds() : t.kinds()));
        LocalDate today = connector.host().today();
        for (String k : kinds) {
            String v;
            try {
                v = connector.host().dataVersion(k, AsOf.of(today)).orElse(null);
            } catch (RuntimeException e) {
                continue;
            }
            if (v == null) {
                continue;
            }
            String before = versions.put(k, v);
            if (before != null && !before.equals(v)) {
                connector.tick(new ComputeEvent("data-version/" + k + "/" + today + "/" + v, ComputeEvent.DATA_VERSION, k, "", today, Map.of(),
                        clock.instant(), "", List.of(), Map.of("old", before, "new", v)));
            }
        }
    }

    /** The base keys an event affects, by the triggers that fire on it. */
    List<String> affected(ComputeEvent e) {
        Set<String> out = new LinkedHashSet<>();
        String trackedKey = e.kind() + "|" + e.entity();
        for (ReactiveSpec.TriggerSpec t : spec.triggers()) {
            if (!t.firesOn(e.type(), e.kind()) || e.type().equals(ComputeEvent.WRITE_BACK) && !t.when().equals(String.valueOf(e.payload().getOrDefault("when", "applied")))) {
                continue;
            }
            List<String> bases = connector.bases().stream().filter(b -> recomputes(t, b) && onDate(b, e.businessDate())).toList();
            if (t.affectsAll()) {
                out.addAll(bases);
            } else if (t.affectsSubscribed()) {
                if (ComputeEvent.LIVE.equals(e.type()) && liveBases.containsKey(trackedKey)) {
                    bases.stream().filter(liveBases.get(trackedKey)::contains).forEach(out::add);
                } else {
                    bases.stream().filter(b -> !connector.watching(b).isEmpty()).forEach(out::add);
                }
            } else {
                Set<String> ids = new LinkedHashSet<>();
                boolean expression = false;
                for (String a : t.affects()) {
                    if (Templates.isTemplate(a)) {
                        ids.addAll(Templates.many(a, Templates.of(e)));
                    } else {
                        expression = true;
                        Optional<Set<String>> got = runner.affected(e, a);
                        if (got.isEmpty()) {
                            bases.stream().filter(b -> !connector.watching(b).isEmpty()).forEach(out::add);   // the engine cannot say: the watched ones
                        } else {
                            ids.addAll(got.get());
                        }
                    }
                }
                if (!ids.isEmpty() || !expression) {
                    bases.stream().filter(b -> ids.contains(idOf(b))).forEach(out::add);
                }
            }
        }
        return new ArrayList<>(out);
    }

    private static boolean recomputes(ReactiveSpec.TriggerSpec t, String base) {
        return t.recompute().isEmpty() || t.recompute().contains(base.split("\\|", -1)[1]);
    }

    private static boolean onDate(String base, LocalDate date) {
        return date == null || base.split("\\|", -1)[3].equals(date.toString());
    }

    /** The question's id of a base key ({@code <connector>|<kind>|<id~params>|...}), without its parameters. */
    static String idOf(String base) {
        return ComputeRef.parse(base.split("\\|", -1)[2]).id();
    }

    /** Follows the live entities a watched computation read, for the {@code live} triggers that name their kind. */
    void readLive(String base, String kind, List<String> ids) {
        boolean live = spec.triggers().stream().anyMatch(t -> "live".equals(t.on()) && (t.kinds().isEmpty() || t.kinds().contains(kind)));
        if (!live || ids == null) {
            return;
        }
        for (String id : ids) {
            String k = kind + "|" + id;
            liveBases.computeIfAbsent(k, x -> ConcurrentHashMap.newKeySet()).add(base);
            if (!liveSubs.containsKey(k) && liveSubs.size() < MAX_LIVE) {
                liveSubs.computeIfAbsent(k, x -> connector.host().subscribe(kind, id, gen -> connector.tick(new ComputeEvent("live/" + kind + "/" + id + "/" + gen,
                        ComputeEvent.LIVE, kind, id, null, Map.of("live:" + kind + "/" + id, gen), clock.instant(), "", List.of(), Map.of("generation", gen)))));
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        synchronized (timers) {
            timers.forEach(t -> t.cancel(false));
            timers.clear();
        }
        liveSubs.values().forEach(s -> {
            try {
                s.close();
            } catch (Exception e) {
                LOG.debug("closing a live subscription: {}", e.toString());
            }
        });
        liveSubs.clear();
    }
}
