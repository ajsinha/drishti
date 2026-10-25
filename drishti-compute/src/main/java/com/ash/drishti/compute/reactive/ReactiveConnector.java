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

import com.ash.drishti.api.compute.ComputeRef;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.compute.ComputeEvent;
import com.ash.drishti.api.compute.EngineInputs;
import com.ash.drishti.api.compute.EngineRequest;
import com.ash.drishti.api.compute.EngineResult;
import com.ash.drishti.api.compute.Viewer;
import com.ash.drishti.compute.ComputeException;
import com.ash.drishti.compute.ComputeRequest;
import com.ash.drishti.compute.RequestClass;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One reactive connector at work (REACTIVE_CONNECTORS.md sections 6 to 11): every read of a computed kind goes through
 * {@link #answer}, which authorises the viewer, checks the parameters, pins the inputs' versions, makes the key (with the viewer's view
 * signature, so two people with different rights never share an answer), answers from the cache or joins the computation already under
 * way for the key (single flight), admits a new one by class and per-user limit, reads the inputs as the viewer, runs the engine with
 * progress on the requesters' event streams, validates and stamps the result, caches it and audits it. Triggers ({@link #event}) mark
 * results stale and recompute, coalesced and ordered by watermark, the ones somebody watches.
 */
public final class ReactiveConnector implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ReactiveConnector.class);

    /**
     * One read of a computed kind.
     *
     * @param kind the kind
     * @param ref the id with its parameters ({@link ComputeRef})
     * @param asOf the business date asked for
     * @param viewer who asks (null: nobody, refused)
     * @param cls interactive, event or batch
     * @param waitFor how long to wait for a result before answering {@code computing}
     */
    public record Ask(String kind, String ref, AsOf asOf, Viewer viewer, RequestClass cls, Duration waitFor) {}

    /** A computation under way for one key; every request that asks for the key meanwhile waits on it. */
    static final class Flight {
        final String key;
        final String base;
        final String id;
        final String user;
        final RequestClass cls;
        final CompletableFuture<ResultCache.Entry> done = new CompletableFuture<>();
        final Set<String> waiters = ConcurrentHashMap.newKeySet();
        volatile Map<String, Object> progress;
        volatile Runnable cancel = () -> { };

        Flight(String key, String base, String id, String user, RequestClass cls) {
            this.key = key;
            this.base = base;
            this.id = id;
            this.user = user;
            this.cls = cls;
        }
    }

    /** Somebody watching a result: the request id they listen to, and what they asked, to recompute it for their signature. */
    record Watch(String requestId, Ask ask, Prepared prepared) {}

    /** A read checked and resolved: the kind, its normalised parameters, the date, the sandbox, the pin, the keys. */
    record Prepared(ReactiveSpec.KindSpec kind, ComputeRef ref, Map<String, Object> params, AsOf at, Sandboxes.Sandbox sandbox,
            Map<String, String> versions, String pin, String signature, String base, String key) {}

    private final String name;
    private final ReactiveSpec spec;
    private final EngineRunner runner;
    private final ReactiveHub hub;
    private final ResultCache cache;
    private final SeenEvents seen;
    private final Coalescer<String> coalescer;
    private final Triggers triggers;
    private final Clock clock;
    private final Map<String, Flight> flights = new ConcurrentHashMap<>();
    private final Map<String, Flight> byRequest = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Watch>> watchers = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> inflight = new ConcurrentHashMap<>();
    private final Map<String, Integer> incrementalRuns = new ConcurrentHashMap<>();
    private final AtomicLong started = new AtomicLong();
    private final AtomicLong computations = new AtomicLong();
    private final AtomicLong joined = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final Map<String, Long> latencyMs = new ConcurrentHashMap<>();

    public ReactiveConnector(String name, ReactiveSpec spec, EngineRunner runner, ReactiveHub hub, ScheduledExecutorService scheduler, Clock clock) {
        this.name = name;
        this.spec = spec;
        this.runner = runner;
        this.hub = hub;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.cache = new ResultCache(spec.cacheResults(), spec.cacheTtl(), this.clock);
        this.seen = new SeenEvents(spec.limits().dedupeWindow(), 1_000_000, this.clock);
        this.coalescer = new Coalescer<>(spec.quiet(), spec.maxWait(), spec.limits().eventQueue(), scheduler, this::recompute, this.clock);
        this.triggers = new Triggers(this, spec, runner, scheduler, this.clock);
    }

    public String name() {
        return name;
    }

    public ReactiveSpec spec() {
        return spec;
    }

    ReactiveHost host() {
        return hub.host();
    }

    /** Starts the schedules; registers with the hub. */
    public ReactiveConnector start() {
        hub.register(this);
        triggers.start();
        return this;
    }

    // ------------------------------------------------------------------------------------------------------------ a read

    /** Answers one read of a computed kind; never throws: a refusal or a failure is an answer that says so. */
    public Answer answer(Ask ask) {
        String requestId = ComputeRequest.newId();
        Prepared p;
        try {
            p = prepare(ask);
        } catch (IllegalArgumentException | SecurityException e) {
            return Answer.refused(name, requestId, e.getMessage());
        }
        hub.events().open(requestId, ask.viewer().user(), () -> idle(requestId));
        watch(p.base(), new Watch(requestId, ask, p));
        Optional<ResultCache.Entry> hit = cache.get(p.key());          // a sandbox's key holds its owner and its version: private
        if (hit.isEmpty()) {                                            // a trigger's recompute on the same inputs, with the events since
            hit = cache.newest(p.base()).filter(e -> !e.stale() && e.key().startsWith(p.key() + "|wm:"));
        }
        if (hit.isPresent()) {
            return new Answer(Answer.READY, requestId, name, hit.get().document(), null, null);
        }
        Optional<String> off = runner.unavailable();
        if (off.isPresent()) {
            return last(p, Answer.REFUSED, requestId, off.get(), null);
        }
        Flight f;
        try {
            f = join(p, ask, requestId, null, List.of());
        } catch (ComputeException e) {
            return last(p, Answer.REFUSED, requestId, e.getMessage(), null);
        }
        return await(p, f, requestId, ask.waitFor());
    }

    /** Checks the read and resolves what it is about; refused in words. */
    Prepared prepare(Ask ask) {
        ReactiveSpec.KindSpec kind = spec.kind(ask.kind());
        if (kind == null) {
            throw new IllegalArgumentException(name + " does not compute " + ask.kind() + " (it computes " + String.join(", ", spec.kinds().keySet()) + ")");
        }
        Viewer v = ask.viewer();
        if (v == null) {
            throw new SecurityException(ask.kind() + " is computed for a person: this read has no viewer");
        }
        Optional<String> no = host().refusal(v, name, ask.kind());
        if (no.isPresent()) {
            throw new SecurityException(no.get());
        }
        ComputeRef ref = ComputeRef.parse(ask.ref());
        if (ref.id().isBlank()) {
            throw new IllegalArgumentException(ask.kind() + " needs an id (the scope it is computed for)");
        }
        Map<String, Object> params = kind.normalise(ref.params());
        Sandboxes.Sandbox sandbox = null;
        Optional<String> sp = kind.sandboxParam();
        if (sp.isPresent() && params.get(sp.get()) != null) {
            String sid = String.valueOf(params.get(sp.get()));
            sandbox = hub.sandboxes().get(v.user(), sid).orElseThrow(() -> new IllegalArgumentException("there is no sandbox " + sid));
            if (!sandbox.connector().equals(name)) {
                throw new IllegalArgumentException("sandbox " + sid + " is for " + sandbox.connector() + ", not " + name);
            }
        }
        LocalDate date = ask.asOf() == null || ask.asOf().businessDate() == null ? host().today() : ask.asOf().businessDate();
        if (!spec.dated() && ask.asOf() != null && ask.asOf().businessDate() != null && !date.equals(host().today())) {
            throw new IllegalArgumentException(name + " computes on current data only (history.dated: false)");
        }
        AsOf at = new AsOf(date, ask.asOf() == null ? null : ask.asOf().knownAt(), ask.asOf() == null || ask.asOf().live());
        Map<String, String> versions = new TreeMap<>();
        for (String k : spec.readKinds()) {
            versions.put(k, host().dataVersion(k, at).orElse("?"));
        }
        if (sandbox != null) {
            versions.put("sandbox", sandbox.id() + "@" + sandbox.version());
        }
        String pin = "pin-" + Results.sha(versions.toString() + "|" + date + "|" + at.knownAt()).substring(0, 16);
        String signature = v.viewSignature() + (sandbox == null ? "" : "|user:" + v.user());   // a sandbox's results are its owner's alone
        String canonical = ComputeRef.format(ref.id(), params);
        String base = String.join("|", name, kind.name(), canonical, date.toString(), String.valueOf(at.knownAt()), signature);
        String key = base + "|" + pin + "|" + runner.identity();
        return new Prepared(kind, ref, params, at, sandbox, versions, pin, signature, base, key);
    }

    /** The answer with the last good result of the base key, if there is one. */
    private Answer last(Prepared p, String state, String requestId, String message, Map<String, Object> progress) {
        Optional<ResultCache.Entry> e = cache.newest(p.base());
        String s = e.isPresent() && Answer.COMPUTING.equals(state) && e.get().stale() ? Answer.STALE : state;
        return new Answer(s, requestId, name, e.map(ResultCache.Entry::document).orElse(null), message, progress);
    }

    private Answer await(Prepared p, Flight f, String requestId, Duration wait) {
        try {
            ResultCache.Entry e = f.done.get(Math.max(0, wait.toMillis()), TimeUnit.MILLISECONDS);
            return new Answer(Answer.READY, requestId, name, e.document(), null, null);
        } catch (TimeoutException e) {
            return last(p, Answer.COMPUTING, requestId, null, f.progress);
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            return last(p, Answer.FAILED, requestId, words(c), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return last(p, Answer.COMPUTING, requestId, null, f.progress);
        }
    }

    static String words(Throwable c) {
        if (c instanceof ComputeException ce) {
            return switch (ce.code()) {
                case BUSY -> "busy: " + ce.getMessage() + "; try again in a moment";
                case TIMEOUT -> "the computation took too long (" + ce.getMessage() + ")";
                case CRASHED -> "the worker stopped while computing this (" + ce.getMessage() + ")";
                case LIMIT -> "the computation passed a limit: " + ce.getMessage();
                default -> ce.getMessage();
            };
        }
        return "the computation failed: " + c;
    }

    /**
     * Joins the computation of the key, or starts it: admitted by class and per-user limit (an interactive request over the limit is
     * refused as busy). {@code force} computes even if a result is cached (a trigger); {@code events} the events it reflects.
     */
    private Flight join(Prepared p, Ask ask, String requestId, ComputeEvent trigger, List<ComputeEvent> events) {
        Flight[] made = new Flight[1];
        Flight f = flights.computeIfAbsent(p.key(), k -> {
            AtomicInteger n = inflight.computeIfAbsent(ask.viewer().user(), u -> new AtomicInteger());
            if (ask.cls() == RequestClass.INTERACTIVE && n.get() >= spec.limits().maxInflightPerUser()) {
                throw new ComputeException(ComputeException.Code.BUSY, "busy: " + ask.viewer().user() + " already has " + n.get()
                        + " computations running on " + name + " (limits.max-inflight-per-user); try again in a moment");
            }
            n.incrementAndGet();
            made[0] = new Flight(k, p.base(), requestId, ask.viewer().user(), ask.cls());
            return made[0];
        });
        f.waiters.add(requestId);
        byRequest.put(requestId, f);
        if (made[0] == null) {
            joined.incrementAndGet();
            if (f.progress != null) {
                hub.events().publish(requestId, "progress", f.progress);
            }
        } else {
            Thread.ofVirtual().name("reactive-" + name + "-" + requestId).start(() -> run(f, p, ask, trigger, events));
        }
        return f;
    }

    private void run(Flight f, Prepared p, Ask ask, ComputeEvent trigger, List<ComputeEvent> events) {
        long t0 = System.nanoTime();
        long seq = started.incrementAndGet();
        computations.incrementAndGet();
        Watermark wm = Watermark.NONE.startedAt(seq);
        for (ComputeEvent e : events) {
            wm = wm.merge(e.watermark());
        }
        try {
            EngineInputs inputs = inputs(p, ask.viewer());
            EngineRequest req = new EngineRequest(f.id, name, p.kind().engineKind(), p.ref().id(), p.params(), p.at(), p.pin(), ask.viewer(),
                    ask.cls().label(), seed(p.key()), trigger);
            EngineResult result = incremental(p, req, events, inputs).orElse(null);
            if (result == null) {
                EngineRunner.Running r = runner.start(req, inputs, ask.cls(), spec.limits().budget(ask.cls()), data -> progress(f, data));
                f.cancel = r.cancel();
                result = r.result().join();
            }
            Results.validate(p.kind(), result, f.id);
            Instant at = clock.instant();
            Map<String, Object> doc = Results.document(p.kind().name(), req, result, p.versions(), wm, runner.identity(), at);
            ResultCache.Entry entry = new ResultCache.Entry(p.key(), p.base(), doc, wm, at, false);
            boolean kept = cache.put(entry);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            latencyMs.merge(p.kind().name(), ms, (a, b) -> (a * 7 + b) / 8);
            f.done.complete(entry);
            for (String w : f.waiters) {
                hub.events().publish(w, "done", Map.of("state", kept ? Answer.READY : Answer.STALE, "ms", ms, "stamp", doc.get("STAMP")));
            }
            Object hash = doc.get("STAMP") instanceof Map<?, ?> s ? s.get("resultHash") : null;
            host().audit(ask.viewer().user(), "compute.answer", name + "/" + p.kind().name() + "/" + p.ref().id(),
                    "request " + f.id + (f.waiters.size() > 1 ? " (+" + (f.waiters.size() - 1) + " joined)" : "") + ", params " + p.params()
                            + ", pin " + p.pin() + ", signature " + p.signature() + ", " + ms + " ms, hash " + hash
                            + (trigger == null ? "" : ", trigger " + trigger.id()));
            if (!kept) {
                LOG.debug("reactive: {} dropped a late result for {} (an older watermark than the one shown)", name, p.base());
            }
        } catch (RuntimeException | Error e) {
            Throwable c = e instanceof java.util.concurrent.CompletionException && e.getCause() != null ? e.getCause() : e;
            failures.incrementAndGet();
            if (!(c instanceof ComputeException)) {
                LOG.warn("reactive: {} failed computing {} for {} (request {})", name, p.kind().name(), p.ref().id(), f.id, c);
            }
            f.done.completeExceptionally(c);
            for (String w : f.waiters) {
                hub.events().publish(w, "failed", Map.of("message", words(c)));
            }
            host().audit(ask.viewer().user(), "compute.failed", name + "/" + p.kind().name() + "/" + p.ref().id(), "request " + f.id + ": " + words(c));
        } finally {
            flights.remove(f.key, f);
            AtomicInteger n = inflight.get(f.user);
            if (n != null) {
                n.decrementAndGet();
            }
            for (String w : f.waiters) {
                byRequest.remove(w, f);
            }
        }
    }

    private static long seed(String key) {
        return Results.sha(key).substring(0, 15).chars().asLongStream().reduce(17, (a, b) -> a * 31 + b);
    }

    private void progress(Flight f, Map<String, Object> data) {
        Map<String, Object> m = new LinkedHashMap<>(data == null ? Map.of() : data);
        f.progress = m;
        for (String w : f.waiters) {
            hub.events().publish(w, "progress", m);
        }
    }

    /**
     * The inputs of a read, read as the viewer through the normal routing, in the order {@code reads:} gives them (a later one may take its
     * ids from an earlier one: {@code {inputs.book.MEMBERS}}); too many is refused before anything is computed.
     */
    private EngineInputs inputs(Prepared p, Viewer viewer) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("id", p.ref().id());
        vars.put("params", p.params());
        Map<String, Object> earlier = new LinkedHashMap<>();
        vars.put("inputs", earlier);                                   // a later read may name ids an earlier one holds: {inputs.book.MEMBERS}
        Map<String, List<DataNode>> in = new LinkedHashMap<>();
        int total = 0;
        for (ReactiveSpec.ReadSpec r : spec.reads()) {
            List<String> ids = r.all() ? null : r.ids() != null ? Templates.many(r.ids(), vars) : Templates.many(r.id(), vars);
            if (ids != null && ids.size() > r.limit()) {
                throw new ComputeException(ComputeException.Code.REFUSED, "the input " + r.as() + " names " + ids.size() + " " + r.kind()
                        + " entities; at most " + r.limit());
            }
            List<DataNode> docs;
            try {
                docs = ids != null && ids.isEmpty() ? List.of() : host().read(viewer, r.kind(), ids, p.at(), r.limit());
            } catch (SecurityException e) {
                throw new ComputeException(ComputeException.Code.REFUSED, e.getMessage());
            } catch (Exception e) {
                throw new ComputeException(ComputeException.Code.UNAVAILABLE, "the input " + r.kind() + " could not be read: " + e.getMessage());
            }
            triggers.readLive(p.base(), r.kind(), ids);
            total += docs.size();
            if (total > spec.limits().maxInputRows()) {
                throw new ComputeException(ComputeException.Code.REFUSED, "the inputs hold more than " + spec.limits().maxInputRows()
                        + " records (limits.max-input-rows)");
            }
            in.put(r.as(), docs);
            earlier.put(r.as(), docs.size() == 1 ? docs.get(0).unwrap() : docs.stream().map(DataNode::unwrap).toList());
        }
        if (p.sandbox() != null) {
            in.put("sandbox", p.sandbox().items().stream().map(DataNode::of).toList());
        }
        return new EngineInputs(in);
    }

    /** An incremental update of the newest result when the kind allows it and events caused this computation. */
    private Optional<EngineResult> incremental(Prepared p, EngineRequest req, List<ComputeEvent> events, EngineInputs inputs) {
        if (!p.kind().incremental() || events.isEmpty()) {
            return Optional.empty();
        }
        Optional<ResultCache.Entry> prev = cache.newest(p.base());
        if (prev.isEmpty()) {
            return Optional.empty();
        }
        int n = incrementalRuns.merge(p.base(), 1, Integer::sum);
        if (spec.verifyEvery() > 0 && n % spec.verifyEvery() == 0) {
            return Optional.empty();                                 // every Nth: a full recompute checks the incremental ones
        }
        return runner.incremental(req, Results.result(prev.get().document()), events, inputs);
    }

    // ------------------------------------------------------------------------------------------------------------ requests

    /** Cancels a request of {@code user}; the computation stops only if nobody else waits on it. False when unknown or another's. */
    public boolean cancel(String requestId, String user) {
        Optional<String> owner = hub.events().owner(requestId);
        if (owner.isEmpty() || !owner.get().equals(user)) {
            return false;
        }
        Flight f = byRequest.remove(requestId);
        if (f != null) {
            f.waiters.remove(requestId);
            if (f.waiters.isEmpty() && !f.done.isDone()) {
                f.cancel.run();
            }
        }
        unwatch(requestId);
        return true;
    }

    /** The last listener of a request went: it no longer watches; an interactive computation nobody waits for stops. */
    private void idle(String requestId) {
        Flight f = byRequest.get(requestId);
        if (f != null && f.cls == RequestClass.INTERACTIVE && !f.done.isDone()) {
            f.waiters.remove(requestId);
            byRequest.remove(requestId, f);
            if (f.waiters.isEmpty()) {
                f.cancel.run();
            }
        }
        unwatch(requestId);
    }

    private void watch(String base, Watch w) {
        watchers.computeIfAbsent(base, b -> new ConcurrentHashMap<>()).put(w.requestId(), w);
    }

    private void unwatch(String requestId) {
        watchers.values().forEach(m -> m.remove(requestId));
        watchers.values().removeIf(Map::isEmpty);
    }

    /** The watches of a base key whose request somebody listens to now. */
    List<Watch> watching(String base) {
        Map<String, Watch> m = watchers.get(base);
        if (m == null) {
            return List.of();
        }
        List<Watch> out = new ArrayList<>();
        for (Watch w : m.values()) {
            if (hub.events().listeners(w.requestId()) > 0) {
                out.add(w);
            }
        }
        return out;
    }

    /** Every base key held or watched. */
    List<String> bases() {
        List<String> out = new ArrayList<>(cache.bases());
        watchers.keySet().stream().filter(b -> !out.contains(b)).forEach(out::add);
        return out;
    }

    // ------------------------------------------------------------------------------------------------------------ triggers

    /** An event (REACTIVE_CONNECTORS.md section 7): ignored when seen before; else what its triggers affect is marked stale and, where watched, recomputed. */
    public Map<String, Object> event(ComputeEvent e) {
        if (!seen.first(e.id())) {
            return Map.of("ignored", "already processed " + e.id());
        }
        List<String> affected = triggers.affected(e);
        int queued = 0;
        int stale = 0;
        for (String base : affected) {
            cache.markStale(b -> b.equals(base));
            stale++;
            List<Watch> ws = watching(base);
            for (Watch w : ws) {
                hub.events().publish(w.requestId(), "state", Map.of("state", Answer.STALE, "message", name + " is recomputing after " + e.type()
                        + (e.entity().isBlank() ? "" : " of " + e.entity())));
            }
            if (!ws.isEmpty()) {
                if (coalescer.offer(base, e)) {
                    queued++;
                }
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("affected", stale);
        m.put("recomputing", queued);
        return m;
    }

    /** One coalesced recompute of a base key for the events of a burst: for the first watcher's signature (all share it), then pushed. */
    private void recompute(String base, List<ComputeEvent> events) {
        List<Watch> ws = watching(base);
        if (ws.isEmpty()) {
            return;                                                   // nobody watches any more: it recomputes on its next read
        }
        Watch w = ws.get(0);
        Prepared p;
        try {
            Ask a = new Ask(w.ask().kind(), w.ask().ref(), w.ask().asOf(), w.ask().viewer(), RequestClass.EVENT, spec.limits().timeoutBatch());
            Prepared fresh = prepare(a);
            Watermark lanes = Watermark.NONE;
            for (ComputeEvent e : events) {
                lanes = lanes.merge(e.watermark());
            }
            String key = fresh.key() + "|wm:" + lanes.words() + "|" + events.get(events.size() - 1).id();
            p = new Prepared(fresh.kind(), fresh.ref(), fresh.params(), fresh.at(), fresh.sandbox(), fresh.versions(), fresh.pin(), fresh.signature(),
                    fresh.base(), key);
            String rid = ComputeRequest.newId();
            hub.events().open(rid, a.viewer().user(), null);
            Flight f = join(p, a, rid, events.get(events.size() - 1), events);
            ResultCache.Entry e = f.done.get(spec.limits().timeoutBatch().toMillis() + 1000, TimeUnit.MILLISECONDS);
            for (Watch x : watching(base)) {
                hub.events().publish(x.requestId(), "updated", Map.of("stamp", e.document().get("STAMP"), "events", events.size()));
            }
        } catch (Exception ex) {
            String why = ex instanceof ExecutionException ee ? words(ee.getCause()) : words(ex);
            for (Watch x : watching(base)) {
                hub.events().publish(x.requestId(), "state", Map.of("state", Answer.STALE, "message", "the recompute after " + events.get(events.size() - 1).id()
                        + " failed: " + why));
            }
        }
    }

    /** A schedule or version tick from {@link Triggers}. */
    void tick(ComputeEvent e) {
        event(e);
    }

    // ------------------------------------------------------------------------------------------------------------ admin

    /** Checks the data versions of the kinds the triggers watch (after a load signal); events for those that moved. */
    public void checkVersions() {
        triggers.checkVersions();
    }

    public void purgeCaches() {
        cache.clear();
    }

    /** Admin's view: the computation, the cache, coalescing, flights and latency. */
    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("engine", runner.identity());
        m.put("available", runner.unavailable().orElse("ready"));
        Map<String, Object> kinds = new LinkedHashMap<>();
        spec.kinds().forEach((k, ks) -> {
            Map<String, Object> d = new LinkedHashMap<>();
            Map<String, Object> ps = new LinkedHashMap<>();
            ks.params().forEach((pn, pp) -> ps.put(pn, pp.describe()));
            d.put("params", ps);
            d.put("incremental", ks.incremental());
            d.put("latencyMs", latencyMs.get(k));
            kinds.put(k, d);
        });
        m.put("kinds", kinds);
        m.put("reads", spec.readKinds());
        m.put("triggers", spec.triggers().stream().map(t -> t.on() + (t.kinds().isEmpty() ? "" : " " + t.kinds())).toList());
        m.put("cache", cache.stats());
        m.put("coalescing", coalescer.stats());
        m.put("eventsIgnored", seen.ignored());
        m.put("running", flights.size());
        m.put("computations", computations.get());
        m.put("joined", joined.get());
        m.put("failures", failures.get());
        m.put("watched", watchers.size());
        return m;
    }

    /** What the cache holds now, for tests and Admin. */
    ResultCache cache() {
        return cache;
    }

    @Override
    public void close() {
        hub.unregister(this);
        triggers.close();
        flights.values().forEach(f -> f.cancel.run());
        runner.close();
    }
}
