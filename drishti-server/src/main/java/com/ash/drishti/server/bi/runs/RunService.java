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
package com.ash.drishti.server.bi.runs;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.bi.engine.ArrowIpcWriter;
import com.ash.drishti.server.bi.engine.QueryContext;
import com.ash.drishti.server.bi.plan.SecuritySignature;
import com.ash.drishti.server.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The BI runs pipeline (docs/architecture/BI_RUNS.md sections 3, 6, 8, 10, 11, 12): submit, wait inline, become visible, run, store, notify.
 *
 * <ul>
 *   <li>A question is computed <b>as its runner</b>: its {@link Work} is a closure the endpoint built with the runner's bound model (their row rules
 *       and masks). Nothing runs as the system.
 *   <li>Its result key is the hash of the canonical question, the runner's <b>security signature</b>, the data version and the engine semantics.
 *       A stored result or a running twin is reused or joined only under the same key, so never across signatures (SOAK2-01 class).
 *   <li>Interactive questions run at once on their own thread, in the {@code INTERACTIVE} class; background runs pass {@link RunQueue} (fair share,
 *       {@code workers} at a time) and run in the {@code BACKGROUND} class under its admission cap.
 *   <li>A run that finishes inside the inline wait writes no row and sends no notice. One that does not becomes visible: a row, events, a notice.
 *   <li>At start every queued or running row of this node is resumed (when it can be replayed as its runner) or failed with DRS-9085 and a reason.
 * </ul>
 */
public final class RunService {

    private static final Logger LOG = LoggerFactory.getLogger(RunService.class);
    private static final List<RunState> UNFINISHED = List.of(RunState.PLANNED, RunState.AWAITING_CONFIRM, RunState.QUEUED, RunState.RUNNING, RunState.STORING);

    /** The answer of a question, as the endpoint gives it: the body, and the typed columns for Arrow (null when only JSON is kept). */
    public record Answer(Map<String, Object> body, List<ArrowIpcWriter.Column> columns, int rows) {}

    /** The work of a question, computed as its runner on the thread that runs it; an interrupt cancels it. */
    @FunctionalInterface
    public interface Work {
        Answer run();
    }

    /**
     * One question.
     *
     * @param caller      the runner (whose rights compute it)
     * @param sourceType  report | board | mq | sql | statement | subscription | schedule
     * @param question    the canonical question (JSON): replayable, and the first part of the key
     * @param signature   the runner's security signature on what the question reads (the model's, else a personal one)
     * @param dataVersion the version of the data it reads; null: not reusable by others (stored for its runner only)
     * @param interaction a slicer, cross-filter or drill: never becomes a visible job (section 5.3)
     * @param supersedes  a newer question with the same key (of the same caller) cancels the older at once
     */
    public record Question(Principal caller, String sourceType, String sourceId, String sourceRef, String title, String workspace, String model,
                           String question, String params, String signature, String dataVersion, boolean interaction, String supersedes, Work work,
                           RunPlanner.Planned planned, String sourceTitle) {

        /** A question without its source's title (a model's or a statement's: the run object's {@code source.title} is then null). */
        public Question(Principal caller, String sourceType, String sourceId, String sourceRef, String title, String workspace, String model, String question,
                        String params, String signature, String dataVersion, boolean interaction, String supersedes, Work work, RunPlanner.Planned planned) {
            this(caller, sourceType, sourceId, sourceRef, title, workspace, model, question, params, signature, dataVersion, interaction, supersedes, work, planned, null);
        }

        /** A question the planner has not sized (its estimate says {@code basis: unknown}; it waits inline as before 2.6.7's planner). */
        public Question(Principal caller, String sourceType, String sourceId, String sourceRef, String title, String workspace, String model, String question,
                        String params, String signature, String dataVersion, boolean interaction, String supersedes, Work work) {
            this(caller, sourceType, sourceId, sourceRef, title, workspace, model, question, params, signature, dataVersion, interaction, supersedes, work, null, null);
        }
    }

    /**
     * How the caller waits.
     *
     * @param inlineWait the inline wait (0: fire and forget)
     * @param background run as ad-hoc heavy in the background class at once
     * @param confirmed  the confirmation is given in advance ({@code options.confirm}, the CLI's {@code --yes}): no {@code DRS-9082}
     * @param sharedFrom the run this one recomputes for a share's recipient (section 9.3), else null
     */
    public record Options(Duration inlineWait, boolean background, boolean confirmed, String sharedFrom) {
        public Options(Duration inlineWait, boolean background) {
            this(inlineWait, background, false, null);
        }

        public Options(Duration inlineWait, boolean background, boolean confirmed) {
            this(inlineWait, background, confirmed, null);
        }
    }

    /** What submit answers: an answer inline (with the invisible run), or a visible run. */
    public record Outcome(Answer answer, Run run) {
        public boolean inline() {
            return answer != null;
        }
    }

    /** Who is told when a visible run ends (the bell); names and counts only. */
    @FunctionalInterface
    public interface Notifier {
        void tell(String user, Run run, String summary);

        Notifier NONE = (u, r, s) -> {};
    }

    /** Rebuilds the work of a stored run as its runner after a restart; empty when it cannot (the run then fails with DRS-9085). */
    @FunctionalInterface
    public interface Replay {
        Optional<Question> of(Run run);

        Replay NONE = r -> Optional.empty();
    }

    /** One execution and everyone following it (the runner, and joined twins). */
    final class Flight {
        final String key;
        final Question q;
        final CompletableFuture<Answer> done = new CompletableFuture<>();
        final List<String> twins = new CopyOnWriteArrayList<>();
        volatile Run run;
        volatile boolean visible;
        volatile Thread thread;
        volatile boolean background;
        volatile String cancelReason;
        volatile String confirmToken;
        volatile Instant confirmBy;
        final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();

        Flight(String key, Question q, Run run) {
            this.key = key;
            this.q = q;
            this.run = run;
        }
    }

    private final RunsProperties props;
    private final RunRecords records;
    private final RunStore store;
    private final RunQueue queue;
    private final Clock clock;
    private final String node;
    private final SplittableRandom random = new SplittableRandom();
    private final Map<String, Flight> byKey = new ConcurrentHashMap<>();
    private final Map<String, Flight> byRun = new ConcurrentHashMap<>();
    private final Map<String, String> superseded = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<Map<String, Object>>>> listeners = new ConcurrentHashMap<>();
    private final RunMetrics metrics;
    private volatile Notifier notifier = Notifier.NONE;
    private volatile Replay replay = Replay.NONE;

    public RunService(RunsProperties props, RunRecords records, RunStore store, Clock clock, String node, RunMetrics metrics) {
        this.props = props;
        this.records = records;
        this.store = store;
        this.clock = clock;
        this.node = node == null || node.isBlank() ? "local" : node;
        this.metrics = metrics == null ? RunMetrics.NONE : metrics;
        this.queue = new RunQueue(props.workers(), props.queue().max(), this::execute);
        this.suggestions = new RunSuggestions(props.suggest(), props.store().dir());
    }

    private final RunSuggestions suggestions;

    /** Repeated heavy questions as rollup suggestions (section 8.3a). */
    public RunSuggestions suggestions() {
        return suggestions;
    }

    /** The run log the suggestions count: finished runs, newest first. */
    public List<Run> finishedLog() {
        return records.inStates(List.of(RunState.DONE, RunState.REUSED, RunState.EXPIRED), 5000);
    }

    public RunsProperties properties() {
        return props;
    }

    public RunStore store() {
        return store;
    }

    public RunRecords records() {
        return records;
    }

    public String node() {
        return node;
    }

    public void notifier(Notifier n) {
        this.notifier = n == null ? Notifier.NONE : n;
    }

    public void replay(Replay r) {
        this.replay = r == null ? Replay.NONE : r;
    }

    /** What Admin shows beside the metrics: how often the inline wait was enough, how long jobs take, how often the size was unknown. */
    static final class Stats {
        final java.util.concurrent.atomic.AtomicLong inlined = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong jobs = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong planned = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong unknown = new java.util.concurrent.atomic.AtomicLong();
        private final java.util.ArrayDeque<Double> jobSeconds = new java.util.ArrayDeque<>();

        synchronized void job(double seconds) {
            jobSeconds.addLast(seconds);
            while (jobSeconds.size() > 200) {
                jobSeconds.removeFirst();
            }
        }

        synchronized List<Double> jobSeconds() {
            return List.copyOf(jobSeconds);
        }

        private final Map<String, java.util.ArrayDeque<Double>> byBasis = new LinkedHashMap<>();

        /** A finished run's actual ÷ estimated seconds under its estimate's basis (Admin's accuracy by basis). */
        synchronized void ratio(String basis, double r) {
            java.util.ArrayDeque<Double> d = byBasis.computeIfAbsent(basis, b -> new java.util.ArrayDeque<>());
            d.addLast(r);
            while (d.size() > 200) {
                d.removeFirst();
            }
        }

        synchronized Map<String, List<Double>> ratios() {
            Map<String, List<Double>> out = new LinkedHashMap<>();
            byBasis.forEach((k, v) -> out.put(k, List.copyOf(v)));
            return out;
        }
    }

    final Stats stats = new Stats();

    private volatile RunPlanner planner;

    /** The planner whose rates learn from finished runs (section 4.4); the sources plan questions with it before submitting them. */
    public void planner(RunPlanner p) {
        this.planner = p;
    }

    public RunPlanner planner() {
        return planner;
    }

    /** The result key of a question (section 8.2): question, signature, data version and engine semantics. */
    public static String key(Question q) {
        return SecuritySignature.sha(String.join("|", q.question(), q.signature(), q.dataVersion() == null ? "unversioned:" + q.caller().user()
                : q.dataVersion(), ResultKey.ENGINE_SEMANTICS));
    }

    // ---- submit -----------------------------------------------------------------------------------------------------

    public Outcome submit(Question q, Options o) {
        Instant now = clock.instant();
        String key = key(q);
        boolean reusable = q.dataVersion() != null;
        metrics.submitted(q.sourceType());
        if (q.supersedes() != null && !q.supersedes().isBlank()) {
            String older = superseded.put(q.caller().user() + "|" + q.supersedes(), "pending");
            Flight running = older == null || "pending".equals(older) ? null : byRun.get(older);
            if (running != null && !running.run.state().finished()) {
                try {
                    cancel(older, q.caller().user(), false, "replaced by a newer selection");
                } catch (DrishtiException e) {
                    // it finished (an invisible run leaves no row) or was cancelled meanwhile: nothing to replace
                }
            }
        }
        RunPlanner.Planned plan = q.planned();
        if (plan != null) {
            stats.planned.incrementAndGet();
            if (!plan.estimate().known()) {
                stats.unknown.incrementAndGet();
            }
        }
        if (plan != null && plan.refusal().filter(c -> c == ErrorCode.RUN_NEEDED).isPresent() && !plan.estimate().known()) {
            // an interaction of unknown size is not refused: it runs interactively, bounded by the interactive limit, and never becomes a job (5.3)
            plan = plan.with(new RunEstimator.Decision(Tier.INTERACTIVE, false, Optional.empty()));
            q = withPlan(q, plan);
        }
        if (plan != null && plan.refusal().isPresent()) {
            refuse(q, plan);                                                    // DRS-9086 past every engine, DRS-9089 an interaction: nothing ran
        }
        Tier tier = plan != null ? plan.tier() : o.background() ? Tier.AD_HOC_HEAVY : Tier.INTERACTIVE;
        if (plan != null && tier == Tier.AD_HOC_HEAVY && !o.background()) {
            o = new Options(Duration.ZERO, true, o.confirmed(), o.sharedFrom());               // its estimate says it will not finish inline: 202 at once (6.1)
        }
        Run base = planned(q, key, reusable, tier, now, o.sharedFrom());
        // 1. the same key's result is stored: reused at once, nothing runs (8.3)
        if (reusable) {
            Optional<Map<String, Object>> hit = store.read(key, q.signature());
            if (hit.isPresent()) {
                Run r = base.moveTo(RunState.REUSED, null, null, now);
                Run.Builder b = r.toBuilder();
                b.reuseOf = records.doneWithKey(key).map(Run::id).orElse(null);
                metrics.reuse("same");
                Map<String, Object> body = hit.get();
                Object rc = body.get("rowCount");
                return new Outcome(new Answer(body, null, rc instanceof Number n ? n.intValue() : 0), b.build());
            }
        }
        // 2. a running twin with the same key: join it (single-flight, 13.3)
        Flight twin = reusable ? byKey.get(key) : null;
        if (twin != null && !twin.done.isDone()) {
            metrics.reuse("joined");
            Run r = base.moveTo(RunState.RUNNING, null, null, now);
            Run.Builder b = r.toBuilder();
            b.reuseOf = twin.run.id();
            Run joined = b.build();
            twin.twins.add(joined.id());
            return await(twin, joined, q, o, now);
        }
        if (o.background()) {
            checkQueue(q.caller().user());
        }
        Flight f = new Flight(key, q, base);
        if (reusable) {
            byKey.put(key, f);
        }
        byRun.put(base.id(), f);
        if (q.supersedes() != null && !q.supersedes().isBlank()) {
            superseded.put(q.caller().user() + "|" + q.supersedes(), base.id());
        }
        if (o.background() && plan != null && plan.confirm() && !o.confirmed()) {
            return awaitConfirm(f, plan, now);                                  // 409 DRS-9082 with the estimate and a token (4.3, 6.1)
        }
        if (o.background()) {
            f.background = true;
            f.run = base.moveTo(RunState.QUEUED, null, null, now);
            becomeVisible(f);
            if (!queue.offer(f, q.caller().user())) {
                fail(f, new DrishtiException(ErrorCode.RUN_QUEUE_FULL, "the server's run queue is full (queue.max " + props.queue().max() + "): try again later"));
                throw new RunProblem(ErrorCode.RUN_QUEUE_FULL, "the server's run queue is full (queue.max " + props.queue().max() + ")", Map.of(), 30);
            }
            metrics.inline(false);
            stats.jobs.incrementAndGet();
            return new Outcome(null, f.run);
        }
        f.run = base.moveTo(RunState.RUNNING, null, null, now);
        Thread.ofVirtual().name("bi-run-" + base.id()).start(() -> execute(f));
        return await(f, null, q, o, now);
    }

    private static Question withPlan(Question q, RunPlanner.Planned p) {
        return new Question(q.caller(), q.sourceType(), q.sourceId(), q.sourceRef(), q.title(), q.workspace(), q.model(), q.question(), q.params(),
                q.signature(), q.dataVersion(), q.interaction(), q.supersedes(), q.work(), p, q.sourceTitle());
    }

    /** A refusal at plan time, with the estimate and the way forward (sections 4.3 and 5.3). */
    private void refuse(Question q, RunPlanner.Planned plan) {
        ErrorCode code = plan.refusal().get();
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("estimate", plan.estimate().toMap());
        extra.put("tier", plan.tier().wire());
        metrics.finished(plan.tier().wire(), "failed", code.code());
        if (code == ErrorCode.RUN_NEEDED) {
            extra.put("run", Map.of("submit", "POST /api/v1/bi/runs"));
            throw new RunProblem(code, "this selection needs a background run (" + words(plan.estimate()) + "): run it in the background, or ask for a rollup at this grain",
                    extra);
        }
        throw new RunProblem(code, "this question is too large for this server (" + words(plan.estimate()) + "; the local engine reads at most "
                + (planner == null ? "its" : String.valueOf(planner.settings().engineMaxReadRows())) + " rows): narrow the dates, ask at a rollup's grain, or configure a larger engine", extra);
    }

    /** The estimate in words: "about 80 rows over 2 business dates (delta-stats)". */
    static String words(RunEstimate e) {
        if (!e.known()) {
            return "its size is not known: " + e.why();
        }
        return "about " + RunEstimator.words(e.readRows()) + " rows over " + e.dates() + (e.dates() == 1 ? " business date" : " business dates") + ", "
                + Math.round(e.secondsLow()) + " to " + Math.round(Math.max(e.secondsHigh(), 1)) + " s, " + e.basis().wire();
    }

    /**
     * An ad-hoc heavy run above the confirm threshold: a visible row in {@code awaiting-confirm}, a token, and {@code 409 DRS-9082} with the estimate.
     * {@link #confirm} queues it; it is discarded after {@code confirm.ttl}.
     */
    private Outcome awaitConfirm(Flight f, RunPlanner.Planned plan, Instant now) {
        f.background = true;
        f.confirmToken = Long.toHexString(random.nextLong()) + Long.toHexString(random.nextLong());
        f.confirmBy = now.plus(props.confirmTtl());
        f.run = f.run.moveTo(RunState.AWAITING_CONFIRM, null, "the estimate asks for a confirmation: " + plan.estimate().why(), now);
        byKey.remove(f.key, f);
        becomeVisible(f);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("estimate", plan.estimate().toMap());
        extra.put("confirmToken", f.confirmToken);
        extra.put("runId", f.run.id());
        Map<String, Object> run = RunJson.of(f.run, RunJson.View.of(f.run.owner()), now);
        run.put("confirmToken", f.confirmToken);
        extra.put("run", run);
        extra.put("confirm", "POST /api/v1/bi/runs/" + f.run.id() + "/confirm");
        throw new RunProblem(ErrorCode.RUN_CONFIRM, "this is a background run of " + words(plan.estimate()) + ": confirm it to start", extra);
    }

    /** The confirm token of an {@code awaiting-confirm} run, for its owner only (the run object carries it so the drawer can confirm); else null. */
    public String confirmToken(String id, String user) {
        Flight f = byRun.get(id);
        return f != null && f.run.state() == RunState.AWAITING_CONFIRM && f.run.owner().equals(user) ? f.confirmToken : null;
    }

    /** Confirms the {@code awaiting-confirm} run of {@code user} that {@code token} was given for (a question asked again with its token). */
    public Optional<Run> confirmByToken(String user, String token) {
        for (Flight f : byRun.values()) {
            if (token.equals(f.confirmToken) && f.run.owner().equals(user) && f.run.state() == RunState.AWAITING_CONFIRM) {
                return Optional.of(confirm(f.run.id(), user, token));
            }
        }
        return Optional.empty();
    }

    /** Confirms an {@code awaiting-confirm} run with its token: it is queued as a background run. */
    public Run confirm(String id, String by, String token) {
        Flight f = byRun.get(id);
        if (f == null || f.run.state() != RunState.AWAITING_CONFIRM || !by.equals(f.run.owner())) {
            Run r = records.get(id).orElseThrow(() -> new DrishtiException(ErrorCode.RUN_NOT_FOUND, "no run '" + id + "' that you may see"));
            throw new DrishtiException(ErrorCode.RUN_NOT_DONE, "the run is " + r.state().wire() + ": nothing to confirm");
        }
        f.lock.lock();
        try {
            if (clock.instant().isAfter(f.confirmBy)) {
                f.run = f.run.moveTo(RunState.CANCELLED, "DRS-9083", "not confirmed within " + props.confirmTtl().toMinutes() + " min", clock.instant());
                f.done.cancel(false);
                records.save(f.run);
                publish(f.run);
                byRun.remove(id, f);
                throw new DrishtiException(ErrorCode.RUN_NOT_DONE, "the run was not confirmed in time: submit it again");
            }
            if (token == null || !token.equals(f.confirmToken)) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "the confirmToken does not match this run's");
            }
            checkQueue(by);
            f.confirmToken = null;
            f.run = f.run.moveTo(RunState.QUEUED, null, null, clock.instant());
            records.save(f.run);
            publish(f.run);
        } finally {
            f.lock.unlock();
        }
        if (f.key != null && f.run.resultKey() != null) {
            byKey.putIfAbsent(f.key, f);
        }
        if (!queue.offer(f, f.q.caller().user())) {
            fail(f, new DrishtiException(ErrorCode.RUN_QUEUE_FULL, "the server's run queue is full (queue.max " + props.queue().max() + "): try again later"));
        }
        return f.run;
    }

    private Run planned(Question q, String key, boolean reusable, Tier tier, Instant now, String sharedFrom) {
        Map<String, Object> estimate = q.planned() != null ? q.planned().estimate().toMap() : new RunEstimate(null, null, null, null, null, null,
                RunEstimate.Basis.UNKNOWN, 0, 0, false, 0, tier == Tier.AD_HOC_HEAVY ? "a background run: its size is learnt as it runs" : "an interactive question").toMap();
        if (q.planned() != null && !q.planned().tables().isEmpty()) {
            estimate.put(RunDataWords.KEY, q.planned().tables());              // what it reads, in words (the run object's data; BIR-09)
        }
        if (q.sourceTitle() != null) {
            estimate.put(RunDataWords.SOURCE_TITLE, q.sourceTitle());          // the report's title (source.title; BIR-10), kept with the row
        }
        Run r = Run.planned(RunId.next(node, now, random), q.caller().user(), q.caller().user(), q.sourceType(), q.question(), key, q.signature(), tier,
                "local", RunJson.write(estimate), node, now);
        Run.Builder b = r.toBuilder();
        b.workspace = q.workspace();
        b.sourceId = q.sourceId();
        b.sourceRef = q.sourceRef();
        b.title = q.title();
        b.params = q.params();
        b.dataVersion = q.dataVersion();
        if (q.planned() != null && !q.planned().dates().isEmpty()) {
            b.asOf = RunJson.asOfText(q.planned().dates().stream().filter(d -> !java.time.LocalDate.MIN.equals(d)).toList());
        }
        b.resultKey = reusable ? key : null;
        b.expiresAt = null;
        b.sharedFrom = sharedFrom;
        return b.build();
    }

    private void checkQueue(String user) {
        long mine = records.owned(user, null, 500).stream().filter(r -> r.state().active() || r.state() == RunState.AWAITING_CONFIRM).count();
        if (mine >= props.queue().perUser()) {
            throw new RunProblem(ErrorCode.RUN_QUEUE_FULL, "you have " + mine + " runs queued or running already (queue.per-user " + props.queue().perUser()
                    + "): wait for one to finish, or cancel one", Map.of(), 30);
        }
    }

    /** Waits inline for {@code f}; done in time: the answer; not: the run becomes visible (a twin: its own row following the original). */
    private Outcome await(Flight f, Run twin, Question q, Options o, Instant now) {
        Duration wait = o.inlineWait() == null ? props.inline().inlineWait() : o.inlineWait();
        if (wait.compareTo(props.inline().maxWait()) > 0) {
            wait = props.inline().maxWait();
        }
        try {
            Answer a = wait.isZero() ? null : f.done.get(wait.toMillis(), TimeUnit.MILLISECONDS);
            if (a != null) {
                metrics.inline(true);
                stats.inlined.incrementAndGet();
                Run r = twin == null ? f.run : twin.moveTo(RunState.REUSED, null, null, clock.instant());
                return new Outcome(a, r);
            }
        } catch (TimeoutException e) {
            // not done in time: it becomes a visible job (below)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (twin == null) {
                goneCaller(f);                                              // the caller left inside the inline wait: its invisible run stops
            }
            throw new DrishtiException(ErrorCode.QUERY_BUSY, "the request was interrupted while it waited for its answer");
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (twin == null && c instanceof DrishtiException d && d.errorCode() == ErrorCode.QUERY_COST && !q.interaction()) {
                return submit(q, new Options(Duration.ZERO, true, false, o.sharedFrom()));       // too large for an interactive answer: a background run (6.1)
            }
            if (c instanceof DrishtiException d && d.errorCode() == ErrorCode.QUERY_COST && q.interaction()) {
                throw new RunProblem(ErrorCode.RUN_NEEDED, "this selection needs a background run: " + d.getMessage(),
                        Map.of("run", Map.of("submit", "POST /api/v1/bi/runs")));
            }
            if (c instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(c);
        } catch (CancellationException e) {
            throw new DrishtiException(ErrorCode.RUN_NOT_DONE, "the run was cancelled: " + (f.cancelReason == null ? "cancelled" : f.cancelReason));
        }
        if (q.interaction()) {
            return awaitInteraction(f);                                     // an interaction never becomes a visible job (5.3)
        }
        metrics.inline(false);
        stats.jobs.incrementAndGet();
        if (twin != null) {
            records.save(twin);
            publish(twin);
            return new Outcome(null, twin);
        }
        f.lock.lock();
        try {
            if (f.done.isDone() && !f.done.isCompletedExceptionally()) {
                return new Outcome(f.done.join(), f.run);                 // finished in the meantime
            }
            becomeVisible(f);
        } finally {
            f.lock.unlock();
        }
        return new Outcome(null, f.run);
    }

    private Outcome awaitInteraction(Flight f) {
        try {
            return new Outcome(f.done.get(), f.run);                      // bounded by the interactive time limit (DRS-9024)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            goneCaller(f);
            throw new DrishtiException(ErrorCode.QUERY_BUSY, "the request was interrupted while it waited for its answer");
        } catch (CancellationException e) {
            throw new DrishtiException(ErrorCode.RUN_NOT_DONE, "the question was cancelled: " + (f.cancelReason == null ? "cancelled" : f.cancelReason));
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(e.getCause());
        }
    }

    /**
     * The caller of an invisible run is gone (its request thread was interrupted: the connection closed, the proxy gave up): the run is cancelled and
     * its work let go at once (section 11). A visible run is never cancelled by its caller leaving.
     */
    private void goneCaller(Flight f) {
        if (f.visible) {
            return;
        }
        long t0 = System.nanoTime();
        f.cancelReason = "the page was closed";
        Thread t = f.thread;
        if (t != null) {
            t.interrupt();
        }
        f.done.cancel(false);
        byKey.remove(f.key, f);
        byRun.remove(f.run.id(), f);
        metrics.cancelRelease((System.nanoTime() - t0) / 1e9);
    }

    private void becomeVisible(Flight f) {
        f.visible = true;
        Run.Builder lb = f.run.toBuilder();
        lb.leaseUntil = clock.instant().plus(props.lease().ttl());                // the lease a node renews while it holds the run (13.3)
        f.run = lb.build();
        records.save(f.run);
        publish(f.run);
    }

    // ---- execute ----------------------------------------------------------------------------------------------------

    /** Runs a flight on this thread: interactive at once, background from {@link RunQueue}. */
    void execute(Flight f) {
        f.thread = Thread.currentThread();
        long t0 = System.nanoTime();
        try {
            if (f.done.isDone()) {
                return;                                                     // cancelled while queued
            }
            if (f.background) {
                move(f, RunState.RUNNING, null, null);
            }
            QueryContext.Ctx ctx = f.background
                    ? new QueryContext.Ctx(QueryContext.WorkClass.BACKGROUND, props.engine().maxReadRows(), props.maxDuration().toMillis())
                    : QueryContext.Ctx.INTERACTIVE;
            Answer a = QueryContext.with(ctx, () -> f.q.work().run());
            if (Thread.interrupted()) {
                throw new CancellationException();
            }
            double seconds = (System.nanoTime() - t0) / 1e9;
            finish(f, a, seconds);
        } catch (CancellationException e) {
            f.done.cancel(false);
        } catch (RuntimeException e) {
            if (f.cancelReason != null) {
                f.done.cancel(false);
            } else {
                fail(f, e);
            }
        } finally {
            f.thread = null;
            byKey.remove(f.key, f);
            if (f.run.state().finished()) {
                byRun.remove(f.run.id(), f);
            }
            metrics.seconds(f.run.tier().wire(), (System.nanoTime() - t0) / 1e9);
        }
    }

    private void finish(Flight f, Answer a, double seconds) {
        Long bytes = null;
        boolean keep = f.visible || f.background || props.store().keepInteractive() && seconds >= props.store().minSeconds();
        if (keep) {
            f.lock.lock();
            try {
                if (f.visible || f.background) {
                    move(f, RunState.STORING, null, null);
                }
            } finally {
                f.lock.unlock();
            }
            try {
                bytes = store.write(f.key, f.q.signature(), f.run.id(), f.q.caller().user(), a.body(), a.rows());
            } catch (DrishtiException e) {
                if (f.visible) {
                    fail(f, e);
                    return;
                }
                LOG.debug("an inline answer was not stored: {}", e.getMessage());
            }
        }
        f.lock.lock();
        try {
            Run.Builder b = f.run.toBuilder();
            b.rowsOut = (long) a.rows();
            b.bytesOut = bytes;
            f.run = b.build().moveTo(RunState.DONE, null, null, clock.instant());
            f.done.complete(a);
            if (f.visible) {
                records.save(f.run);
                publish(f.run);
                tell(f.run, "Run finished: " + title(f.run) + " (" + a.rows() + " rows, " + Math.round(seconds) + " s)");
            }
        } finally {
            f.lock.unlock();
        }
        metrics.finished(f.run.tier().wire(), "done", "");
        if (f.visible) {
            stats.job(seconds);
        }
        RunPlanner p = planner;
        if (p != null && f.q.planned() != null) {
            Double ratio = p.rates().finished(RunPlanner.LOCAL, f.q.planned().estimate(), seconds, f.q.planned().buildPath());   // the rates learn (4.4)
            if (ratio != null) {
                stats.ratio(f.q.planned().estimate().basis().wire(), ratio);
                metrics.estimateRatio(f.q.planned().estimate().basis().wire(), RunPlanner.LOCAL, ratio);
            }
        }
        for (String twin : f.twins) {
            records.get(twin).ifPresent(t -> {
                if (!t.state().finished()) {
                    Run.Builder tb = t.toBuilder();
                    tb.rowsOut = (long) a.rows();
                    Run done = tb.build().moveTo(RunState.REUSED, null, null, clock.instant());
                    records.save(done);
                    publish(done);
                    tell(done, "Run finished: " + title(done) + " (" + a.rows() + " rows)");
                }
            });
        }
    }

    private void fail(Flight f, RuntimeException e) {
        String code = e instanceof DrishtiException d ? d.errorCode().code() : ErrorCode.QUERY_ENGINE.code();
        String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        f.lock.lock();
        try {
            if (!f.run.state().finished()) {
                f.run = f.run.moveTo(RunState.FAILED, code, reason, clock.instant());
            }
            f.done.completeExceptionally(e);
            if (f.visible) {
                records.save(f.run);
                publish(f.run);
                if (props.notifyOn().contains("failed")) {
                    tell(f.run, "Run failed: " + title(f.run) + " (" + code + ")");
                }
            }
        } finally {
            f.lock.unlock();
        }
        metrics.finished(f.run.tier().wire(), "failed", code);
        for (String twin : f.twins) {
            records.get(twin).filter(t -> !t.state().finished()).ifPresent(t -> {
                Run failed = t.moveTo(RunState.FAILED, code, reason, clock.instant());
                records.save(failed);
                publish(failed);
            });
        }
    }

    private void move(Flight f, RunState to, String code, String reason) {
        f.lock.lock();
        try {
            f.run = f.run.moveTo(to, code, reason, clock.instant());
            if (f.visible) {
                records.save(f.run);
                publish(f.run);
            }
        } finally {
            f.lock.unlock();
        }
    }

    // ---- actions ----------------------------------------------------------------------------------------------------

    /** The run, as stored or in flight. */
    public Optional<Run> get(String id) {
        Flight f = byRun.get(id);
        if (f != null && f.visible) {
            return Optional.of(f.run);
        }
        return records.get(id);
    }

    /**
     * Cancels a run (section 11): out of the queue, or its thread interrupted (an admission wait, a connector read and a DuckDB statement all
     * let go on interrupt). The run row says {@code cancelled} with who did it.
     */
    public Run cancel(String id, String by, boolean admin, String reason) {
        Flight f = byRun.get(id);
        Run r = f != null ? f.run : records.get(id).orElseThrow(() -> new DrishtiException(ErrorCode.RUN_NOT_FOUND, "no run '" + id + "' that you may see"));
        if (!admin && !by.equals(r.owner()) && !by.equals(r.runner())) {
            throw new DrishtiException(ErrorCode.RUN_NOT_FOUND, "no run '" + id + "' that you may see");
        }
        if (r.state().finished()) {
            return r;
        }
        String why = reason != null ? reason : admin && !by.equals(r.owner()) ? "cancelled by an administrator (" + by + ")" : "cancelled by you";
        if (f == null) {
            Run c = r.moveTo(RunState.CANCELLED, "DRS-9083", why, clock.instant());
            records.save(c);
            publish(c);
            return c;
        }
        f.lock.lock();
        try {
            f.cancelReason = why;
            queue.remove(f);
            Thread t = f.thread;
            if (t != null) {
                t.interrupt();
            }
            f.run = f.run.moveTo(RunState.CANCELLED, "DRS-9083", why, clock.instant());
            f.done.cancel(false);
            if (f.visible) {
                records.save(f.run);
                publish(f.run);
                if (!by.equals(f.run.owner())) {
                    tell(f.run, "Run cancelled: " + title(f.run) + " (" + why + ")");
                }
            }
        } finally {
            f.lock.unlock();
        }
        byKey.remove(f.key, f);
        metrics.finished(f.run.tier().wire(), "cancelled", "DRS-9083");
        return f.run;
    }

    /** The stored answer of a run, for its runner (or a reader of the same signature). DRS-9083 not done; DRS-9084 removed. */
    public Map<String, Object> result(Run r, String readerSignature) {
        if (r.state() == RunState.EXPIRED) {
            throw new DrishtiException(ErrorCode.RUN_EXPIRED, "this result was removed (kept " + props.retention().days() + " days): re-run it");
        }
        if (r.state() != RunState.DONE && r.state() != RunState.REUSED) {
            throw new DrishtiException(ErrorCode.RUN_NOT_DONE, "the run is " + r.state().wire() + (r.reason() == null ? "" : ": " + r.reason()));
        }
        String key = r.fingerprint();
        return store.read(key, readerSignature).orElseThrow(() -> {
            if (!store.has(key)) {
                Run gone = r.moveTo(RunState.EXPIRED, null, "the result was removed", clock.instant());
                records.save(gone);
                return new DrishtiException(ErrorCode.RUN_EXPIRED, "this result was removed: re-run it on the same or the newest data");
            }
            return new DrishtiException(ErrorCode.QUERY_MASKED, "this result was computed with other rights than yours: re-run it to see your own");
        });
    }

    /** Removes a run's result now; the row stays, {@code expired}. */
    public Run deleteResult(Run r) {
        store.delete(r.fingerprint());
        Run gone = r.state() == RunState.DONE || r.state() == RunState.REUSED ? r.moveTo(RunState.EXPIRED, null, "the result was deleted", clock.instant()) : r;
        records.save(gone);
        publish(gone);
        return gone;
    }

    /** Sets how long a run's result is kept ({@code keep}): up to {@code retention.max-days}. */
    public Run keep(Run r, int days) {
        int d = Math.max(1, Math.min(days, props.retention().maxDays()));
        Run.Builder b = r.toBuilder();
        b.expiresAt = clock.instant().plus(Duration.ofDays(d));
        Run kept = b.build();
        records.save(kept);
        return kept;
    }

    /** The runs the caller owns or ran, newest first. */
    public List<Run> list(String user, Instant before, int limit) {
        List<Run> out = new ArrayList<>(records.owned(user, before, limit));
        return out;
    }

    /** Runs queued on this node (the {@code drishti.bi.runs.queue} gauge). */
    public int queued() {
        Object q = queue.stats().get("queued");
        return q instanceof Number n ? n.intValue() : 0;
    }

    /** Runs executing on this node now (the {@code drishti.bi.runs.running} gauge). */
    public int running() {
        Object q = queue.stats().get("running");
        return q instanceof Number n ? n.intValue() : 0;
    }

    /** What {@code GET /api/v1/bi/engine} adds for runs: the queue, the workers and the engine runs execute on. */
    public Map<String, Object> engineStats() {
        Map<String, Object> m = new LinkedHashMap<>(queue.stats());
        m.remove("byUser");                                                     // names stay in Admin → Runs
        m.put("engine", "shared");                                              // no second DuckDB instance yet: the interactive engine, bounded by the class cap
        m.put("memoryLimit", props.engine().memoryLimitBytes());
        m.put("threads", props.engine().threads());
        m.put("enabled", props.enabled());
        return m;
    }

    /** The queue and the runs now (Admin). */
    public Map<String, Object> admin() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("queue", queue.stats());
        m.put("running", records.inStates(List.of(RunState.RUNNING, RunState.STORING, RunState.QUEUED), 200));
        m.put("storage", Map.of("results", store.usage()));
        m.put("node", node);
        return m;
    }

    // ---- events -----------------------------------------------------------------------------------------------------

    /** Hears every change of {@code user}'s visible runs; the returned handle stops it. */
    public Runnable listen(String user, Consumer<Map<String, Object>> l) {
        listeners.computeIfAbsent(user, u -> new CopyOnWriteArrayList<>()).add(l);
        return () -> listeners.getOrDefault(user, List.of()).remove(l);
    }

    private void publish(Run r) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", r.id());
        e.put("state", r.state().wire());
        e.put("progress", r.progress());
        e.put("phase", r.phase());
        e.put("rowsOut", r.rowsOut());
        e.put("code", r.code());
        for (String u : new java.util.LinkedHashSet<>(List.of(r.owner(), r.runner()))) {
            for (Consumer<Map<String, Object>> l : listeners.getOrDefault(u, List.of())) {
                try {
                    l.accept(e);
                } catch (RuntimeException ex) {
                    LOG.debug("a run listener failed: {}", ex.getMessage());
                }
            }
        }
    }

    /** Tells {@code user} about a run (a share: "anna shared a run with you"); names only, never a value. */
    public void tellAbout(String user, Run r, String summary) {
        try {
            notifier.tell(user, r, summary);
        } catch (RuntimeException e) {
            LOG.warn("could not tell {} about run {}: {}", user, r.id(), e.getMessage());
        }
    }

    private void tell(Run r, String summary) {
        try {
            notifier.tell(r.owner(), r, summary);
        } catch (RuntimeException e) {
            LOG.warn("could not tell {} about run {}: {}", r.owner(), r.id(), e.getMessage());
        }
    }

    static String title(Run r) {
        return r.title() == null || r.title().isBlank() ? r.sourceType() + (r.sourceId() == null ? "" : " " + r.sourceId()) : r.title();
    }

    // ---- restart ----------------------------------------------------------------------------------------------------

    /**
     * At start (section 12.1): every unfinished row of this node is started again, when its question replays as its runner and it has attempts
     * left, or failed with DRS-9085 and a reason. Each change is published and told. Nothing is left {@code running}. The rows changed.
     */
    public int recover() {
        int n = 0;
        String at = java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneOffset.UTC).format(clock.instant());
        for (Run r : records.onNode(node, UNFINISHED)) {
            n++;
            boolean wasRunning = r.state() == RunState.RUNNING || r.state() == RunState.STORING;
            resume(r, wasRunning, false, "started again after the server restarted at " + at + " UTC (attempt " + (r.attempt() + 1) + " of "
                    + props.restart().maxAttempts() + ")", "the server restarted at " + at + " UTC while this run " + (wasRunning ? "ran" : "waited")
                    + ", and it could not be started again", "Your run %s was started again after a server restart", "the server restarted");
        }
        if (n > 0) {
            LOG.info("BI runs: {} unfinished run(s) of node {} recovered after the start", n, node);
        }
        return n;
    }

    /**
     * Starts a row again on this node (a restart, 12.1, or an adoption, 13.3), when its question replays as its runner and it has attempts left; else
     * fails it with DRS-9085 and {@code failedWhy}. A running row is moved to queued (attempt + 1) with {@code again}.
     */
    private void resume(Run r, boolean wasRunning, boolean adopted, String again, String failedWhy, String told, String failedTold) {
        if (r.state() == RunState.AWAITING_CONFIRM || r.state() == RunState.PLANNED) {
            keepAwaiting(r);                                                    // nothing ran and nobody confirmed: not a failure (BIR-08)
            return;
        }
        int before = adopted ? r.attempt() - 1 : r.attempt();                   // an adopted row was counted again by the conditional update
        Optional<Question> q = props.restart().resumeRunning() && before < props.restart().maxAttempts() ? replay.of(r) : Optional.empty();
        if (q.isPresent() && r.state() != RunState.AWAITING_CONFIRM && r.state() != RunState.PLANNED) {
            Run moved = wasRunning ? r.moveTo(RunState.QUEUED, null, again, clock.instant()) : r;
            Run.Builder rb = moved.toBuilder();
            rb.signature = q.get().signature();                                // computed as the runner now: the result is read with this signature
            rb.node = node;
            rb.leaseUntil = clock.instant().plus(props.lease().ttl());
            if (adopted) {
                rb.reason = again;
            }
            moved = rb.build();
            Flight f = new Flight(r.fingerprint(), q.get(), moved);
            f.background = true;
            f.visible = true;
            byRun.put(r.id(), f);
            records.save(moved);
            publish(moved);
            tell(moved, String.format(told, title(moved)));
            queue.offer(f, r.runner());
            return;
        }
        String why = before >= props.restart().maxAttempts() && (wasRunning || adopted)
                ? "the server stopped while this ran, " + r.attempt() + " times; it may be too large for this server"
                : failedWhy;
        Run failed = r.moveTo(RunState.FAILED, ErrorCode.RUN_INTERRUPTED.code(), why, clock.instant());
        records.save(failed);
        publish(failed);
        tell(failed, "Run failed: " + title(failed) + " (" + ErrorCode.RUN_INTERRUPTED.code() + ": " + failedTold + ")");
    }

    /**
     * A run that waited for its confirmation at a restart (BIR-08, section 12.1): it keeps waiting, with a new token (the owner's drawer has it; the run
     * object carries it) until the end of its {@code confirm.ttl} from when it was asked. Past that, or when its question cannot be asked again as its runner,
     * it is cancelled quietly ({@code DRS-9083}, the reason in words) with no bell: nothing ran and nobody confirmed, so nothing failed.
     */
    private void keepAwaiting(Run r) {
        Instant now = clock.instant();
        Instant by = (r.submittedAt() == null ? now : r.submittedAt()).plus(props.confirmTtl());
        Optional<Question> q = r.state() == RunState.AWAITING_CONFIRM && now.isBefore(by) ? replay.of(r) : Optional.empty();
        if (q.isEmpty()) {
            String why = r.state() == RunState.AWAITING_CONFIRM && !now.isBefore(by)
                    ? "not confirmed within " + props.confirmTtl().toMinutes() + " min"
                    : "the confirmation lapsed when the server restarted; nothing ran";
            Run cancelled = r.moveTo(RunState.CANCELLED, ErrorCode.RUN_NOT_DONE.code(), why, now);
            records.save(cancelled);
            publish(cancelled);
            return;
        }
        Run.Builder rb = r.toBuilder();
        rb.signature = q.get().signature();
        rb.node = node;
        rb.leaseUntil = now.plus(props.lease().ttl());
        Run kept = rb.build();
        Flight f = new Flight(r.fingerprint(), q.get(), kept);
        f.background = true;
        f.visible = true;
        f.confirmToken = Long.toHexString(random.nextLong()) + Long.toHexString(random.nextLong());
        f.confirmBy = by;
        byRun.put(r.id(), f);
        records.save(kept);
        publish(kept);
    }

    // ---- the cluster: leases and adoption (section 13.3) --------------------------------------------------------------

    /**
     * Renews the lease of every visible unfinished run this node holds; a run whose row another node holds now (it adopted it while this node was taken
     * for dead) is abandoned here: its work stops and nothing of it is saved. The runs abandoned.
     */
    public int renewLeases() {
        int abandoned = 0;
        Instant until = clock.instant().plus(props.lease().ttl());
        for (Flight f : List.copyOf(byRun.values())) {
            if (!f.visible || f.run.state().finished() || !node.equals(f.run.node())) {
                continue;
            }
            if (records.renew(f.run.id(), node, until)) {
                f.lock.lock();
                try {
                    Run.Builder b = f.run.toBuilder();
                    b.leaseUntil = until;
                    f.run = b.build();
                } finally {
                    f.lock.unlock();
                }
                continue;
            }
            Optional<Run> row = records.get(f.run.id());
            if (row.isPresent() && !node.equals(row.get().node())) {
                abandoned++;
                f.cancelReason = "adopted by " + row.get().node();
                queue.remove(f);
                Thread t = f.thread;
                if (t != null) {
                    t.interrupt();
                }
                f.done.cancel(false);
                byKey.remove(f.key, f);
                byRun.remove(f.run.id(), f);
                LOG.info("BI runs: run {} is held by node {} now; this node abandons its copy", f.run.id(), row.get().node());
            }
        }
        return abandoned;
    }

    /**
     * Adopts the runs of nodes that are gone (section 13.3, a shared identity database): a run in queued, running or storing whose node is not alive and
     * whose lease lapsed, and whose model this node owns now, is taken by one conditional update (exactly one node wins) and started again here under the
     * rules of 12.1. The runs adopted.
     */
    public int adoptOrphans(java.util.function.Predicate<String> alive, java.util.function.Function<String, String> ownerOf) {
        int n = 0;
        Instant now = clock.instant();
        for (Run r : records.inStates(RunRecords.LEASED, 500)) {
            if (node.equals(r.node()) || alive.test(r.node()) || r.leaseUntil() == null || !r.leaseUntil().isBefore(now)) {
                continue;
            }
            String key = ("mq".equals(r.sourceType()) || "sql".equals(r.sourceType())) && r.sourceId() != null ? r.sourceId() : r.id();
            if (!node.equals(ownerOf.apply(key))) {
                continue;
            }
            String why = "started again on " + node + " after " + r.node() + " stopped";
            if (!records.adopt(r.id(), r.node(), node, now, why)) {
                continue;                                                       // another node won, or the old one renewed in time
            }
            Run adopted = records.get(r.id()).orElse(null);
            if (adopted == null) {
                continue;
            }
            n++;
            LOG.info("BI runs: run {} of node {} adopted by {} (attempt {})", r.id(), r.node(), node, adopted.attempt());
            resume(adopted, false, true, why, r.node() + " stopped while this run " + (r.state() == RunState.QUEUED ? "waited" : "ran")
                    + ", and it could not be started again here", "Your run %s was " + why, r.node() + " stopped");
        }
        return n;
    }

    private volatile Thread sweeper;

    /** Sweeps the result store at start and every {@code retention.sweep} (section 8.4); removed results turn their runs {@code expired} when opened. */
    public void startSweeper() {
        Duration every = props.retention().sweep();
        sweeper = Thread.ofVirtual().name("bi-runs-sweeper").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    List<String> removed = store.sweep(clock.instant());
                    if (!removed.isEmpty()) {
                        LOG.info("BI runs: the sweeper removed {} result(s) past retention or the budget", removed.size());
                    }
                    Thread.sleep(every.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException e) {
                    LOG.warn("BI runs: the sweep failed: {}", e.getMessage());
                }
            }
        });
    }

    /** Stops the workers and the sweeper (shutdown); unfinished runs are recovered at the next start. */
    public void close() {
        queue.close();
        Thread t = sweeper;
        if (t != null) {
            t.interrupt();
        }
    }
}
