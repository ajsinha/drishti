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
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
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
 * The BI runs API (docs/architecture/BI_RUNS.md section 14.1): every endpoint, answering the documented shapes. R1: stubs over no runs
 * (the contract the console builds against); the pipeline behind them comes with R4 to R8.
 */
@RestController
@RequestMapping("/api/v1/bi")
public class RunsController {

    private final RunsProperties props;
    private final BiService bi;

    public RunsController(RunsProperties props, BiService bi) {
        this.props = props;
        this.bi = bi;
    }

    private static <T> ResponseEntity<T> noStore(int status, T body) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }

    private static DrishtiException notFound(String id) {
        return new DrishtiException(ErrorCode.RUN_NOT_FOUND, "no run '" + id + "' that you may see");
    }

    @PostMapping("/runs")
    public ResponseEntity<?> submit(@RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        Instant now = Instant.now();
        String id = RunId.next("local", now, new SplittableRandom());
        Run r = Run.planned(id, p.user(), p.user(), body.path("source").path("type").asText("mq"), "{}", "stub", "stub", Tier.AD_HOC_HEAVY, "local",
                "{\"readRows\":null,\"buildRows\":null,\"bytes\":null,\"rowsOut\":null,\"seconds\":{\"low\":null,\"high\":null},\"basis\":\"unknown\","
                        + "\"dates\":0,\"datesToBuild\":0,\"covered\":false,\"why\":\"stub\"}", "local", now).moveTo(RunState.QUEUED, null, null, now);
        return ResponseEntity.accepted().location(URI.create("/api/v1/bi/runs/" + id)).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Map.of("run", RunJson.of(r, RunJson.View.of(p.user()), now)));
    }

    @PostMapping("/runs/estimate")
    public ResponseEntity<?> estimate(@RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tier", Tier.INTERACTIVE.wire());
        m.put("engine", "local");
        m.put("estimate", new RunEstimate(null, null, null, null, null, null, RunEstimate.Basis.UNKNOWN, 0, 0, false, 0, "stub").toMap());
        m.put("reuse", null);
        m.put("confirm", false);
        return noStore(200, m);
    }

    @GetMapping("/runs")
    public ResponseEntity<?> list(@RequestParam Map<String, String> query, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runs", List.of());
        m.put("next", null);
        m.put("storage", Map.of("bytes", 0, "results", 0));
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

    @GetMapping(path = "/runs/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@RequestAttribute(Principal.ATTRIBUTE) Principal p) throws IOException {
        bi.requirePower(p, BiAccess.BI);
        SseEmitter e = new SseEmitter(0L);
        e.send(SseEmitter.event().name("hello").data(RunJson.write(Map.of("runs", List.of()))));
        return e;
    }

    @GetMapping("/runs/{id}")
    public ResponseEntity<?> get(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        throw notFound(id);
    }

    @GetMapping("/runs/{id}/result")
    public ResponseEntity<?> result(@PathVariable String id, @RequestParam(required = false) String format, @RequestParam(defaultValue = "0") long offset,
            @RequestParam(defaultValue = "5000") int limit, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        throw notFound(id);
    }

    @DeleteMapping("/runs/{id}/result")
    public ResponseEntity<?> deleteResult(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        throw notFound(id);
    }

    @PostMapping("/runs/{id}/{action:confirm|cancel|rerun|export|share|promote|keep|schedule}")
    public ResponseEntity<?> action(@PathVariable String id, @PathVariable String action, @RequestBody(required = false) JsonNode body,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        throw notFound(id);
    }

    @DeleteMapping("/runs/{id}/share/{recipient}")
    public ResponseEntity<?> unshare(@PathVariable String id, @PathVariable String recipient, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        throw notFound(id);
    }

    @GetMapping("/admin/runs")
    public ResponseEntity<?> admin(@RequestParam Map<String, String> query, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.ADMIN);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("queue", Map.of("byClass", Map.of(), "byUser", Map.of()));
        m.put("running", List.of());
        m.put("storage", Map.of("results", Map.of("bytes", 0, "count", 0), "snapshots", Map.of("bytes", 0, "count", 0)));
        m.put("accuracy", Map.of());
        m.put("nodes", List.of());
        return noStore(200, m);
    }

    @PostMapping("/admin/runs/{id}/cancel")
    public ResponseEntity<?> adminCancel(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.ADMIN);
        throw notFound(id);
    }
}
