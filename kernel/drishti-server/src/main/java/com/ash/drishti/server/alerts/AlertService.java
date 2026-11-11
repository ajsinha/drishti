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
package com.ash.drishti.server.alerts;

import com.ash.drishti.common.BusinessCalendar;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.time.BusinessDates;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.PreferenceStore;
import com.ash.drishti.server.alerts.AlertLogic.Action;
import com.ash.drishti.server.alerts.AlertLogic.Outcome;
import com.ash.drishti.server.alerts.AlertLogic.Reading;
import com.ash.drishti.server.alerts.AlertLogic.State;
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
 * Alerts on measures, fields and board tiles (HOW_IT_FITS.md §A28 › Rūpaka: the original design and every phase as built, section 20, phase 7 part 2). A person watches a number: one visual of a report, one measure of a
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
public final class AlertService implements AutoCloseable {

    static final String NS = "measure-alerts";
    static final String BOARD_OWNER = "_boards";
    static final String BOARD_NS = "board-alerts";
    private static final Logger LOG = LoggerFactory.getLogger(AlertService.class);
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
    private final AlertProperties props;
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
    private final Map<String, Instant> hookAt = new ConcurrentHashMap<>();
    private volatile DeliveryRetry retries;
    private volatile List<AlertSpec> boardSpecs = List.of();
    private volatile long boardSpecsAt;

    @SuppressWarnings("java:S107")
    public AlertService(PreferenceStore store, ValueReader reader, AlertEnvironment.Checker checker, AlertEnvironment.People people, AlertEnvironment.Live live,
            AlertEnvironment.Boards boards, AlertChannels channels, AuditLog audit, AlertProperties props, BusinessDates dates, Clock clock, boolean schedule) {
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
        this.retries = new DeliveryRetry(com.ash.drishti.common.RetryPolicy.defaults(), props.maxAttempts());
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

    /**
     * The number an alert on BI watches must be one its owner may see and one number: a value masked for the owner is refused (an alert never reads a value
     * its owner may not see, so it could never fire), and a visual or model query that answers several rows needs the cell to watch (a state that filters it to
     * one row) or how its rows become one number ({@code source.reduce}).
     */
    private void watchable(AlertSpec spec, JsonNode body, com.ash.drishti.kernel.api.security.Principal owner) {
        String type = spec.source().type();
        if (!Set.of("visual", "dataset", "model").contains(type)) {
            return;
        }
        AlertLogic.Reading probe = reader.read(spec, owner, null);
        if (probe.status() == AlertLogic.Reading.Status.MASKED) {
            throw AlertSpec.invalid("the value this alert watches (" + probe.note() + ") is masked for you, so it could never be read: an alert only watches a value "
                    + "its owner may see. Choose a measure that is not masked for you");
        }
        int rows = BiValueReader.rowsOf(probe);
        if (rows > 1 && !"dataset".equals(type) && !body.path("source").has("reduce")) {
            throw AlertSpec.invalid("this " + ("visual".equals(type) ? "visual" : "query") + " answers " + rows + " rows, so say which number to watch: one cell "
                    + "(filter it to one row with source.state) or an aggregate of the rows (source.reduce: sum, min, max, avg, count, first or last)");
        }
    }

    /** Saves an alert for {@code owner} (a new one is armed now: if it already holds, it fires once). Body: see {@link AlertSpec#parse}. */
    public JsonNode save(String owner, String name, JsonNode body) {
        if (!props.enabled()) {
            throw AlertSpec.invalid("alerts on measures are turned off on this server (drishti.alerts.enabled)");
        }
        AlertSpec spec = AlertSpec.parse(owner, name, body, props);
        Optional<JsonNode> old = store.get(owner, NS, name);
        if (old.isEmpty() && store.keys(owner, NS).size() >= props.perUser()) {
            throw AlertSpec.invalid("at most " + props.perUser() + " alerts per person (drishti.alerts.per-user)");
        }
        Person me = people.person(owner).filter(Person::enabled).orElseThrow(() -> AlertSpec.invalid("no enabled user '" + owner + "'"));
        try {
            checker.check(spec, me.principal());
        } catch (DrishtiException e) {
            if (e.errorCode() == ErrorCode.EL_SYNTAX) {
                throw e;                                                          // a rule's expression that does not compile says so, with its own code
            }
            throw AlertSpec.invalid("you cannot use this source yourself, so it cannot be watched: " + e.getMessage());
        }
        watchable(spec, body, me.principal());
        if (spec.trigger().mode().equals("live") && live.generation(spec, me.principal()) < 0) {
            throw AlertSpec.invalid("trigger live needs a source with a live table behind it; use a schedule or after-load for this one");
        }
        if (spec.delivery().email() && (!props.email() || me.email() == null || me.email().isBlank())) {
            throw AlertSpec.invalid(!props.email() ? "email delivery is turned off on this server (drishti.alerts.email)"
                    : "your account has no email address, so email delivery cannot be used (ask an administrator to add it)");
        }
        if (spec.delivery().bridge() && !props.bridges()) {
            throw AlertSpec.invalid("chat bridge delivery is turned off on this server (drishti.alerts.bridges)");
        }
        if (!spec.delivery().webhooks().isEmpty()) {
            if (!props.webhooks()) {
                throw AlertSpec.invalid("webhook delivery is turned off on this server (drishti.alerts.webhooks)");
            }
            for (String hook : spec.delivery().webhooks()) {
                if (!channels.webhookNames().contains(hook)) {
                    throw AlertSpec.invalid("no webhook named '" + hook + "' is defined; an administrator defines them (drishti.notify.webhooks)");
                }
            }
        }
        if (spec.delivery().sms()) {
            if (!props.sms() || !channels.smsConfigured()) {
                throw AlertSpec.invalid("text messages are not set up on this server (drishti.notify.sms.url, drishti.alerts.sms)");
            }
            if (!channels.hasPhone(owner)) {
                throw AlertSpec.invalid("give your mobile number first (PUT /api/v1/me/phone, or Reports, Text messages) so a text can reach you");
            }
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
        ReentrantLock lock = locks.computeIfAbsent(owner + "/" + name, k -> new ReentrantLock());
        lock.lock();                                                              // not between an evaluation's read and its write-back
        try {
            boolean gone = store.delete(owner, NS, name);
            if (gone) {
                personal.remove(owner + "/" + name);
                resetRuntime(owner + "/" + name);
                audit.record(owner, "alert.delete", "alert/" + owner + "/" + name, "");
            }
            return gone;
        } finally {
            lock.unlock();
        }
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

    /**
     * A data load finished ready: alerts that follow loads, "no data" alerts (which a load may satisfy) and rules on the loaded kind are
     * evaluated after {@code load-delay}. Answers how many alerts will look (the load's {@code alerts} step).
     */
    public int onLoad(String kind, LocalDate businessDate) {
        if (!props.enabled()) {
            return 0;
        }
        Instant at = clock.instant().plus(props.loadDelay());
        int n = 0;
        for (AlertSpec s : allSpecs()) {
            boolean follows = s.trigger().mode().equals("after-load") || s.condition().type().equals("nodata") || s.condition().type().equals("rule");
            String wanted = s.trigger().loadKind();
            boolean kindMatches = !wanted.isBlank() ? wanted.equalsIgnoreCase(kind) : !s.source().type().equals("field") || s.source().kind().equalsIgnoreCase(kind);
            if (s.enabled() && follows && kindMatches) {
                pendingLoad.put(s.key(), at);
                n++;
            }
        }
        return n;
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
                    note(spec, "audience", "this tile would tell " + audience.size() + " people, more than " + props.maxAudience() + " (drishti.alerts.max-audience), so it tells nobody");
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
        Instant snoozed = snoozedUntil(holder(spec).path("cases").path(person.username()));
        if (o.action() == Action.FIRE && snoozed != null && now.isBefore(snoozed)) {
            o = new Outcome(Action.HELD, o.value(), o.reference(), o.delta(), "snoozed until " + snoozed);   // fires when the snooze ends, if it still holds
        }
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
            entry = entry(spec, now, person, "cleared", spec.severity(), cur, o, "back inside the limit" + (spec.hysteresis() > 0 ? " by " + AlertSpec.num(spec.hysteresis()) : ""), trigger);
        } else if ((o.action() == Action.SKIP || o.action() == Action.HELD) && !why.equals(st.note())) {
            entry = entry(spec, now, person, o.action() == Action.SKIP ? "skipped" : "held", spec.severity(), cur, o, o.why(), trigger);
        }
        ObjectNode written = entry;
        Action act = o.action();
        update(spec, h -> {
            ObjectNode states = child(h, "state");
            states.set(person.username(), spec.board() == null ? next.toJson() : compact(next));
            ObjectNode cases = child(h, "cases");                                 // the lifecycle: raised, acknowledged, resolved
            if (act == Action.FIRE) {
                ObjectNode c = JSON.createObjectNode().put("stage", RAISED).put("raisedAt", now.toString()).put("message", clip(written.path("message").asText("")));
                JsonNode old = cases.path(person.username());
                if (old.hasNonNull("assignee")) {
                    c.put("assignee", old.get("assignee").asText());
                }
                cases.set(person.username(), c);
            } else if (act == Action.CLEAR && cases.path(person.username()).isObject() && !RESOLVED.equals(cases.path(person.username()).path("stage").asText())) {
                ((ObjectNode) cases.get(person.username())).put("stage", RESOLVED).put("resolvedAt", now.toString()).put("how", "cleared");
            }
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
        String message = message(spec, o);
        AlertChannels.Fired f = new AlertChannels.Fired(id, now, spec, person, spec.key(), message, spec.severity(), link(spec));
        ObjectNode delivered = JSON.createObjectNode();
        delivered.put("inbox", spec.delivery().inbox() ? safe(() -> channels.inbox(f)) : "off");
        deliver(spec, f, delivered, "email", spec.delivery().email() && props.email(), () -> channels.email(f));
        boolean bridgeOn = spec.delivery().bridge() && props.bridges();
        delivered.put("bridge", !bridgeOn ? "off" : claimBridge(spec, now) ? safe(() -> channels.bridge(f)) : "skipped: already posted for this alert within its cooldown");
        if (props.webhooks()) {
            for (String hook : spec.delivery().webhooks()) {                      // a webhook is a shared endpoint: once per alert within its cooldown, not once per person
                if (claim(hookAt, spec.key() + "|" + hook, spec, now)) {
                    deliver(spec, f, delivered, "webhook:" + hook, true, () -> channels.webhook(f, hook));
                } else {
                    delivered.put("webhook:" + hook, "skipped: already posted for this alert within its cooldown");
                }
            }
        }
        deliver(spec, f, delivered, "sms", spec.delivery().sms() && props.sms(), () -> channels.sms(f));
        audit.record(person.username(), "alert.fire", "alert/" + spec.key(), spec.title() + " · " + trigger);
        ObjectNode e = entry(spec, now, person, "fired", spec.severity(), cur, o, message, trigger);
        e.put("id", id);
        e.set("delivered", delivered);
        return e;
    }

    /** A bridge is a shared room: the first firing of an alert posts, and the others within its cooldown (one minute at least) do not. */
    private boolean claimBridge(AlertSpec spec, Instant now) {
        return claim(bridgeAt, spec.key(), spec, now);
    }

    private boolean claim(Map<String, Instant> at, String key, AlertSpec spec, Instant now) {
        Duration hold = spec.cooldown().compareTo(Duration.ofMinutes(1)) < 0 ? Duration.ofMinutes(1) : spec.cooldown();
        boolean[] won = {false};
        at.compute(key, (k, last) -> {
            if (last == null || !now.isBefore(last.plus(hold))) {
                won[0] = true;
                return now;
            }
            return last;
        });
        return won[0];
    }

    private ObjectNode entry(AlertSpec spec, Instant at, Person p, String action, String severity, Reading cur, Outcome o, String message, String trigger) {
        ObjectNode e = JSON.createObjectNode();
        e.put("at", at.toString()).put("user", p.username()).put("action", action).put("severity", severity).put("trigger", trigger);
        if (cur.isOk() && !spec.condition().type().equals("level")) {           // a tile's level is a colour, not a number
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

    /** The one line the person is told: the alert's title (its name unless it was given one), then the number as they read it and the condition. */
    private String message(AlertSpec spec, Outcome o) {
        AlertSpec.Condition c = spec.condition();
        String who = spec.title() + " — ";
        return switch (c.type()) {
            case "threshold" -> who + "now " + AlertSpec.num(o.value()) + " (" + spec.describe() + ")";
            case "change" -> who + "now " + AlertSpec.num(o.value()) + ", " + (o.delta() >= 0 ? "up " : "down ") + AlertSpec.num(Math.abs(round(o.delta())))
                    + (c.mode().equals("pct") ? "%" : "") + " from " + AlertSpec.num(o.reference()) + " (" + c.against().replace('-', ' ') + ")";
            case "nodata" -> who + "no data for " + dates.current() + " yet (expected by " + c.by() + ")";
            case "rule" -> who + "holds (" + spec.source().label() + ")";
            default -> who + "is now " + TileLevel.word(o.value());
        };
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

    /** Delivers on a channel that can fail and be tried again: the first attempt now, the later ones by the shared retry policy; the history shows the attempts. */
    private void deliver(AlertSpec spec, AlertChannels.Fired f, ObjectNode delivered, String channel, boolean on, java.util.function.Supplier<String> attempt) {
        if (!on) {
            delivered.put(channel, "off");
            return;
        }
        DeliveryRetry.First r = retries.run(channel, () -> safe(attempt), (ch, text, n) -> amend(spec, f, ch, text, n));
        delivered.put(channel, r.text());
        delivered.put(channel + "Attempts", r.attempts());
    }

    /** Writes the outcome of a later attempt into the history entry of the firing (waiting a moment if the entry is still being written). */
    private void amend(AlertSpec spec, AlertChannels.Fired f, String channel, String text, int attempts) {
        for (int i = 0; i < 40; i++) {
            boolean[] found = {false};
            update(spec, h -> {
                for (JsonNode e : array(h, "history")) {
                    if (e.path("id").asLong(-1) == f.id() && e.path("user").asText().equals(f.person().username()) && e.path("delivered").isObject()) {
                        ((ObjectNode) e.get("delivered")).put(channel, text).put(channel + "Attempts", attempts);
                        found[0] = true;
                    }
                }
            });
            if (found[0]) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Replaces the retry policy (tests use a short one); {@code drishti.retry.alerts.*} in production. */
    public void retryWith(com.ash.drishti.common.RetryPolicy policy) {
        DeliveryRetry old = this.retries;
        this.retries = new DeliveryRetry(policy, props.maxAttempts());
        old.close();
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
        JsonNode d = store.get(BOARD_OWNER, BOARD_NS, boardKey(spec.board().board())).orElse(null);
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
                ObjectNode doc = store.get(BOARD_OWNER, BOARD_NS, boardKey(spec.board().board())).map(d -> (ObjectNode) d.deepCopy()).orElseGet(JSON::createObjectNode);
                ObjectNode tile = child(child(doc, "tiles"), spec.board().page() + "/" + spec.board().tile());
                change.accept(tile);
                fit(doc);
                store.put(BOARD_OWNER, BOARD_NS, boardKey(spec.board().board()), doc);
            }
        } finally {
            lock.unlock();
        }
    }

    /** The preference store's key for a board: its id with the characters the store refuses (a catalogue id has colons) replaced, and cut with a hash when long. */
    static String boardKey(String boardId) {
        String clean = boardId.replaceAll("[^A-Za-z0-9 ._-]", "_");
        if (clean.equals(boardId) && clean.length() <= 64) {
            return clean;
        }
        return clean.substring(0, Math.min(clean.length(), 50)) + "-" + Integer.toHexString(boardId.hashCode());
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
                if (tile.path("cases").isObject()) {                                  // resolved cases go first: they are only history
                    List<String> done = new ArrayList<>();
                    tile.get("cases").fields().forEachRemaining(e -> {
                        if (RESOLVED.equals(e.getValue().path("stage").asText())) {
                            done.add(e.getKey());
                        }
                    });
                    done.forEach(((ObjectNode) tile.get("cases"))::remove);
                }
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
            case "model" -> "/bi/i/" + enc(s.source().model());
            case "field" -> "/v/" + enc(s.source().kind()) + "/" + enc(s.source().id());
            default -> "/bi/r/" + enc(s.board() == null ? s.source().report() : s.board().board());      // a board is a report with board pages (2.4.0)
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

    // ---- the alerts raised: one lifecycle (raised, acknowledged, resolved), owners and snooze ---------------------------------------

    static final String RAISED = "raised";
    static final String ACKNOWLEDGED = "acknowledged";
    static final String RESOLVED = "resolved";

    /**
     * A platform alert as the engine sees it (the server's own meters, {@code config/alerts/platform-alerts.yaml}): its series, open or
     * closed. {@code key} names the series (rule and tags), so a snooze holds across re-openings.
     */
    public record SystemAlert(String key, String rule, String severity, String summary, String member, Instant openedAt, Instant closedAt) {}

    private volatile java.util.function.Supplier<List<SystemAlert>> system = List::of;
    /** Acknowledgements and owners of platform alerts, by case id; snoozes by series key. In memory: a restart forgets them. */
    private final Map<String, ObjectNode> systemCases = new ConcurrentHashMap<>();
    private final Map<String, Instant> systemSnoozes = new ConcurrentHashMap<>();

    /** The platform alerts the engine lists with the rest (set once at start-up). */
    public void systemAlerts(java.util.function.Supplier<List<SystemAlert>> s) {
        this.system = s;
    }

    /** True while a platform series is snoozed: its opening tells nobody. */
    public boolean systemSnoozed(String key) {
        Instant until = systemSnoozes.get(key);
        return until != null && clock.instant().isBefore(until);
    }

    private static Instant snoozedUntil(JsonNode c) {
        try {
            return c != null && c.hasNonNull("snoozedUntil") ? Instant.parse(c.get("snoozedUntil").asText()) : null;
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    /** A case's id: where it lives and whose it is, in one URL-safe word. */
    static String caseId(String kind, String key, String user) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString((kind + "\u0000" + key + "\u0000" + user).getBytes(StandardCharsets.UTF_8));
    }

    private static String[] parseCase(String id) {
        try {
            String[] p = new String(java.util.Base64.getUrlDecoder().decode(id), StandardCharsets.UTF_8).split("\u0000", -1);
            if (p.length == 3) {
                return p;
            }
        } catch (IllegalArgumentException e) {
            // not an id of ours
        }
        throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no alert " + clip(id));
    }

    /**
     * The alerts raised for this person, newest first: their own alerts' cases, their own case of each board tile they read, and, for an
     * administrator, the platform alerts. {@code open} keeps the raised and acknowledged ones (resolved ones are history). Nothing of
     * another person is ever listed.
     */
    public List<Map<String, Object>> raised(String user, boolean admin, boolean open) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AlertSpec s : allSpecs()) {
            if (s.board() == null && !s.owner().equals(user)) {
                continue;
            }
            JsonNode c = holder(s).path("cases").path(user);
            if (c.isObject() && (!open || !RESOLVED.equals(c.path("stage").asText()))) {
                out.add(caseView(s.board() == null ? "alert" : "board", s.key(), user, s.title(), s.severity(), link(s), c));
            }
        }
        if (admin) {
            Set<String> current = new java.util.HashSet<>();
            for (SystemAlert a : system.get()) {
                current.add(caseId("platform", a.key() + "@" + a.openedAt(), ""));
            }
            systemCases.keySet().retainAll(current);                              // a case the platform no longer remembers is gone
            for (SystemAlert a : system.get()) {
                String id = caseId("platform", a.key() + "@" + a.openedAt(), "");
                ObjectNode c = systemCases.computeIfAbsent(id, k -> JSON.createObjectNode().put("stage", RAISED).put("raisedAt", a.openedAt().toString())).deepCopy();
                c.put("message", a.summary());
                if (a.closedAt() != null && !RESOLVED.equals(c.path("stage").asText())) {
                    c.put("stage", RESOLVED).put("resolvedAt", a.closedAt().toString()).put("how", "cleared");
                }
                if (systemSnoozed(a.key())) {
                    c.put("snoozedUntil", systemSnoozes.get(a.key()).toString());
                }
                if (!open || !RESOLVED.equals(c.path("stage").asText())) {
                    Map<String, Object> m = caseView("platform", a.key() + "@" + a.openedAt(), "", a.rule() + " on " + a.member(), a.severity(), "/admin/health", c);
                    m.put("id", id);
                    out.add(m);
                }
            }
        }
        out.sort((x, y) -> String.valueOf(y.get("raisedAt")).compareTo(String.valueOf(x.get("raisedAt"))));
        return out;
    }

    private static Map<String, Object> caseView(String kind, String key, String user, String title, String severity, String link, JsonNode c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", caseId(kind, key, user));
        m.put("source", kind);
        m.put("rule", key);
        m.put("title", title);
        m.put("severity", severity);
        m.put("stage", c.path("stage").asText(RAISED));
        m.put("message", c.path("message").asText(""));
        for (String f : List.of("raisedAt", "ackBy", "ackAt", "assignee", "snoozedUntil", "resolvedAt", "resolvedBy", "how", "note")) {
            m.put(f, c.hasNonNull(f) ? c.get(f).asText() : null);
        }
        m.put("link", link);
        return m;
    }

    /**
     * Acts on a case of this person's: {@code acknowledge} (they own it now), {@code resolve} (with a note; a condition that still holds does
     * not fire again until it clears and breaks again), {@code snooze} for a while ({@code for}: 15m, 1h, 1d; 0 ends it: a breach in the
     * snooze fires when it ends) or {@code assign} to someone (platform alerts, which administrators share). Audited.
     */
    public Map<String, Object> act(String user, boolean admin, String id, String action, String note, String forText, String to) {
        String[] p = parseCase(id);
        Instant now = clock.instant();
        Consumer<ObjectNode> change = c -> {
            switch (action) {
                case "acknowledge" -> {
                    if (RESOLVED.equals(c.path("stage").asText())) {
                        throw new DrishtiException(ErrorCode.BAD_REQUEST, "this alert is resolved already");
                    }
                    c.put("stage", ACKNOWLEDGED).put("ackBy", user).put("ackAt", now.toString());
                    if (!c.hasNonNull("assignee")) {
                        c.put("assignee", user);
                    }
                }
                case "resolve" -> c.put("stage", RESOLVED).put("resolvedBy", user).put("resolvedAt", now.toString()).put("how", "by hand")
                        .put("note", note == null ? "" : clip(note));
                case "snooze" -> {
                    Duration d = forText == null || forText.isBlank() || forText.strip().equals("0") ? Duration.ZERO
                            : com.ash.drishti.server.bi.dataset.DatasetParser.span(forText.strip());
                    if (d == null || d.isNegative() || d.compareTo(Duration.ofDays(30)) > 0) {
                        throw new DrishtiException(ErrorCode.BAD_REQUEST, "snooze for a time such as 15m, 1h or 1d (at most 30d), or 0 to end it");
                    }
                    if (d.isZero()) {
                        c.remove("snoozedUntil");
                    } else {
                        c.put("snoozedUntil", now.plus(d).toString());
                    }
                }
                case "assign" -> {
                    if (to == null || to.isBlank()) {
                        throw new DrishtiException(ErrorCode.BAD_REQUEST, "assign to whom: a user name");
                    }
                    c.put("assignee", to.strip());
                }
                default -> throw new DrishtiException(ErrorCode.BAD_REQUEST, "the action is acknowledge, resolve, snooze or assign");
            }
        };
        if (p[0].equals("platform")) {
            if (!admin) {
                throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no alert " + clip(id));
            }
            ObjectNode c = systemCases.computeIfAbsent(id, k -> JSON.createObjectNode().put("stage", RAISED));
            synchronized (c) {
                change.accept(c);
            }
            String series = p[1].contains("@") ? p[1].substring(0, p[1].lastIndexOf('@')) : p[1];
            if (action.equals("snooze")) {
                Instant until = snoozedUntil(c);
                if (until == null) {
                    systemSnoozes.remove(series);
                } else {
                    systemSnoozes.put(series, until);
                }
            }
            audit.record(user, "alert." + action, "platform/" + series, note == null ? "" : clip(note));
            return caseView("platform", p[1], "", series, "", "/admin/health", c);
        }
        if (!p[2].equals(user) || p[0].equals("alert") && !p[1].startsWith(user + "/")) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no alert " + clip(id));          // only your own cases: nothing of another person
        }
        if (action.equals("assign")) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "an alert of yours is yours: assign is for the platform alerts administrators share");
        }
        AlertSpec spec = allSpecs().stream().filter(s -> s.key().equals(p[1])).findFirst()
                .orElseThrow(() -> new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no alert " + clip(id)));
        Object[] done = new Object[1];
        update(spec, h -> {
            JsonNode cur = h.path("cases").path(user);
            if (!cur.isObject()) {
                throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "this alert has not been raised for you");
            }
            ObjectNode c = (ObjectNode) cur;
            change.accept(c);
            done[0] = caseView(spec.board() == null ? "alert" : "board", spec.key(), user, spec.title(), spec.severity(), link(spec), c);
        });
        if (done[0] == null) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no alert " + clip(id));
        }
        audit.record(user, "alert." + action, "alert/" + spec.key(), note == null ? (forText == null ? "" : forText) : clip(note));
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) done[0];
        return out;
    }

    // ---- the converter: the rules of the retired entity-rule engine become alerts of this one ---------------------------------------

    /** The namespace the retired entity-rule engine kept its rules in. */
    static final String OLD_RULES_NS = "alerts";

    /**
     * Converts every rule of the retired entity-rule engine ({@code {ref: {kind, id}, when, severity, message, enabled}} in the
     * preference namespace {@code alerts}) into an alert of this engine: a field source whose field is the rule's Rachana-EL predicate,
     * the condition {@code rule}, live when the entity's source pushes changes (else after each load of its kind), delivered to the
     * inbox. The rule's message becomes the alert's title. Each converted rule is removed from the old namespace, so this runs once;
     * answers how many it converted.
     */
    public int convertOldRules() {
        int n = 0;
        for (String owner : store.users()) {
            if (owner.startsWith("_")) {
                continue;
            }
            for (String key : List.copyOf(store.keys(owner, OLD_RULES_NS))) {
                JsonNode old = store.get(owner, OLD_RULES_NS, key).orElse(null);
                if (old == null || !old.has("when") || !old.path("ref").has("kind")) {
                    continue;
                }
                String name = key.replaceAll("[^A-Za-z0-9 ._-]", "-").replaceAll("^[^A-Za-z0-9]+", "");
                name = name.isEmpty() ? "rule" : name.length() > 60 ? name.substring(0, 60) : name;
                if (store.get(owner, NS, name).isPresent()) {
                    name = (name.length() > 55 ? name.substring(0, 55) : name) + "-rule";
                }
                ObjectNode body = JSON.createObjectNode();
                String message = old.path("message").asText("").strip();
                body.put("title", message.isEmpty() ? key : clip(message)).put("severity", old.path("severity").asText("warn")).put("enabled", old.path("enabled").asBoolean(true));
                body.putObject("source").put("type", "field").put("kind", old.path("ref").path("kind").asText()).put("id", old.path("ref").path("id").asText())
                        .put("field", old.path("when").asText());
                body.putObject("condition").put("type", "rule");
                body.putObject("trigger").put("mode", "after-load").put("kind", old.path("ref").path("kind").asText());
                body.putObject("delivery").put("inbox", true);
                AlertSpec spec;
                try {
                    spec = AlertSpec.parse(owner, name, body, props);
                } catch (DrishtiException e) {
                    LOG.warn("alert rule {} of {} could not be converted, kept where it is: {}", key, owner, e.getMessage());
                    continue;
                }
                Person me = people.person(owner).filter(Person::enabled).orElse(null);
                if (me != null && live.generation(spec, me.principal()) >= 0) {             // the entity's source pushes changes: watch it live, as before
                    body.putObject("trigger").put("mode", "live");
                    spec = AlertSpec.parse(owner, name, body, props);
                }
                ObjectNode doc = spec.toJson();
                doc.put("createdAt", clock.instant().toString());
                doc.put("convertedFrom", "entity rule " + key);
                doc.set("state", JSON.createObjectNode());
                doc.set("history", JSON.createArrayNode());
                store.put(owner, NS, name, doc);
                store.delete(owner, OLD_RULES_NS, key);
                personal.put(spec.key(), spec);
                audit.record(owner, "alert.convert", "alert/" + spec.key(), "from the entity rule " + key);
                n++;
            }
        }
        return n;
    }

    @Override
    public void close() {
        retries.close();
        if (timer != null) {
            timer.shutdownNow();
        }
        if (executor instanceof java.util.concurrent.ExecutorService es) {
            es.shutdownNow();
        }
    }
}
