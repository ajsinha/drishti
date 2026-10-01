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
package com.ash.drishti.server.reports;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.search.SearchQuery;
import com.ash.drishti.engine.time.BusinessDates;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.PreferenceStore;
import com.ash.drishti.identity.UserService;
import com.ash.drishti.server.api.SearchController;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scheduled reports: a saved search, run as its owner (their roles and redaction at run time) on a schedule, delivered
 * as CSV to a folder or posted to an allowed webhook. Kept with the owner's preferences (namespace {@code reports}),
 * with their last runs. A report whose owner is disabled or deleted does not run.
 */
public final class ReportService implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ReportService.class);
    static final String NS = "reports";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("HHmm");
    private final PreferenceStore store;
    private final SearchController search;
    private final BusinessDates dates;
    private final UserService users;
    private final AuditLog audit;
    private final ReportProperties props;
    private final boolean security;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("drishti-reports").factory());

    public ReportService(PreferenceStore store, SearchController search, BusinessDates dates, UserService users, AuditLog audit,
            ReportProperties props, boolean security) {
        this.store = store;
        this.search = search;
        this.dates = dates;
        this.users = users;
        this.audit = audit;
        this.props = props;
        this.security = security;
        if (props.enabled()) {
            long ms = props.tick().toMillis();
            ticker.scheduleWithFixedDelay(this::tick, ms, ms, TimeUnit.MILLISECONDS);
        }
    }

    public List<JsonNode> of(String owner) {
        List<JsonNode> out = new ArrayList<>();
        store.keys(owner, NS).forEach(k -> store.get(owner, NS, k).ifPresent(out::add));
        return out;
    }

    public Optional<JsonNode> get(String owner, String name) {
        return store.get(owner, NS, name);
    }

    /**
     * Saves a report: {@code {"query": "TRD where mtm < -1000000", "schedule": "business-days 18:30", "deliver": "folder",
     * "webhook": "https://…", "date": "today" | "previous", "enabled": true}}. Its run history is kept.
     */
    public JsonNode save(String owner, String name, JsonNode body) {
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9 ._-]{0,63}")) {
            throw bad("a report's name is 1-64 letters, digits, spaces, dots, dashes or underscores");
        }
        String query = body.path("query").asText("").trim();
        try {
            SearchQuery.pick(query);
        } catch (RuntimeException e) {
            throw bad("the query does not parse: " + e.getMessage());
        }
        String schedule = body.path("schedule").asText("");
        try {
            ReportSchedule.parse(schedule);
        } catch (IllegalArgumentException e) {
            throw bad(e.getMessage());
        }
        String deliver = body.path("deliver").asText("folder");
        String webhook = body.path("webhook").asText("");
        if (!deliver.equals("folder") && !deliver.equals("webhook")) {
            throw bad("deliver is folder or webhook (email needs SMTP settings, not configured on this server)");
        }
        if (deliver.equals("webhook") && !props.webhookAllowed(webhook)) {
            throw bad("this webhook is not allowed: an administrator lists allowed URL prefixes in drishti.reports.webhooks");
        }
        String date = body.path("date").asText("today");
        if (!date.equals("today") && !date.equals("previous")) {
            throw bad("date is today or previous (the business day before)");
        }
        Optional<JsonNode> old = store.get(owner, NS, name);
        if (old.isEmpty() && store.keys(owner, NS).size() >= props.perUser()) {
            throw bad("at most " + props.perUser() + " reports per person");
        }
        ObjectNode r = json.createObjectNode().put("name", name).put("owner", owner).put("query", query).put("schedule", schedule.trim())
                .put("deliver", deliver).put("webhook", deliver.equals("webhook") ? webhook : "").put("date", date)
                .put("enabled", body.path("enabled").asBoolean(true));
        r.put("nextRun", nextRun(r, Instant.now()));
        r.set("runs", old.<JsonNode>map(o -> o.path("runs").deepCopy()).orElse(json.createArrayNode()));
        store.put(owner, NS, name, r);
        audit.record(owner, "report.save", "report/" + owner + "/" + name, query + " · " + schedule);
        return r;
    }

    public boolean delete(String owner, String name) {
        boolean gone = store.delete(owner, NS, name);
        if (gone) {
            audit.record(owner, "report.delete", "report/" + owner + "/" + name, "");
        }
        return gone;
    }

    /** Runs a report now (by hand or when due) and records the run; returns the run. */
    public JsonNode run(String owner, String name, String trigger) {
        JsonNode r = get(owner, name).orElseThrow(() -> new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no report '" + name + "'"));
        String key = owner + "\u001f" + name;
        if (!running.add(key)) {
            throw bad("'" + name + "' is already running");
        }
        ObjectNode run = json.createObjectNode().put("at", Instant.now().toString()).put("trigger", trigger);
        try {
            Principal who = principal(owner);
            LocalDate day = r.path("date").asText().equals("previous") ? dates.calendar().previous(dates.current()) : dates.current();
            String csv = search.csv(r.path("query").asText(), AsOf.of(day), who);
            int rows = Math.max(0, csv.split("\r\n", -1).length - 2);
            run.put("businessDate", day.toString()).put("rows", rows).put("bytes", csv.getBytes(StandardCharsets.UTF_8).length);
            run.put("target", deliver(r, owner, name, day, csv)).put("status", "ok");
        } catch (RuntimeException | IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            run.put("status", "failed").put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            LOG.warn("report {} of {} failed: {}", name, owner, e.toString());
        } finally {
            running.remove(key);
        }
        record(owner, name, run);
        audit.record(owner, "report.run", "report/" + owner + "/" + name, run.path("status").asText() + " · " + trigger);
        return run;
    }

    private void record(String owner, String name, ObjectNode run) {
        get(owner, name).ifPresent(cur -> {                   // the report may have changed or gone while it ran
            ObjectNode r = cur.deepCopy();
            ArrayNode runs = json.createArrayNode().add(run);
            cur.path("runs").forEach(x -> {
                if (runs.size() < props.keepRuns()) {
                    runs.add(x);
                }
            });
            r.set("runs", runs);
            r.put("nextRun", nextRun(r, Instant.now()));
            store.put(owner, NS, name, r);
        });
    }

    private String deliver(JsonNode r, String owner, String name, LocalDate day, String csv) throws IOException, InterruptedException {
        if (r.path("deliver").asText().equals("webhook")) {
            String url = r.path("webhook").asText();
            if (!props.webhookAllowed(url)) {                  // the allow-list may have changed since it was saved
                throw new IllegalStateException("the webhook is no longer allowed");
            }
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "text/csv; charset=utf-8").header("X-Drishti-Report", name).header("X-Drishti-Owner", owner)
                    .header("X-Drishti-Business-Date", day.toString()).POST(HttpRequest.BodyPublishers.ofString(csv, StandardCharsets.UTF_8)).build();
            HttpResponse<Void> res = http.send(req, HttpResponse.BodyHandlers.discarding());
            if (res.statusCode() / 100 != 2) {
                throw new IllegalStateException("the webhook answered " + res.statusCode());
            }
            return url;
        }
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
        Path dir = Path.of(props.folder()).toAbsolutePath().normalize().resolve(owner).resolve(safe);
        Files.createDirectories(dir);
        Path file = dir.resolve(safe + "-" + day + "-" + ZonedDateTime.now(dates.zone()).format(STAMP) + ".csv");
        Path tmp = Files.createTempFile(dir, ".", ".part");
        Files.writeString(tmp, csv, StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return file.toString();
    }

    /** The owner as they are now: a disabled or deleted owner's reports do not run. */
    private Principal principal(String owner) {
        var u = users.find(owner);
        if (u.isPresent()) {
            if (!u.get().enabled()) {
                throw new IllegalStateException(owner + " is disabled");
            }
            return new Principal(owner, List.copyOf(u.get().roles()));
        }
        if (security) {
            throw new IllegalStateException(owner + " no longer exists");
        }
        return new Principal(owner, List.of());                // security off: roles are not checked
    }

    private String nextRun(JsonNode r, Instant after) {
        if (!r.path("enabled").asBoolean(true)) {
            return null;
        }
        ZonedDateTime next = ReportSchedule.parse(r.path("schedule").asText()).next(after.atZone(dates.zone()), dates.zone(), dates.calendar());
        return next == null ? null : next.toInstant().toString();
    }

    /** Runs every enabled report that is due, each on its own virtual thread. */
    void tick() {
        try {
            Instant now = Instant.now();
            for (String owner : store.users()) {
                for (String name : store.keys(owner, NS)) {
                    store.get(owner, NS, name).ifPresent(r -> {
                        String next = r.path("nextRun").asText(null);
                        if (r.path("enabled").asBoolean(true) && next != null && !Instant.parse(next).isAfter(now)
                                && !running.contains(owner + "\u001f" + name)) {
                            Thread.ofVirtual().name("drishti-report-" + name).start(() -> run(owner, name, "schedule"));
                        }
                    });
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("report scheduler: {}", e.toString());
        }
    }

    private static DrishtiException bad(String message) {
        return new DrishtiException(ErrorCode.BAD_REQUEST, message);
    }

    @Override
    public void close() {
        ticker.shutdownNow();
    }
}
