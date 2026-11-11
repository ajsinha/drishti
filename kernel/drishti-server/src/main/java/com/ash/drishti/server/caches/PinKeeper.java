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
package com.ash.drishti.server.caches;

import com.ash.drishti.common.BusinessCalendar;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An engine's pinned dates at work (VAR_GUIDE.md section 26.8; VaR and exposure each have one): which dates are pinned now (from the
 * newest business date, the calendars, the fiscal year-end and the stress window in use), and keeping them ready. After an official
 * record, a {@code disk} date without a hot file has one built in the background (one at a time, on a low-priority thread), never trimmed
 * afterwards; a {@code memory} date is opened and stays open (protected from eviction, counted within {@code hot-dates}).
 */
public final class PinKeeper implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(PinKeeper.class);

    /** What the engine offers the pins: its newest date, its stress window, whether a date is built or open, and how to build or open it. */
    public interface Engine {
        LocalDate newest();

        List<LocalDate> stressWindow();

        boolean onDisk(LocalDate d);

        boolean open(LocalDate d);

        /** Builds the date's hot file and closes it again (the file stays). */
        void build(LocalDate d);

        /** Opens the date (it stays: a memory pin is protected). */
        void openNow(LocalDate d);
    }

    private final String engine;
    private final PinnedDates.Settings settings;
    private final MonthDay fiscalEnd;
    private final Supplier<BusinessCalendar> calendar;
    private final Engine on;
    private final Map<LocalDate, String> state = new ConcurrentHashMap<>();
    private final java.util.concurrent.ExecutorService worker;
    private volatile Map.Entry<String, List<PinnedDates.Pin>> memo;

    /**
     * @param engine the engine's name in the log ({@code VaR}, {@code exposure})
     * @param settings the rules
     * @param fiscalYearEnd {@code MM-DD} or blank
     * @param calendar the business-date calendars ({@code drishti.business-date.calendar})
     */
    public PinKeeper(String engine, PinnedDates.Settings settings, String fiscalYearEnd, Supplier<BusinessCalendar> calendar, Engine on) {
        this.engine = engine;
        this.settings = settings;
        this.fiscalEnd = PinnedDates.fiscalYearEnd(fiscalYearEnd);
        this.calendar = calendar;
        this.on = on;
        this.worker = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "drishti-" + engine.toLowerCase(java.util.Locale.ROOT) + "-pins");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
    }

    /** The pinned dates now, newest first (none when no rule is on or there is no business date yet). */
    public List<PinnedDates.Pin> pins() {
        if (settings.off()) {
            return List.of();
        }
        LocalDate newest = on.newest();
        if (newest == null) {
            return List.of();
        }
        List<LocalDate> sw = settings.stressWindow().back() > 0 ? on.stressWindow() : List.of();
        BusinessCalendar cal = calendar.get();
        String key = newest + "|" + sw + "|" + cal.name();
        Map.Entry<String, List<PinnedDates.Pin>> m = memo;
        if (m != null && m.getKey().equals(key)) {
            return m.getValue();
        }
        List<PinnedDates.Pin> ps = PinnedDates.of(settings, cal, newest, fiscalEnd, sw);
        memo = Map.entry(key, ps);
        return ps;
    }

    /** Every pinned date (its hot file is never trimmed). */
    public Set<LocalDate> all() {
        return pins().stream().map(PinnedDates.Pin::date).collect(Collectors.toUnmodifiableSet());
    }

    /** The dates pinned in memory (protected from eviction, counted within hot-dates). */
    public Set<LocalDate> memory() {
        return pins().stream().filter(PinnedDates.Pin::memory).map(PinnedDates.Pin::date).collect(Collectors.toUnmodifiableSet());
    }

    /** After an official record (and at start): the pinned dates not ready are made ready in the background, one at a time. */
    public void afterOfficial() {
        List<PinnedDates.Pin> ps = pins();
        for (PinnedDates.Pin p : ps) {
            LocalDate d = p.date();
            boolean ready = p.memory() ? on.open(d) : on.onDisk(d) || on.open(d);
            if (ready || "queued".equals(state.get(d)) || "building".equals(state.get(d))) {
                continue;
            }
            state.put(d, "queued");
            worker.submit(() -> make(p));
        }
    }

    private void make(PinnedDates.Pin p) {
        LocalDate d = p.date();
        state.put(d, "building");
        long t0 = System.nanoTime();
        try {
            if (p.memory()) {
                on.openNow(d);
            } else {
                on.build(d);
            }
            state.put(d, (p.memory() ? "opened" : "built") + " in " + (System.nanoTime() - t0) / 1_000_000 + " ms");
            LOG.info("{}: pinned date {} ({}) {}", engine, d, String.join(", ", p.reasons()), state.get(d));
        } catch (RuntimeException e) {
            state.put(d, "not built: " + e.getMessage());
            LOG.info("{}: pinned date {} not made ready: {}", engine, d, e.getMessage());
        }
    }

    /** What the status and Admin show: each pinned date, why, how it is kept and its state. */
    public Map<String, Object> describe() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fiscalYearEnd", fiscalEnd == null ? "" : fiscalEnd.toString().substring(2));
        out.put("calendar", calendar.get().name());
        out.put("rules", rules());
        List<Map<String, Object>> dates = new ArrayList<>();
        for (PinnedDates.Pin p : pins()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("businessDate", p.date().toString());
            m.put("reasons", p.reasons());
            m.put("keep", p.keep().word());
            boolean open = on.open(p.date());
            m.put("open", open);
            m.put("hotFile", on.onDisk(p.date()));
            m.put("state", state.getOrDefault(p.date(), open ? "open" : on.onDisk(p.date()) ? "built" : "not built yet"));
            dates.add(m);
        }
        out.put("dates", dates);
        return out;
    }

    private Map<String, Object> rules() {
        Map<String, Object> r = new LinkedHashMap<>();
        Function<PinnedDates.Rule, Object> f = x -> Map.of("back", x.back(), "keep", x.keep());
        r.put("month-end", f.apply(settings.monthEnd()));
        r.put("quarter-end", f.apply(settings.quarterEnd()));
        r.put("year-end", f.apply(settings.yearEnd()));
        r.put("fiscal-year-end", f.apply(settings.fiscalYearEnd()));
        r.put("stress-window", f.apply(settings.stressWindow()));
        r.put("dates", Map.of("dates", settings.dates(), "keep", settings.datesKeep()));
        return r;
    }

    @Override
    public void close() {
        worker.shutdownNow();
    }
}
