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
import com.ash.drishti.server.bi.BiAccess;
import com.ash.drishti.server.bi.BiService;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The BI runs API (docs/architecture/BI_RUNS.md section 14.1). A run is seen by its runner and owner; a bi-admin sees its state, never its result.
 * Sharing, promotion (section 9.4), export and schedules are built (R6).
 */
@RestController
@RequestMapping("/api/v1/bi")
public class RunsController {

    private final RunsProperties props;
    private final BiService bi;
    private final BiAccess access;
    private final RunSources sources;
    private final ExecutorService executor;

    private final org.springframework.beans.factory.ObjectProvider<com.ash.drishti.server.bi.engine.QueryService> queries;
    private final RunSharing sharing;
    private final RunSchedules schedules;
    private final RunPromotion promotion;
    private final org.springframework.beans.factory.ObjectProvider<com.ash.drishti.server.net.RunCluster> cluster;
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /** The cluster when this node is a member of a running one and the request is this node's own client's (a forwarded one is answered here only). */
    private com.ash.drishti.server.net.RunCluster cluster() {
        com.ash.drishti.server.net.RunCluster c = cluster == null ? null : cluster.getIfAvailable();
        return c != null && c.on() && !com.ash.drishti.server.net.NetCaller.fromPeer() ? c : null;
    }

    /**
     * A heavy run of a model another node owns goes to that node (13.1): the same submit, for the caller (their rights there); its answer comes back as
     * it is. A node that cannot be reached leaves the run here (runs are reads: running it here is always correct). Empty: run it here.
     */
    private Optional<ResponseEntity<?>> toOwner(RunService.Question q, JsonNode body, Duration wait, Principal p) {
        com.ash.drishti.server.net.RunCluster c = cluster();
        if (c == null || q.model() == null || q.model().isBlank()) {
            return Optional.empty();
        }
        boolean heavy = q.planned() != null ? q.planned().tier() == Tier.AD_HOC_HEAVY : wait.isZero();
        if (!heavy && !wait.isZero()) {
            return Optional.empty();                                            // interactive and known heavy: answered here (asked queries are stateless)
        }
        String owner = c.owner(q.model());
        if (owner.equals(c.self())) {
            return Optional.empty();
        }
        try {
            com.ash.drishti.net.transport.PeerHttp.Answer a = c.call(owner, "POST", "/api/v1/bi/runs", JSON.writeValueAsBytes(body), p, c.timeout());
            if (a.status() == 502 || a.status() == 503) {
                return Optional.empty();
            }
            String type = a.header("content-type");
            return Optional.of(ResponseEntity.status(a.status()).header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .contentType(MediaType.parseMediaType(type == null ? "application/json" : type)).body(a.body()));
        } catch (IOException e) {
            return Optional.empty();                                            // the owner died before answering: this node runs it (13.3)
        }
    }

    /** The caller's runs on the other nodes (13.2, a per-node database), each page as it answers; the nodes that did not answer in {@code failed}. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> scatter(String pathAndQuery, Principal p, List<String> failed) {
        com.ash.drishti.server.net.RunCluster c = cluster();
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        if (c == null) {
            return out;
        }
        List<java.util.concurrent.Future<Map<String, Object>>> asks = new java.util.ArrayList<>();
        List<String> peers = c.peers();
        for (String peer : peers) {
            asks.add(executor.submit(() -> {
                com.ash.drishti.net.transport.PeerHttp.Answer a = c.call(peer, "GET", pathAndQuery, null, p, c.timeout());
                if (a.status() != 200) {
                    throw new IOException(peer + " answered " + a.status());
                }
                return JSON.readValue(a.body(), Map.class);
            }));
        }
        for (int i = 0; i < peers.size(); i++) {
            try {
                Map<String, Object> page = asks.get(i).get(c.timeout().toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
                if (page.get("runs") instanceof List<?> l) {
                    l.forEach(x -> out.add((Map<String, Object>) x));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failed.add(peers.get(i));
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
                asks.get(i).cancel(true);
                failed.add(peers.get(i));
            }
        }
        return out;
    }
    private final org.springframework.beans.factory.ObjectProvider<com.ash.drishti.server.dist.BiExporter> exporter;

    public RunsController(RunsProperties props, BiService bi, BiAccess access, RunSources sources, ExecutorService drishtiVirtualExecutor,
            org.springframework.beans.factory.ObjectProvider<com.ash.drishti.server.bi.engine.QueryService> queries, RunSharing sharing,
            org.springframework.beans.factory.ObjectProvider<com.ash.drishti.server.dist.BiExporter> exporter, RunSchedules schedules, RunPromotion promotion,
            org.springframework.beans.factory.ObjectProvider<com.ash.drishti.server.net.RunCluster> cluster) {
        this.cluster = cluster;
        this.schedules = schedules;
        this.promotion = promotion;
        this.queries = queries;
        this.sharing = sharing;
        this.exporter = exporter;
        this.props = props;
        this.bi = bi;
        this.access = access;
        this.sources = sources;
        this.executor = drishtiVirtualExecutor;
    }

    private RunService runs() {
        return sources.runs();
    }

    private static <T> ResponseEntity<T> noStore(int status, T body) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }

    private static DrishtiException notFound(String id) {
        return new DrishtiException(ErrorCode.RUN_NOT_FOUND, "no run '" + id + "' that you may see");
    }

    private void enabled() {
        if (!props.enabled()) {
            throw new DrishtiException(ErrorCode.BI_OFF, "BI runs are off (config/runs/runs.yaml enabled: false): ask without Prefer: respond-async");
        }
    }

    private boolean mine(Run r, Principal p) {
        return p.user().equals(r.owner()) || p.user().equals(r.runner());
    }

    private boolean recipient(Run r, Principal p) {
        return !mine(r, p) && sharing.shareOf(r.id(), p.user()).isPresent();
    }

    /** The run when the caller may see it: their own, one shared with them, or any for a bi-admin (state only). */
    private Run visible(String id, Principal p) {
        Run r = runs().get(id).orElseThrow(() -> notFound(id));
        if (!mine(r, p) && !access.has(p, BiAccess.ADMIN) && !recipient(r, p)) {
            throw notFound(id);
        }
        return r;
    }

    private Run own(String id, Principal p) {
        Run r = runs().get(id).orElseThrow(() -> notFound(id));
        if (!mine(r, p)) {
            throw notFound(id);
        }
        return r;
    }

    Map<String, Object> json(Run r, Principal p) {
        boolean has = (r.state() == RunState.DONE || r.state() == RunState.REUSED) && runs().store().has(r.fingerprint());
        RunJson.View v = RunJson.View.of(p.user()).withResult(has).withPowers(access.has(p, BiAccess.ADMIN), access.has(p, BiAccess.AUTHOR)).withExports(exporter.getIfAvailable() != null)
                .withPromote(props.promote().enabled() && has && mine(r, p) && p.user().equals(r.runner()) && ("mq".equals(r.sourceType())
                        || "sql".equals(r.sourceType())) && access.has(p, BiAccess.AUTHOR)).asRecipient(recipient(r, p));
        if (mine(r, p)) {
            v = v.withShares(sharing.shares().of(r.id()).stream().map(RunShares.Share::toMap).toList());
        }
        Map<String, Object> m = RunJson.of(r, v, Instant.now());
        m.put("confirmToken", runs().confirmToken(r.id(), p.user()));          // the owner's, while it awaits confirmation (the drawer confirms with it)
        m.put("schedule", mine(r, p) ? schedules.ofRun(r.id(), p.user()) : null);   // the owner's schedule of this run
        return m;
    }

    /** {@code since}: {@code 7d}, {@code 30d}, {@code 12h}, or an ISO instant or date; null when absent or unreadable. */
    static Instant since(String since, Instant now) {
        if (since == null || since.isBlank()) {
            return null;
        }
        String s = since.strip();
        try {
            if (s.matches("\\d+[dh]")) {
                long n = Long.parseLong(s.substring(0, s.length() - 1));
                return now.minus(s.endsWith("d") ? Duration.ofDays(n) : Duration.ofHours(n));
            }
            if (s.length() == 10) {
                return java.time.LocalDate.parse(s).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
            }
            return Instant.parse(s);
        } catch (RuntimeException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "since is 7d, 30d, 12h or an ISO date or instant, not '" + since + "'");
        }
    }

    @PostMapping("/runs")
    public ResponseEntity<?> submit(@RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        enabled();
        JsonNode options = body.path("options").isObject() ? body.path("options") : JsonNodeFactory.instance.objectNode();
        if (options.path("confirmToken").isTextual()) {                       // the question asked again with the token of its DRS-9082
            Optional<Run> confirmed = runs().confirmByToken(p.user(), options.path("confirmToken").asText());
            if (confirmed.isPresent()) {
                return sources.respond(new RunService.Outcome(null, confirmed.get()), "json", p);
            }
        }
        RunService.Question q = sources.of(p, body.path("source"), options);
        Duration wait = options.path("wait").isTextual() ? Duration.parse(options.path("wait").asText())
                : options.path("wait").isNumber() ? Duration.ofMillis((long) (options.path("wait").asDouble() * 1000)) : props.inline().inlineWait();
        Optional<ResponseEntity<?>> forwarded = toOwner(q, body, wait, p);
        if (forwarded.isPresent()) {
            return forwarded.get();
        }
        RunService.Outcome o = runs().submit(q, new RunService.Options(wait, wait.isZero(), options.path("confirm").asBoolean(false)));
        if (o.inline()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("run", json(o.run(), p));
            m.put("answer", o.answer().body());
            return noStore(200, m);
        }
        return sources.respond(o, "json", p);
    }

    @PostMapping("/runs/estimate")
    public ResponseEntity<?> estimate(@RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        RunService.Question q = sources.of(p, body.path("source"), body.path("options").isObject() ? body.path("options")
                : JsonNodeFactory.instance.objectNode());                     // binds as the caller and plans from metadata; reads no data
        String key = RunService.key(q);
        boolean reusable = q.dataVersion() != null && runs().store().has(key);
        RunPlanner.Planned plan = q.planned();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tier", plan == null ? Tier.INTERACTIVE.wire() : plan.tier().wire());
        m.put("engine", RunPlanner.LOCAL);
        m.put("estimate", plan != null ? plan.estimate().toMap() : new RunEstimate(null, null, null, null, null, null, RunEstimate.Basis.UNKNOWN, 0, 0,
                false, 0, "this question cannot be sized from metadata (it reads other questions' answers, or compiling it reads data)").toMap());
        m.put("reuse", reusable ? Map.of("key", key, "runId", runs().records().doneWithKey(key).map(Run::id).orElse("")) : null);
        m.put("confirm", plan != null && plan.confirm());
        m.put("refusal", plan == null ? null : plan.refusal().map(ErrorCode::code).orElse(null));
        return noStore(200, m);
    }

    @GetMapping("/runs")
    public ResponseEntity<?> list(@RequestParam(required = false) String state, @RequestParam(required = false) String source,
            @RequestParam(required = false) String q, @RequestParam(defaultValue = "50") int limit, @RequestParam(required = false) String after,
            @RequestParam(required = false) String since, @RequestParam(defaultValue = "true") boolean mine,
            @RequestParam(defaultValue = "true") boolean shared, @RequestAttribute(Principal.ATTRIBUTE) Principal p,
            jakarta.servlet.http.HttpServletRequest req) {
        bi.requirePower(p, BiAccess.BI);
        int n = Math.max(1, Math.min(limit, 200));
        Instant before = null;
        if (after != null && !after.isBlank()) {
            before = runs().get(after).map(Run::submittedAt).orElse(null);
        }
        Instant from = since(since, Instant.now());
        java.util.Set<String> states = state == null || state.isBlank() ? java.util.Set.of()
                : java.util.Arrays.stream(state.split(",")).map(String::strip).filter(s -> !s.isEmpty()).collect(java.util.stream.Collectors.toSet());
        // mine: the caller's own runs; shared: the runs shared with them (the Runs tab's "Who" filter, BIR-03)
        List<Run> all = new java.util.ArrayList<>(mine ? runs().list(p.user(), before, 500) : List.of());
        java.util.Set<String> seen = new java.util.HashSet<>(all.stream().map(Run::id).toList());
        final Instant until = before;
        for (RunShares.Share s : shared ? sharing.shares().to(p.user(), 200) : List.<RunShares.Share>of()) {   // marked in the console
            runs().get(s.runId()).filter(r -> seen.add(r.id())).filter(r -> until == null || r.submittedAt().isBefore(until)).ifPresent(all::add);
        }
        all.sort(java.util.Comparator.comparing(Run::submittedAt).reversed().thenComparing(Run::id, java.util.Comparator.reverseOrder()));
        List<Run> page = all.stream()
                .filter(r -> from == null || !r.submittedAt().isBefore(from))
                .filter(r -> states.isEmpty() || states.contains(r.state().wire()))
                .filter(r -> source == null || source.isBlank() || r.sourceType().equals(source))
                .filter(r -> q == null || q.isBlank() || String.valueOf(r.title()).toLowerCase(java.util.Locale.ROOT).contains(q.toLowerCase(java.util.Locale.ROOT)))
                .limit(n + 1).toList();
        boolean more = page.size() > n;
        List<Run> shown = more ? page.subList(0, n) : page;
        List<Map<String, Object>> rows = new java.util.ArrayList<>(shown.stream().map(r -> json(r, p)).toList());
        List<String> failed = new java.util.ArrayList<>();
        if (cluster() != null) {                                                // the person's runs on every node, merged by submittedAt (13.2)
            java.util.Set<Object> have = new java.util.HashSet<>(rows.stream().map(r -> r.get("id")).toList());
            for (Map<String, Object> r : scatter(req.getRequestURI() + (req.getQueryString() == null ? "" : "?" + req.getQueryString()), p, failed)) {
                if (have.add(r.get("id"))) {
                    rows.add(r);
                }
            }
            rows.sort(java.util.Comparator.comparing((Map<String, Object> r) -> String.valueOf(r.get("submittedAt"))).reversed()
                    .thenComparing(r -> String.valueOf(r.get("id")), java.util.Comparator.reverseOrder()));
            more = more || rows.size() > n;
            rows = new java.util.ArrayList<>(rows.subList(0, Math.min(n, rows.size())));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runs", rows);
        m.put("next", more && !rows.isEmpty() ? rows.get(rows.size() - 1).get("id") : null);
        m.put("storage", runs().store().usageOf(p.user()));          // the caller's own results; the node's, with the largest users, is Admin's (BIR-04)
        m.put("nodes", Map.of("failed", failed.stream().map(f -> Map.of("node", f, "reason", "node " + f
                + " is not reachable: its runs' state is unknown until it returns")).toList()));
        return noStore(200, m);
    }

    @GetMapping("/runs/settings")
    public ResponseEntity<?> settings(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        return noStore(200, props.console());
    }

    @GetMapping("/runs/schedules")
    public ResponseEntity<?> schedules(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        return noStore(200, Map.of("schedules", schedules.of(p.user()).stream().map(RunSchedules.Schedule::toMap).toList()));
    }

    @PutMapping("/runs/schedules/{id}")
    public ResponseEntity<?> changeSchedule(@PathVariable String id, @RequestBody(required = false) JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        String text = body != null && body.path("schedule").isTextual() ? body.path("schedule").asText() : null;
        Boolean on = body != null && body.path("enabled").isBoolean() ? body.path("enabled").asBoolean() : null;
        return noStore(200, schedules.change(id, p.user(), text, on).toMap());
    }

    @DeleteMapping("/runs/schedules/{id}")
    public ResponseEntity<?> deleteSchedule(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        schedules.delete(id, p.user());
        return ResponseEntity.noContent().build();
    }

    /** {@code hello} (the caller's active runs), then a {@code run} event per change, and {@code status} every 15 s. */
    @GetMapping(path = "/runs/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@RequestAttribute(Principal.ATTRIBUTE) Principal p) throws IOException {
        bi.requirePower(p, BiAccess.BI);
        SseEmitter e = new SseEmitter(0L);
        List<Map<String, Object>> active = runs().list(p.user(), null, 200).stream().filter(r -> !r.state().finished()).map(r -> json(r, p)).toList();
        e.send(SseEmitter.event().name("hello").data(RunJson.write(Map.of("runs", active))));
        java.util.concurrent.atomic.AtomicBoolean open = new java.util.concurrent.atomic.AtomicBoolean(true);
        Runnable stop = runs().listen(p.user(), ev -> {
            try {
                e.send(SseEmitter.event().name("run").data(RunJson.write(ev)));
            } catch (IOException | IllegalStateException x) {
                open.set(false);
            }
        });
        relay(e, open, p);                                                      // the person's runs on the other nodes too (13.2)
        e.onCompletion(() -> {
            open.set(false);
            stop.run();
        });
        e.onTimeout(() -> {
            open.set(false);
            stop.run();
        });
        executor.execute(() -> {
            try {
                while (open.get()) {
                    Thread.sleep(15_000);
                    e.send(SseEmitter.event().name("status").data("{}"));
                }
            } catch (InterruptedException x) {
                Thread.currentThread().interrupt();
            } catch (IOException | IllegalStateException x) {
                // the client went away
            } finally {
                stop.run();
            }
        });
        return e;
    }

    /** Relays the {@code run} events of the caller's runs on every other node alive now into this stream, until it closes. */
    private void relay(SseEmitter e, java.util.concurrent.atomic.AtomicBoolean open, Principal p) {
        com.ash.drishti.server.net.RunCluster c = cluster();
        if (c == null) {
            return;
        }
        for (String peer : c.peers()) {
            executor.execute(() -> {
                try {
                    java.net.http.HttpResponse<java.io.InputStream> s = c.stream(peer, "/api/v1/bi/runs/events", p);
                    if (s.statusCode() != 200) {
                        s.body().close();
                        return;
                    }
                    try (java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(s.body(), java.nio.charset.StandardCharsets.UTF_8))) {
                        String event = null;
                        String line;
                        while (open.get() && (line = in.readLine()) != null) {
                            if (line.startsWith("event:")) {
                                event = line.substring(6).strip();
                            } else if (line.startsWith("data:") && "run".equals(event)) {
                                e.send(SseEmitter.event().name("run").data(line.substring(5).strip()));
                            }
                        }
                    }
                } catch (IOException | IllegalStateException x) {
                    // the peer or the client went away: the relay ends
                }
            });
        }
    }

    @GetMapping("/runs/{id}")
    public ResponseEntity<?> get(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        return noStore(200, json(visible(id, p), p));
    }

    /** The stored answer, for the runner only (a result is never served across signatures; an administrator sees state, never values). */
    @GetMapping("/runs/{id}/result")
    public ResponseEntity<?> result(@PathVariable String id, @RequestParam(defaultValue = "json") String format, @RequestParam(defaultValue = "0") long offset,
            @RequestParam(defaultValue = "5000") int limit, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        Run r = runs().get(id).orElseThrow(() -> notFound(id));
        boolean own = mine(r, p);
        if (!own && !recipient(r, p)) {
            throw notFound(id);
        }
        if (!"json".equalsIgnoreCase(format)) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a stored run result is served as json (format=json); arrow is not built yet");
        }
        Map<String, Object> full = own ? runs().result(r, r.signature()) : sharing.read(r, p);      // a recipient reads their own version (9.3)
        Map<String, Object> answer = RunStore.page(full, offset, Math.max(1, Math.min(limit, 50_000)));
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").header("X-Bi-Run", r.id()).body(answer);
    }

    @DeleteMapping("/runs/{id}/result")
    public ResponseEntity<?> deleteResult(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        Run r = runs().get(id).orElseThrow(() -> notFound(id));
        if (!mine(r, p) && !access.has(p, BiAccess.ADMIN)) {
            throw notFound(id);
        }
        runs().deleteResult(r);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/runs/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        own(id, p);
        return noStore(202, json(runs().cancel(id, p.user(), false, null), p));
    }

    @PostMapping("/runs/{id}/rerun")
    public ResponseEntity<?> rerun(@PathVariable String id, @RequestBody(required = false) JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        enabled();
        Run r = own(id, p);
        RunService.Question q = sources.replay(r, p).orElseThrow(() -> new DrishtiException(ErrorCode.BAD_REQUEST, "this run cannot be asked again here"));
        Duration wait = body != null && body.path("wait").isTextual() ? Duration.parse(body.path("wait").asText()) : props.inline().inlineWait();
        RunService.Outcome o = runs().submit(q, new RunService.Options(wait, wait.isZero()));
        Run.Builder b = o.run().toBuilder();
        b.rerunOf = r.id();
        Run linked = b.build();
        if (!o.inline()) {
            runs().records().save(linked);
            return sources.respond(new RunService.Outcome(null, linked), "json", p);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("run", json(linked, p));
        m.put("answer", o.answer().body());
        return noStore(200, m);
    }

    @PostMapping("/runs/{id}/keep")
    public ResponseEntity<?> keep(@PathVariable String id, @RequestBody(required = false) JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        Run r = own(id, p);
        int days = body != null && body.path("days").isNumber() ? body.path("days").asInt() : props.retention().days();
        return noStore(200, json(runs().keep(r, days), p));
    }

    @PostMapping("/runs/{id}/confirm")
    public ResponseEntity<?> confirm(@PathVariable String id, @RequestBody(required = false) JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        own(id, p);
        String token = body != null && body.path("confirmToken").isTextual() ? body.path("confirmToken").asText() : null;
        return noStore(202, json(runs().confirm(id, p.user(), token), p));
    }

    @PostMapping("/runs/{id}/export")
    public ResponseEntity<?> export(@PathVariable String id, @RequestBody(required = false) JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        Run r = runs().get(id).orElseThrow(() -> notFound(id));
        boolean own = mine(r, p);
        if (!own && !recipient(r, p)) {
            throw notFound(id);
        }
        String format = body != null && body.path("format").isTextual() ? body.path("format").asText().toLowerCase(java.util.Locale.ROOT) : "csv";
        if (!format.equals("csv") && !format.equals("xlsx")) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a run's result exports as csv or xlsx, not '" + format + "'");
        }
        com.ash.drishti.server.dist.BiExporter x = exporter.getIfAvailable();
        if (x == null) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "exports are not available on this server (distribution is off)");
        }
        Map<String, Object> answer = own ? runs().result(r, r.signature()) : sharing.read(r, p);       // the reader's own version, masks applied
        String title = RunService.title(r);
        com.ash.drishti.server.dist.ExportDoc doc = x.ofAnswer(title, "Run " + r.id() + (r.asOf() == null ? "" : " · as of " + r.asOf()), answer);
        byte[] bytes = format.equals("csv") ? com.ash.drishti.server.dist.CsvWriter.write(doc) : com.ash.drishti.server.dist.XlsxWriter.write(doc);
        String file = title.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^-+|-+$", "") + "." + format;
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").header("X-Bi-Run", r.id())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + (file.startsWith(".") ? "run" + file : file) + "\"")
                .contentType(format.equals("csv") ? MediaType.parseMediaType("text/csv; charset=utf-8")
                        : MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }

    @PostMapping("/runs/{id}/share")
    public ResponseEntity<?> share(@PathVariable String id, @RequestBody(required = false) JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        Run r = own(id, p);
        List<String> to = new java.util.ArrayList<>();
        if (body != null && body.path("to").isArray()) {
            body.path("to").forEach(n -> to.add(n.asText()));
        } else if (body != null && body.path("to").isTextual()) {
            to.add(body.path("to").asText());
        }
        if (to.isEmpty()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "to: name the people (bob) or roles (role:risk-team) to share this run with");
        }
        return noStore(200, Map.of("shares", sharing.share(r, p, to)));
    }

    /**
     * Promotes the caller's finished run (section 9.4): {@code {name?, title?, to: dataset|report}}; the snapshot as the caller sees it and a draft dataset
     * (and report) in their workspace. Needs {@code bi-author} and {@code promote.enabled}; a share's recipient cannot promote another's run.
     */
    @PostMapping("/runs/{id}/promote")
    public ResponseEntity<?> promote(@PathVariable String id, @RequestBody(required = false) JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.AUTHOR);
        enabled();
        Run r = own(id, p);
        if (!p.user().equals(r.runner())) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "only the person the run was computed as may promote it (it holds their view of the data)");
        }
        String name = body != null && body.path("name").isTextual() ? body.path("name").asText() : null;
        String title = body != null && body.path("title").isTextual() ? body.path("title").asText() : null;
        String to = body != null && body.path("to").isTextual() ? body.path("to").asText() : "dataset";
        return noStore(201, promotion.promote(r, p, name, title, to).toMap());
    }

    @PostMapping("/runs/{id}/schedule")
    public ResponseEntity<?> schedule(@PathVariable String id, @RequestBody(required = false) JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        enabled();
        Run r = own(id, p);
        if (!"mq".equals(r.sourceType()) && !"sql".equals(r.sourceType()) && !"report".equals(r.sourceType())) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a " + r.sourceType() + " run cannot be scheduled");
        }
        String text = body != null && body.path("schedule").isTextual() ? body.path("schedule").asText() : "";
        if (text.isBlank()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "schedule: when to run it (daily 07:30, business-days 07:30, weekly mon 08:00, monthly last 18:00)");
        }
        String data = body.path("data").isTextual() ? body.path("data").asText() : "latest";
        return noStore(201, schedules.create(r, p, text, data).toMap());
    }

    @DeleteMapping("/runs/{id}/share/{recipient}")
    public ResponseEntity<?> unshare(@PathVariable String id, @PathVariable String recipient, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        own(id, p);
        if (!sharing.unshare(id, recipient)) {
            throw notFound(id + "/share/" + recipient);
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/admin/runs")
    public ResponseEntity<?> admin(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.ADMIN);
        Map<String, Object> raw = runs().admin();
        @SuppressWarnings("unchecked")
        List<Run> running = (List<Run>) raw.get("running");
        com.ash.drishti.server.bi.engine.QueryService q = queries == null ? null : queries.getIfAvailable();
        Map<String, Object> admission = q == null || q.admission() == null ? null : q.admission().stats();
        List<Map<String, Object>> suggestions = runs().suggestions().of(runs().finishedLog(), Instant.now(), sources.rollupBuilt(p)).stream()
                .map(RunSuggestions.Suggestion::toMap).toList();
        Double p95 = q == null || q.admission() == null ? null : q.admission().interactiveWaitP95Ms(Duration.ofMinutes(5));
        return noStore(200, RunAdmin.of(runs(), admission, p95, running.stream().map(r -> json(r, p)).toList(), suggestions));
    }

    /** Accepts a rollup suggestion (8.3a): the rollup is added to the model's draft, reviewed and published as any change. */
    @PostMapping("/admin/runs/suggestions/{sid}/accept")
    public ResponseEntity<?> acceptSuggestion(@PathVariable String sid, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.ADMIN);
        return noStore(200, runs().suggestions().accept(sid, runs().finishedLog(), Instant.now(), p).toMap());
    }

    @PostMapping("/admin/runs/suggestions/{sid}/dismiss")
    public ResponseEntity<?> dismissSuggestion(@PathVariable String sid, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.ADMIN);
        return noStore(200, runs().suggestions().dismiss(sid, runs().finishedLog(), Instant.now(), p.user()).toMap());
    }

    @PostMapping("/admin/runs/{id}/cancel")
    public ResponseEntity<?> adminCancel(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.ADMIN);
        return noStore(202, json(runs().cancel(id, p.user(), true, null), p));
    }
}
