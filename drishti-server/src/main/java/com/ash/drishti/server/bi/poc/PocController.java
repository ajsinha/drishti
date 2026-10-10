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
package com.ash.drishti.server.bi.poc;

import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.Subscription;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.live.TopicHub;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * RUPAKA PHASE 0 PROOF OF CONCEPT (docs/architecture/RUPAKA_POC.md). Three endpoints, none of which exists unless
 * {@code drishti.bi.poc.enabled}:
 * <ul>
 *   <li>{@code POST /api/v1/bi/poc/query}: the masked aggregation as Arrow IPC (administrators);</li>
 *   <li>{@code GET /api/v1/bi/poc/bench}: the JSON / typed / rollup comparison (administrators);</li>
 *   <li>{@code GET /api/v1/bi/poc/rows}: a live feed of rows for Perspective, from the same topics live views use.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/bi/poc")
@ConditionalOnProperty(prefix = "drishti.bi.poc", name = "enabled", havingValue = "true")
public class PocController {

    public static final String ARROW = "application/vnd.apache.arrow.stream";
    private static final int MAX_FEEDS = 20;

    private final PocProperties props;
    private final PocQueryService service;
    private final Entitlements entitlements;
    private final TopicHub hub;
    private final SourceRouter router;
    private final ExecutorService executor;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger feeds = new AtomicInteger();

    public PocController(PocProperties props, PocQueryService service, Entitlements entitlements, TopicHub hub, SourceRouter router,
            ExecutorService drishtiVirtualExecutor) {
        this.props = props;
        this.service = service;
        this.entitlements = entitlements;
        this.hub = hub;
        this.router = router;
        this.executor = drishtiVirtualExecutor;
    }

    /**
     * Body: {@code {"groupBy": ["desk"], "layout": "typed", "date": "2026-10-09", "filter": {"column": "currency", "value": "USD"},
     * "preview": "masked"}}. {@code preview: masked} answers as a user without {@code raw} would see it (an administrator
     * checking the masks); without it the caller's own roles decide.
     */
    @PostMapping("/query")
    public ResponseEntity<byte[]> query(@RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        List<String> groupBy = new ArrayList<>();
        body.path("groupBy").forEach(n -> groupBy.add(n.asText()));
        PocQueryService.Layout layout = layout(body.path("layout").asText("typed"));
        JsonNode filter = body.path("filter");
        PocQueryService.Query q = new PocQueryService.Query(groupBy, layout, text(body, "date"),
                filter.isObject() ? text(filter, "column") : null, filter.isObject() ? text(filter, "value") : null);
        boolean forced = "masked".equals(body.path("preview").asText(""));
        PocQueryService.Answer a = service.run(q, field -> forced ? entitlements.redactNames(field) : entitlements.masked(p, field));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(ARROW))
                .header("X-Poc-Rows", Integer.toString(a.rows()))
                .header("X-Poc-Layout", a.layout().name().toLowerCase())
                .header("X-Poc-Masked", String.join(",", a.masked()))
                .header("Server-Timing", "query;dur=" + Math.round(a.millis() * 100) / 100.0)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(a.arrow());
    }

    @GetMapping("/bench")
    public Map<String, Object> bench(@RequestParam(required = false) Integer runs, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return service.compareLayouts(runs == null || runs <= 0 ? props.benchRuns() : Math.min(runs, 500));
    }

    /**
     * The live rows: first a {@code view} event (the snapshot) (every followed trade), then {@code row} events carrying the rows that
     * changed since the last one (the latest value of each; a row that ticked three times inside a batch is sent once), as the
     * caller may see them: masked fields read the mask.
     */
    @GetMapping(path = "/rows", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter rows(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireOpen(p, "trade");
        if (feeds.incrementAndGet() > MAX_FEEDS) {
            feeds.decrementAndGet();
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "too many POC feeds are open");
        }
        var redact = entitlements.redactor(p);
        Map<String, Map<String, Object>> pending = new ConcurrentHashMap<>();
        List<Subscription> subscriptions = new ArrayList<>();
        SseEmitter emitter = new SseEmitter(0L);
        executor.execute(() -> {
            try {
                List<Map<String, Object>> snapshot = new ArrayList<>();
                for (String id : props.feedTrades()) {
                    EntityRef ref = EntityRef.of("trade", id);
                    try {
                        snapshot.add(row(router.fetch(ref).join(), redact));
                    } catch (RuntimeException e) {
                        // a trade this source does not hold is skipped
                    }
                    subscriptions.add(hub.subscribe(ref, d -> pending.put(id, row(d, redact))));
                }
                emitter.send(SseEmitter.event().name("view").data(json.writeValueAsString(snapshot)));
                long every = props.feedBatch().toMillis();
                long quiet = 0;
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(every);
                    List<Map<String, Object>> changed = new ArrayList<>(pending.values());
                    pending.clear();
                    if (changed.isEmpty()) {
                        quiet += every;
                        if (quiet >= 15_000) {
                            emitter.send(SseEmitter.event().comment("hb"));
                            quiet = 0;
                        }
                    } else {
                        quiet = 0;
                        emitter.send(SseEmitter.event().name("row").data(json.writeValueAsString(changed)));
                    }
                }
            } catch (IOException | IllegalStateException e) {
                emitter.completeWithError(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                emitter.complete();
            } finally {
                subscriptions.forEach(Subscription::close);
                feeds.decrementAndGet();
            }
        });
        return emitter;
    }

    private Map<String, Object> row(EntityDocument d, java.util.function.UnaryOperator<com.ash.drishti.api.DataNode> redact) {
        var data = redact.apply(d.data());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tradeId", data.get("tradeId").asText());
        m.put("desk", data.get("desk").asText());
        m.put("currency", data.get("currency").asText());
        m.put("productType", data.get("productType").asText());
        String side = data.get("direction").asText();
        m.put("side", side.isEmpty() ? data.get("side").asText() : side);
        m.put("trader", data.get("trader").asText());
        m.put("notional", num(data.get("notional").asDouble()));
        m.put("mtm", num(data.get("mtm").asDouble()));
        m.put("generation", d.provenance().generation());
        m.put("at", d.provenance().fetchedAt() == null ? null : d.provenance().fetchedAt().toString());
        return m;
    }

    private static Object num(double v) {
        return Double.isNaN(v) ? null : (Object) v;
    }

    private static PocQueryService.Layout layout(String name) {
        try {
            return PocQueryService.Layout.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "layout is json, typed or rollup");
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() || v.asText().isEmpty() ? null : v.asText();
    }
}
