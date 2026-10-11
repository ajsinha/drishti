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
    private record Job(QueryService.Loaded ds, String table, String schedule) {

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
        final java.util.concurrent.atomic.AtomicLong runs = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean();

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
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("drishti-bi-refresh").factory());

    public DatasetRefresher(QueryService queries, Supplier<List<QueryService.Loaded>> published, BiRefreshProperties props, RetryPolicy retry, BusinessDates dates,
            Clock clock, AuditLog audit, boolean schedule) {
        this.queries = queries;
        this.published = published;
        this.props = props;
        this.retry = retry;
        this.dates = dates;
        this.clock = clock;
        this.audit = audit;
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
        } else if (!now.isBefore(st.nextRun)) {
            st.nextRun = next(sch, now);
            start(j, st, sch, "schedule", sources());
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
        st.status = "running";
        st.trigger = trigger;
        Instant started = clock.instant();
        try {
            QueryService.Refreshed r = queries.refresh(j.ds(), j.tables(), date);
            st.lastRun = started;
            st.millis = r.millis();
            st.rows = r.rows();
            st.date = r.date();
            st.tables = r.tables();
            st.failures.set(0);
            st.retryAt = null;
            st.error = r.warnings().isEmpty() ? null : String.join("; ", r.warnings());
            st.status = "ok";
            st.runs.incrementAndGet();
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
                st.status = "retrying";
            } else {
                st.retryAt = null;
                st.status = "failed";
            }
            LOG.warn("BI refresh {} failed (attempt {}): {}", j.key(), failed, st.error);
        } finally {
            st.running.set(false);
        }
    }

    /** Refreshes the dataset's layers for the current business date on a background thread and returns at once; the state shows how it went. */
    public Map<String, Object> runNow(QueryService.Loaded ds, String actor) {
        Job j = new Job(ds, null, ds.dataset().refresh().scheduled() ? ds.dataset().refresh().schedule() : "on demand");
        State st = states.compute(j.key(), (k, old) -> old != null ? old : new State(j.schedule(), null));
        if (st.running.compareAndSet(false, true)) {
            Thread.ofVirtual().name("drishti-bi-refresh-" + j.key()).start(() -> {
                run(j, st, "manual (" + actor + ")", sources());
            });
        }
        return st.toMap();
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
                ds.dataset().tables().values().stream().filter(t -> j.table() == null || t.name().equals(j.table())).forEach(t -> kinds.add(t.kind()));
                if (!kinds.contains(kind)) {
                    continue;
                }
                State st = states.compute(j.key(), (k, old) -> old != null && old.schedule.equals(j.schedule()) ? old : new State(j.schedule(), old));
                if (st.running.get()) {
                    continue;
                }
                st.status = "waiting";
                ticker.schedule(() -> {
                    if (st.running.compareAndSet(false, true)) {
                        Thread.ofVirtual().name("drishti-bi-refresh-" + j.key()).start(() -> run(j, st, "after-load " + kind, businessDate));
                    }
                }, props.loadDelay().toMillis(), TimeUnit.MILLISECONDS);
            }
        }
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
