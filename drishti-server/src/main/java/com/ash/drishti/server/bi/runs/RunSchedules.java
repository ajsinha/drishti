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
import com.ash.drishti.common.BusinessCalendar;
import com.ash.drishti.server.dist.SubscriptionSchedule;
import com.ash.drishti.server.security.Principal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scheduled runs (docs/architecture/BI_RUNS.md sections 9.2 and 14.1): a run's question asked again on a {@link SubscriptionSchedule} ({@code daily 07:30},
 * {@code business-days 07:30}, {@code weekly mon 08:00}, {@code monthly last 18:00 business}), as its owner, checked again at every firing: an owner who
 * lost access gets a failed run with the code, never someone else's rights. Each firing is an ordinary visible background run (confirmed in advance:
 * the owner confirmed when scheduling). Rows in {@code drishti_bi_run_schedule}.
 */
public final class RunSchedules {

    private static final Logger LOG = LoggerFactory.getLogger(RunSchedules.class);

    /** One schedule. {@code data}: {@code latest} (the newest data at each firing). */
    public record Schedule(String id, String owner, String runId, String title, String question, String params, String sourceType, String sourceId,
                           String schedule, String data, boolean enabled, String lastRun, Instant nextAt, Instant createdAt) {

        Schedule with(String text, boolean on, String last, Instant next) {
            return new Schedule(id, owner, runId, title, question, params, sourceType, sourceId, text, data, on, last, next, createdAt);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("runId", runId);
            m.put("title", title);
            m.put("source", Map.of("type", sourceType, "id", sourceId == null ? "" : sourceId));
            m.put("schedule", schedule);
            m.put("data", data);
            m.put("enabled", enabled);
            m.put("lastRun", lastRun);
            m.put("nextAt", nextAt == null ? null : nextAt.toString());
            m.put("createdAt", createdAt == null ? null : createdAt.toString());
            return m;
        }
    }

    /** Where schedules are kept. */
    public interface Store {
        void save(Schedule s);

        Optional<Schedule> get(String id);

        void delete(String id);

        List<Schedule> all();

        /**
         * Takes a due schedule for this node: writes {@code next} only while the stored schedule is still {@code due} (a store every node shares
         * lets exactly one node fire it); true when this node took it.
         */
        default boolean claim(Schedule due, Schedule next) {
            save(next);
            return true;
        }
    }

    /** In memory: tests and a server without an identity database. */
    public static final class Memory implements Store {
        private final Map<String, Schedule> rows = new ConcurrentHashMap<>();

        @Override
        public void save(Schedule s) {
            rows.put(s.id(), s);
        }

        @Override
        public Optional<Schedule> get(String id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public void delete(String id) {
            rows.remove(id);
        }

        @Override
        public List<Schedule> all() {
            return rows.values().stream().sorted(Comparator.comparing(Schedule::createdAt)).toList();
        }
    }

    /** The {@code drishti_bi_run_schedule} table. */
    public static final class Jdbc implements Store {
        private static final String COLUMNS = "id, owner, run_id, title, question, params, source_type, source_id, schedule, data, enabled, last_run, next_at,"
                + " created_at";
        private final DataSource ds;

        public Jdbc(DataSource ds) {
            this.ds = ds;
        }

        @Override
        public void save(Schedule s) {
            try (Connection c = ds.getConnection()) {
                try (PreparedStatement del = c.prepareStatement("DELETE FROM drishti_bi_run_schedule WHERE id = ?")) {
                    del.setString(1, s.id());
                    del.executeUpdate();
                }
                try (PreparedStatement in = c.prepareStatement("INSERT INTO drishti_bi_run_schedule (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    Object[] v = {s.id(), s.owner(), s.runId(), s.title(), s.question(), s.params(), s.sourceType(), s.sourceId(), s.schedule(), s.data(),
                            s.enabled() ? 1 : 0, s.lastRun(), s.nextAt() == null ? null : Timestamp.from(s.nextAt()), Timestamp.from(s.createdAt())};
                    for (int i = 0; i < v.length; i++) {
                        if (v[i] == null) {
                            in.setNull(i + 1, Types.NULL);
                        } else if (v[i] instanceof Timestamp t) {
                            in.setTimestamp(i + 1, t);
                        } else if (v[i] instanceof Integer n) {
                            in.setInt(i + 1, n);
                        } else {
                            in.setString(i + 1, String.valueOf(v[i]));
                        }
                    }
                    in.executeUpdate();
                }
            } catch (SQLException e) {
                throw new IllegalStateException("could not save the run schedule " + s.id() + ": " + e.getMessage(), e);
            }
        }

        private List<Schedule> query(String where, String arg) {
            List<Schedule> out = new ArrayList<>();
            try (Connection c = ds.getConnection();
                    PreparedStatement st = c.prepareStatement("SELECT " + COLUMNS + " FROM drishti_bi_run_schedule" + (where == null ? "" : " WHERE " + where)
                            + " ORDER BY created_at")) {
                if (arg != null) {
                    st.setString(1, arg);
                }
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        Timestamp next = rs.getTimestamp("next_at");
                        Timestamp created = rs.getTimestamp("created_at");
                        out.add(new Schedule(rs.getString("id"), rs.getString("owner"), rs.getString("run_id"), rs.getString("title"), rs.getString("question"),
                                rs.getString("params"), rs.getString("source_type"), rs.getString("source_id"), rs.getString("schedule"), rs.getString("data"),
                                rs.getInt("enabled") != 0, rs.getString("last_run"), next == null ? null : next.toInstant(),
                                created == null ? Instant.EPOCH : created.toInstant()));
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("could not read run schedules: " + e.getMessage(), e);
            }
            return out;
        }

        @Override
        public Optional<Schedule> get(String id) {
            return query("id = ?", id).stream().findFirst();
        }

        @Override
        public void delete(String id) {
            try (Connection c = ds.getConnection(); PreparedStatement st = c.prepareStatement("DELETE FROM drishti_bi_run_schedule WHERE id = ?")) {
                st.setString(1, id);
                st.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("could not delete the run schedule " + id + ": " + e.getMessage(), e);
            }
        }

        @Override
        public List<Schedule> all() {
            return query(null, null);
        }
    }

    private final RunService runs;
    private final Store store;
    private final RunService.Replay replayAs;
    private final ZoneId zone;
    private final BusinessCalendar calendar;
    private final Clock clock;
    private final SplittableRandom random = new SplittableRandom();
    private volatile Thread ticker;

    /**
     * @param replayAs rebuilds a run's question as its owner <em>now</em> (their current rights); empty when they may no longer ask it
     */
    public RunSchedules(RunService runs, Store store, RunService.Replay replayAs, ZoneId zone, BusinessCalendar calendar, Clock clock) {
        this.runs = runs;
        this.store = store;
        this.replayAs = replayAs;
        this.zone = zone == null ? ZoneId.of("UTC") : zone;
        this.calendar = calendar;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    private SubscriptionSchedule parse(String text) {
        SubscriptionSchedule s;
        try {
            s = SubscriptionSchedule.parse(text);
        } catch (IllegalArgumentException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, e.getMessage());
        }
        if (s.isAfterLoad()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a run is scheduled at a time (daily 07:30, business-days 07:30, weekly mon 08:00, monthly last 18:00), "
                    + "not after-load");
        }
        return s;
    }

    private Instant next(String text, Instant after) {
        return parse(text).next(after.atZone(zone), zone, calendar).toInstant();
    }

    /** Schedules a run's question for its owner. */
    public Schedule create(Run r, Principal owner, String text, String data) {
        if (!"latest".equals(data == null ? "latest" : data)) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a scheduled run reads the newest data at each firing: data is latest");
        }
        Instant now = clock.instant();
        String id = "rs-" + Long.toHexString(now.toEpochMilli()) + Long.toHexString(random.nextLong() & 0xffffffL);
        Schedule s = new Schedule(id, owner.user(), r.id(), RunService.title(r), r.question(), r.params(), r.sourceType(), r.sourceId(), text.strip(),
                "latest", true, null, next(text, now), now);
        store.save(s);
        return s;
    }

    public List<Schedule> of(String owner) {
        return store.all().stream().filter(s -> s.owner().equals(owner)).toList();
    }

    /** The owner's schedule of a run (the run object's {@code schedule}), else null. */
    public Map<String, Object> ofRun(String runId, String owner) {
        return store.all().stream().filter(s -> runId.equals(s.runId()) && s.owner().equals(owner)).findFirst().map(Schedule::toMap).orElse(null);
    }

    private Schedule mine(String id, String owner) {
        return store.get(id).filter(s -> s.owner().equals(owner))
                .orElseThrow(() -> new DrishtiException(ErrorCode.RUN_NOT_FOUND, "no run schedule '" + id + "' of yours"));
    }

    /** Changes the schedule text or turns it on or off. */
    public Schedule change(String id, String owner, String text, Boolean enabled) {
        Schedule s = mine(id, owner);
        String t = text == null || text.isBlank() ? s.schedule() : text.strip();
        boolean on = enabled == null ? s.enabled() : enabled;
        Schedule n = s.with(t, on, s.lastRun(), on ? next(t, clock.instant()) : s.nextAt());
        store.save(n);
        return n;
    }

    public void delete(String id, String owner) {
        mine(id, owner);
        store.delete(id);
    }

    /** Fires every enabled schedule that is due: its question asked as its owner now, a visible background run. The runs started. */
    public List<String> fireDue() {
        Instant now = clock.instant();
        List<String> started = new ArrayList<>();
        for (Schedule s : store.all()) {
            if (!s.enabled() || s.nextAt() == null || s.nextAt().isAfter(now)) {
                continue;
            }
            if (!store.claim(s, s.with(s.schedule(), s.enabled(), s.lastRun(), next(s.schedule(), now)))) {
                continue;                                                       // another node fired it
            }
            String last;
            try {
                Run probe = Run.planned(RunId.next(runs.node(), now, random), s.owner(), s.owner(), s.sourceType(), s.question(), "", "", Tier.AD_HOC_HEAVY,
                        "local", "{}", runs.node(), now);
                Run.Builder b = probe.toBuilder();
                b.title = s.title();
                Optional<RunService.Question> q = replayAs.of(b.build());
                if (q.isEmpty()) {
                    last = "failed: " + ErrorCode.QUERY_MASKED.code() + " " + s.owner() + " may no longer ask this question";
                    runs.tellAbout(s.owner(), probe, "Scheduled run failed: " + s.title() + " (" + ErrorCode.QUERY_MASKED.code() + ": you may no longer ask it)");
                } else {
                    RunService.Outcome o = runs.submit(q.get(), new RunService.Options(Duration.ZERO, true, true));
                    if (o.inline()) {
                        runs.records().save(o.run());
                    }
                    last = o.run().id();
                    started.add(last);
                }
            } catch (RuntimeException e) {
                String code = e instanceof DrishtiException d ? d.errorCode().code() : "error";
                last = "failed: " + code + " " + e.getMessage();
                LOG.info("BI runs: the schedule {} of {} could not start: {}", s.id(), s.owner(), e.getMessage());
            }
            store.save(s.with(s.schedule(), s.enabled(), last, next(s.schedule(), now)));
        }
        return started;
    }

    /** Checks the schedules every {@code every} on a virtual thread. */
    public void start(Duration every) {
        ticker = Thread.ofVirtual().name("bi-run-schedules").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(every.toMillis());
                    fireDue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException e) {
                    LOG.warn("BI runs: the schedules could not be checked: {}", e.getMessage());
                }
            }
        });
    }

    public void close() {
        Thread t = ticker;
        if (t != null) {
            t.interrupt();
        }
    }

    /** For a principal lookup by name (the owner as they are now). */
    public static RunService.Replay asOwner(Function<String, Principal> principals, java.util.function.BiFunction<Run, Principal, Optional<RunService.Question>> replay) {
        return r -> replay.apply(r, principals.apply(r.runner()));
    }
}
