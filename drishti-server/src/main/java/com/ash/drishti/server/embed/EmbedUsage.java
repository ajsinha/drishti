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
package com.ash.drishti.server.embed;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * What each host application did since this server started (Admin → Embedding): tokens issued, refusals by DRS code, embed calls
 * made, live streams open now, and when it was last used. Held in memory, lock-free on the hot path, and also exported as
 * Micrometer meters ({@code drishti.embed.tokens}, {@code .calls}, {@code .refusals} by {@code code}, {@code .streams}), each
 * tagged {@code app}. Only applications the registry knows are counted, so a stranger cannot grow it; {@link #UNKNOWN} gathers the
 * refusals of the others.
 */
public final class EmbedUsage {

    /** The bucket for refusals that name no registered application. */
    public static final String UNKNOWN = "(unknown)";

    /** A copy of one application's counters. */
    public record Snapshot(long tokensIssued, long calls, long refusals, Map<String, Long> refusalsByCode, int streamsOpen, Instant lastUsedAt) {}

    private static final class Counters {
        final LongAdder tokens = new LongAdder();
        final LongAdder calls = new LongAdder();
        final Map<String, LongAdder> refusals = new ConcurrentHashMap<>();
        final AtomicInteger streams = new AtomicInteger();
        final AtomicLong lastUsedMillis = new AtomicLong();
    }

    private final Map<String, Counters> byApp = new ConcurrentHashMap<>();
    private final MeterRegistry meters;
    private final Clock clock;

    public EmbedUsage(MeterRegistry meters, Clock clock) {
        this.meters = meters;
        this.clock = clock;
    }

    private Counters of(String app) {
        return byApp.computeIfAbsent(app, a -> {
            Counters c = new Counters();
            if (meters != null) {
                Gauge.builder("drishti.embed.streams", c.streams, AtomicInteger::get).tag("app", a).register(meters);
            }
            return c;
        });
    }

    public void tokenIssued(String app) {
        Counters c = of(app);
        c.tokens.increment();
        c.lastUsedMillis.set(clock.millis());
        count("drishti.embed.tokens", app, null);
    }

    public void call(String app) {
        Counters c = of(app);
        c.calls.increment();
        c.lastUsedMillis.set(clock.millis());
        count("drishti.embed.calls", app, null);
    }

    /** A refusal of an exchange or of a call; {@code app} is {@link #UNKNOWN} when no registered application is named. */
    public void refused(String app, String code) {
        of(app).refusals.computeIfAbsent(code, k -> new LongAdder()).increment();
        count("drishti.embed.refusals", app, code);
    }

    public void streamOpened(String app) {
        of(app).streams.incrementAndGet();
    }

    public void streamClosed(String app) {
        of(app).streams.updateAndGet(n -> Math.max(0, n - 1));
    }

    private void count(String name, String app, String code) {
        if (meters != null) {
            if (code == null) {
                meters.counter(name, "app", app).increment();
            } else {
                meters.counter(name, "app", app, "code", code).increment();
            }
        }
    }

    public Snapshot snapshot(String app) {
        Counters c = byApp.get(app);
        if (c == null) {
            return new Snapshot(0, 0, 0, Map.of(), 0, null);
        }
        Map<String, Long> codes = new TreeMap<>();
        long total = 0;
        for (Map.Entry<String, LongAdder> e : c.refusals.entrySet()) {
            codes.put(e.getKey(), e.getValue().sum());
            total += e.getValue().sum();
        }
        long at = c.lastUsedMillis.get();
        return new Snapshot(c.tokens.sum(), c.calls.sum(), total, Map.copyOf(codes), c.streams.get(), at == 0 ? null : Instant.ofEpochMilli(at));
    }

    /** The given registered ids (zeros when unused), keyed by id, plus the bucket of unknown applications when it has anything. */
    public Map<String, Snapshot> snapshots(List<String> registered) {
        Map<String, Snapshot> out = new LinkedHashMap<>();
        for (String id : registered) {
            out.put(id, snapshot(id));
        }
        if (byApp.containsKey(UNKNOWN)) {
            out.put(UNKNOWN, snapshot(UNKNOWN));
        }
        return out;
    }
}
