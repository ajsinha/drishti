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
package com.ash.drishti.server.dist;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.common.RetryPolicy;
import com.ash.drishti.engine.time.BusinessDates;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.PreferenceStore;
import com.ash.drishti.server.collab.mail.MailFailures;
import com.ash.drishti.server.collab.mail.MailRenderer;
import com.ash.drishti.server.collab.mail.MailTransport;
import com.ash.drishti.server.collab.mail.RenderedMail;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Subscriptions: a BI report, page or visual, or a Sutra view, delivered by email on a schedule (or after the day's data lands) to people,
 * and to a role (a group). Never to outside addresses: a person who is not a user has no rights, so no render can be made for them. Kept with the owner's preferences (namespace
 * {@code subscriptions}) with their history.
 *
 * <p>Each recipient gets <b>their own render</b>: the report is asked of the engine as that person, so their row rules and masks apply, never
 * the owner's. Someone who cannot open the target is skipped, with the reason in the history, not sent a refusal. A failed delivery is
 * retried with the shared retry policy ({@code drishti.retry.distribution.*}) up to {@code max-attempts}; a refused address or an SMTP 5xx
 * is not retried.  A subscription saved before outside addresses were refused keeps its address,
 * but the address is skipped at every send with the reason in the history.
 */
public final class SubscriptionService implements AutoCloseable {

    static final String NS = "subscriptions";
    private static final Logger LOG = LoggerFactory.getLogger(SubscriptionService.class);
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 ._-]{0,63}");
    private static final int DELIVERY_DETAIL = 40;
    private static final int RUNS_WITH_DETAIL = 3;

    /** Who the service sends to and as whom it renders: the directory of users and their current rights. */
    public interface Directory {

        /** A user as they are now, or empty. */
        Optional<Person> person(String username);

        /** Every enabled member of a role. */
        List<Person> members(String role);

        /** Whether the user may subscribe other people and roles (a BI author or an administrator). */
        boolean mayAddOthers(String username);
    }

    /** A user: the address (maybe blank), the rights to render with, and whether they may sign in. */
    public record Person(String username, String displayName, String email, boolean enabled, Principal principal) {}

    /** Makes the file for a target as a reader ({@link ContentExporter#render}). */
    @FunctionalInterface
    public interface Renderer {
        ContentExporter.Output render(ContentExporter.Target target, ContentExporter.Format format, Principal who);
    }

    /** Checks that a reader may use a target (so a subscription cannot be saved for something that cannot be read). */
    @FunctionalInterface
    public interface Checker {
        void check(ContentExporter.Target target, Principal who);
    }

    private record Recipient(String address, String user, Principal principal, String label) {}

    private final PreferenceStore store;
    private final Renderer renderer;
    private final Checker checker;
    private final Directory directory;
    private final AuditLog audit;
    private final MailRenderer mail;
    private final MailTransport transport;
    private final String from;
    private final String consoleUrl;
    private final DistProperties.Subscriptions props;
    private final RetryPolicy retry;
    private final BusinessDates dates;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("drishti-subscriptions").factory());

    @SuppressWarnings("java:S107")
    public SubscriptionService(PreferenceStore store, Renderer renderer, Checker checker, Directory directory, AuditLog audit, MailRenderer mail, MailTransport transport,
            String from, String consoleUrl, DistProperties.Subscriptions props, RetryPolicy retry, BusinessDates dates, Clock clock, boolean schedule) {
        this.store = store;
        this.renderer = renderer;
        this.checker = checker;
        this.directory = directory;
        this.audit = audit;
        this.mail = mail;
        this.transport = transport;
        this.from = from;
        this.consoleUrl = consoleUrl == null ? "" : consoleUrl.replaceAll("/+$", "");
        this.props = props;
        this.retry = retry;
        this.dates = dates;
        this.clock = clock;
        if (schedule && props.enabled()) {
            long ms = props.tick().toMillis();
            ticker.scheduleWithFixedDelay(this::tick, ms, ms, TimeUnit.MILLISECONDS);
        }
    }

    // ---- reading -----------------------------------------------------------------------------------------------------

    public List<JsonNode> of(String owner) {
        List<JsonNode> out = new ArrayList<>();
        store.keys(owner, NS).forEach(k -> store.get(owner, NS, k).ifPresent(out::add));
        return out;
    }

    /** Every subscription of everyone (administrators). */
    public List<JsonNode> all() {
        List<JsonNode> out = new ArrayList<>();
        for (String owner : store.users()) {
            out.addAll(of(owner));
        }
        return out;
    }

    public Optional<JsonNode> get(String owner, String name) {
        return store.get(owner, NS, name);
    }

    // ---- writing -----------------------------------------------------------------------------------------------------

    /**
     * Saves a subscription for {@code owner}. Body: {@code {target: {type, id, page?, visual?, state?, view?}, schedule, format, recipients: [...],
     * date?, enabled?, title?}}; recipients are user names or {@code role:NAME}; an email address is refused.
     */
    public JsonNode save(String owner, String name, JsonNode body) {
        if (!props.enabled()) {
            throw invalid("subscriptions are turned off on this server (drishti.distribution.subscriptions.enabled)");
        }
        if (!NAME.matcher(name).matches()) {
            throw invalid("a subscription's name is 1-64 letters, digits, spaces, dots, dashes or underscores");
        }
        ContentExporter.Target target = target(body.path("target"));
        SubscriptionSchedule schedule;
        String scheduleText = body.path("schedule").asText("").trim();
        try {
            schedule = SubscriptionSchedule.parse(scheduleText);
        } catch (IllegalArgumentException e) {
            throw invalid(e.getMessage());
        }
        String format = body.path("format").asText("html").toLowerCase(Locale.ROOT);
        if (!Set.of("html", "pdf", "xlsx").contains(format)) {
            throw invalid("format is html (inline in the email), pdf or xlsx");
        }
        String date = body.path("date").asText("today");
        if (!Set.of("today", "previous", "load").contains(date)) {
            throw invalid("date is today (the latest business date), previous (the business day before) or load (the date of the load that triggered it)");
        }
        List<String> recipients = recipients(owner, body.path("recipients"));
        Optional<JsonNode> old = get(owner, name);
        if (old.isEmpty() && store.keys(owner, NS).size() >= props.perUser()) {
            throw invalid("at most " + props.perUser() + " subscriptions per person (drishti.distribution.subscriptions.per-user)");
        }
        Person me = directory.person(owner).orElse(null);
        if (me != null) {
            try {
                checker.check(target, me.principal());
            } catch (DrishtiException e) {
                throw invalid("you cannot use this target yourself, so it cannot be subscribed to: " + e.getMessage());
            }
        }
        ObjectNode s = json.createObjectNode().put("name", name).put("owner", owner);
        s.put("title", body.path("title").asText("").strip().isEmpty() ? defaultTitle(target) : body.path("title").asText().strip());
        s.set("target", targetJson(target));
        s.put("schedule", scheduleText).put("format", format).put("date", date);
        ArrayNode r = s.putArray("recipients");
        recipients.forEach(r::add);
        s.put("enabled", body.path("enabled").asBoolean(true));
        s.put("createdAt", old.map(o -> o.path("createdAt").asText()).orElse(clock.instant().toString()));
        s.set("runs", old.<JsonNode>map(o -> o.path("runs").deepCopy()).orElse(json.createArrayNode()));
        s.put("nextRun", nextRun(s, schedule));
        put(owner, name, s);
        audit.record(owner, "subscription.save", "subscription/" + owner + "/" + name, target.type() + ":" + target.id() + " · " + scheduleText + " · " + format);
        return s;
    }

    public boolean delete(String owner, String name) {
        boolean gone = store.delete(owner, NS, name);
        if (gone) {
            audit.record(owner, "subscription.delete", "subscription/" + owner + "/" + name, "");
        }
        return gone;
    }

    /** Pause or resume. A resumed subscription is scheduled from now (it does not catch up on what it missed). */
    public JsonNode setEnabled(String owner, String name, boolean enabled, String actor) {
        JsonNode cur = get(owner, name).orElseThrow(() -> notFound(name));
        ObjectNode s = cur.deepCopy();
        s.put("enabled", enabled);
        s.put("nextRun", nextRun(s, SubscriptionSchedule.parse(s.path("schedule").asText())));
        put(owner, name, s);
        audit.record(actor, enabled ? "subscription.resume" : "subscription.pause", "subscription/" + owner + "/" + name, "");
        return s;
    }

    // ---- the schedule ------------------------------------------------------------------------------------------------

    private String nextRun(JsonNode s, SubscriptionSchedule schedule) {
        if (!s.path("enabled").asBoolean(true) || schedule.isAfterLoad()) {
            return null;
        }
        ZonedDateTime next = schedule.next(clock.instant().atZone(dates.zone()), dates.zone(), dates.calendar());
        return next == null ? null : next.toInstant().toString();
    }

    /** The next time a schedule text runs after {@code after} (for the form's preview); null when it waits for a load. */
    public Instant preview(String scheduleText, Instant after) {
        ZonedDateTime n = SubscriptionSchedule.parse(scheduleText).next(after.atZone(dates.zone()), dates.zone(), dates.calendar());
        return n == null ? null : n.toInstant();
    }

    /** A data load finished ready: every enabled {@code after-load} subscription whose kind matches runs after {@code load-delay}. */
    public void onLoad(String kind, LocalDate businessDate) {
        if (!props.enabled()) {
            return;
        }
        Instant at = clock.instant().plus(props.loadDelay());
        for (String owner : store.users()) {
            for (String name : store.keys(owner, NS)) {
                store.get(owner, NS, name).ifPresent(s -> {
                    SubscriptionSchedule sch = safe(s.path("schedule").asText());
                    if (s.path("enabled").asBoolean(true) && sch != null && sch.isAfterLoad() && (sch.loadKind() == null || sch.loadKind().equalsIgnoreCase(kind)
                            || (s.path("target").path("kind").asText("").equalsIgnoreCase(kind)))) {
                        ObjectNode n = s.deepCopy();
                        n.put("nextRun", at.toString());
                        n.put("loadDate", businessDate == null ? "" : businessDate.toString());
                        put(owner, name, n);
                    }
                });
            }
        }
    }

    private static SubscriptionSchedule safe(String text) {
        try {
            return SubscriptionSchedule.parse(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Runs what is due, and retries deliveries whose wait is over, each subscription on its own virtual thread. */
    void tick() {
        try {
            Instant now = clock.instant();
            for (String owner : store.users()) {
                for (String name : store.keys(owner, NS)) {
                    store.get(owner, NS, name).ifPresent(s -> {
                        String key = owner + "\u001f" + name;
                        if (running.contains(key) || !s.path("enabled").asBoolean(true)) {
                            return;
                        }
                        String next = s.path("nextRun").asText(null);
                        if (next != null && !Instant.parse(next).isAfter(now)) {
                            Thread.ofVirtual().name("drishti-subscription-" + name).start(() -> run(owner, name, "schedule"));
                        } else if (retryDue(s, now)) {
                            Thread.ofVirtual().name("drishti-subscription-retry-" + name).start(() -> retryDeliveries(owner, name));
                        }
                    });
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("subscription scheduler: {}", e.toString());
        }
    }

    private static boolean retryDue(JsonNode s, Instant now) {
        JsonNode last = s.path("runs").path(0);
        for (JsonNode d : last.path("deliveries")) {
            if ("retry".equals(d.path("status").asText()) && d.hasNonNull("nextAttempt") && !Instant.parse(d.path("nextAttempt").asText()).isAfter(now)) {
                return true;
            }
        }
        return false;
    }

    // ---- running -----------------------------------------------------------------------------------------------------

    /** Runs a subscription now (by hand, when due, or after a load): one render and one mail per recipient. Returns the run as recorded. */
    public JsonNode run(String owner, String name, String trigger) {
        JsonNode s = get(owner, name).orElseThrow(() -> notFound(name));
        String key = owner + "\u001f" + name;
        if (!running.add(key)) {
            throw invalid("'" + name + "' is already running");
        }
        ObjectNode run = json.createObjectNode().put("id", UUID.randomUUID().toString().substring(0, 8)).put("at", clock.instant().toString()).put("trigger", trigger);
        try {
            List<Recipient> who = expand(owner, s);
            ArrayNode deliveries = json.createArrayNode();
            for (Recipient r : who) {
                deliveries.add(deliver(s, r, 1, trigger));
            }
            run.set("deliveries", deliveries);
            summarise(run);
        } catch (RuntimeException e) {
            run.put("status", "failed").put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()).put("sent", 0).put("failed", 0)
                    .put("skipped", 0).put("retrying", 0);
            LOG.warn("subscription {} of {} failed: {}", name, owner, e.toString());
        } finally {
            running.remove(key);
        }
        record(owner, name, run, true);
        audit.record(owner, "subscription.run", "subscription/" + owner + "/" + name, run.path("status").asText() + " · " + trigger + " · " + run.path("sent").asInt() + " sent");
        return run;
    }

    /** Tries again the deliveries of the latest run whose wait is over (and only those). */
    void retryDeliveries(String owner, String name) {
        String key = owner + "\u001f" + name;
        if (!running.add(key)) {
            return;
        }
        try {
            JsonNode s = get(owner, name).orElse(null);
            if (s == null || s.path("runs").isEmpty()) {
                return;
            }
            ObjectNode run = s.path("runs").path(0).deepCopy();
            Instant now = clock.instant();
            ArrayNode ds = (ArrayNode) run.path("deliveries");
            List<Recipient> known;
            try {
                known = expand(owner, s);
            } catch (RuntimeException e) {
                known = List.of();
            }
            for (int i = 0; i < ds.size(); i++) {
                JsonNode d = ds.get(i);
                if ("retry".equals(d.path("status").asText()) && d.hasNonNull("nextAttempt") && !Instant.parse(d.path("nextAttempt").asText()).isAfter(now)) {
                    String to = d.path("to").asText();
                    Recipient r = known.stream().filter(x -> x.address() != null && x.address().equalsIgnoreCase(to)).findFirst().orElse(null);
                    if (r == null) {
                        ds.set(i, ((ObjectNode) d.deepCopy()).put("status", "skipped").put("error", "no longer a recipient"));
                    } else {
                        ds.set(i, deliver(s, r, d.path("attempts").asInt(1) + 1, run.path("trigger").asText("schedule")));
                    }
                }
            }
            summarise(run);
            record(owner, name, run, false);
        } finally {
            running.remove(key);
        }
    }

    private ObjectNode deliver(JsonNode s, Recipient r, int attempt, String trigger) {
        ObjectNode d = json.createObjectNode().put("to", r.address() == null ? r.label() : r.address()).put("label", r.label()).put("attempts", attempt);
        if (r.user() != null) {
            d.put("user", r.user());
        }
        if (r.principal() == null) {
            return d.put("status", "skipped").put("error", "outside address: subscriptions go to users only, so it is not sent to");
        }
        if (r.address() == null || r.address().isBlank()) {
            return d.put("status", "skipped").put("error", "no email address on file");
        }
        RenderedMail m;
        try {
            m = build(s, r, trigger);
        } catch (DrishtiException e) {
            return d.put("status", "skipped").put("error", clip(e.getMessage()));
        } catch (RuntimeException e) {
            return failure(d, e, attempt, false);
        }
        try {
            transport.send(m, from);
            return d.put("status", "sent").put("at", clock.instant().toString()).put("bytes", m.files().stream().mapToInt(f -> f.bytes().length).sum());
        } catch (RuntimeException e) {
            return failure(d, e, attempt, MailFailures.permanent(e));
        }
    }

    private ObjectNode failure(ObjectNode d, RuntimeException e, int attempt, boolean permanent) {
        String why = clip(MailFailures.describe(e));
        d.put("error", why);
        if (permanent || !retry.enabled() || attempt >= props.maxAttempts()) {
            return d.put("status", "failed");
        }
        boolean hint = RetryPolicy.looksPermanent(why);
        d.put("status", "retry").put("nextAttempt", clock.instant().plus(retry.delay(attempt, hint, RandomGenerator.getDefault().nextDouble())).toString());
        return d;
    }

    /** The message for one recipient: rendered as them (their masks and row rules), the body inline or the file attached. */
    private RenderedMail build(JsonNode s, Recipient r, String trigger) {
        String format = s.path("format").asText("html");
        ContentExporter.Target t = target(s.path("target"), dateOf(s, trigger));
        ContentExporter.Output o = renderer.render(t, "html".equals(format) ? ContentExporter.Format.HTML : ContentExporter.Format.of(format), r.principal());
        ExportDoc doc = o.doc();
        String asOf = doc.subtitle() == null ? "" : doc.subtitle().replaceFirst("^.*Data as of ", "");
        String headline = doc.title() + (asOf.isBlank() ? "" : " · " + asOf);
        String link = link(t);
        List<RenderedMail.Attachment> files = new ArrayList<>();
        String bodyHtml;
        String bodyText;
        if ("html".equals(format)) {
            bodyHtml = DocHtml.html(doc, props.emailRows());
            bodyText = DocHtml.text(doc, props.emailRows());
        } else {
            files.add(new RenderedMail.Attachment(o.filename(), o.contentType(), o.bytes()));
            bodyHtml = DocHtml.summaryHtml(doc, o.filename());
            bodyText = DocHtml.summaryText(doc, o.filename());
        }
        String id = "<sub." + UUID.randomUUID().toString().replace("-", "") + "@" + mail.host() + ">";
        return mail.renderBody("subscription", headline, bodyText, bodyHtml, link, r.address(), id, files);
    }

    private String dateOf(JsonNode s, String trigger) {
        return switch (s.path("date").asText("today")) {
            case "previous" -> dates.calendar().previous(dates.current()).toString();
            case "load" -> s.path("loadDate").asText("").isBlank() ? null : s.path("loadDate").asText();
            default -> null;
        };
    }

    private String link(ContentExporter.Target t) {
        if (t.isReport()) {
            return consoleUrl + "/bi/r/" + enc(t.id()) + (t.page() == null || t.page().isBlank() ? "" : "/" + enc(t.page()));
        }
        return consoleUrl + "/v/" + t.id() + (t.view() == null || t.view().isBlank() ? "" : "?" + t.view());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    // ---- recipients --------------------------------------------------------------------------------------------------

    private List<String> recipients(String owner, JsonNode list) {
        if (!list.isArray() || list.isEmpty()) {
            throw invalid("name at least one recipient: a user, role:NAME" + "");
        }
        boolean others = directory.mayAddOthers(owner);
        List<String> out = new ArrayList<>();
        for (JsonNode n : list) {
            String x = n.asText("").strip();
            if (x.isEmpty() || out.stream().anyMatch(y -> y.equalsIgnoreCase(x))) {
                continue;
            }
            if (x.contains("@")) {
                throw invalid("'" + x + "' is an email address, not a user: a subscription goes to users and roles only, because each person gets the report with their own access (an address outside the system has none)");
            } else if (x.startsWith("role:")) {
                if (!others) {
                    throw invalid("only a BI author or an administrator may subscribe a role");
                }
                if (directory.members(x.substring(5)).isEmpty()) {
                    throw invalid("no enabled user has the role '" + x.substring(5) + "'");
                }
            } else {
                if (!x.equals(owner) && !others) {
                    throw invalid("you may subscribe yourself; only a BI author or an administrator may subscribe other people");
                }
                if (directory.person(x).isEmpty()) {
                    throw invalid("no user '" + x + "'");
                }
            }
            out.add(x);
        }
        if (out.isEmpty()) {
            throw invalid("name at least one recipient");
        }
        return out;
    }

    private List<Recipient> expand(String owner, JsonNode s) {
        Map<String, Recipient> out = new LinkedHashMap<>();
        for (JsonNode n : s.path("recipients")) {
            String x = n.asText();
            if (x.contains("@")) {
                out.putIfAbsent(x.toLowerCase(Locale.ROOT), new Recipient(x, null, null, x));   // kept from an older save; skipped at send
            } else if (x.startsWith("role:")) {
                directory.members(x.substring(5)).forEach(p -> out.putIfAbsent(p.username(), new Recipient(p.email(), p.username(), p.principal(), p.displayName())));
            } else {
                directory.person(x).filter(Person::enabled).ifPresent(p -> out.putIfAbsent(p.username(), new Recipient(p.email(), p.username(), p.principal(), p.displayName())));
            }
        }
        if (out.size() > props.maxRecipients()) {
            throw invalid("the subscription reaches " + out.size() + " people, more than " + props.maxRecipients() + " (drishti.distribution.subscriptions.max-recipients)");
        }
        return new ArrayList<>(out.values());
    }

    // ---- history -----------------------------------------------------------------------------------------------------

    private void summarise(ObjectNode run) {
        int sent = 0;
        int failed = 0;
        int skipped = 0;
        int retrying = 0;
        for (JsonNode d : run.path("deliveries")) {
            switch (d.path("status").asText()) {
                case "sent" -> sent++;
                case "failed" -> failed++;
                case "skipped" -> skipped++;
                default -> retrying++;
            }
        }
        run.put("sent", sent).put("failed", failed).put("skipped", skipped).put("retrying", retrying);
        run.put("status", retrying > 0 ? "retrying" : failed > 0 ? (sent > 0 ? "partial" : "failed") : sent == 0 && skipped > 0 ? "skipped" : "ok");
        run.remove("error");
    }

    private void record(String owner, String name, ObjectNode run, boolean newRun) {
        get(owner, name).ifPresent(cur -> {
            ObjectNode s = cur.deepCopy();
            ArrayNode runs = json.createArrayNode();
            runs.add(trim((ObjectNode) run.deepCopy()));
            int skip = newRun ? 0 : 1;                                            // a retry replaces the run it continues
            int i = 0;
            for (JsonNode x : cur.path("runs")) {
                if (i++ >= skip && runs.size() < props.keepRuns()) {
                    runs.add(x);
                }
            }
            s.set("runs", runs);
            if (newRun || s.path("nextRun").isNull() || s.path("nextRun").asText("").isBlank() || !Instant.parse(s.path("nextRun").asText()).isAfter(clock.instant())) {
                SubscriptionSchedule sch = safe(s.path("schedule").asText());
                s.put("nextRun", sch == null ? null : nextRun(s, sch));
            }
            put(owner, name, s);
        });
    }

    /** Keeps the history within a preference document: deliveries in detail for the newest runs only, the problems first, a fixed number. */
    private ObjectNode trim(ObjectNode run) {
        JsonNode ds = run.path("deliveries");
        if (ds.size() > DELIVERY_DETAIL) {
            List<JsonNode> sorted = new ArrayList<>();
            ds.forEach(sorted::add);
            sorted.sort((a, b) -> Integer.compare("sent".equals(a.path("status").asText()) ? 1 : 0, "sent".equals(b.path("status").asText()) ? 1 : 0));
            ArrayNode kept = json.createArrayNode();
            sorted.stream().limit(DELIVERY_DETAIL).forEach(kept::add);
            run.set("deliveries", kept);
            run.put("detailCut", ds.size() - DELIVERY_DETAIL);
        }
        return run;
    }

    private void put(String owner, String name, ObjectNode s) {
        ArrayNode runs = (ArrayNode) s.path("runs");
        for (int i = RUNS_WITH_DETAIL; i < runs.size(); i++) {
            ((ObjectNode) runs.get(i)).remove("deliveries");
        }
        store.put(owner, NS, name, s);
    }

    // ---- targets -----------------------------------------------------------------------------------------------------

    private ContentExporter.Target target(JsonNode t) {
        return target(t, null);
    }

    private ContentExporter.Target target(JsonNode t, String date) {
        String type = t.path("type").asText("");
        if (!type.equals("report") && !type.equals("view")) {
            throw invalid("target.type is report or view");
        }
        String id = t.path("id").asText("").strip();
        if (id.isEmpty() || (type.equals("view") && id.indexOf('/') < 1)) {
            throw invalid(type.equals("report") ? "target.id is the report's id" : "target.id is kind/id, for example TRD/T-1001");
        }
        JsonNode state = t.path("state");
        if (!state.isMissingNode() && !state.isNull() && !state.isObject()) {
            throw invalid("target.state is the report's view state, an object");
        }
        return new ContentExporter.Target(type, id, t.path("page").asText(""), t.path("visual").asText(""), state.isObject() ? state : null, date, t.path("view").asText(""));
    }

    private ObjectNode targetJson(ContentExporter.Target t) {
        ObjectNode n = json.createObjectNode().put("type", t.type()).put("id", t.id()).put("page", nz(t.page())).put("visual", nz(t.visual())).put("view", nz(t.view()));
        if (t.state() != null) {
            n.set("state", t.state());
        }
        if (!t.isReport()) {
            n.put("kind", t.id().substring(0, t.id().indexOf('/')));
        }
        return n;
    }

    private static String defaultTitle(ContentExporter.Target t) {
        return t.isReport() ? "Report " + t.id() + (t.page().isBlank() ? "" : " · " + t.page()) : "View " + t.id();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String clip(String s) {
        String t = s == null ? "" : s.replaceAll("[\\r\\n]+", " ").strip();
        return t.length() > 240 ? t.substring(0, 239) + "…" : t;
    }

    private static DrishtiException invalid(String message) {
        return new DrishtiException(ErrorCode.SUBSCRIPTION_INVALID, message);
    }

    private static DrishtiException notFound(String name) {
        return new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no subscription '" + name + "'");
    }

    @Override
    public void close() {
        ticker.shutdownNow();
    }
}
