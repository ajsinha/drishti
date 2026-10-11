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
package com.ash.drishti.server.bi.report;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.bi.engine.BiLiveProperties;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Business intelligence, phase 3 (docs/architecture/RUPAKA.md section 8): reports. The viewer asks for a report once and then for each visual's
 * data as the person works; every answer is the dataset engine's, as that caller (their row rules, their masks).
 */
@RestController
@RequestMapping("/api/v1/bi/reports")
public class BiReportController {

    public static final String ARROW = "application/vnd.apache.arrow.stream";

    private final BiReportService reports;
    private final BiLiveProperties live;
    private final ExecutorService executor;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger streams = new AtomicInteger();
    private final Map<String, AtomicInteger> perUser = new ConcurrentHashMap<>();

    public BiReportController(BiReportService reports, BiLiveProperties live, ExecutorService executor) {
        this.reports = reports;
        this.live = live;
        this.executor = executor;
    }

    /** The visuals a report may hold, their wells, and the types reserved for later. */
    @GetMapping("/catalogue")
    public Map<String, Object> catalogue(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return reports.catalogue(p);
    }

    /** The model of the dataset a report (or a draft) names, for the designer's Fields pane (authors). */
    @GetMapping("/dataset")
    public Map<String, Object> dataset(@RequestParam String ref, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return reports.datasetModel(p, ref);
    }

    /** Body {@code {yaml, quality?}}: checks report YAML without storing it; with {@code quality} also the accessibility and layout warnings. */
    @PostMapping("/validate")
    public Map<String, Object> validate(@RequestBody Map<String, Object> body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return reports.validate(p, String.valueOf(body.getOrDefault("yaml", "")), Boolean.TRUE.equals(body.get("quality")));
    }

    /** Body {@code {report: {...}}}: the report model written as YAML. */
    @PostMapping("/format")
    public Map<String, Object> format(@RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return reports.format(p, body.path("report"));
    }

    /**
     * Body {@code {yaml | report, page, visual, state}}: one visual of a draft, for the designer's preview (authors). The draft is YAML text, or the report model
     * as JSON (what the designer holds), which is written as YAML and read back, so a draft is judged exactly as a saved one would be.
     */
    @PostMapping("/query")
    public ResponseEntity<Map<String, Object>> draftQuery(@RequestBody JsonNode body, @RequestParam(defaultValue = "false") boolean explain,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        String yaml = body.path("report").isObject() ? String.valueOf(reports.format(p, body.get("report")).get("yaml")) : body.path("yaml").asText("");
        return noStore(reports.query(p, null, yaml, body.path("page").asText(""), body.path("visual").asText(""), body.get("state"), explain));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return noStore(reports.get(p, id));
    }

    /**
     * Body {@code {page, visual, state}}: the data of one visual under the page's slicers, selections, drill path and carried values. {@code format=arrow} answers an
     * Arrow IPC stream (what a Perspective visual loads), with the table's live state in {@code X-Bi-Live}.
     */
    @PostMapping("/{id}/query")
    public ResponseEntity<?> query(@PathVariable String id, @RequestBody JsonNode body, @RequestParam(defaultValue = "false") boolean explain,
            @RequestParam(defaultValue = "json") String format, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        if ("arrow".equalsIgnoreCase(format)) {
            BiReportService.ArrowAnswer a = reports.queryArrow(p, id, body.path("page").asText(""), body.path("visual").asText(""), body.get("state"));
            Object masked = a.meta().get("masked");
            return ResponseEntity.ok().contentType(MediaType.parseMediaType(ARROW))
                    .header("X-Bi-Rows", String.valueOf(a.meta().get("rows")))
                    .header("X-Bi-Masked", masked instanceof java.util.Collection<?> c ? c.stream().map(String::valueOf).collect(Collectors.joining(",")) : "")
                    .header("X-Bi-Live", a.live() == null ? "" : write(a.live()))
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(a.bytes());
        }
        return noStore(reports.query(p, id, null, body.path("page").asText(""), body.path("visual").asText(""), body.get("state"), explain));
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "";
        }
    }

    /**
     * The live stream of a report (RUPAKA.md section 12): {@code hello} (the state of its dataset's live tables), then a {@code tick} whenever the tables changed,
     * at most one per {@code drishti.bi.live.frame} (the latest state: a slow client skips frames, never queues them), and a {@code status} every
     * {@code drishti.bi.live.status} when nothing changed (also the heartbeat). A tick carries no data: the client asks its visuals again, as itself, so its row
     * rules and masks apply to every update.
     */
    @GetMapping(path = "/{id}/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter liveStream(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        Supplier<Map<String, Object>> watch = reports.liveWatch(p, id);
        AtomicInteger mine = perUser.computeIfAbsent(p.user(), u -> new AtomicInteger());
        if (streams.incrementAndGet() > live.maxStreams() || mine.incrementAndGet() > live.maxStreamsPerUser()) {
            streams.decrementAndGet();
            mine.decrementAndGet();
            throw new DrishtiException(ErrorCode.LIVE_STREAM, "too many live streams are open (drishti.bi.live.max-streams " + live.maxStreams() + " for the server, max-streams-per-user "
                    + live.maxStreamsPerUser() + " for you): close a report or board");
        }
        SseEmitter emitter = new SseEmitter(0L);
        executor.execute(() -> {
            try {
                if (watch == null) {
                    emitter.send(SseEmitter.event().name("hello").data(json.writeValueAsString(Map.of("state", "none", "tables", java.util.List.of()))));
                    emitter.complete();
                    return;
                }
                Map<String, Object> first = watch.get();
                emitter.send(SseEmitter.event().name("hello").data(json.writeValueAsString(first)));
                long sent = ((Number) first.get("generation")).longValue();
                String state = String.valueOf(first.get("state"));
                long quiet = 0;
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(live.frame().toMillis());
                    Map<String, Object> now = watch.get();
                    long g = ((Number) now.get("generation")).longValue();
                    quiet += live.frame().toMillis();
                    if (g != sent) {
                        sent = g;
                        quiet = 0;
                        state = String.valueOf(now.get("state"));
                        emitter.send(SseEmitter.event().name("tick").data(json.writeValueAsString(now)));
                    } else if (quiet >= live.status().toMillis() || !state.equals(String.valueOf(now.get("state")))) {
                        quiet = 0;
                        state = String.valueOf(now.get("state"));
                        emitter.send(SseEmitter.event().name("status").data(json.writeValueAsString(now)));
                    }
                }
            } catch (IOException | IllegalStateException e) {
                emitter.completeWithError(e);                         // the browser went away
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                emitter.complete();
            } catch (RuntimeException e) {
                emitter.completeWithError(e);
            } finally {
                streams.decrementAndGet();
                mine.decrementAndGet();
            }
        });
        return emitter;
    }

    private static ResponseEntity<Map<String, Object>> noStore(Map<String, Object> body) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }
}
