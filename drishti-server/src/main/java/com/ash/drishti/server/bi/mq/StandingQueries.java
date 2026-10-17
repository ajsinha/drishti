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
import com.ash.drishti.server.bi.engine.GenerationBus;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Standing queries (docs/architecture/BI_DESIGN.md section 4.6, BI_MODEL_SPEC.md section 5.8): a model query asked once answers; subscribed, it is kept
 * current. Wave B: <b>pushed</b>, not polled: the {@link GenerationBus} tells which model's data moved (a live batch with its rows, a load, a refresh, a
 * history sample) and only the subscriptions on that model are evaluated. Subscriptions are grouped by <b>(plan hash, security signature)</b>
 * ({@link StandingQueryRegistry}): one evaluation per group however many subscribers share it (500 viewers of one report with 5 signatures are 5
 * evaluations per visual), each delivering to its own throttled, latest-wins mailbox. A group is kept current by its {@link Maintainer} (by difference, the
 * touched groups asked again, the whole question, or on the tick) within a per-model <b>maintenance budget</b>: past it the model's groups degrade to tick
 * mode and say {@code tick}. An evaluation that overruns its throttle three times doubles it ({@code throttled}). Caps: {@code DRS-9181}, {@code DRS-9182}; a
 * past as-of never changes ({@code DRS-9183}). A security change of the owner (roles, {@code user.*}) changes the signature: the group is re-planned, never
 * patched. Thread-safe.
 */
public final class StandingQueries implements AutoCloseable {

    /** What a delivery carries: the rows, the difference from the last delivery, or the one value (an alert). */
    public static final Set<String> DELIVERIES = Set.of("rows", "diff", "value");

    /** An event for a stream: {@code answer} or {@code status} with its body. */
    public record Event(String name, Map<String, Object> data) {}

    /** What the standing queries need of the models: a reader's binding, the data's generation, a live table's generation (the catalogue in the server). */
    public interface Models {
        MqService.Bound bind(Principal p, String model);

        long generation(Principal p, ModelView v);

        long liveGeneration(String key, String table);

        /** The catalogue as the standing queries' models. */
        static Models of(ModelCatalog c) {
            return new Models() {
                @Override
                public MqService.Bound bind(Principal p, String model) {
                    return c.bind(p, model);
                }

                @Override
                public long generation(Principal p, ModelView v) {
                    return c.generation(p, v);
                }

                @Override
                public long liveGeneration(String key, String table) {
                    return c.liveGeneration(key, table);
                }
            };
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Models catalog;
    private final MqService mq;
    private final MqProperties props;
    private final Clock clock;
    private final Map<String, Sub> subs = new ConcurrentHashMap<>();
    private final StandingQueryRegistry registry;
    private final ReentrantLock signal = new ReentrantLock();
    private final Condition moved = signal.newCondition();
    private volatile AutoCloseable listening;

    /** One subscription: its owner and query, how it is delivered, and what it delivered last. */
    static final class Sub {
        final String id;
        final Principal owner;
        final ModelQuery query;
        final String deliver;
        final ReentrantLock lock = new ReentrantLock();
        volatile Duration throttle;
        volatile Instant lastSent = Instant.EPOCH;
        volatile Instant lastRead;
        volatile StandingQueryRegistry.Group group;
        volatile List<List<Object>> delivered = List.of();
        volatile long seen;
        volatile long sentGeneration;
        volatile String failure;

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
        this(Models.of(catalog), mq, props, clock);
    }

    public StandingQueries(Models catalog, MqService mq, MqProperties props, Clock clock) {
        this.catalog = catalog;
        this.mq = mq;
        this.props = props;
        this.clock = clock;
        this.registry = new StandingQueryRegistry(catalog, mq, props, clock, this::wake);
    }

    /** Listens to the generation bus: every notice for a model with subscriptions schedules their groups (push). */
    public void attach(GenerationBus bus) {
        AutoCloseable old = listening;
        listening = bus.listen(registry::onNotice);
        if (old != null) {
            try {
                old.close();
            } catch (Exception ignored) {
                // a listener handle's close cannot fail
            }
        }
    }

    StandingQueryRegistry registry() {
        return registry;
    }

    /** The live subscriptions on a model (one entry each, for the model's Used by). */
    List<Map<String, Object>> on(java.util.function.Predicate<String> names) {
        return subs.values().stream().filter(s -> names.test(s.query.model()))
                .map(s -> Map.<String, Object>of("kind", "subscription", "id", s.id, "title", "live query (" + s.deliver + ")", "status", state(s), "owner", s.owner.user())).toList();
    }

    /** Subscribes the caller to a query: the first answer and the cursor it is at (the group's, when another reader of the same signature holds it already). */
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
        StandingQueryRegistry.Group g = registry.join(p, b, q, s.id, t);
        s.group = g;
        StandingQueryRegistry.Snapshot snap = g.snapshot();
        s.delivered = snap.rows();
        s.seen = snap.version();
        s.sentGeneration = snap.generation();
        s.lastSent = clock.instant();
        subs.put(s.id, s);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("subscription", s.id);
        out.put("throttle", t.toMillis() + "ms");
        out.put("deliver", deliver);
        out.put("answer", snap.body());
        out.put("cursor", cursor(snap.generation()));
        out.put("group", g.id());
        out.put("mode", g.mode());
        out.put("subscribers", g.members());
        return out;
    }

    private static byte[] bytes() {
        byte[] b = new byte[8];
        RANDOM.nextBytes(b);
        return b;
    }

    static String cursor(long g) {
        return "g-" + g;
    }

    /** Ends a subscription of the caller (DRS-9180 when they hold none of that id). */
    public void unsubscribe(Principal p, String id) {
        Sub s = subs.get(id);
        if (s == null || !s.owner.user().equals(p.user())) {
            throw new MqException(MqException.Problem.of("DRS-9180", "", "you hold no standing query '" + id + "'"));
        }
        subs.remove(id);
        registry.leave(s.group, id);
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
     * The deliveries now due to the given subscriptions: each whose group moved on since it last delivered and whose throttle has passed gives an
     * {@code answer} (rows, the difference, or the value); one whose group failed gives a {@code status}. Nothing is evaluated here: the groups are kept
     * current by the bus. Ids no longer held are skipped.
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
                Event e = deliver(s, now);
                if (e != null) {
                    out.add(e);
                }
            } finally {
                s.lock.unlock();
            }
        }
        return out;
    }

    /** As {@link #poll}, waiting up to {@code millis} for a delivery (a stream's loop: woken by the groups it reads, never by a timer of generations). */
    public List<Event> await(List<String> ids, long millis) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (true) {
            List<Event> out = poll(ids);
            long left = until - System.nanoTime();
            if (!out.isEmpty() || left <= 0) {
                return out;
            }
            long wait = Math.min(left, TimeUnit.MILLISECONDS.toNanos(Math.max(5, nextDue(ids))));
            signal.lock();
            try {
                moved.awaitNanos(wait);
            } finally {
                signal.unlock();
            }
        }
    }

    /** The milliseconds until a moved subscription's throttle lets it deliver (a long wait when none moved). */
    private long nextDue(List<String> ids) {
        long best = props.poll().toMillis() * 10;
        Instant now = clock.instant();
        for (String id : ids) {
            Sub s = subs.get(id);
            if (s != null && s.group != null && s.group.version() > s.seen) {
                best = Math.min(best, Math.max(1, Duration.between(now, s.lastSent.plus(s.throttle)).toMillis()));
            }
        }
        return best;
    }

    private void wake() {
        signal.lock();
        try {
            moved.signalAll();
        } finally {
            signal.unlock();
        }
    }

    private Event deliver(Sub s, Instant now) {
        StandingQueryRegistry.Group g = s.group;
        if (g == null) {
            return null;
        }
        StandingQueryRegistry.Snapshot snap = g.snapshot();
        if (snap.version() <= s.seen) {
            return null;
        }
        if (Duration.between(s.lastSent, now).compareTo(s.throttle) < 0) {
            return null;                                                        // latest wins: the newest state is delivered when the throttle has passed
        }
        s.seen = snap.version();
        s.lastSent = now;
        if (snap.failure() != null) {
            if (Objects.equals(s.failure, snap.failure().getMessage())) {
                return null;
            }
            s.failure = snap.failure().getMessage();
            DrishtiException e = snap.failure();
            return new Event("status", Map.of("subscription", s.id, "state", "failed", "code", e.errorCode().code(), "detail", e.getMessage()));
        }
        s.failure = null;
        if (snap.generation() == s.sentGeneration && sameRows(snap.rows(), s.delivered)) {
            return null;                                                        // the data moved but not this answer
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("subscription", s.id);
        data.put("cursor", cursor(snap.generation()));
        switch (s.deliver) {
            case "diff" -> data.put("diff", diff(s.delivered, snap.rows(), snap.keyColumns()));
            case "value" -> data.put("value", snap.rows().isEmpty() || snap.rows().get(0).isEmpty() ? null : snap.rows().get(0).get(snap.rows().get(0).size() - 1));
            default -> data.put("rows", snap.rows());
        }
        if (snap.body() != null && snap.body().get("totals") != null) {
            data.put("totals", snap.body().get("totals"));
        }
        data.put("mode", snap.mode());
        s.delivered = snap.rows();
        s.sentGeneration = snap.generation();
        return new Event("answer", data);
    }

    private String state(Sub s) {
        StandingQueryRegistry.Group g = s.group;
        return g == null ? "closed" : g.state();
    }

    /** The status of each subscription (the stream's heartbeat): its state ({@code live}, {@code throttled}, {@code tick}, {@code failed}), mode and cursor. */
    public List<Event> status(List<String> ids) {
        List<Event> out = new ArrayList<>();
        for (String id : ids) {
            Sub s = subs.get(id);
            if (s != null) {
                StandingQueryRegistry.Group g = s.group;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("subscription", s.id);
                m.put("state", state(s));
                m.put("throttle", s.throttle.toMillis() + "ms");
                m.put("cursor", cursor(s.sentGeneration));
                if (g != null) {
                    m.put("mode", g.mode());
                    m.put("group", g.id());
                    m.put("lagMillis", g.lagMillis());
                }
                out.add(new Event("status", m));
            }
        }
        return out;
    }

    static boolean sameRows(List<List<Object>> a, List<List<Object>> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!sameRow(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }

    static boolean sameRow(List<Object> a, List<Object> b) {
        if (a == null || b == null || a.size() != b.size()) {
            return Objects.equals(a, b);
        }
        for (int i = 0; i < a.size(); i++) {
            Object x = a.get(i);
            Object y = b.get(i);
            if (x instanceof Number u && y instanceof Number w) {
                double d = u.doubleValue();
                double e = w.doubleValue();
                if (Double.compare(d, e) != 0 && Math.abs(d - e) > 1e-9 * Math.max(1, Math.abs(d))) {
                    return false;
                }
            } else if (!Objects.equals(x, y)) {
                return false;
            }
        }
        return true;
    }

    /** The rows that are new or changed (by the values of the field columns) and the keys of the rows that went away. */
    static Map<String, Object> diff(List<List<Object>> before, List<List<Object>> after, int keys) {
        Map<List<Object>, List<Object>> was = new LinkedHashMap<>();
        before.forEach(r -> was.put(r.subList(0, Math.min(keys, r.size())), r));
        Map<List<Object>, List<Object>> now = new LinkedHashMap<>();
        after.forEach(r -> now.put(r.subList(0, Math.min(keys, r.size())), r));
        List<List<Object>> upsert = new ArrayList<>();
        now.forEach((k, r) -> {
            if (!sameRow(was.get(k), r)) {
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
        for (Sub s : List.copyOf(subs.values())) {
            if (s.lastRead.isBefore(cut)) {
                subs.remove(s.id);
                registry.leave(s.group, s.id);
            }
        }
    }

    /** Waits until every notice so far has been applied to the groups (tests; an orderly shutdown). */
    public boolean settled(long millis) {
        return registry.settled(millis);
    }

    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("subscriptions", subs.size());
        m.put("max", props.maxSubscriptions());
        m.put("maxPerUser", props.maxSubscriptionsPerUser());
        m.putAll(registry.stats());
        return m;
    }

    @Override
    public void close() {
        AutoCloseable l = listening;
        if (l != null) {
            try {
                l.close();
            } catch (Exception ignored) {
                // a listener handle's close cannot fail
            }
        }
        registry.close();
    }
}
