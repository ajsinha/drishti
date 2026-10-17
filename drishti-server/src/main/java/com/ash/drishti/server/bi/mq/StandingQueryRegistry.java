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
import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.engine.GenerationBus;
import com.ash.drishti.server.bi.engine.Incremental;
import com.ash.drishti.server.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The standing queries grouped by <b>(plan hash, security signature)</b> (docs/architecture/BI_DESIGN.md section 4.6): every subscriber of a group asks the
 * same question with the same effective security, so one evaluation serves them all. A group is evaluated when the generation bus says its model's data moved
 * (no polling), at most once per its throttle (the shortest of its members'; the latest state wins: notices accumulate while it waits), by its
 * {@link Maintainer}; a full recompute otherwise. Per model, the time spent maintaining is counted per second against {@code maintenance-budget}: past it the
 * model's groups move to tick mode (recomputed every {@code tick}) until a quiet second. A group no member reads stays {@code linger} (30 s) then goes.
 */
final class StandingQueryRegistry implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(StandingQueryRegistry.class);
    static final Duration LINGER = Duration.ofSeconds(30);

    /** What a group answers now: what a member delivers from. */
    record Snapshot(List<List<Object>> rows, Map<String, Object> body, int keyColumns, long generation, long version, DrishtiException failure, String mode) {}

    /** One evaluated question for one effective security. */
    final class Group {
        volatile String key;
        final String id;
        final Principal owner;
        final ModelQuery query;
        final String modelKey;
        final LocalDate asOf;
        final ReentrantLock lock = new ReentrantLock();
        final Set<String> members = ConcurrentHashMap.newKeySet();
        final ConcurrentLinkedQueue<GenerationBus.Notice> pending = new ConcurrentLinkedQueue<>();
        final AtomicBoolean scheduled = new AtomicBoolean();
        final AtomicLong version = new AtomicLong();
        volatile String signature;
        volatile Duration throttle;
        volatile Snapshot snapshot;
        volatile String state = "live";
        volatile String mode = "recompute";
        volatile Instant lastEval = Instant.EPOCH;
        volatile Instant emptySince;
        volatile Instant firstPending;
        volatile long lagMillis;
        volatile Maintainer maintainer;
        int overruns;                                                          // under the lock only

        Group(String key, Principal owner, ModelQuery query, String modelKey, String signature, Duration throttle) {
            this.key = key;
            this.id = "sg_" + Integer.toHexString(key.hashCode() & 0x7fffffff);
            this.owner = owner;
            this.query = query;
            this.modelKey = modelKey;
            this.asOf = query.time().asOf();
            this.signature = signature;
            this.throttle = throttle;
        }

        String id() {
            return id;
        }

        Snapshot snapshot() {
            return snapshot;
        }

        long version() {
            return version.get();
        }

        String state() {
            return state;
        }

        String mode() {
            return mode;
        }

        int members() {
            return members.size();
        }

        long lagMillis() {
            return lagMillis;
        }
    }

    /** The maintenance time of one model in the current second, and whether it is degraded to the tick. */
    private static final class Budget {
        final ReentrantLock lock = new ReentrantLock();
        long second;
        long nanos;
        volatile boolean over;
    }

    private final StandingQueries.Models catalog;
    private final MqService mq;
    private final MqProperties props;
    private final Clock clock;
    private final Runnable wake;
    private final Map<String, Group> groups = new ConcurrentHashMap<>();
    private final Map<String, Budget> budgets = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().daemon().name("drishti-bi-sq").unstarted(r));
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicInteger running = new AtomicInteger();
    private final AtomicLong evaluations = new AtomicLong();
    private final AtomicLong incremental = new AtomicLong();
    private final AtomicLong full = new AtomicLong();
    private final AtomicLong notices = new AtomicLong();

    StandingQueryRegistry(StandingQueries.Models catalog, MqService mq, MqProperties props, Clock clock, Runnable wake) {
        this.catalog = catalog;
        this.mq = mq;
        this.props = props;
        this.clock = clock;
        this.wake = wake;
        scheduler.scheduleWithFixedDelay(this::linger, 5, 5, TimeUnit.SECONDS);
    }

    /** The group of a subscriber's question (made and evaluated when it is the first), with the subscriber added. */
    Group join(Principal p, MqService.Bound b, ModelQuery q, String subId, Duration throttle) {
        String plan = mq.plan(b, q).hash();
        String signature = b.signature();
        String key = plan + "|" + signature;
        Group g = groups.get(key);
        if (g == null) {
            Group made = new Group(key, p, q, b.view().key(), signature, throttle);
            made.lock.lock();
            try {
                full(made, b, List.of());                                       // the first answer: refusals reach the subscriber
                if (made.snapshot.failure() != null) {
                    throw made.snapshot.failure();
                }
            } finally {
                made.lock.unlock();
            }
            g = groups.putIfAbsent(key, made);
            if (g == null) {
                g = made;
            }
        }
        g.members.add(subId);
        g.emptySince = null;
        if (throttle.compareTo(g.throttle) < 0) {
            g.throttle = throttle;
        }
        return g;
    }

    void leave(Group g, String subId) {
        if (g != null && g.members.remove(subId) && g.members.isEmpty()) {
            g.emptySince = clock.instant();
        }
    }

    private void linger() {
        Instant cut = clock.instant().minus(LINGER);
        groups.values().removeIf(g -> g.members.isEmpty() && g.emptySince != null && g.emptySince.isBefore(cut));
    }

    // ---- the bus -----------------------------------------------------------------------------------------------------

    /** A change of some model's data: each group on that model whose answer it can move is scheduled. */
    void onNotice(GenerationBus.Notice n) {
        notices.incrementAndGet();
        if (!n.origin().isEmpty() && ("live".equals(n.cause()) || "history".equals(n.cause()))) {
            return;                                                             // live data is each node's own: its live tables hear the connector themselves
        }
        for (Group g : groups.values()) {
            if (!g.modelKey.equals(n.key()) || g.members.isEmpty() && g.emptySince != null) {
                continue;
            }
            if (n.date() != null && !reads(g, n.date())) {
                continue;                                                       // a load for an earlier date moves that date's answers only
            }
            if (g.pending.isEmpty()) {
                g.firstPending = clock.instant();
            }
            g.pending.add(n);
            schedule(g);
        }
    }

    /**
     * True when a load or refresh of {@code date} can move the group's answer: an as-of answer of that date, or an answer that read that date or an earlier one
     * (a back-dated load before the dates an answer read leaves it as it is).
     */
    private static boolean reads(Group g, LocalDate date) {
        if (g.asOf != null) {
            return g.asOf.equals(date);
        }
        Snapshot s = g.snapshot;
        if (s == null || s.body() == null || !(s.body().get("meta") instanceof Map<?, ?> meta) || !(meta.get("dates") instanceof List<?> dates) || dates.isEmpty()) {
            return true;
        }
        LocalDate first = null;
        for (Object d : dates) {
            try {
                LocalDate x = LocalDate.parse(String.valueOf(d));
                first = first == null || x.isBefore(first) ? x : first;
            } catch (RuntimeException e) {
                return true;
            }
        }
        return first == null || !date.isBefore(first);
    }

    private void schedule(Group g) {
        if (!g.scheduled.compareAndSet(false, true)) {
            return;
        }
        Duration every = "tick".equals(g.mode) || degraded(g.modelKey) ? props.tick() : g.throttle;
        long since = Duration.between(g.lastEval, clock.instant()).toMillis();
        long delay = Math.max(0, every.toMillis() - since);
        running.incrementAndGet();
        scheduler.schedule(() -> workers.execute(() -> {
            try {
                evaluate(g);
            } finally {
                running.decrementAndGet();
            }
        }), delay, TimeUnit.MILLISECONDS);
    }

    /** One evaluation at a time per owner: standing queries never take more than one of a person's query places (the rest stay theirs). */
    private final Map<String, ReentrantLock> owners = new ConcurrentHashMap<>();

    private void evaluate(Group g) {
        ReentrantLock owner = owners.computeIfAbsent(g.owner.user(), u -> new ReentrantLock());
        owner.lock();
        try {
            com.ash.drishti.server.bi.engine.QueryContext.with(new com.ash.drishti.server.bi.engine.QueryContext.Ctx(
                    com.ash.drishti.server.bi.engine.QueryContext.WorkClass.STANDING, null, null), () -> {
                        evaluateAs(g);                                          // the standing work class: after interactive questions, not a person's share
                        return null;
                    });
        } finally {
            owner.unlock();
        }
    }

    private void evaluateAs(Group g) {
        g.lock.lock();
        try {
            g.scheduled.set(false);
            List<GenerationBus.Notice> drained = new ArrayList<>();
            for (GenerationBus.Notice n = g.pending.poll(); n != null; n = g.pending.poll()) {
                drained.add(n);
            }
            if (drained.isEmpty()) {
                return;
            }
            long t0 = System.nanoTime();
            MqService.Bound b;
            try {
                b = catalog.bind(g.owner, g.query.model());
            } catch (DrishtiException e) {
                fail(g, e);
                return;
            }
            String sig = b.signature();
            boolean replanned = !sig.equals(g.signature);
            if (replanned) {
                rekey(g, b, sig);                                               // a security change: re-planned, never patched
            }
            boolean done = false;
            if (!replanned && !degraded(g.modelKey) && !"tick".equals(g.mode) && drained.stream().allMatch(n -> "live".equals(n.cause()) && n.change() != null)) {
                done = incrementally(g, b, drained);
            }
            if (!done) {
                full(g, b, drained);
            }
            long took = System.nanoTime() - t0;
            account(g, took);
            Instant first = g.firstPending;
            g.lagMillis = first == null ? 0 : Math.max(0, Duration.between(first, clock.instant()).toMillis());     // from the change to the answer
            g.firstPending = g.pending.isEmpty() ? null : clock.instant();
            g.lastEval = clock.instant();
            Duration t = Duration.ofNanos(took);
            if (t.compareTo(g.throttle) > 0 && ++g.overruns >= 3) {
                g.throttle = g.throttle.multipliedBy(2);
                g.overruns = 0;
                g.state = "throttled";
            } else if (t.compareTo(g.throttle) <= 0) {
                g.overruns = 0;
                g.state = degraded(g.modelKey) ? "tick" : "live";
            }
            if (!g.pending.isEmpty()) {
                schedule(g);
            }
        } catch (RuntimeException e) {
            if (e instanceof DrishtiException d && d.errorCode() == com.ash.drishti.common.ErrorCode.QUERY_BUSY && g.snapshot != null) {
                // the engine is busy (slots or the owner's own queries): ask the whole question again a little later, never fail the group for it
                g.pending.add(new GenerationBus.Notice(g.modelKey, null, "retry", null, 0, null));
                g.lastEval = clock.instant();
                schedule(g);
                return;
            }
            LOG.warn("standing query group {} failed: {}", g.id, e.getMessage());
            fail(g, e instanceof DrishtiException d ? d : new DrishtiException(com.ash.drishti.common.ErrorCode.QUERY_ENGINE, String.valueOf(e.getMessage())));
        } finally {
            g.lock.unlock();
        }
    }

    /** The live changes applied by the group's maintainer, table by table; false when a table needs the whole question asked again. */
    private boolean incrementally(Group g, MqService.Bound b, List<GenerationBus.Notice> drained) {
        Maintainer m = g.maintainer;
        if (m == null || g.snapshot == null || g.snapshot.failure() != null) {
            return false;
        }
        Map<String, List<GenerationBus.Notice>> byTable = new LinkedHashMap<>();
        drained.forEach(n -> byTable.computeIfAbsent(n.table(), k -> new ArrayList<>()).add(n));
        List<List<Object>> rows = g.snapshot.rows();
        Incremental.Kind worst = Incremental.Kind.DIFFERENCE;
        for (Map.Entry<String, List<GenerationBus.Notice>> e : byTable.entrySet()) {
            Maintainer.Applied a = m.apply(e.getKey(), e.getValue(), rows);
            if (a == null) {
                return false;
            }
            rows = a.rows();
            worst = a.kind().ordinal() > worst.ordinal() ? a.kind() : worst;
        }
        long gen = catalog.generation(g.owner, b.view());
        g.snapshot = new Snapshot(rows, g.snapshot.body(), g.snapshot.keyColumns(), gen, g.snapshot.version() + 1, null, "incremental");
        g.version.set(g.snapshot.version());
        g.mode = "incremental";
        incremental.incrementAndGet();
        evaluations.incrementAndGet();
        wake.run();
        return true;
    }

    /** The whole question asked again as the group's owner (the first answer, a load, a refresh, a context, the tick). */
    private void full(Group g, MqService.Bound b, List<GenerationBus.Notice> drained) {
        Map<String, Long> gens = new LinkedHashMap<>();
        for (Dataset.Table t : b.view().flat().tables().values()) {
            if (t.isLive()) {
                gens.put(t.name(), catalog.liveGeneration(g.modelKey, t.name()));
            }
        }
        try {
            MqService.Answer a = mq.ask(b, g.query, false);
            List<List<Object>> rows = MqService.rows(a.result());
            long gen = catalog.generation(g.owner, b.view());
            Maintainer m = g.maintainer;
            if (m == null) {
                m = new Maintainer(mq, b, g.query, false, t -> catalog.liveGeneration(g.modelKey, t));
                g.maintainer = m;
            }
            m.synced(gens);
            Incremental.Kind kind = m.mode();
            g.mode = kind == Incremental.Kind.TICK ? "tick" : kind == Incremental.Kind.RECOMPUTE ? "recompute" : "incremental";
            long v = g.version.incrementAndGet();
            g.snapshot = new Snapshot(rows, a.body(), keyColumns(a), gen, v, null, g.mode);
            full.incrementAndGet();
            evaluations.incrementAndGet();
            wake.run();
        } catch (DrishtiException e) {
            if (e.errorCode() == com.ash.drishti.common.ErrorCode.QUERY_BUSY && g.snapshot != null) {
                throw e;                                                        // busy: retried by evaluate, the answer kept as it was
            }
            fail(g, e);
        }
    }

    private void fail(Group g, DrishtiException e) {
        long v = g.version.incrementAndGet();
        Snapshot s = g.snapshot;
        g.snapshot = new Snapshot(s == null ? List.of() : s.rows(), s == null ? null : s.body(), s == null ? 0 : s.keyColumns(), s == null ? 0 : s.generation(), v, e, g.mode);
        g.state = "failed";
        wake.run();
    }

    private void rekey(Group g, MqService.Bound b, String sig) {
        String plan = mq.plan(b, g.query).hash();
        String key = plan + "|" + sig;
        groups.remove(g.key, g);
        g.key = key;
        g.signature = sig;
        g.maintainer = null;
        groups.putIfAbsent(key, g);
    }

    private static int keyColumns(MqService.Answer a) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cols = (List<Map<String, Object>>) a.body().get("columns");
        return (int) cols.stream().filter(c -> "field".equals(c.get("kind"))).count();
    }

    // ---- the maintenance budget --------------------------------------------------------------------------------------

    private void account(Group g, long nanos) {
        long second = clock.millis() / 1000;
        Budget b = budgets.computeIfAbsent(g.modelKey, k -> new Budget());
        b.lock.lock();
        try {
            if (b.second != second) {
                boolean quiet = b.nanos <= props.maintenanceBudget().toNanos() / 2;
                b.over = b.over && !quiet;
                b.second = second;
                b.nanos = 0;
            }
            b.nanos += nanos;
            if (b.nanos > props.maintenanceBudget().toNanos()) {
                if (!b.over) {
                    LOG.info("standing queries of {} over their maintenance budget ({} ms in a second): recomputed every {} until quiet", g.modelKey,
                            b.nanos / 1_000_000, props.tick());
                }
                b.over = true;
            }
        } finally {
            b.lock.unlock();
        }
    }

    boolean degraded(String modelKey) {
        Budget b = budgets.get(modelKey);
        return b != null && b.over;
    }

    boolean settled(long millis) {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < until) {
            boolean busy = running.get() > 0 || groups.values().stream().anyMatch(g -> !g.pending.isEmpty() || g.scheduled.get());
            if (!busy) {
                return true;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** The groups, their modes and the work done (Admin, metrics). */
    Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("groups", groups.size());
        m.put("evaluations", evaluations.get());
        m.put("incremental", incremental.get());
        m.put("recomputed", full.get());
        m.put("notices", notices.get());
        Map<String, Long> modes = new LinkedHashMap<>();
        groups.values().forEach(g -> modes.merge(g.mode, 1L, Long::sum));
        m.put("modes", modes);
        Set<String> over = new LinkedHashSet<>();
        budgets.forEach((k, b) -> {
            if (b.over) {
                over.add(k);
            }
        });
        m.put("overBudget", over);
        return m;
    }

    /** The groups (for the metrics: active subscriptions per mode, lag). */
    List<Group> groups() {
        return List.copyOf(groups.values());
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        workers.shutdownNow();
    }
}
