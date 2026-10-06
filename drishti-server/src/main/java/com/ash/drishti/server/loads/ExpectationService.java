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
package com.ash.drishti.server.loads;

import com.ash.drishti.common.BusinessCalendar;
import com.ash.drishti.engine.time.BusinessDates;
import com.ash.drishti.packs.Pack;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What each pack expects to land, by when, and whether it did. For every expected kind it looks at today (when today is a business day
 * of the kind's calendar) and at the last few business days: {@code pending} (not due yet), {@code on-time}, {@code landed-late},
 * {@code late} (due today and nothing ready yet), {@code missing} (a past day nothing ready ever landed for), or {@code failed} (the
 * newest announcement says the load failed). A check, every {@code check-interval}, tells the people a pack names the first time a load
 * is late or missing, and clears the flag when the load lands (the load itself clears it too). The clock is injected so tests drive it.
 */
public final class ExpectationService implements AutoCloseable {

    public static final String PENDING = "pending";
    public static final String ON_TIME = "on-time";
    public static final String LANDED_LATE = "landed-late";
    public static final String LATE = "late";
    public static final String MISSING = "missing";
    public static final String FAILED = "failed";

    /** One expectation on one business date. */
    public record State(String pack, String kind, LocalDate businessDate, String by, String zone, Instant deadline, String state, String loadId,
            Instant landedAt) {
        /** True when someone should look: late, missing, or a load that failed. */
        public boolean attention() {
            return LATE.equals(state) || MISSING.equals(state) || FAILED.equals(state);
        }

        /** The one line shown on Health and in the late notice. */
        public String line() {
            return "data " + (FAILED.equals(state) ? "load failed" : state) + ": " + pack + "/" + kind + " " + businessDate;
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(ExpectationService.class);

    private final LoadService loads;
    private final LoadNotifier notifier;
    private final BusinessDates dates;
    private final Clock clock;
    private ScheduledExecutorService timer;

    public ExpectationService(LoadService loads, LoadNotifier notifier, BusinessDates dates, Clock clock) {
        this.loads = loads;
        this.notifier = notifier;
        this.dates = dates;
        this.clock = clock;
    }

    /** Starts the periodic check (a daemon thread). */
    public synchronized ExpectationService start() {
        if (timer == null) {
            long every = Math.max(1, loads.properties().checkInterval().toMillis());
            timer = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "drishti-load-expectations");
                t.setDaemon(true);
                return t;
            });
            timer.scheduleWithFixedDelay(this::safeCheck, every, every, TimeUnit.MILLISECONDS);
        }
        return this;
    }

    private void safeCheck() {
        try {
            check(clock.instant());
        } catch (RuntimeException e) {
            LOG.warn("the data-load expectation check failed: {}", e.getMessage());
        }
    }

    /** Every expectation of one pack, newest date first. */
    public List<State> states(Pack pack, Instant now) {
        LoadsConfig cfg = loads.configs().effective(pack);
        List<State> out = new ArrayList<>();
        new TreeMap<>(cfg.expect()).forEach((kind, e) -> out.addAll(states(pack.name(), kind, e, now)));
        out.sort(Comparator.comparing(State::businessDate).reversed().thenComparing(State::kind));
        return out;
    }

    private List<State> states(String pack, String kind, LoadsConfig.Expect e, Instant now) {
        ZoneId zone = e.zone().isEmpty() ? dates.zone() : ZoneId.of(e.zone());
        BusinessCalendar cal = e.calendar().isEmpty() ? dates.calendar() : BusinessCalendar.of(e.calendar());
        LocalDate today = LocalDate.ofInstant(now, zone);
        List<LocalDate> days = new ArrayList<>();
        if (cal.isBusinessDay(today)) {
            days.add(today);
        }
        LocalDate d = today;
        for (int i = 0; i < loads.properties().lookbackDays(); i++) {
            d = cal.previous(d);
            days.add(d);
        }
        List<State> out = new ArrayList<>();
        for (LocalDate day : days) {
            Instant deadline = day.atTime(e.time()).atZone(zone).toInstant();
            var latest = loads.store().latest(pack, kind, day);
            var ready = loads.store().latestReady(pack, kind, day);
            String state;
            Instant landed = null;
            String id = null;
            if (latest.isPresent() && LoadRecord.FAILED.equals(latest.get().status())) {
                state = FAILED;
                id = latest.get().id();
            } else if (ready.isPresent()) {
                landed = ready.get().receivedAt();
                id = ready.get().id();
                state = landed.isAfter(deadline) ? LANDED_LATE : ON_TIME;
            } else {
                state = now.isBefore(deadline) ? PENDING : day.equals(today) ? LATE : MISSING;
            }
            out.add(new State(pack, kind, day, e.by(), zone.getId(), deadline, state, id, landed));
        }
        return out;
    }

    /** Everything that needs attention now, across the loaded packs. */
    public List<State> attention(Instant now) {
        List<State> out = new ArrayList<>();
        for (Pack p : loads.loadedPacks()) {
            states(p, now).stream().filter(State::attention).forEach(out::add);
        }
        return out;
    }

    /**
     * One pass: tells about each late or missing load once, and clears the flag of those that landed. Returns the loads newly flagged.
     */
    public List<State> check(Instant now) {
        List<State> flagged = new ArrayList<>();
        for (Pack p : loads.loadedPacks()) {
            LoadsConfig cfg = loads.configs().effective(p);
            for (State s : states(p, now)) {
                if (LATE.equals(s.state()) || MISSING.equals(s.state())) {
                    if (loads.store().flagLate(p.name(), s.kind(), s.businessDate(), now)) {
                        flagged.add(s);
                        String what = s.kind() + " for " + s.businessDate() + (LATE.equals(s.state()) ? " is LATE" : " is MISSING") + ": expected by " + s.by()
                                + " " + s.zone();
                        notifier.tell(cfg, p.name(), s.kind(), s.businessDate(), LoadNotifier.TYPE_LATE, what,
                                "late:" + p.name() + ":" + s.kind() + ":" + s.businessDate(), "drishti");
                    }
                } else if (ON_TIME.equals(s.state()) || LANDED_LATE.equals(s.state())) {
                    loads.store().clearLate(p.name(), s.kind(), s.businessDate());
                }
            }
        }
        return flagged;
    }

    /** The late flags per pack (what Health shows), for the page. */
    public Map<String, Instant> flags(String pack) {
        return loads.store().lateFlags(pack);
    }

    @Override
    public synchronized void close() {
        if (timer != null) {
            timer.shutdownNow();
        }
    }
}
