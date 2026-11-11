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
package com.ash.drishti.bicompute.live;

import com.ash.drishti.bicompute.core.PivotCore;
import com.ash.drishti.kernel.api.pivot.PivotView;
import com.ash.drishti.bicompute.core.ScaleOptions;
import com.ash.drishti.kernel.api.pivot.Viewer;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Live pivots (HOW_IT_FITS.md section A26.7, item 5; phase P3): a pivot view kept current. The host tells the engine when a source's data moved
 * ({@link #signal}: a data load for a business date, a live table's update); every live view on that source and date is computed again
 * through the one {@link PivotCore} (the partial-state cache keeps every set whose date and data version did not move, so only the touched
 * dates' sets, or a refreshed rollup's states, are read again), the new answer compared with the last one cell by cell, and only the
 * difference pushed as a {@code patch} ({@link AnswerPatch}); a change of the rows or columns themselves (a new member, a member gone) is a
 * {@code reshape} carrying the whole answer. Nothing is computed for a signal of another date or source.
 *
 * <p>Limits, each refused in words ({@code DRS-5721}): the feature's switch, live views per person and per node, the cells a live view keeps
 * current, the rows an exact recompute may read (a larger view may be live only when it asked ≈), and a view pinned to a past version
 * ({@code asOf}, which never changes). Signals are coalesced: at most one recompute per view per {@code min-interval}, the latest data
 * wins. A live recompute is never sampled and never ≈ unless the view asked ≈ ({@link ScaleOptions#live}). A view nobody streams is ended
 * after {@code idle-seconds}. Thread-safe.
 */
public final class LivePivots implements AutoCloseable {

    /**
     * The limits ({@code drishti.pivot.live}).
     *
     * @param enabled false: no live pivots on this node
     * @param maxPerPerson live views one person may hold
     * @param maxTotal live views this node holds
     * @param minIntervalMs the least time between two recomputes of one view (signals in between are coalesced)
     * @param maxCells the most visible cells a live view keeps current
     * @param maxRows the most rows an exact live recompute may read (estimated); a larger view is live only when it asked ≈ (0: no limit)
     * @param idleSeconds a live view with no stream reading it is ended after this
     */
    public record Settings(boolean enabled, int maxPerPerson, int maxTotal, long minIntervalMs, int maxCells, long maxRows, int idleSeconds) {

        public static Settings defaults() {
            return new Settings(true, 5, 200, 1_000, 5_000, 50_000_000L, 60);
        }
    }

    /**
     * Data moved.
     *
     * @param source the pivot source (a table, {@code panel/<kind>/<panel>/<id>}), or a kind whose document sources move with it
     * @param dates the business dates touched (empty: every date)
     * @param cause {@code load}, {@code live}, {@code refresh}
     * @param detail in words, for the patch ("a load of trade for 2026-10-30")
     */
    public record Signal(String source, Set<String> dates, String cause, String detail) {
        public Signal {
            dates = dates == null ? Set.of() : Set.copyOf(dates);
        }

        /** A data load of a source for one business date. */
        public static Signal load(String source, String date) {
            return new Signal(source, date == null ? Set.of() : Set.of(date), "load", "a load of " + source + (date == null ? "" : " for " + date));
        }

        /** A live table's update (every date it shows). */
        public static Signal live(String source) {
            return new Signal(source, Set.of(), "live", "an update of " + source);
        }

        /** True when a view of {@code viewSource} answered on {@code answerDate} ({@code follows}: the view names no date) is touched. */
        boolean touches(String viewSource, String answerDate, boolean follows) {
            boolean same = viewSource.equals(source) || viewSource.startsWith(source) && viewSource.length() > source.length()
                    && !Character.isLetterOrDigit(viewSource.charAt(source.length())) && viewSource.charAt(source.length()) != '_';
            if (!same) {
                return false;
            }
            return dates.isEmpty() || follows || answerDate == null || dates.contains(answerDate);
        }
    }

    /** An event for the stream: {@code answer}, {@code patch}, {@code reshape}, {@code status}, {@code error}, {@code ended}. */
    public record Event(String name, Map<String, Object> data) {}

    /** Called before the views touched by a signal are computed again (the host refreshes the rollups of that source and date). */
    @FunctionalInterface
    public interface Before {
        void before(Signal s);
    }

    /** One live view. */
    static final class Live {
        final String id;
        final Viewer viewer;
        final PivotView view;
        final ScaleOptions options;
        final Instant since;
        final Set<Signal> pending = new LinkedHashSet<>();
        /** One recompute of a view at a time, so patches leave in order. */
        final Object running = new Object();
        volatile Map<String, Object> last;
        volatile Consumer<Event> sink;
        volatile long lastRun;
        volatile long detachedAt;
        final AtomicLong seq = new AtomicLong();
        final AtomicLong recomputes = new AtomicLong();
        final AtomicLong unchanged = new AtomicLong();
        volatile boolean scheduled;
        volatile String failure;

        Live(String id, Viewer viewer, PivotView view, ScaleOptions options, Instant since, long now) {
            this.id = id;
            this.viewer = viewer;
            this.view = view;
            this.options = options;
            this.since = since;
            this.detachedAt = now;
        }
    }

    private final PivotCore core;
    private final Clock clock;
    private final ScheduledExecutorService timer;
    private final Map<String, Live> lives = new ConcurrentHashMap<>();
    private volatile Settings settings;
    private volatile Before before = s -> {};

    public LivePivots(PivotCore core, Settings settings, Clock clock) {
        this.core = core;
        this.settings = settings == null ? Settings.defaults() : settings;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "drishti-pivot-live");
            t.setDaemon(true);
            return t;
        });
    }

    public Settings settings() {
        return settings;
    }

    public void settings(Settings s) {
        this.settings = s == null ? Settings.defaults() : s;
    }

    /** What runs before a touched view is computed again. */
    public void before(Before b) {
        this.before = b == null ? s -> {} : b;
    }

    private static DrishtiException refused(String words) {
        return new DrishtiException(ErrorCode.PIVOT_LIVE, words);
    }

    /**
     * Starts a live view for {@code v}: refused in words past a limit; answered with its id, its first answer (computed as a live recompute
     * is: exact unless ≈ was asked) and its limits.
     */
    public Map<String, Object> subscribe(Viewer v, PivotView view, ScaleOptions asked) {
        Settings s = settings;
        if (!s.enabled()) {
            throw refused("live pivots are switched off on this server (drishti.pivot.live.enabled)");
        }
        if (view.asOf() != null && !view.asOf().isBlank()) {
            throw refused("a view pinned to a past version (asOf " + view.asOf() + ") never changes: it cannot be live");
        }
        long mine = lives.values().stream().filter(l -> l.viewer.user().equals(v.user())).count();
        if (mine >= s.maxPerPerson()) {
            throw refused(v.user() + " already keeps " + mine + " live pivots, the most one person may (drishti.pivot.live.max-per-person): "
                    + "stop one first");
        }
        if (lives.size() >= s.maxTotal()) {
            throw refused("this server keeps " + lives.size() + " live pivots, its most (drishti.pivot.live.max-total): ask again later");
        }
        ScaleOptions o = (asked == null ? ScaleOptions.AUTO : asked).live();
        if (!o.approximate() && s.maxRows() > 0) {
            Object rows = core.estimate(v, view).get("rows");
            if (rows instanceof Number n && n.longValue() > s.maxRows()) {
                throw refused(String.format(Locale.ROOT, "a live pivot is computed again exactly on every change; this view reads ≈%,d rows, more "
                        + "than %,d (drishti.pivot.live.max-rows): narrow it, or ask ≈ for a live view of its sketches", n.longValue(), s.maxRows()));
            }
        }
        Map<String, Object> first = core.query(v, view, o);
        int cells = first.get("cellCount") instanceof Number n ? n.intValue() : 0;
        if (cells > s.maxCells()) {
            throw refused("a live pivot keeps at most " + s.maxCells() + " cells current (drishti.pivot.live.max-cells); this view has " + cells
                    + ": filter it or collapse a level");
        }
        long now = clock.millis();
        Live l = new Live("lp-" + UUID.randomUUID().toString().substring(0, 12), v, view, o, clock.instant(), now);
        l.last = first;
        l.lastRun = now;
        lives.put(l.id, l);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", l.id);
        out.put("answer", first);
        out.put("options", o.toMap());
        out.put("limits", Map.of("minIntervalMs", s.minIntervalMs(), "maxCells", s.maxCells(), "idleSeconds", s.idleSeconds()));
        out.put("words", "live: computed again when " + view.source() + (view.date() == null ? "" : " for " + view.date())
                + " changes, at most once every " + s.minIntervalMs() + " ms; " + (o.approximate() ? "≈ as asked" : "exact"));
        return out;
    }

    /** The live view {@code id} of {@code user}, refused when it is not theirs or not here. */
    private Live own(String user, String id) {
        Live l = id == null ? null : lives.get(id);
        if (l == null || !l.viewer.user().equals(user)) {
            throw new DrishtiException(ErrorCode.PIVOT_NOT_FOUND, "no live pivot " + id + " of " + user + " on this server (ended, idle, or "
                    + "the server restarted): start it again");
        }
        return l;
    }

    /** A stream reads the live view: its current answer first, then its patches. A second stream replaces the first. */
    public void attach(String user, String id, Consumer<Event> sink) {
        Live l = own(user, id);
        l.sink = sink;
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("id", l.id);
        a.put("seq", l.seq.get());
        a.put("answer", l.last);
        deliver(l, new Event("answer", a));
    }

    /** The stream went away: the view stays for {@code idle-seconds} so a reconnect finds it. */
    public void detach(String user, String id, Consumer<Event> sink) {
        Live l = lives.get(id);
        if (l != null && l.viewer.user().equals(user) && l.sink == sink) {
            l.sink = null;
            l.detachedAt = clock.millis();
        }
    }

    /** Ends a live view of {@code user}; true when there was one. */
    public boolean end(String user, String id) {
        Live l = lives.get(id);
        if (l == null || !l.viewer.user().equals(user)) {
            return false;
        }
        lives.remove(id);
        deliver(l, new Event("ended", Map.of("id", id, "why", "stopped")));
        return true;
    }

    /** The caller's live views, without their figures. */
    public List<Map<String, Object>> list(String user) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Live l : lives.values()) {
            if (l.viewer.user().equals(user)) {
                out.add(describe(l));
            }
        }
        out.sort((a, b) -> String.valueOf(a.get("since")).compareTo(String.valueOf(b.get("since"))));
        return out;
    }

    /** How many live views this node holds. */
    public int size() {
        return lives.size();
    }

    /** The sources at least one live view reads (the host keeps a live table's subscription only while one does). */
    public Set<String> sources() {
        Set<String> s = new java.util.TreeSet<>();
        lives.values().forEach(l -> s.add(l.view.source()));
        return s;
    }

    private Map<String, Object> describe(Live l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.id);
        m.put("source", l.view.source());
        m.put("date", l.last == null ? l.view.date() : l.last.get("date"));
        m.put("since", l.since.toString());
        m.put("options", l.options.toMap());
        m.put("seq", l.seq.get());
        m.put("recomputes", l.recomputes.get());
        m.put("unchanged", l.unchanged.get());
        m.put("streaming", l.sink != null);
        if (l.failure != null) {
            m.put("failure", l.failure);
        }
        return m;
    }

    /** Data moved: every live view it touches is computed again (coalesced, at most once per {@code min-interval}). */
    public int signal(Signal s) {
        int touched = 0;
        for (Live l : lives.values()) {
            Object date = l.last == null ? null : l.last.get("date");
            if (!s.touches(l.view.source(), date == null ? null : String.valueOf(date), l.view.date() == null)) {
                continue;
            }
            touched++;
            synchronized (l) {
                l.pending.add(s);
                if (l.scheduled) {
                    continue;
                }
                l.scheduled = true;
            }
            long wait = Math.max(0, l.lastRun + settings.minIntervalMs() - clock.millis());
            timer.schedule(() -> Thread.ofVirtual().name("drishti-pivot-live-" + l.id).start(() -> recompute(l)), wait, TimeUnit.MILLISECONDS);
        }
        return touched;
    }

    /** Computes the view again now (also what a signal schedules): the patch, a reshape or nothing when no cell moved. */
    public Event recomputeNow(String user, String id) {
        Live l = own(user, id);
        synchronized (l) {
            l.scheduled = true;
        }
        return recompute(l);
    }

    private Event recompute(Live l) {
        synchronized (l.running) {
            return recomputeInOrder(l);
        }
    }

    private Event recomputeInOrder(Live l) {
        List<Signal> signals;
        synchronized (l) {
            signals = new ArrayList<>(l.pending);
            l.pending.clear();
            l.scheduled = false;
        }
        if (!lives.containsKey(l.id)) {
            return null;
        }
        try {
            for (Signal s : signals) {
                before.before(s);
            }
            Map<String, Object> fresh = core.query(l.viewer, l.view, l.options);
            l.lastRun = clock.millis();
            l.recomputes.incrementAndGet();
            int cells = fresh.get("cellCount") instanceof Number n ? n.intValue() : 0;
            if (cells > settings.maxCells()) {
                lives.remove(l.id);
                Event e = new Event("ended", Map.of("id", l.id, "why", "the view now has " + cells + " cells, more than a live pivot keeps current ("
                        + settings.maxCells() + "); open it again as a plain view"));
                deliver(l, e);
                return e;
            }
            Map<String, Object> patch = AnswerPatch.diff(l.last, fresh);
            l.failure = null;
            l.last = fresh;
            Map<String, Object> data = new LinkedHashMap<>(patch);
            data.put("id", l.id);
            data.put("at", clock.instant().toString());
            data.put("causes", signals.stream().map(Signal::detail).distinct().toList());
            String kind = String.valueOf(patch.get("kind"));
            if ("none".equals(kind)) {
                l.unchanged.incrementAndGet();
                data.put("seq", l.seq.get());
                Event e = new Event("status", data);
                deliver(l, e);
                return e;
            }
            data.put("seq", l.seq.incrementAndGet());
            Event e = new Event("reshape".equals(kind) ? "reshape" : "patch", data);
            deliver(l, e);
            return e;
        } catch (RuntimeException ex) {
            l.failure = ex.getMessage();
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("id", l.id);
            err.put("error", ex.getMessage());
            if (ex instanceof DrishtiException de) {
                err.put("code", de.errorCode().code());
            }
            Event e = new Event("error", err);
            deliver(l, e);
            return e;
        }
    }

    private void deliver(Live l, Event e) {
        Consumer<Event> s = l.sink;
        if (s == null) {
            return;
        }
        try {
            s.accept(e);
        } catch (RuntimeException ex) {
            if (l.sink == s) {                                // the person went away: the view waits for a reconnect
                l.sink = null;
                l.detachedAt = clock.millis();
            }
        }
    }

    /** The heartbeat (the host's scheduler): a {@code status} to every stream, and views nobody streamed for {@code idle-seconds} ended. */
    public void heartbeat() {
        long now = clock.millis();
        for (Live l : lives.values()) {
            if (l.sink == null) {
                if (now - l.detachedAt > settings.idleSeconds() * 1000L) {
                    lives.remove(l.id);
                }
                continue;
            }
            deliver(l, new Event("status", describe(l)));
        }
    }

    /** Every live view ended (the engine switched off). */
    public void clear() {
        for (Live l : new ArrayList<>(lives.values())) {
            lives.remove(l.id);
            deliver(l, new Event("ended", Map.of("id", l.id, "why", "the pivot engine stopped on this server")));
        }
    }

    @Override
    public void close() {
        clear();
        timer.shutdownNow();
    }
}
