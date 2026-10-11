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
package com.ash.drishti.server.alerts.measure;

import com.ash.drishti.common.BusinessCalendar;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.time.BusinessDates;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.PreferenceStore;
import com.ash.drishti.server.alerts.measure.AlertLogic.Action;
import com.ash.drishti.server.alerts.measure.AlertLogic.Outcome;
import com.ash.drishti.server.alerts.measure.AlertLogic.Reading;
import com.ash.drishti.server.alerts.measure.AlertLogic.State;
import com.ash.drishti.server.dist.SubscriptionSchedule;
import com.ash.drishti.server.dist.SubscriptionService.Person;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Alerts on measures, fields and board tiles (RUPAKA.md section 20, phase 7 part 2). A person watches a number: one visual of a report, one measure of a
 * dataset, or one field of a Sutra entity. The alert fires when the number crosses a limit ({@code threshold}), moves too far from an earlier reading
 * ({@code change}) or does not arrive by a time ({@code nodata}); and a board tile whose colour reaches red (or amber) can fire the same way for everyone who
 * reads the board ({@code board}).
 *
 * <p><b>Each person is evaluated as themselves.</b> The number is read through the person's own row rules and masks; a number masked for them is never judged
 * and never put in a message. The evaluation, the memory of whether the alert is active and when it last fired, and the message are all per person.
 * {@link AlertLogic} makes the decision (hysteresis and cooldown included); this class reads, remembers, schedules and delivers.
 *
 * <p>When an alert looks: on a schedule (the subscription schedule words), after a data load (the load signal, after {@code load-delay}), or on change for a
 * source with a live table (after a quiet time, the debounce). The state and the history live with the alert in the owner's preferences (namespace
 * {@code measure-alerts}); board tiles keep theirs together under the pseudo-owner {@code _boards}. Delivery goes to the inbox, to email (users only) and to the
 * chat bridges, and every result is in the history.
 */
public final class MeasureAlertService implements AutoCloseable {

    static final String NS = "measure-alerts";
    static final String BOARD_OWNER = "_boards";
    static final String BOARD_NS = "board-alerts";
    private static final Logger LOG = LoggerFactory.getLogger(MeasureAlertService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int BOARD_DOC_LIMIT = 56_000;
    private static final int BOARD_HISTORY = 15;
    private static final long BOARD_INDEX_TTL_MS = 60_000;
    private static final int PARALLEL = 8;

    /** What a live alert remembers between looks. */
    private static final class Watch {
        long generation = Long.MIN_VALUE;
        Instant changedAt;
        Instant lastRun;
        boolean dirty;
    }

    private final PreferenceStore store;
    private final ValueReader reader;
    private final AlertEnvironment.Checker checker;
    private final AlertEnvironment.People people;
    private final AlertEnvironment.Live live;
    private final AlertEnvironment.Boards boards;
    private final AlertChannels channels;
    private final AuditLog audit;
    private final MeasureAlertProperties props;
    private final BusinessDates dates;
    private final Clock clock;
    private final Map<String, AlertSpec> personal = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final Map<String, Instant> nextRun = new ConcurrentHashMap<>();
    private final Map<String, LocalDate> deadlineDone = new ConcurrentHashMap<>();
    private final Map<String, Instant> pendingLoad = new ConcurrentHashMap<>();
    private final Map<String, Watch> watches = new ConcurrentHashMap<>();
    private final Map<String, Instant> bridgeAt = new ConcurrentHashMap<>();
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final AtomicLong ids = new AtomicLong(System.currentTimeMillis());
    private final Executor executor;
    private final ScheduledExecutorService timer;
    private volatile List<AlertSpec> boardSpecs = List.of();
    private volatile long boardSpecsAt;

    @SuppressWarnings("java:S107")
    public MeasureAlertService(PreferenceStore store, ValueReader reader, AlertEnvironment.Checker checker, AlertEnvironment.People people, AlertEnvironment.Live live,
            AlertEnvironment.Boards boards, AlertChannels channels, AuditLog audit, MeasureAlertProperties props, BusinessDates dates, Clock clock, boolean schedule) {
        this.store = store;
        this.reader = reader;
        this.checker = checker;
        this.people = people;
        this.live = live;
        this.boards = boards;
        this.channels = channels;
        this.audit = audit;
        this.props = props;
        this.dates = dates;
        this.clock = clock;
        reindex();
        if (schedule && props.enabled()) {
            this.executor = Executors.newVirtualThreadPerTaskExecutor();
            this.timer = Executors.newScheduledThreadPool(1, r -> Thread.ofPlatform().daemon().name("drishti-measure-alerts").unstarted(r));
            timer.scheduleWithFixedDelay(this::tick, props.tick().toMillis(), props.tick().toMillis(), TimeUnit.MILLISECONDS);
            timer.scheduleWithFixedDelay(this::pollLive, props.livePoll().toMillis(), props.livePoll().toMillis(), TimeUnit.MILLISECONDS);
        } else {
            this.executor = Runnable::run;                                         // tests drive tick() and pollLive() themselves, and see the work done when they return
            this.timer = null;
        }
    }

    // ---- reading -------------------------------------------------------------------------------------------------------------

    /** The owner's alerts as the API shows them: the stored document, plus the condition in words and the link. */
    public List<JsonNode> of(String owner) {
        List<JsonNode> out = new ArrayList<>();
        store.keys(owner, NS).forEach(k -> store.get(owner, NS, k).ifPresent(d -> out.add(view(d))));
        return out;
    }

    /** Every alert of everyone, for administrators. */
    public List<JsonNode> all() {
        List<JsonNode> out = new ArrayList<>();
        for (String owner : store.users()) {
            if (!owner.equals(BOARD_OWNER)) {
                out.addAll(of(owner));
            }
        }
        return out;
    }

    public Optional<JsonNode> get(String owner, String name) {
        return store.get(owner, NS, name).map(this::view);
    }

    /** The board tiles that notify, with how many people each has told and its history (administrators, and the board's author). */
    public List<JsonNode> boardAlerts() {
        List<JsonNode> out = new ArrayList<>();
        for (AlertSpec spec : boardSpecs()) {
            ObjectNode n = spec.toJson();
            ObjectNode holder = holder(spec);
            n.set("history", holder.path("history").isArray() ? holder.get("history") : JSON.createArrayNode());
            long active = 0;
            for (JsonNode s : holder.path("state")) {
                if (s.path("active").asBoolean() || s.path("a").asInt() == 1) {
                    active++;
                }
            }
            n.put("activeFor", active);
            n.put("describe", spec.describe());
            n.put("label", spec.source().label());
            n.put("key", spec.key());
            n.put("link", link(spec));
            out.add(n);
        }
        return out;
    }

    private JsonNode view(JsonNode doc) {
        ObjectNode n = doc.deepCopy();
        AlertSpec spec = parseStored(doc);
        if (spec != null) {
            n.put("describe", spec.describe());
            n.put("label", spec.source().label());
            n.put("link", link(spec));
        }
        return n;
    }

    private AlertSpec parseStored(JsonNode doc) {
        try {
            return AlertSpec.parse(doc.path("owner").asText(), doc.path("name").asText(), doc, props);
        } catch (DrishtiException e) {
            LOG.warn("alert {}/{} no longer reads: {}", doc.path("owner").asText(), doc.path("name").asText(), e.getMessage());
            return null;
        }
    }

    private void reindex() {
        personal.clear();
        for (String owner : store.users()) {
            if (owner.equals(BOARD_OWNER)) {
                continue;
            }
            for (String name : store.keys(owner, NS)) {
                store.get(owner, NS, name).ifPresent(d -> {
                    AlertSpec s = parseStored(d);
                    if (s != null) {
                        personal.put(s.key(), s);
                    }
                });
            }
        }
    }

    private List<AlertSpec> boardSpecs() {
        long now = clock.millis();
        if (now - boardSpecsAt > BOARD_INDEX_TTL_MS || now < boardSpecsAt) {
            try {
                boardSpecs = props.boards() ? List.copyOf(boards.specs()) : List.of();
            } catch (RuntimeException e) {
                LOG.warn("board notifications: the boards could not be read: {}", e.toString());
            }
            boardSpecsAt = now;
        }
        return boardSpecs;
    }

    // ---- writing -------------------------------------------------------------------------------------------------------------

    /** Saves an alert for {@code owner} (a new one is armed now: if it already holds, it fires once). Body: see {@link AlertSpec#parse}. */
    public JsonNode save(String owner, String name, JsonNode body) {
        if (!props.enabled()) {
            throw AlertSpec.invalid("alerts on measures are turned off on this server (drishti.alerts.measure.enabled)");
        }
        AlertSpec spec = AlertSpec.parse(owner, name, body, props);
        Optional<JsonNode> old = store.get(owner, NS, name);
        if (old.isEmpty() && store.keys(owner, NS).size() >= props.perUser()) {
            throw AlertSpec.invalid("at most " + props.perUser() + " alerts per person (drishti.alerts.measure.per-user)");
        }
        Person me = people.person(owner).filter(Person::enabled).orElseThrow(() -> AlertSpec.invalid("no enabled user '" + owner + "'"));
        try {
            checker.check(spec, me.principal());
        } catch (DrishtiException e) {
            throw AlertSpec.invalid("you cannot use this source yourself, so it cannot be watched: " + e.getMessage());
        }
        if (spec.trigger().mode().equals("live") && live.generation(spec, me.principal()) < 0) {
            throw AlertSpec.invalid("trigger live needs a source with a live table behind it; use a schedule or after-load for this one");
        }
        if (spec.delivery().email() && (!props.email() || me.email() == null || me.email().isBlank())) {
            throw AlertSpec.invalid(!props.email() ? "email delivery is turned off on this server (drishti.alerts.measure.email)"
                    : "your account has no email address, so email delivery cannot be used (ask an administrator to add it)");
        }
        if (spec.delivery().bridge() && !props.bridges()) {
            throw AlertSpec.invalid("chat bridge delivery is turned off on this server (drishti.alerts.measure.bridges)");
        }
        ObjectNode doc = spec.toJson();
        doc.put("createdAt", old.map(o -> o.path("createdAt").asText()).orElse(clock.instant().toString()));
        doc.set("state", JSON.createObjectNode());                                // an edited alert is armed afresh
        doc.set("history", old.<JsonNode>map(o -> o.path("history").deepCopy()).orElse(JSON.createArrayNode()));
        store.put(owner, NS, name, doc);
        personal.put(spec.key(), spec);
        resetRuntime(spec.key());
        audit.record(owner, "alert.save", "alert/" + spec.key(), spec.source().label() + " · " + spec.describe() + " · " + spec.trigger().mode());
        dispatch(spec, () -> List.of(me), "saved");
        return view(doc);
    }

    public boolean delete(String owner, String name) {
        boolean gone = store.delete(owner, NS, name);
        if (gone) {
            personal.remove(owner + "/" + name);
            resetRuntime(owner + "/" + name);
            audit.record(owner, "alert.delete", "alert/" + owner + "/" + name, "");
        }
        return gone;
    }

    /** Pause or resume. A resumed alert is armed afresh. */
    public JsonNode setEnabled(String owner, String name, boolean enabled, String actor) {
        JsonNode cur = store.get(owner, NS, name).orElseThrow(() -> notFound(name));
        ObjectNode doc = cur.deepCopy();
        doc.put("enabled", enabled);
        if (enabled) {
            doc.set("state", JSON.createObjectNode());
        }
        store.put(owner, NS, name, doc);
        AlertSpec spec = parseStored(doc);
        if (spec != null) {
            personal.put(spec.key(), spec);
            resetRuntime(spec.key());
            if (enabled) {
                Person me = people.person(owner).filter(Person::enabled).orElse(null);
                if (me != null) {
                    dispatch(spec, () -> List.of(me), "resumed");
                }
            }
        }
        audit.record(actor, enabled ? "alert.resume" : "alert.pause", "alert/" + owner + "/" + name, "");
        return view(doc);
    }

    private void resetRuntime(String key) {
        nextRun.remove(key);
        pendingLoad.remove(key);
        watches.remove(key);
        deadlineDone.remove(key);
    }

    /**
     * What the alert would read for the person right now, without saving, firing or remembering anything: the number, the date it is of, and whether it would
     * fire. For the form's "check now".
     */
    public Map<String, Object> preview(String owner, JsonNode body) {
        AlertSpec spec = AlertSpec.parse(owner, "preview", body, props);
        Person me = people.person(owner).filter(Person::enabled).orElseThrow(() -> AlertSpec.invalid("no enabled user '" + owner + "'"));
        try {
            checker.check(spec, me.principal());
        } catch (DrishtiException e) {
            throw AlertSpec.invalid("you cannot use this source yourself: " + e.getMessage());
        }
        Reading r = readNow(spec, me);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", r.status().name().toLowerCase(java.util.Locale.ROOT));
        out.put("value", r.isOk() ? r.value() : null);
        out.put("asOf", r.asOf() == null ? null : r.asOf().toString());
        out.put("note", r.note());
        out.put("condition", spec.describe());
        if (r.isOk() && spec.condition().type().equals("threshold")) {
            out.put("holds", AlertLogic.decide(spec, r, null, State.FRESH, clock.instant(), true).action() == Action.FIRE);
        }
        out.put("live", live.generation(spec, me.principal()) >= 0);
        return out;
    }

    /** Looks now, by hand, for the owner: the same as a scheduled look, and the outcome is returned. */
    public JsonNode check(String owner, String name) {
        AlertSpec spec = personal.get(owner + "/" + name);
        if (spec == null) {
            throw notFound(name);
        }
        Person me = people.person(owner).filter(Person::enabled).orElseThrow(() -> AlertSpec.invalid("no enabled user '" + owner + "'"));
        evaluateOne(spec, me, "by hand", true);
        return get(owner, name).orElseThrow(() -> notFound(name));
    }

    // ---- triggers ------------------------------------------------------------------------------------------------------------

    /** A data load finished ready: alerts that follow loads (and "no data" alerts, which a load may satisfy) are evaluated after {@code load-delay}. */
    public void onLoad(String kind, LocalDate businessDate) {
        if (!props.enabled()) {
            return;
        }
        Instant at = clock.instant().plus(props.loadDelay());
        for (AlertSpec s : allSpecs()) {
            boolean follows = s.trigger().mode().equals("after-load") || s.condition().type().equals("nodata");
            String wanted = s.trigger().loadKind();
            boolean kindMatches = !wanted.isBlank() ? wanted.equalsIgnoreCase(kind) : !s.source().type().equals("field") || s.source().kind().equalsIgnoreCase(kind);
            if (s.enabled() && follows && kindMatches) {
                pendingLoad.put(s.key(), at);
            }
        }
    }

    /** Looks at what is due: schedules, "no data" deadlines, and the wait after a load. Called every {@code tick}. */
    public void tick() {
        try {
            Instant now = clock.instant();
            for (AlertSpec s : allSpecs()) {
                if (!s.enabled()) {
                    continue;
                }
                String key = s.key();
                Instant pending = pendingLoad.get(key);
                boolean due = pending != null && !pending.isAfter(now);
                if (due) {
                    pendingLoad.remove(key);
                }
                if (s.trigger().mode().equals("schedule")) {
                    Instant next = nextRun.computeIfAbsent(key, k -> computeNext(s, now));
                    if (next != null && !next.isAfter(now)) {
                        nextRun.put(key, computeNext(s, now));
                        due = true;
                    }
                }
                if (s.condition().type().equals("nodata") && pastDeadline(s, now)) {
                    LocalDate today = now.atZone(dates.zone()).toLocalDate();
                    if (!today.equals(deadlineDone.get(key))) {
                        deadlineDone.put(key, today);
                        due = true;
                    }
                }
                if (due) {
                    dispatch(s, () -> audience(s), s.trigger().mode().equals("after-load") ? "load" : "schedule");
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("measure alerts: scheduler: {}", e.toString());
        }
    }

    /** Looks at the live sources: an alert whose source changed evaluates once the changes have been quiet for its debounce (or after {@code live-max-wait}). */
    public void pollLive() {
        try {
            Instant now = clock.instant();
            for (AlertSpec s : allSpecs()) {
                if (!s.enabled() || !s.trigger().mode().equals("live")) {
                    continue;
                }
                List<Person> who = audience(s, 1);
                if (who.isEmpty()) {
                    continue;
                }
                long g = live.generation(s, who.get(0).principal());
                if (g < 0) {
                    continue;
                }
                Watch w = watches.computeIfAbsent(s.key(), k -> new Watch());
                boolean fire;
                synchronized (w) {
                    if (w.generation == Long.MIN_VALUE) {                          // first look: read once to establish the state
                        w.generation = g;
                        w.changedAt = now.minus(s.trigger().debounce());
                        w.lastRun = now;
                        w.dirty = true;
                    } else if (g != w.generation) {
                        w.generation = g;
                        w.changedAt = now;
                        w.dirty = true;
                    }
                    fire = w.dirty && (!now.isBefore(w.changedAt.plus(s.trigger().debounce())) || !now.isBefore(w.lastRun.plus(props.liveMaxWait())));
                    if (fire) {
                        w.dirty = false;
                        w.lastRun = now;
                    }
                }
                if (fire) {
                    dispatch(s, () -> audience(s), "live");
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("measure alerts: live poll: {}", e.toString());
        }
    }

    private List<AlertSpec> allSpecs() {
        List<AlertSpec> all = new ArrayList<>(personal.values());
        all.addAll(boardSpecs());
        return all;
    }

    private Instant computeNext(AlertSpec s, Instant after) {
        try {
            ZonedDateTime n = SubscriptionSchedule.parse(s.trigger().schedule()).next(after.atZone(dates.zone()), dates.zone(), dates.calendar());
            return n == null ? null : n.toInstant();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private boolean pastDeadline(AlertSpec s, Instant now) {
        if (!s.condition().type().equals("nodata")) {
            return false;
        }
        ZonedDateTime z = now.atZone(dates.zone());
        BusinessCalendar cal = dates.calendar();
        return cal.isBusinessDay(z.toLocalDate()) && !z.toLocalTime().isBefore(s.condition().by());
    }

    private List<Person> audience(AlertSpec s) {
        return audience(s, props.maxAudience());
    }

    private List<Person> audience(AlertSpec s, int max) {
        if (s.board() == null) {
            return people.person(s.owner()).filter(Person::enabled).map(List::of).orElse(List.of());
        }
        return boards.audience(s, max);
    }

    // ---- evaluating ----------------------------------------------------------------------------------------------------------

    private void dispatch(AlertSpec spec, java.util.function.Supplier<List<Person>> who, String trigger) {
        if (!running.add(spec.key())) {
            return;                                                                // a look is still going on: the next trigger will catch up
        }
        executor.execute(() -> {
            try {
                List<Person> audience = who.get();
                if (spec.board() != null && audience.size() > props.maxAudience()) {
                    note(spec, "audience", "this tile would tell " + audience.size() + " people, more than " + props.maxAudience() + " (drishti.alerts.measure.max-audience), so it tells nobody");
                    return;
                }
                evaluateAll(spec, audience, trigger);
            } catch (RuntimeException e) {
                LOG.warn("alert {} could not be evaluated: {}", spec.key(), e.toString());
            } finally {
                running.remove(spec.key());
            }
        });
    }

    /** Evaluates the alert for each person, a few at a time. Returns how many alerts fired. */
    int evaluateAll(AlertSpec spec, List<Person> audience, String trigger) {
        if (audience.size() == 1) {
            return evaluateOne(spec, audience.get(0), trigger, false) == Action.FIRE ? 1 : 0;
        }
        Semaphore gate = new Semaphore(PARALLEL);
        AtomicLong fired = new AtomicLong();
        List<Thread> threads = new ArrayList<>();
        for (Person p : audience) {
            gate.acquireUninterruptibly();
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    if (evaluateOne(spec, p, trigger, false) == Action.FIRE) {
                        fired.incrementAndGet();
                    }
                } finally {
                    gate.release();
                }
            }));
        }
        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return (int) fired.get();
    }

    /** One look for one person, as that person. */
    Action evaluateOne(AlertSpec spec, Person person, String trigger, boolean force) {
        Instant now = clock.instant();
        State st = state(spec, person.username());
        if (!force && st.lastEval() != null && now.isBefore(st.lastEval().plus(props.minGap()))) {
            return Action.NONE;
        }
        Reading cur = readNow(spec, person);
        Reading ref = null;
        if (spec.condition().type().equals("change") && cur.isOk()) {
            if (spec.condition().against().equals("previous-run")) {
                ref = st.lastValue() == null ? null : Reading.ok(st.lastValue(), null);
            } else {
                LocalDate base = cur.asOf() != null ? cur.asOf() : dates.current();
                ref = reader.read(spec, person.principal(), dates.calendar().previous(base));
            }
        }
        Outcome o = AlertLogic.decide(spec, cur, ref, st, now, pastDeadline(spec, now));
        Double last = cur.isOk() ? Double.valueOf(cur.value()) : st.lastValue();
        String why = o.action() == Action.SKIP ? o.why() : o.action() == Action.HELD ? "held" : "";
        State next = switch (o.action()) {
            case FIRE -> st.with(true, now, last, now, "");
            case CLEAR -> st.with(false, st.firedAt(), last, now, "");
            default -> st.with(st.active(), st.firedAt(), last, now, why);
        };
        ObjectNode entry = null;
        if (o.action() == Action.FIRE) {
            entry = fire(spec, person, o, cur, now, trigger);
        } else if (o.action() == Action.CLEAR) {
            entry = entry(now, person, "cleared", spec.severity(), cur, o, "back inside the limit" + (spec.hysteresis() > 0 ? " by " + AlertSpec.num(spec.hysteresis()) : ""), trigger);
        } else if ((o.action() == Action.SKIP || o.action() == Action.HELD) && !why.equals(st.note())) {
            entry = entry(now, person, o.action() == Action.SKIP ? "skipped" : "held", spec.severity(), cur, o, o.why(), trigger);
        }
        ObjectNode written = entry;
        update(spec, h -> {
            ObjectNode states = child(h, "state");
            states.set(person.username(), spec.board() == null ? next.toJson() : compact(next));
            if (written != null) {
                ArrayNode hist = array(h, "history");
                hist.insert(0, written);
                while (hist.size() > (spec.board() == null ? props.keepHistory() : BOARD_HISTORY)) {
                    hist.remove(hist.size() - 1);
                }
            }
        });
        return o.action();
    }

    private ObjectNode fire(AlertSpec spec, Person person, Outcome o, Reading cur, Instant now, String trigger) {
        long id = ids.incrementAndGet();
        String message = message(spec, o, cur);
        AlertChannels.Fired f = new AlertChannels.Fired(id, now, spec, person, spec.key(), message, spec.severity(), link(spec));
        ObjectNode delivered = JSON.createObjectNode();
        delivered.put("inbox", spec.delivery().inbox() ? safe(() -> channels.inbox(f)) : "off");
        delivered.put("email", spec.delivery().email() && props.email() ? safe(() -> channels.email(f)) : "off");
        boolean bridgeOn = spec.delivery().bridge() && props.bridges();
        delivered.put("bridge", !bridgeOn ? "off" : claimBridge(spec, now) ? safe(() -> channels.bridge(f)) : "skipped: already posted for this alert within its cooldown");
        audit.record(person.username(), "alert.fire", "alert/" + spec.key(), spec.title() + " · " + trigger);
        ObjectNode e = entry(now, person, "fired", spec.severity(), cur, o, message, trigger);
        e.put("id", id);
        e.set("delivered", delivered);
        return e;
    }

    /** A bridge is a shared room: the first firing of an alert posts, and the others within its cooldown (one minute at least) do not. */
    private boolean claimBridge(AlertSpec spec, Instant now) {
        Duration hold = spec.cooldown().compareTo(Duration.ofMinutes(1)) < 0 ? Duration.ofMinutes(1) : spec.cooldown();
        boolean[] won = {false};
        bridgeAt.compute(spec.key(), (k, last) -> {
            if (last == null || !now.isBefore(last.plus(hold))) {
                won[0] = true;
                return now;
            }
            return last;
        });
        return won[0];
    }

    private ObjectNode entry(Instant at, Person p, String action, String severity, Reading cur, Outcome o, String message, String trigger) {
        ObjectNode e = JSON.createObjectNode();
        e.put("at", at.toString()).put("user", p.username()).put("action", action).put("severity", severity).put("trigger", trigger);
        if (cur.isOk()) {
            e.put("value", cur.value());
        }
        if (cur.asOf() != null) {
            e.put("asOf", cur.asOf().toString());
        }
        if (o.reference() != null) {
            e.put("reference", o.reference());
        }
        if (!Double.isNaN(o.delta())) {
            e.put("delta", o.delta());
        }
        e.put("message", clip(message));
        return e;
    }

    private String message(AlertSpec spec, Outcome o, Reading cur) {
        AlertSpec.Condition c = spec.condition();
        String body = switch (c.type()) {
            case "threshold" -> "now " + AlertSpec.num(o.value()) + " (" + spec.describe() + ")";
            case "change" -> "now " + AlertSpec.num(o.value()) + ", " + (o.delta() >= 0 ? "up " : "down ") + AlertSpec.num(Math.abs(round(o.delta()))) + (c.mode().equals("pct") ? "%" : "")
                    + " from " + AlertSpec.num(o.reference()) + " (" + c.against().replace('-', ' ') + ")";
            case "nodata" -> "no data for " + dates.current() + " yet (expected by " + c.by() + ")";
            default -> "is now " + TileLevel.word(o.value());
        };
        return spec.title().equals(spec.source().label()) ? spec.title() + ": " + body : spec.title() + " — " + body;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    Reading readNow(AlertSpec spec, Person person) {
        Reading r = reader.read(spec, person.principal(), null);
        if (spec.condition().type().equals("nodata") && r.isOk() && r.asOf() != null && r.asOf().isBefore(dates.current())) {
            return Reading.noData("the latest data is of " + r.asOf() + ", the business date is " + dates.current());
        }
        return r;
    }

    private static String safe(java.util.function.Supplier<String> s) {
        try {
            return s.get();
        } catch (RuntimeException e) {
            return "failed: " + clip(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    // ---- the bridge's words --------------------------------------------------------------------------------------------------

    /** The words a bridge posts for one firing: the alert, what it watches and what condition it fired on; no number. Empty when the event is gone. */
    public Optional<Map<String, String>> bridgeFacts(String ref) {
        int bar = ref.lastIndexOf('|');
        if (bar < 0) {
            return Optional.empty();
        }
        String key = ref.substring(0, bar);
        AlertSpec spec = personal.get(key);
        if (spec == null) {
            spec = boardSpecs().stream().filter(s -> s.key().equals(key)).findFirst().orElse(null);
        }
        if (spec == null) {
            return Optional.empty();
        }
        Map<String, String> m = new LinkedHashMap<>();
        m.put("title", spec.title());
        m.put("owner", spec.board() == null ? spec.owner() : "board " + spec.board().board());
        m.put("label", spec.source().label());
        m.put("condition", spec.describe());
        m.put("severity", spec.severity());
        m.put("link", link(spec));
        return Optional.of(m);
    }

    /** The reference a bridge row carries for a firing. */
    public static String bridgeRef(String key, long id) {
        return key + "|" + id;
    }

    // ---- state and history ---------------------------------------------------------------------------------------------------

    private State state(AlertSpec spec, String user) {
        return expand(holder(spec).path("state").path(user));
    }

    static ObjectNode compact(State s) {
        ObjectNode n = JSON.createObjectNode().put("a", s.active() ? 1 : 0);
        if (s.firedAt() != null) {
            n.put("f", s.firedAt().getEpochSecond());
        }
        if (s.lastEval() != null) {
            n.put("e", s.lastEval().getEpochSecond());
        }
        if (s.note() != null && !s.note().isEmpty()) {
            n.put("n", clip(s.note()));
        }
        if (s.lastValue() != null && Double.isFinite(s.lastValue())) {
            n.put("v", s.lastValue());
        }
        return n;
    }

    /** Reads either form of a stored state. */
    static State expand(JsonNode n) {
        if (n != null && n.has("a")) {
            return new State(n.path("a").asInt() == 1, n.has("f") ? Instant.ofEpochSecond(n.get("f").asLong()) : null, n.path("v").isNumber() ? n.get("v").asDouble() : null,
                    n.has("e") ? Instant.ofEpochSecond(n.get("e").asLong()) : null, n.path("n").asText(""));
        }
        return State.of(n);
    }

    private ObjectNode holder(AlertSpec spec) {
        if (spec.board() == null) {
            return store.get(spec.owner(), NS, spec.name()).map(d -> (ObjectNode) d.deepCopy()).orElseGet(JSON::createObjectNode);
        }
        JsonNode d = store.get(BOARD_OWNER, BOARD_NS, spec.board().board()).orElse(null);
        JsonNode t = d == null ? null : d.path("tiles").path(spec.board().page() + "/" + spec.board().tile());
        return t != null && t.isObject() ? (ObjectNode) t.deepCopy() : JSON.createObjectNode();
    }

    private void update(AlertSpec spec, Consumer<ObjectNode> change) {
        ReentrantLock lock = locks.computeIfAbsent(spec.board() == null ? spec.key() : "board:" + spec.board().board(), k -> new ReentrantLock());
        lock.lock();
        try {
            if (spec.board() == null) {
                Optional<JsonNode> cur = store.get(spec.owner(), NS, spec.name());
                if (cur.isEmpty()) {
                    return;                                                       // deleted while it was being evaluated
                }
                ObjectNode doc = cur.get().deepCopy();
                change.accept(doc);
                store.put(spec.owner(), NS, spec.name(), doc);
            } else {
                ObjectNode doc = store.get(BOARD_OWNER, BOARD_NS, spec.board().board()).map(d -> (ObjectNode) d.deepCopy()).orElseGet(JSON::createObjectNode);
                ObjectNode tile = child(child(doc, "tiles"), spec.board().page() + "/" + spec.board().tile());
                change.accept(tile);
                fit(doc);
                store.put(BOARD_OWNER, BOARD_NS, spec.board().board(), doc);
            }
        } finally {
            lock.unlock();
        }
    }

    /** Keeps a board's document within the preference store's size: states of people who are not active go first, then the oldest history. */
    private static void fit(ObjectNode doc) {
        for (int round = 0; round < 6 && doc.toString().length() > BOARD_DOC_LIMIT; round++) {
            for (JsonNode tile : doc.path("tiles")) {
                ObjectNode states = (ObjectNode) tile.path("state");
                List<String> drop = new ArrayList<>();
                states.fields().forEachRemaining(e -> {
                    if (e.getValue().path("a").asInt() != 1) {
                        drop.add(e.getKey());
                    }
                });
                drop.stream().limit(Math.max(1, drop.size() / 2)).forEach(states::remove);
                JsonNode hist = tile.path("history");
                if (hist.isArray() && hist.size() > 3) {
                    ((ArrayNode) hist).remove(hist.size() - 1);
                }
            }
        }
    }

    private void note(AlertSpec spec, String key, String text) {
        update(spec, h -> {
            ObjectNode notes = child(h, "notes");
            if (!text.equals(notes.path(key).asText())) {
                notes.put(key, text);
                ObjectNode e = JSON.createObjectNode().put("at", clock.instant().toString()).put("action", "skipped").put("user", "").put("severity", spec.severity()).put("message", text);
                array(h, "history").insert(0, e);
            }
        });
    }

    private static ObjectNode child(ObjectNode parent, String name) {
        JsonNode c = parent.get(name);
        if (c instanceof ObjectNode o) {
            return o;
        }
        return parent.putObject(name);
    }

    private static ArrayNode array(ObjectNode parent, String name) {
        JsonNode c = parent.get(name);
        if (c instanceof ArrayNode a) {
            return a;
        }
        return parent.putArray(name);
    }

    // ---- links and small things ----------------------------------------------------------------------------------------------

    /** The console path that opens what the alert is about. */
    static String link(AlertSpec s) {
        return switch (s.source().type()) {
            case "visual" -> "/bi/r/" + enc(s.source().report()) + (s.source().page().isBlank() ? "" : "/" + enc(s.source().page()));
            case "dataset" -> "/bi/i/" + enc(s.source().dataset());
            case "field" -> "/v/" + enc(s.source().kind()) + "/" + enc(s.source().id());
            default -> "/bi/b/" + enc(s.board() == null ? s.source().report() : s.board().board());
        };
    }

    private static String enc(String x) {
        return URLEncoder.encode(x, StandardCharsets.UTF_8).replace("+", "%20").replace("%3A", ":");
    }

    private static String clip(String s) {
        String t = s == null ? "" : s.replaceAll("[\\r\\n]+", " ").strip();
        return t.length() > 240 ? t.substring(0, 239) + "…" : t;
    }

    private static DrishtiException notFound(String name) {
        return new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no alert '" + name + "'");
    }

    @Override
    public void close() {
        if (timer != null) {
            timer.shutdownNow();
        }
        if (executor instanceof java.util.concurrent.ExecutorService es) {
            es.shutdownNow();
        }
    }
}
