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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.server.security.Principal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Standing queries (docs/architecture/BI_MODEL_SPEC.md section 5.8): a model query asked once answers; subscribed, it is kept current. Wave W1 builds the
 * endpoints and answers by full recompute whenever a dataset the model reads changes generation (a live batch, a load, a refresh), at most once per throttle
 * (the latest state wins, nothing queues). Every evaluation runs as the subscription's owner, so the stream carries that reader's answer only. A subscription
 * whose evaluation overruns its throttle three times in a row moves to twice the throttle and says {@code throttled}. Caps: {@code drishti.bi.mq.max-subscriptions}
 * ({@code DRS-9181}) and {@code max-subscriptions-per-user} ({@code DRS-9182}); a past as-of never changes ({@code DRS-9183}). A subscription no stream reads
 * for {@code idle-close} is dropped. The incremental rules per measure are wave W3.
 */
public final class StandingQueries {

    /** What a delivery carries: the rows, the difference from the last delivery, or the one value (an alert). */
    public static final Set<String> DELIVERIES = Set.of("rows", "diff", "value");

    /** An event for a stream: {@code answer} or {@code status} with its body. */
    public record Event(String name, Map<String, Object> data) {}

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ModelCatalog catalog;
    private final MqService mq;
    private final MqProperties props;
    private final Clock clock;
    private final Map<String, Sub> subs = new ConcurrentHashMap<>();

    /** One subscription; its evaluation is serialised by its lock (a stream and a delete may meet). */
    static final class Sub {
        final String id;
        final Principal owner;
        final ModelQuery query;
        final String deliver;
        final ReentrantLock lock = new ReentrantLock();
        volatile Duration throttle;
        volatile long generation;
        volatile Instant lastSent = Instant.EPOCH;
        volatile Instant lastRead;
        volatile String state = "live";
        int overruns;                                                   // read and written under the lock only
        volatile List<List<Object>> rows = List.of();
        volatile int keyColumns;

        Sub(String id, Principal owner, ModelQuery query, String deliver, Duration throttle, Instant now) {
            this.id = id;
            this.owner = owner;
            this.query = query;
            this.deliver = deliver;
            this.throttle = throttle;
            this.lastRead = now;
        }
    }

    public StandingQueries(ModelCatalog catalog, MqService mq, MqProperties props, Clock clock) {
        this.catalog = catalog;
        this.mq = mq;
        this.props = props;
        this.clock = clock;
    }

    /** Subscribes the caller to a query: the first answer and the cursor it is at. */
    public Map<String, Object> subscribe(Principal p, ModelQuery q, Duration throttle, String deliver) {
        sweep();
        if (!DELIVERIES.contains(deliver)) {
            throw new MqException(MqException.Problem.of("DRS-9150", "deliver", "deliver is rows, diff or value"));
        }
        MqService.Bound b = catalog.bind(p, q.model());
        if (q.time().asOf() != null && b.context().today() != null && q.time().asOf().isBefore(b.context().today())) {
            throw new MqException(new MqException.Problem("DRS-9183", "time.as-of", "an answer as of " + q.time().asOf() + " never changes: ask it once instead of subscribing",
                    Map.of("remove", "time.as-of")));
        }
        if (subs.size() >= props.maxSubscriptions()) {
            throw new MqException(MqException.Problem.of("DRS-9181", "", "the server holds " + props.maxSubscriptions() + " standing queries already (drishti.bi.mq.max-subscriptions)"));
        }
        long mine = subs.values().stream().filter(s -> s.owner.user().equals(p.user())).count();
        if (mine >= props.maxSubscriptionsPerUser()) {
            throw new MqException(MqException.Problem.of("DRS-9182", "", "you hold " + mine + " standing queries already (drishti.bi.mq.max-subscriptions-per-user): close one"));
        }
        Duration t = throttle == null || throttle.compareTo(props.minThrottle()) < 0 ? props.minThrottle() : throttle;
        Sub s = new Sub("sq_" + HexFormat.of().formatHex(bytes()), p, q, deliver, t, clock.instant());
        MqService.Answer a = mq.ask(b, q, false);
        s.generation = catalog.generation(p, b.view());
        s.rows = MqService.rows(a.result());
        s.keyColumns = keyColumns(a);
        s.lastSent = clock.instant();
        subs.put(s.id, s);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("subscription", s.id);
        out.put("throttle", t.toMillis() + "ms");
        out.put("deliver", deliver);
        out.put("answer", a.body());
        out.put("cursor", cursor(s.generation));
        return out;
    }

    private static byte[] bytes() {
        byte[] b = new byte[8];
        RANDOM.nextBytes(b);
        return b;
    }

    private static String cursor(long g) {
        return "g-" + g;
    }

    private static int keyColumns(MqService.Answer a) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cols = (List<Map<String, Object>>) a.body().get("columns");
        return (int) cols.stream().filter(c -> "field".equals(c.get("kind"))).count();
    }

    /** Ends a subscription of the caller (DRS-9180 when they hold none of that id). */
    public void unsubscribe(Principal p, String id) {
        Sub s = subs.get(id);
        if (s == null || !s.owner.user().equals(p.user())) {
            throw new MqException(MqException.Problem.of("DRS-9180", "", "you hold no standing query '" + id + "'"));
        }
        subs.remove(id);
    }

    /** The caller's subscriptions among {@code ids} (all of theirs when empty); DRS-9180 for an id that is not theirs. */
    public List<String> mine(Principal p, List<String> ids) {
        if (ids.isEmpty()) {
            return subs.values().stream().filter(s -> s.owner.user().equals(p.user())).map(s -> s.id).toList();
        }
        for (String id : ids) {
            Sub s = subs.get(id);
            if (s == null || !s.owner.user().equals(p.user())) {
                throw new MqException(MqException.Problem.of("DRS-9180", "ids", "you hold no standing query '" + id + "'"));
            }
        }
        return List.copyOf(ids);
    }

    /**
     * One pass over the given subscriptions for a stream: each whose data moved on and whose throttle has passed is evaluated again (as its owner) and gives an
     * {@code answer}; one that failed gives a {@code status}. Ids no longer held are skipped.
     */
    public List<Event> poll(List<String> ids) {
        List<Event> out = new ArrayList<>();
        Instant now = clock.instant();
        for (String id : ids) {
            Sub s = subs.get(id);
            if (s == null) {
                continue;
            }
            s.lastRead = now;
            if (!s.lock.tryLock()) {
                continue;
            }
            try {
                Event e = evaluate(s, now);
                if (e != null) {
                    out.add(e);
                }
            } finally {
                s.lock.unlock();
            }
        }
        return out;
    }

    private Event evaluate(Sub s, Instant now) {
        if (Duration.between(s.lastSent, now).compareTo(s.throttle) < 0) {
            return null;
        }
        long started = System.nanoTime();
        try {
            MqService.Bound b = catalog.bind(s.owner, s.query.model());
            long g = catalog.generation(s.owner, b.view());
            if (g == s.generation && !"failed".equals(s.state)) {
                return null;
            }
            MqService.Answer a = mq.ask(b, s.query, false);
            List<List<Object>> rows = MqService.rows(a.result());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("subscription", s.id);
            data.put("cursor", cursor(g));
            switch (s.deliver) {
                case "diff" -> data.put("diff", diff(s.rows, rows, s.keyColumns));
                case "value" -> data.put("value", rows.isEmpty() || rows.get(0).isEmpty() ? null : rows.get(0).get(rows.get(0).size() - 1));
                default -> data.put("rows", rows);
            }
            if (a.body().get("totals") != null) {
                data.put("totals", a.body().get("totals"));
            }
            s.rows = rows;
            s.generation = g;
            s.lastSent = clock.instant();
            Duration took = Duration.ofNanos(System.nanoTime() - started);
            if (took.compareTo(s.throttle) > 0 && ++s.overruns >= 3) {
                s.throttle = s.throttle.multipliedBy(2);
                s.overruns = 0;
                s.state = "throttled";
            } else if (took.compareTo(s.throttle) <= 0) {
                s.overruns = 0;
                s.state = "live";
            }
            return new Event("answer", data);
        } catch (DrishtiException e) {
            s.state = "failed";
            s.lastSent = clock.instant();
            return new Event("status", Map.of("subscription", s.id, "state", "failed", "code", e.errorCode().code(), "detail", e.getMessage()));
        }
    }

    /** The status of each subscription (the stream's heartbeat). */
    public List<Event> status(List<String> ids) {
        List<Event> out = new ArrayList<>();
        for (String id : ids) {
            Sub s = subs.get(id);
            if (s != null) {
                out.add(new Event("status", Map.of("subscription", s.id, "state", s.state, "throttle", s.throttle.toMillis() + "ms", "cursor", cursor(s.generation))));
            }
        }
        return out;
    }

    /** The rows that are new or changed (by the values of the field columns) and the keys of the rows that went away. */
    static Map<String, Object> diff(List<List<Object>> before, List<List<Object>> after, int keys) {
        Map<List<Object>, List<Object>> was = new LinkedHashMap<>();
        before.forEach(r -> was.put(r.subList(0, Math.min(keys, r.size())), r));
        Map<List<Object>, List<Object>> now = new LinkedHashMap<>();
        after.forEach(r -> now.put(r.subList(0, Math.min(keys, r.size())), r));
        List<List<Object>> upsert = new ArrayList<>();
        now.forEach((k, r) -> {
            if (!Objects.equals(was.get(k), r)) {
                upsert.add(r);
            }
        });
        List<List<Object>> delete = new ArrayList<>();
        was.keySet().forEach(k -> {
            if (!now.containsKey(k)) {
                delete.add(k);
            }
        });
        return Map.of("upsert", upsert, "delete", delete);
    }

    /** Drops subscriptions no stream has read for {@code drishti.bi.mq.idle-close}. */
    public void sweep() {
        Instant cut = clock.instant().minus(props.idleClose());
        subs.values().removeIf(s -> s.lastRead.isBefore(cut));
    }

    public Map<String, Object> stats() {
        return Map.of("subscriptions", subs.size(), "max", props.maxSubscriptions(), "maxPerUser", props.maxSubscriptionsPerUser());
    }
}
