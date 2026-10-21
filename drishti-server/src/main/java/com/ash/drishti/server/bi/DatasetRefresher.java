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
package com.ash.drishti.server.bi;

import com.ash.drishti.common.RetryPolicy;
import com.ash.drishti.engine.time.BusinessDates;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.RefreshSpec;
import com.ash.drishti.server.bi.engine.BiRefreshProperties;
import com.ash.drishti.server.bi.engine.Layers;
import com.ash.drishti.server.bi.engine.QueryService;
import com.ash.drishti.server.dist.SubscriptionSchedule;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scheduled refresh of datasets (RUPAKA.md section 7). A published dataset's {@code refresh:} (and a table's {@code refresh.schedule}) says when its columns
 * and rollup layers are rebuilt: a clock ({@code daily 06:00}, {@code business-days 07:30} by the pack's calendar, {@code hourly}, a cron) or the data load
 * ({@code after-load}). The rebuild is {@link QueryService#refresh}: it runs on the caller's thread so a failure is seen here, and a failed run waits and tries
 * again by the shared retry policy ({@code drishti.retry.bi.*}) up to {@code drishti.bi.refresh.max-attempts}; a permanent-looking failure waits the policy's
 * maximum. The state of every job (last run, status, rows, duration, next run, failures) is kept in memory for the dataset page.
 */
public final class DatasetRefresher implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(DatasetRefresher.class);

    /** One thing that refreshes: a dataset as a whole ({@code table} null) or one of its tables. */
    private record Job(QueryService.Loaded ds, String table, String schedule, List<DatasetService.ModelGateway.Reader> readers) {

        Job(QueryService.Loaded ds, String table, String schedule) {
            this(ds, table, schedule, null);
        }

        String key() {
            return ds.id() + (table == null ? "" : "#" + table);
        }

        List<String> tables() {
            return table == null ? null : List.of(table);
        }
    }

    /** What is known of one job. */
    static final class State {
        final String schedule;
        volatile Instant nextRun;
        volatile Instant lastRun;
        volatile String status = "never";
        volatile String trigger = "";
        volatile String error;
        volatile long millis;
        volatile long rows;
        volatile String date;
        volatile List<String> tables = List.of();
        final java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger();
        volatile Instant retryAt;
        final java.util.concurrent.ConcurrentLinkedDeque<Map<String, Object>> history = new java.util.concurrent.ConcurrentLinkedDeque<>();
        final java.util.concurrent.atomic.AtomicLong runs = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean();
        /** The business dates signalled and not refreshed yet, and whether a refresh of them waits: one waits at most (QA3B-07). */
        final java.util.concurrent.ConcurrentSkipListMap<LocalDate, Set<String>> loaded = new java.util.concurrent.ConcurrentSkipListMap<>();
        final java.util.concurrent.atomic.AtomicBoolean waiting = new java.util.concurrent.atomic.AtomicBoolean();
        /** The status before "waiting", shown again when every date waiting was already fresh (nothing ran). */
        volatile String beforeWaiting;

        State(String schedule, State previous) {
            this.schedule = schedule;
            if (previous != null) {
                lastRun = previous.lastRun;
                status = previous.status;
                error = previous.error;
                millis = previous.millis;
                rows = previous.rows;
                date = previous.date;
                tables = previous.tables;
                runs.set(previous.runs.get());
                trigger = previous.trigger;
                history.addAll(previous.history);
            }
        }

        /** Remembers one run, newest last, keeping at most {@code keep}. */
        void remember(Map<String, Object> run, int keep) {
            history.addLast(run);
            while (history.size() > keep) {
                history.pollFirst();
            }
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("schedule", schedule);
            m.put("status", status);
            m.put("trigger", trigger);
            m.put("lastRun", lastRun == null ? null : lastRun.toString());
            m.put("nextRun", nextRun == null ? null : nextRun.toString());
            m.put("lastDurationMs", millis);
            m.put("lastRows", rows);
            m.put("lastDate", date);
            m.put("tables", tables);
            m.put("failures", failures.get());
            m.put("retryAt", retryAt == null ? null : retryAt.toString());
            m.put("error", error);
            m.put("runs", runs.get());
            List<Map<String, Object>> past = new ArrayList<>(history);
            java.util.Collections.reverse(past);
            m.put("history", past);
            return m;
        }
    }

    private final QueryService queries;
    private final Supplier<List<QueryService.Loaded>> published;
    private final BiRefreshProperties props;
    private final RetryPolicy retry;
    private final BusinessDates dates;
    private final Clock clock;
    private final AuditLog audit;
    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final java.nio.file.Path stateFile;
    private final java.util.concurrent.locks.ReentrantLock saving = new java.util.concurrent.locks.ReentrantLock(); // file I/O under it: not synchronized (virtual threads)
    private volatile FramedHooks framed = new FramedHooks() {
        @Override
        public Set<String> kinds(Dataset d) {
            return Set.of();
        }

        @Override
        public void refreshed(QueryService.Loaded ds) {
            // nothing is kept for a dataset without frames
        }
    };

    /**
     * What a composed or derived dataset adds to refresh (RUPAKA.md section 20.10): it keeps no layers of its own, so what it reads is the kinds of the datasets it
     * is built from (an {@code after-load} refresh follows their loads), and a refresh drops what was kept for it (a Transform's result).
     */
    public interface FramedHooks {

        /** The pack kinds the sources of a composed or derived dataset read, through any depth of composition. */
        Set<String> kinds(Dataset d);

        /** Called after a refresh of a composed or derived dataset: drop the results kept for it. */
        void refreshed(QueryService.Loaded ds);
    }

    public void attach(FramedHooks hooks) {
        this.framed = hooks;
    }
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("drishti-bi-refresh").factory());

    public DatasetRefresher(QueryService queries, Supplier<List<QueryService.Loaded>> published, BiRefreshProperties props, RetryPolicy retry, BusinessDates dates,
            Clock clock, AuditLog audit, boolean schedule) {
        this(queries, published, props, retry, dates, clock, audit, schedule, null);
    }

    /** @param stateFile where the state and history of every job are kept so they survive a restart; null keeps them in memory only */
    public DatasetRefresher(QueryService queries, Supplier<List<QueryService.Loaded>> published, BiRefreshProperties props, RetryPolicy retry, BusinessDates dates,
            Clock clock, AuditLog audit, boolean schedule, java.nio.file.Path stateFile) {
        this.stateFile = stateFile;
        this.queries = queries;
        this.published = published;
        this.props = props;
        this.retry = retry;
        this.dates = dates;
        this.clock = clock;
        this.audit = audit;
        load();
        if (schedule && props.enabled()) {
            long ms = props.tick().toMillis();
            ticker.scheduleWithFixedDelay(this::tick, ms, ms, TimeUnit.MILLISECONDS);
        }
    }

    // ---- the jobs ----------------------------------------------------------------------------------------------------

    private static List<Job> jobs(QueryService.Loaded ds) {
        List<Job> out = new ArrayList<>();
        if (ds.dataset().refresh().scheduled()) {
            out.add(new Job(ds, null, ds.dataset().refresh().schedule()));
        }
        for (Dataset.Table t : ds.dataset().tables().values()) {
            if (!t.schedule().isBlank() && "cached".equals(t.mode())) {
                out.add(new Job(ds, t.name(), RefreshSpec.normalise(t.schedule())));
            }
        }
        return out;
    }

    /** True when the dataset refreshes on its own at a load (so the plain on-load rebuild leaves it to the scheduler, which tracks and retries it). */
    public static boolean afterLoad(Dataset d) {
        return jobs(new QueryService.Loaded("", "", d, "")).stream().anyMatch(j -> parse(j.schedule()) != null && parse(j.schedule()).isAfterLoad());
    }

    private static SubscriptionSchedule parse(String text) {
        try {
            return RefreshSpec.parse(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ---- the clock ---------------------------------------------------------------------------------------------------

    public void tick() {
        try {
            Instant now = clock.instant();
            for (QueryService.Loaded ds : published.get()) {
                for (Job j : jobs(ds)) {
                    consider(j, now);
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("dataset refresh scheduler: {}", e.toString());
        }
    }

    private void consider(Job j, Instant now) {
        State st = states.compute(j.key(), (k, old) -> old != null && old.schedule.equals(j.schedule()) ? old : new State(j.schedule(), old));
        SubscriptionSchedule sch = parse(j.schedule());
        if (sch == null) {
            st.status = "invalid";
            st.error = "the refresh schedule '" + j.schedule() + "' is not one this server reads";
            return;
        }
        if (st.running.get()) {
            return;
        }
        if (st.retryAt != null) {
            if (!now.isBefore(st.retryAt)) {
                start(j, st, sch, "retry", sources());
            }
            return;
        }
        if (sch.isAfterLoad()) {
            return;
        }
        if (st.nextRun == null) {
            st.nextRun = next(sch, now);
            save();
        } else if (!now.isBefore(st.nextRun)) {
            Instant due = st.nextRun;
            st.nextRun = next(sch, now);
            start(j, st, sch, java.time.Duration.between(due, now).compareTo(props.tick().multipliedBy(2).plusMinutes(5)) > 0 ? "schedule (catch-up)" : "schedule", sources());
        }
    }

    private LocalDate sources() {
        return dates.current();
    }

    private Instant next(SubscriptionSchedule sch, Instant after) {
        ZonedDateTime n = sch.next(after.atZone(dates.zone()), dates.zone(), dates.calendar());
        return n == null ? null : n.toInstant();
    }

    // ---- running -----------------------------------------------------------------------------------------------------

    private void start(Job j, State st, SubscriptionSchedule sch, String trigger, LocalDate date) {
        if (!st.running.compareAndSet(false, true)) {
            return;
        }
        Thread.ofVirtual().name("drishti-bi-refresh-" + j.key()).start(() -> run(j, st, trigger, date));
    }

    /** Runs the job now on this thread. A failure is recorded (and a retry planned) rather than thrown. */
    private void run(Job j, State st, String trigger, LocalDate date) {
        run(j, st, trigger, date, true);
    }

    /** As above; {@code release}: the job's running flag is cleared at the end (false: the caller runs more dates and clears it). */
    private void run(Job j, State st, String trigger, LocalDate date, boolean release) {
        st.status = "running";
        st.trigger = trigger;
        Instant started = clock.instant();
        String ended = null;                                   // shown once the run is over (running cleared): a retry looks at both
        try {
            QueryService.Refreshed r = j.readers() == null ? queries.refreshInBackground(j.ds(), j.tables(), date) : across(j.readers(), date);
            if (j.ds().dataset() != null && j.ds().dataset().framed()) {
                framed.refreshed(j.ds());
            }
            st.lastRun = started;
            st.millis = r.millis();
            st.rows = r.rows();
            st.date = r.date();
            st.tables = r.tables();
            st.failures.set(0);
            st.retryAt = null;
            st.error = r.warnings().isEmpty() ? null : String.join("; ", r.warnings());
            boolean warned = r.warnings().stream().anyMatch(w -> w.startsWith("warning: "));
            ended = warned ? "warning" : "ok";
            st.runs.incrementAndGet();
            st.remember(entry(started, trigger, ended, r.rows(), r.millis(), r.date(), st.error), props.history());
            LOG.info("BI refresh {} {}: {} rows in {} ms ({})", j.key(), r.date(), r.rows(), r.millis(), trigger);
            audit.record("system", "bi-dataset-refresh", j.ds().id(), trigger + ": " + r.rows() + " rows of " + r.tables() + " for " + r.date());
        } catch (RuntimeException e) {
            st.lastRun = started;
            int failed = st.failures.incrementAndGet();
            st.error = e.getMessage() == null ? e.toString() : e.getMessage();
            st.date = date.toString();
            boolean permanent = RetryPolicy.looksPermanent(e);
            if (retry.enabled() && failed < props.maxAttempts()) {
                st.retryAt = clock.instant().plus(retry.delay(failed, permanent, RandomGenerator.getDefault().nextDouble()));
                ended = "retrying";
            } else {
                st.retryAt = null;
                ended = "failed";
            }
            st.remember(entry(started, trigger, ended, 0, 0, date.toString(), st.error), props.history());
            LOG.warn("BI refresh {} failed (attempt {}): {}", j.key(), failed, st.error);
        } finally {
            if (release) {
                st.running.set(false);
            }
            if (ended != null) {
                st.status = ended;
            }
            save();
        }
    }

    private static Map<String, Object> entry(Instant at, String trigger, String status, long rows, long millis, String date, String error) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("at", at.toString());
        e.put("trigger", trigger);
        e.put("status", status);
        e.put("rows", rows);
        e.put("durationMs", millis);
        e.put("date", date);
        e.put("error", error);
        return e;
    }

    // ---- keeping the state across a restart --------------------------------------------------------------------------

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /** Writes every job's state and history (atomically); a failure to write is logged, never thrown into a refresh. */
    private void save() {
        if (stateFile == null) {
            return;
        }
        saving.lock();
        try {
            try {
                Map<String, Object> jobs = new LinkedHashMap<>();
                states.forEach((k, s) -> jobs.put(k, s.toMap()));
                java.nio.file.Files.createDirectories(stateFile.getParent());
                java.nio.file.Path tmp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
                java.nio.file.Files.writeString(tmp, JSON.writeValueAsString(Map.of("saved", clock.instant().toString(), "jobs", jobs)), java.nio.charset.StandardCharsets.UTF_8);
                java.nio.file.Files.move(tmp, stateFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.io.IOException | RuntimeException e) {
                LOG.warn("BI refresh state could not be saved to {}: {}", stateFile, e.toString());
            }
        } finally {
            saving.unlock();
        }
    }

    /** Reads the saved state back: a run that was in progress when the server stopped is shown as interrupted. */
    private void load() {
        if (stateFile == null || !java.nio.file.Files.isRegularFile(stateFile)) {
            return;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root = JSON.readTree(java.nio.file.Files.readString(stateFile, java.nio.charset.StandardCharsets.UTF_8));
            root.path("jobs").fields().forEachRemaining(e -> {
                com.fasterxml.jackson.databind.JsonNode n = e.getValue();
                State s = new State(n.path("schedule").asText(""), null);
                s.lastRun = instant(n.path("lastRun"));
                s.nextRun = instant(n.path("nextRun"));
                s.retryAt = instant(n.path("retryAt"));
                s.status = n.path("status").asText("never");
                s.trigger = n.path("trigger").asText("");
                s.error = n.path("error").isNull() ? null : n.path("error").asText(null);
                s.millis = n.path("lastDurationMs").asLong();
                s.rows = n.path("lastRows").asLong();
                s.date = n.path("lastDate").isNull() ? null : n.path("lastDate").asText(null);
                List<String> tables = new ArrayList<>();
                n.path("tables").forEach(t -> tables.add(t.asText()));
                s.tables = tables;
                s.failures.set(n.path("failures").asInt());
                s.runs.set(n.path("runs").asLong());
                List<Map<String, Object>> past = new ArrayList<>();
                n.path("history").forEach(h -> past.add(JSON.convertValue(h, new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() { })));
                java.util.Collections.reverse(past);
                past.forEach(h -> {
                    h.replaceAll((k, v) -> v instanceof Integer i ? (Object) i.longValue() : v);
                    s.remember(h, props.history());
                });
                if ("running".equals(s.status) || "waiting".equals(s.status)) {
                    s.status = "interrupted";
                    s.error = "the server stopped before this refresh finished";
                }
                states.put(e.getKey(), s);
            });
        } catch (java.io.IOException | RuntimeException e) {
            LOG.warn("BI refresh state in {} could not be read, starting without it: {}", stateFile, e.toString());
        }
    }

    private static Instant instant(com.fasterxml.jackson.databind.JsonNode n) {
        try {
            return n.isTextual() ? Instant.parse(n.asText()) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Refreshes the dataset's layers for the current business date on a background thread and returns at once; the state shows how it went. */
    public Map<String, Object> runNow(QueryService.Loaded ds, String actor) {
        Job j = new Job(ds, null, ds.dataset().refresh().scheduled() ? ds.dataset().refresh().schedule() : "on demand");
        State st = states.compute(j.key(), (k, old) -> old != null ? old : new State(j.schedule(), null));
        if (st.running.compareAndSet(false, true)) {
            st.status = "running";
            st.trigger = "manual (" + actor + ")";
            Thread.ofVirtual().name("drishti-bi-refresh-" + j.key()).start(() -> run(j, st, "manual (" + actor + ")", sources()));
        }
        return st.toMap();
    }

    /**
     * Refreshes a dataset of the model layer ({@code id}, its item) through the models that read it: the tables of each that read the dataset are cleared and
     * rebuilt, on a background thread, and the state is kept under the dataset's id (what {@link #status(String)} shows).
     */
    public Map<String, Object> runNow(String id, List<DatasetService.ModelGateway.Reader> readers, String actor) {
        Job j = new Job(new QueryService.Loaded(id, Layers.key(id), null, ""), null, "on demand", List.copyOf(readers));
        State st = states.compute(j.key(), (k, old) -> old != null ? old : new State(j.schedule(), null));
        if (st.running.compareAndSet(false, true)) {
            st.status = "running";
            st.trigger = "manual (" + actor + ")";
            Thread.ofVirtual().name("drishti-bi-refresh-" + j.key()).start(() -> run(j, st, "manual (" + actor + ")", sources()));
        }
        return st.toMap();
    }

    /** One refresh over several models: the rows, tables and warnings added up, the latest date shown. */
    private QueryService.Refreshed across(List<DatasetService.ModelGateway.Reader> readers, LocalDate date) {
        long rows = 0;
        long millis = 0;
        String shown = date.toString();
        List<String> tables = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (DatasetService.ModelGateway.Reader r : readers) {
            QueryService.Refreshed one = queries.refreshInBackground(r.loaded(), r.tables(), date);
            queries.layers().bump(r.loaded().key());                                // a Python or staged table has no layer: its answers are asked again
            rows += one.rows();
            millis += one.millis();
            shown = one.date().compareTo(shown) > 0 ? one.date() : shown;
            one.tables().forEach(t -> tables.add(r.loaded().id() + "/" + t));
            warnings.addAll(one.warnings());
        }
        return new QueryService.Refreshed(shown, tables, rows, millis, warnings.stream().distinct().toList());
    }

    /** The refresh state of a dataset of the model layer: on demand, with the last run kept under its id. */
    public Map<String, Object> status(String id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", props.enabled());
        m.put("scheduled", false);
        m.put("zone", dates.zone().getId());
        m.put("calendar", dates.calendar().name());
        State own = states.get(id);
        m.put("dataset", own == null ? new LinkedHashMap<>(Map.of("schedule", "on demand", "status", "never")) : own.toMap());
        m.put("tables", List.of());
        return m;
    }

    /** A data load finished ready: every {@code after-load} job whose tables read the kind runs shortly after, for the date loaded. */
    public void onLoad(String kind, LocalDate businessDate) {
        if (!props.enabled()) {
            return;
        }
        for (QueryService.Loaded ds : published.get()) {
            for (Job j : jobs(ds)) {
                SubscriptionSchedule sch = parse(j.schedule());
                if (sch == null || !sch.isAfterLoad() || sch.loadKind() != null && !sch.loadKind().equalsIgnoreCase(kind)) {
                    continue;
                }
                Set<String> kinds = new java.util.HashSet<>();
                if (ds.dataset().framed()) {
                    kinds.addAll(framed.kinds(ds.dataset()));
                } else {
                    ds.dataset().tables().values().stream().filter(t -> j.table() == null || t.name().equals(j.table())).forEach(t -> kinds.add(t.kind()));
                }
                if (!kinds.contains(kind)) {
                    continue;
                }
                State st = states.compute(j.key(), (k, old) -> old != null && old.schedule.equals(j.schedule()) ? old : new State(j.schedule(), old));
                st.loaded.computeIfAbsent(businessDate, d -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(kind);
                if (!st.waiting.compareAndSet(false, true)) {
                    continue;                                  // a refresh waits already: it takes this date too
                }
                if (!st.running.get()) {
                    st.beforeWaiting = st.status;
                    st.status = "waiting";
                }
                afterLoad(j, st, "after-load " + kind);
            }
        }
    }

    /**
     * The one waiting after-load refresh of a job: after {@code load-delay}, and after the refresh running ends, it refreshes each signalled date
     * once, skipping a date whose stored layers were built from the data the source holds now. A burst of signals (2,976 in QA3B-07) started
     * a refresh after each run before; one running and one waiting now, whatever the signals.
     */
    private void afterLoad(Job j, State st, String trigger) {
        ticker.schedule(() -> {
            if (!st.running.compareAndSet(false, true)) {
                afterLoad(j, st, trigger);                     // the running one ends first; this one keeps waiting
                return;
            }
            st.waiting.set(false);
            List<Map.Entry<LocalDate, Set<String>>> dates = new ArrayList<>();
            for (Map.Entry<LocalDate, Set<String>> d; (d = st.loaded.pollLastEntry()) != null; ) {
                dates.add(d);                                  // the newest first, with the kinds signalled for it
            }
            Thread.ofVirtual().name("drishti-bi-refresh-" + j.key()).start(() -> {
                try {
                    for (Map.Entry<LocalDate, Set<String>> d : dates) {
                        if (j.readers() == null && queries.fresh(j.ds(), j.tables(), d.getKey(), d.getValue())) {
                            continue;                          // the signalled kinds hold the same data as the layers: nothing to read again
                        }
                        run(j, st, trigger, d.getKey(), false);
                    }
                } finally {
                    if ("waiting".equals(st.status) && st.beforeWaiting != null) {
                        st.status = st.beforeWaiting;          // every date was fresh: nothing ran
                    }
                    st.running.set(false);
                }
            });
        }, props.loadDelay().toMillis(), TimeUnit.MILLISECONDS);
    }

    // ---- what the page shows -----------------------------------------------------------------------------------------

    /** The refresh state of a dataset: its own schedule and the schedules of its tables; {@code scheduled: false} when it has none. */
    public Map<String, Object> status(QueryService.Loaded ds) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Job> jobs = jobs(ds);
        m.put("enabled", props.enabled());
        m.put("scheduled", !jobs.isEmpty());
        m.put("zone", dates.zone().getId());
        m.put("calendar", dates.calendar().name());
        State own = states.get(ds.id());
        if (ds.dataset().refresh().scheduled() || own != null) {
            Map<String, Object> d = own == null ? new LinkedHashMap<>(Map.of("schedule", ds.dataset().refresh().schedule(), "status", "waiting for the scheduler")) : own.toMap();
            m.put("dataset", d);
        }
        List<Map<String, Object>> tables = new ArrayList<>();
        for (Job j : jobs) {
            if (j.table() != null) {
                State s = states.get(j.key());
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("table", j.table());
                t.putAll(s == null ? Map.of("schedule", j.schedule(), "status", "waiting for the scheduler") : s.toMap());
                tables.add(t);
            }
        }
        m.put("tables", tables);
        return m;
    }

    @Override
    public void close() {
        ticker.shutdownNow();
    }
}
