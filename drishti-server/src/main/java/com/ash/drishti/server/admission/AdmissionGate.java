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
package com.ash.drishti.server.admission;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.AuditLog;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Busy protection: the one gate in front of new heavy work (docs/admin/OPERATIONS.md, "Busy protection"; IMPLEMENTATION_PLAN.md 2.9.11
 * row 8). New sign-ins, date loads (VaR, exposure), Data view downloads, BI runs and bulk loads ask it first; when the server is short of
 * memory (the heap after the last collection), its load slots are full, the open dates' heap is well over its safety net, it answers too
 * many requests at once, or its disk is nearly full, the work is refused politely: {@code 503}, {@code Retry-After}, a {@code DRS-95xx}
 * code and a calm sentence, instead of risking an out-of-memory. People already signed in keep working: reading an open date is not
 * gated.
 *
 * <p>A refusal is remembered for its {@code Retry-After}, as the refusals for room are, so a crowd asking at once gets one decision and
 * one countdown; it is lifted sooner when its cause clears (memory back under the warn level, a load finished, disk freed, an operator's
 * hold ended). An operator may let everything through or hold all new heavy work, with a reason, for a number of minutes; both are
 * audited and end by themselves.
 */
public final class AdmissionGate {

    private static final long MB = 1024L * 1024;

    private static final Logger LOG = LoggerFactory.getLogger(AdmissionGate.class);
    private static final long GB = 1L << 30;

    /** The operator's override: automatic (the default), let everything through, hold all new heavy work. */
    public enum Mode {
        AUTOMATIC, OPEN, HOLD;

        public static Mode of(String s) {
            String x = s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replace('_', '-');
            return switch (x) {
                case "automatic", "auto" -> AUTOMATIC;
                case "open", "let-everything-through", "let everything through" -> OPEN;
                case "hold", "hold-all", "hold all new heavy work" -> HOLD;
                default -> throw new DrishtiException(ErrorCode.BAD_REQUEST, "mode is automatic, open (let everything through) or hold "
                        + "(hold all new heavy work), not '" + s + "'");
            };
        }

        String word() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Why work waits. */
    enum Cause { MEMORY, LOADS, OPEN_DATES, REQUESTS, DISK, HELD }

    /** An engine's date loads: its slots ({@code load-threads}) and the loads running or waiting now. */
    public static final class Loads {
        private final String engine;
        private final IntSupplier threads;
        private final AtomicInteger running = new AtomicInteger();

        Loads(String engine, IntSupplier threads) {
            this.engine = engine;
            this.threads = threads;
        }

        public int running() {
            return running.get();
        }

        public int threads() {
            return Math.max(1, threads.getAsInt());
        }
    }

    /** A load let in: closed when the load ends (loaded, refused or failed), which frees its slot. */
    public static final class Slot implements AutoCloseable {
        private final Loads loads;
        private final AtomicBoolean open = new AtomicBoolean(true);

        Slot(Loads loads) {
            this.loads = loads;
            if (loads != null) {
                loads.running.incrementAndGet();
            }
        }

        @Override
        public void close() {
            if (open.compareAndSet(true, false) && loads != null) {
                loads.running.decrementAndGet();
            }
        }
    }

    /** No gate (tests that make an engine themselves): a slot that counts nothing. */
    public static final Slot NO_SLOT = new Slot(null);

    private record OpenHeap(String engine, LongSupplier used, LongSupplier net) {}

    private record Why(Cause cause, String because) {}

    private record Remembered(Cause cause, String engine, String title, String because, long atMillis, long untilMillis) {}

    /** One refusal, for Health's table. */
    record Refused(long atMillis, String who, String what, String because, String code, long retryAfterSeconds) {}

    private record OperatorOverride(Mode mode, String reason, String by, long atMillis, long untilMillis) {}

    private final AdmissionProperties props;
    private final Signals signals;
    private final LongSupplier clock;
    private final AuditLog audit;
    private final Supplier<String> requester;
    private final Map<String, Loads> loads = new ConcurrentHashMap<>();
    private final List<OpenHeap> openHeaps = new CopyOnWriteArrayList<>();
    private final Map<String, Remembered> remembered = new ConcurrentHashMap<>();
    private final Deque<Refused> refusals = new ArrayDeque<>();
    private final AtomicInteger refusedTotal = new AtomicInteger();
    private volatile OperatorOverride override = new OperatorOverride(Mode.AUTOMATIC, "", "", 0, 0);
    private volatile long waitingSince;

    /**
     * @param props the levels
     * @param signals what is read
     * @param clock the time in milliseconds
     * @param audit where an operator's override is recorded (null: not recorded)
     * @param requester who is asking now (the signed-in user of the request; null or blank: the server itself)
     */
    public AdmissionGate(AdmissionProperties props, Signals signals, LongSupplier clock, AuditLog audit, Supplier<String> requester) {
        this.props = props;
        this.signals = signals;
        this.clock = clock;
        this.audit = audit;
        this.requester = requester == null ? () -> null : requester;
    }

    // ------------------------------------------------------------------------------------------------------------ the engines

    /** An engine's date loads join the gate (its {@code load-threads}); asked again, the same counter. */
    public Loads loads(String engine, IntSupplier threads) {
        return loads.computeIfAbsent(engine, e -> new Loads(e, threads));
    }

    /** Memory held off the heap under its own cap (the in-memory lake's stores): shown with the readings, never refused on. */
    private record OffHeap(String name, LongSupplier used, LongSupplier cap) {}

    private final List<OffHeap> offHeaps = new CopyOnWriteArrayList<>();

    /**
     * A memory store joins the readings (DATA_ARCHITECTURE.md section 7.6): its use against its cap is shown on Health's busy card. Its writes
     * are bounded by the store itself (the eviction ladder, then a refusal), so it never makes new work wait here.
     */
    public void memoryStore(String name, LongSupplier used, LongSupplier cap) {
        offHeaps.removeIf(o -> o.name().equals(name));
        offHeaps.add(new OffHeap(name, used, cap));
    }

    /** An engine's open dates' heap and its safety net join the gate. */
    public void openHeap(String engine, LongSupplier used, LongSupplier net) {
        openHeaps.removeIf(o -> o.engine().equals(engine));
        openHeaps.add(new OpenHeap(engine, used, net));
    }

    // ------------------------------------------------------------------------------------------------------------ admission

    /** New work of the request's user: let in, or {@link BusyRefusal}. */
    public void admit(Work work, String what) {
        admit(work, requester.get(), what);
    }

    /** New work of {@code who} (a sign-in names the user signing in): let in, or {@link BusyRefusal}. */
    public void admit(Work work, String who, String what) {
        decide(work, who, what, null);
    }

    /**
     * A date load of an engine: let in with a slot to close when the load ends, or {@link BusyRefusal}. A load the server starts by itself
     * (no request: a pinned date, a prefetch) takes a slot without waiting for one; memory and disk still apply.
     */
    public Slot admitLoad(Loads engine, String what) {
        decide(Work.LOAD, requester.get(), what, engine);
        return new Slot(engine);
    }

    private void decide(Work work, String who, String what, Loads engine) {
        long now = clock.getAsLong();
        String key = work.name() + (engine == null ? "" : "|" + engine.engine);
        Remembered r = remembered.get(key);
        boolean server = who == null || who.isBlank();
        if (r != null && !(server && r.cause() == Cause.LOADS)) {      // a load the server starts itself never waits for a slot
            if (now < r.untilMillis() && !cleared(r)) {
                throw refuse(work, who, what, r.title(), r.because(), (r.untilMillis() - now + 999) / 1000, now);
            }
            remembered.remove(key, r);
        }
        Why why = why(work, who, engine);
        if (why == null) {
            if (remembered.isEmpty() && waitingSince != 0) {
                waitingSince = 0;
                LOG.info("busy protection: new work is let in again");
            }
            return;
        }
        long wait = props.retryAfter().of(work);
        String title = title(work, what);
        remembered.put(key, new Remembered(why.cause(), engine == null ? null : engine.engine, title, why.because(), now, now + wait * 1000L));
        if (waitingSince == 0) {
            waitingSince = now;
            LOG.warn("busy protection: new {} waits: {} (Admin > Health, Busy protection)", work.label().toLowerCase(Locale.ROOT), why.because());
        }
        throw refuse(work, who, what, title, why.because(), wait, now);
    }

    /** Why {@code work} must wait now, or null. */
    private Why why(Work work, String who, Loads engine) {
        OperatorOverride o = override();
        if (o.mode() == Mode.OPEN) {
            return null;
        }
        if (o.mode() == Mode.HOLD && work.heavy()) {
            return new Why(Cause.HELD, "an operator is holding new heavy work until " + clockTime(o.untilMillis()) + " (" + o.reason() + ")");
        }
        if (!props.enabled()) {
            return null;
        }
        double mem = memoryShare();                                     // after the old generation's last clean-up (-1 none yet: 0)
        double memLevel = work == Work.SIGN_IN ? props.memoryRefuseSignIn() : props.memoryRefuseHeavy();
        if (mem >= memLevel && memoryFreeBytes() < props.memoryFreeMinMb() * MB) {
            signals.requestCleanup();                                   // a concurrent cycle, when the JVM can (never a full collection)
            return new Why(Cause.MEMORY, String.format(Locale.ROOT, "the server is short of memory: %.0f%% in use after clean-up (new %s waits over %.0f%%)",
                    mem * 100, work == Work.SIGN_IN ? "sign-ins" : "heavy work", memLevel * 100));
        }
        if (!work.heavy()) {
            return null;
        }
        if (engine != null && who != null && !who.isBlank()) {
            int limit = engine.threads() + props.queuedLoads();
            if (engine.running() >= limit) {
                return new Why(Cause.LOADS, engine.running() + " other dates are being opened right now; the server opens " + engine.threads()
                        + " at a time and lets " + props.queuedLoads() + " more wait");
            }
        }
        if (work == Work.LOAD) {
            for (OpenHeap h : openHeaps) {
                if (engine != null && !h.engine().equals(engine.engine)) {
                    continue;
                }
                long net = h.net().getAsLong();
                long used = h.used().getAsLong();
                if (net > 0 && used > net * props.openHeapRefuse()) {
                    return new Why(Cause.OPEN_DATES, String.format(Locale.ROOT, "the dates open now fill the memory set aside for them (%s of %s) and "
                            + "are all in use", size(used), size(net)));
                }
            }
        }
        int busy = signals.requestsInFlight();
        int handlers = props.requestHandlers();
        int queue = Math.max(0, busy - handlers);
        if (busy >= handlers * props.requestsRefuse() || (queue > 0 && queue >= props.queueRefuse())) {
            return new Why(Cause.REQUESTS, "the server is answering many requests at once (" + Math.min(busy, handlers) + " of " + handlers
                    + (queue > 0 ? ", " + queue + " more waiting" : "") + ")");
        }
        if (work.writes()) {
            long free = signals.freeDiskBytes();
            if (free < props.diskRefuseGb() * GB) {
                return new Why(Cause.DISK, String.format(Locale.ROOT, "the disk is nearly full: %s free (new work waits under %.0f GB)", size(free),
                        props.diskRefuseGb()));
            }
        }
        return null;
    }

    /** True when a remembered refusal's cause has cleared (under its warn level, a load finished, the hold ended): it is lifted now. */
    private boolean cleared(Remembered r) {
        OperatorOverride o = override();
        if (o.mode() == Mode.OPEN) {
            return true;
        }
        return switch (r.cause()) {
            case HELD -> o.mode() != Mode.HOLD;
            case MEMORY -> memoryShare() < props.memoryWarn();
            case LOADS -> {
                Loads l = r.engine() == null ? null : loads.get(r.engine());
                yield l == null || l.running() < l.threads() + props.queuedLoads();
            }
            case OPEN_DATES -> openHeaps.stream().filter(h -> h.engine().equals(r.engine()) || r.engine() == null)
                    .allMatch(h -> h.net().getAsLong() <= 0 || h.used().getAsLong() <= h.net().getAsLong());
            case REQUESTS -> signals.requestsInFlight() < props.requestHandlers() * props.requestsWarn();
            case DISK -> signals.freeDiskBytes() >= props.diskWarnGb() * GB;
        };
    }

    private BusyRefusal refuse(Work work, String who, String what, String title, String because, long seconds, long now) {
        String whoName = who == null || who.isBlank() ? "server" : who;
        synchronized (refusals) {
            refusals.addFirst(new Refused(now, whoName, what == null || what.isBlank() ? work.label() : what, because, work.code().code(), seconds));
            while (refusals.size() > props.keep()) {
                refusals.removeLast();
            }
        }
        refusedTotal.incrementAndGet();
        return new BusyRefusal(work, title, title + ": " + because + ". " + after(work, seconds), because, seconds);
    }

    /** The headline: the same words in the console, the API and drishti.py. */
    static String title(Work work, String what) {
        return switch (work) {
            case SIGN_IN -> "The server is very busy right now";
            case LOAD -> (what == null || what.isBlank() ? "The date" : what) + " cannot be opened just yet";
            case DOWNLOAD -> "The download did not start";
            case BI_RUN -> "The BI run did not start";
            case BULK_LOAD -> "The load did not start";
            case PIVOT_VIEW -> "The pivot did not start";
            case FLIGHT -> "The Flight read did not start";
        };
    }

    /** What happens next, in words. */
    static String after(Work work, long seconds) {
        String in = "try again in " + seconds + " second" + (seconds == 1 ? "" : "s");
        return switch (work) {
            case SIGN_IN -> "Sign-ins are paused for a moment so the people already working are not slowed down; " + in + ".";
            case LOAD -> "The dates already open keep working; " + in + ".";
            case DOWNLOAD -> "Your selection is kept; " + in + ".";
            case BI_RUN -> "The pages already open keep working; " + in + ".";
            case BULK_LOAD -> "Nothing was written; " + in + ".";
            case PIVOT_VIEW -> "Smaller pivots and the pages already open keep working; " + in + ".";
            case FLIGHT -> "Nothing was sent; reads already running keep going; " + in + ".";
        };
    }

    // ------------------------------------------------------------------------------------------------------------ the operator

    /** The override now (an expired one is automatic again). */
    private OperatorOverride override() {
        OperatorOverride o = override;
        if (o.mode() != Mode.AUTOMATIC && clock.getAsLong() >= o.untilMillis()) {
            override = new OperatorOverride(Mode.AUTOMATIC, "", "", 0, 0);
            remembered.clear();
            LOG.info("busy protection: the operator's override ({}) ended by itself; automatic again", o.mode().word());
            return override;
        }
        return o;
    }

    /**
     * An operator's override: {@code OPEN} lets everything through, {@code HOLD} holds all new heavy work, for {@code minutes} (at most
     * {@code override-max-minutes}), with a reason; {@code AUTOMATIC} ends one now. Audited.
     */
    public Map<String, Object> override(Mode mode, String reason, int minutes, String by) {
        String why = reason == null ? "" : reason.trim();
        if (mode != Mode.AUTOMATIC) {
            if (why.isEmpty()) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "an override needs a reason (for example \"month-end sign-off, CRO approved\")");
            }
            if (minutes <= 0 || minutes > props.overrideMaxMinutes()) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "an override ends after 1 to " + props.overrideMaxMinutes()
                        + " minutes (drishti.admission.override-max-minutes), not " + minutes);
            }
        }
        long now = clock.getAsLong();
        OperatorOverride was = override();
        override = mode == Mode.AUTOMATIC ? new OperatorOverride(Mode.AUTOMATIC, "", "", 0, 0)
                : new OperatorOverride(mode, why.length() > 300 ? why.substring(0, 300) : why, by, now, now + minutes * 60_000L);
        remembered.clear();
        String detail = mode == Mode.AUTOMATIC ? "back to automatic (was " + was.mode().word() + ")"
                : mode.word() + " for " + minutes + " min until " + clockTime(override.untilMillis()) + ": " + override.reason();
        LOG.warn("busy protection: {} set by {}", detail, by);
        if (audit != null) {
            try {
                audit.record(by, "busy-override", mode.word(), detail);
            } catch (RuntimeException e) {
                LOG.warn("busy protection: the override could not be audited: {}", e.getMessage());
            }
        }
        return describeOverride();
    }

    private Map<String, Object> describeOverride() {
        OperatorOverride o = override();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", o.mode().word());
        if (o.mode() != Mode.AUTOMATIC) {
            m.put("reason", o.reason());
            m.put("by", o.by());
            m.put("since", Instant.ofEpochMilli(o.atMillis()).toString());
            m.put("until", Instant.ofEpochMilli(o.untilMillis()).toString());
            m.put("minutesLeft", Math.max(0, (o.untilMillis() - clock.getAsLong() + 59_999) / 60_000));
        }
        m.put("maxMinutes", props.overrideMaxMinutes());
        return m;
    }

    // ------------------------------------------------------------------------------------------------------------ Health

    /** The heap left after the last collection: the memory levels act only while it is under {@code memory-free-min-mb}. */
    long memoryFreeBytes() {
        long max = signals.heapMaxBytes();
        long used = signals.heapAfterGcBytes();
        return max <= 0 || used < 0 ? Long.MAX_VALUE : Math.max(0, max - used);
    }

    double memoryShare() {
        long max = signals.heapMaxBytes();
        long used = signals.heapAfterGcBytes();
        return max <= 0 || used < 0 ? 0 : (double) used / max;
    }

    /** True when some new work waits now (a remembered refusal still running). */
    public boolean waiting() {
        long now = clock.getAsLong();
        return remembered.values().stream().anyMatch(r -> now < r.untilMillis() && !cleared(r));
    }

    /** Admin, Health's "Busy protection" card: the state, every reading against its levels, the last refusals, the override. */
    public Map<String, Object> describe() {
        long now = clock.getAsLong();
        Map<String, Object> out = new LinkedHashMap<>();
        OperatorOverride o = override();
        double mem = memoryShare();
        int busy = signals.requestsInFlight();
        int handlers = props.requestHandlers();
        long free = signals.freeDiskBytes();
        int running = loads.values().stream().mapToInt(Loads::running).sum();
        int slots = loads.values().stream().mapToInt(Loads::threads).sum();
        boolean signInsWait = props.enabled() && mem >= props.memoryRefuseSignIn();
        boolean waits = waiting() || o.mode() == Mode.HOLD;
        String state = o.mode() == Mode.OPEN ? "everything let through" : signInsWait ? "new sign-ins wait" : waits ? "new heavy work waits"
                : !props.enabled() ? "off" : mem >= props.memoryWarn() || busy >= handlers * props.requestsWarn() || free < props.diskWarnGb() * GB
                        || (slots > 0 && running >= slots) ? "getting full" : "fine";
        out.put("enabled", props.enabled());
        out.put("state", state);
        out.put("waitingSince", waitingSince == 0 ? null : Instant.ofEpochMilli(waitingSince).toString());
        out.put("override", describeOverride());
        List<Map<String, Object>> gauges = new ArrayList<>();
        Map<String, Object> m = gauge("memory", "Memory in use after clean-up", mem, props.memoryWarn(), props.memoryRefuseHeavy(),
                level(mem, props.memoryWarn(), props.memoryRefuseHeavy()));
        m.put("usedBytes", signals.heapAfterGcBytes() < 0 ? null : signals.heapAfterGcBytes());
        m.put("maxBytes", signals.heapMaxBytes());
        m.put("refuseSignIn", props.memoryRefuseSignIn());
        m.put("note", String.format(Locale.ROOT, "warn at %.0f%% · new loads, downloads, BI runs and bulk loads wait over %.0f%% · new sign-ins wait over "
                + "%.0f%% · signed-in people keep working", props.memoryWarn() * 100, props.memoryRefuseHeavy() * 100, props.memoryRefuseSignIn() * 100));
        gauges.add(m);
        int limit = slots + props.queuedLoads() * loads.size();
        Map<String, Object> l = gauge("loads", "Loads running", limit == 0 ? 0 : (double) running / limit, slots == 0 ? 1 : (double) slots / Math.max(1, limit), 1.0,
                limit > 0 && running >= limit ? "wait" : slots > 0 && running >= slots ? "warn" : "ok");
        l.put("running", running);
        l.put("slots", slots);
        l.put("queued", props.queuedLoads());
        List<Map<String, Object>> engines = new ArrayList<>();
        loads.values().forEach(x -> engines.add(Map.of("engine", x.engine, "running", x.running(), "threads", x.threads())));
        l.put("engines", engines);
        List<Map<String, Object>> heaps = new ArrayList<>();
        for (OpenHeap h : openHeaps) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("engine", h.engine());
            x.put("usedBytes", h.used().getAsLong());
            x.put("netBytes", h.net().getAsLong());
            heaps.add(x);
        }
        l.put("openHeap", heaps);
        l.put("note", "dates being opened or loaded at once; " + props.queuedLoads() + " more may wait per engine (load-threads), then a new one waits"
                + (heaps.isEmpty() ? "" : String.format(Locale.ROOT, " · new dates also wait when the open dates pass %.0f%% of their safety net",
                        props.openHeapRefuse() * 100)));
        gauges.add(l);
        double share = (double) busy / handlers;
        Map<String, Object> r = gauge("requests", "Request handlers busy", Math.min(1, share), props.requestsWarn(), props.requestsRefuse(),
                level(share, props.requestsWarn(), props.requestsRefuse()));
        r.put("busy", Math.min(busy, handlers));
        r.put("handlers", handlers);
        r.put("queue", Math.max(0, busy - handlers));
        r.put("note", String.format(Locale.ROOT, "warn at %.0f%% · new heavy work waits over %.0f%% or a queue over %d", props.requestsWarn() * 100,
                props.requestsRefuse() * 100, props.queueRefuse()));
        gauges.add(r);
        long total = signals.totalDiskBytes();
        double usedShare = total <= 0 ? 0 : (double) (total - free) / total;
        Map<String, Object> d = gauge("disk", "Disk used", usedShare, total <= 0 ? 0 : 1 - props.diskWarnGb() * GB / total,
                total <= 0 ? 0 : 1 - props.diskRefuseGb() * GB / total, free < props.diskRefuseGb() * GB ? "wait" : free < props.diskWarnGb() * GB ? "warn" : "ok");
        d.put("freeBytes", free);
        d.put("totalBytes", total);
        d.put("note", String.format(Locale.ROOT, "warn under %.0f GB free · loads and downloads wait under %.0f GB free", props.diskWarnGb(),
                props.diskRefuseGb()));
        gauges.add(d);
        for (OffHeap o2 : offHeaps) {
            long cap = o2.cap().getAsLong();
            long used = o2.used().getAsLong();
            double s2 = cap <= 0 ? 0 : (double) used / cap;
            Map<String, Object> g = gauge("memory-store:" + o2.name(), "Memory store " + o2.name() + " (off the heap)", Math.min(1, s2), 0.85, 1.0,
                    s2 >= 0.85 ? "warn" : "ok");
            g.put("usedBytes", used);
            g.put("maxBytes", cap);
            g.put("note", "held in memory only under its own cap; when full the store evicts unpinned tables, then refuses the write (never new work here)");
            gauges.add(g);
        }
        out.put("gauges", gauges);
        List<Map<String, Object>> rows = new ArrayList<>();
        Set<String> people = new HashSet<>();
        int recent = 0;
        synchronized (refusals) {
            for (Refused x : refusals) {
                if (now - x.atMillis() <= 15 * 60_000L) {
                    recent++;
                    people.add(x.who());
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("at", Instant.ofEpochMilli(x.atMillis()).toString());
                row.put("who", x.who());
                row.put("what", x.what());
                row.put("why", x.because());
                row.put("code", x.code());
                row.put("retryAfterSeconds", x.retryAfterSeconds());
                rows.add(row);
            }
        }
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("last15Minutes", recent);
        ref.put("people", people.size());
        ref.put("sinceStart", refusedTotal.get());
        ref.put("rows", rows);
        out.put("refusals", ref);
        return out;
    }

    private static Map<String, Object> gauge(String id, String label, double value, double warn, double refuse, String state) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("id", id);
        g.put("label", label);
        g.put("value", value);
        g.put("warn", warn);
        g.put("refuse", refuse);
        g.put("state", state);
        g.put("words", "wait".equals(state) ? "new work waits" : "warn".equals(state) ? "getting full" : "fine");
        return g;
    }

    private static String level(double v, double warn, double refuse) {
        return v >= refuse ? "wait" : v >= warn ? "warn" : "ok";
    }

    private static String size(long bytes) {
        return bytes >= GB ? String.format(Locale.ROOT, "%.1f GB", bytes / (double) GB) : (bytes >> 20) + " MB";
    }

    private static String clockTime(long millis) {
        return DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis));
    }
}
