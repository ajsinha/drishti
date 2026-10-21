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
 * Sharing, promotion, export and schedules (R6) are not built yet and answer {@code DRS-5001} saying so.
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

    public RunsController(RunsProperties props, BiService bi, BiAccess access, RunSources sources, ExecutorService drishtiVirtualExecutor,
            org.springframework.beans.factory.ObjectProvider<com.ash.drishti.server.bi.engine.QueryService> queries) {
        this.queries = queries;
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

    /** The run when the caller may see it: their own, or any for a bi-admin (state only). */
    private Run visible(String id, Principal p) {
        Run r = runs().get(id).orElseThrow(() -> notFound(id));
        if (!mine(r, p) && !access.has(p, BiAccess.ADMIN)) {
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
        RunJson.View v = RunJson.View.of(p.user()).withResult(has).withPowers(access.has(p, BiAccess.ADMIN), access.has(p, BiAccess.AUTHOR))
                .withPromote(false);
        Map<String, Object> m = RunJson.of(r, v, Instant.now());
        m.put("confirmToken", runs().confirmToken(r.id(), p.user()));          // the owner's, while it awaits confirmation (the drawer confirms with it)
        m.put("schedule", null);
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
            @RequestParam(defaultValue = "true") boolean shared, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        int n = Math.max(1, Math.min(limit, 200));
        Instant before = null;
        if (after != null && !after.isBlank()) {
            before = runs().get(after).map(Run::submittedAt).orElse(null);
        }
        Instant from = since(since, Instant.now());
        java.util.Set<String> states = state == null || state.isBlank() ? java.util.Set.of()
                : java.util.Arrays.stream(state.split(",")).map(String::strip).filter(s -> !s.isEmpty()).collect(java.util.stream.Collectors.toSet());
        // every listed run is the caller's own until runs are shared (R6): mine=false (the Runs tab's "Shared with me") lists none (BIR-03)
        List<Run> page = !mine ? List.of() : runs().list(p.user(), before, 500).stream()
                .filter(r -> from == null || !r.submittedAt().isBefore(from))
                .filter(r -> states.isEmpty() || states.contains(r.state().wire()))
                .filter(r -> source == null || source.isBlank() || r.sourceType().equals(source))
                .filter(r -> q == null || q.isBlank() || String.valueOf(r.title()).toLowerCase(java.util.Locale.ROOT).contains(q.toLowerCase(java.util.Locale.ROOT)))
                .limit(n + 1).toList();
        boolean more = page.size() > n;
        List<Run> shown = more ? page.subList(0, n) : page;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runs", shown.stream().map(r -> json(r, p)).toList());
        m.put("next", more ? shown.get(shown.size() - 1).id() : null);
        m.put("storage", runs().store().usageOf(p.user()));          // the caller's own results; the node's, with the largest users, is Admin's (BIR-04)
        m.put("nodes", Map.of("failed", List.of()));
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
        return noStore(200, Map.of("schedules", List.of()));
    }

    @PutMapping("/runs/schedules/{id}")
    public ResponseEntity<?> changeSchedule(@PathVariable String id, @RequestBody(required = false) JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        throw new DrishtiException(ErrorCode.RUN_NOT_FOUND, "no run schedule '" + id + "' of yours");
    }

    @DeleteMapping("/runs/schedules/{id}")
    public ResponseEntity<?> deleteSchedule(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        throw new DrishtiException(ErrorCode.RUN_NOT_FOUND, "no run schedule '" + id + "' of yours");
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
        Run r = own(id, p);
        if (!"json".equalsIgnoreCase(format)) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a stored run result is served as json (format=json); arrow is not built yet");
        }
        Map<String, Object> answer = RunStore.page(runs().result(r, r.signature()), offset, Math.max(1, Math.min(limit, 50_000)));
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
        return notYet(id, "export", p);
    }

    @PostMapping("/runs/{id}/share")
    public ResponseEntity<?> share(@PathVariable String id, @RequestBody(required = false) JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return notYet(id, "share", p);
    }

    @PostMapping("/runs/{id}/promote")
    public ResponseEntity<?> promote(@PathVariable String id, @RequestBody(required = false) JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return notYet(id, "promote", p);
    }

    @PostMapping("/runs/{id}/schedule")
    public ResponseEntity<?> schedule(@PathVariable String id, @RequestBody(required = false) JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return notYet(id, "schedule", p);
    }

    private ResponseEntity<?> notYet(String id, String action, Principal p) {
        bi.requirePower(p, BiAccess.BI);
        own(id, p);
        throw new DrishtiException(ErrorCode.BAD_REQUEST, "run " + action + " is not built yet (2.6.7 step R6): open the result and export it from the grid");
    }

    @DeleteMapping("/runs/{id}/share/{recipient}")
    public ResponseEntity<?> unshare(@PathVariable String id, @PathVariable String recipient, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        own(id, p);
        throw notFound(id + "/share/" + recipient);
    }

    @GetMapping("/admin/runs")
    public ResponseEntity<?> admin(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.ADMIN);
        Map<String, Object> raw = runs().admin();
        @SuppressWarnings("unchecked")
        List<Run> running = (List<Run>) raw.get("running");
        com.ash.drishti.server.bi.engine.QueryService q = queries == null ? null : queries.getIfAvailable();
        Map<String, Object> admission = q == null || q.admission() == null ? null : q.admission().stats();
        return noStore(200, RunAdmin.of(runs(), admission, null, running.stream().map(r -> json(r, p)).toList()));
    }

    @PostMapping("/admin/runs/{id}/cancel")
    public ResponseEntity<?> adminCancel(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.ADMIN);
        return noStore(202, json(runs().cancel(id, p.user(), true, null), p));
    }
}
