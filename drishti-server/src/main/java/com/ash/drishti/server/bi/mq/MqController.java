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
package com.ash.drishti.server.bi.mq;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.bi.BiAccess;
import com.ash.drishti.server.bi.BiService;
import com.ash.drishti.server.bi.DatasetController;
import com.ash.drishti.server.bi.engine.ArrowIpcWriter;
import com.ash.drishti.server.bi.engine.BiLiveProperties;
import com.ash.drishti.server.bi.layer.DatasetDef;
import com.ash.drishti.server.bi.layer.PackDatasets;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * The model query API (docs/architecture/BI_MODEL_SPEC.md section 5.9; docs/guides/API_GUIDE.md "Model queries"): ask a model ({@code POST /mq}, or
 * {@code POST /models/{id}/query} without {@code model}), check a query without reading data ({@code POST /mq/validate}), describe a model as the caller sees
 * it, list the models, check a model or dataset's YAML, the pack-generated datasets, and standing queries ({@code /mq/subscribe}, {@code /mq/stream}). Every
 * answer is the caller's: their catalogue access, row rules per dataset, masks and kinds.
 */
@RestController
@RequestMapping("/api/v1/bi")
public class MqController {

    private final ModelCatalog catalog;
    private final MqService mq;
    private final StandingQueries standing;
    private final BiService bi;
    private final Entitlements entitlements;
    private final BiLiveProperties live;
    private final ExecutorService executor;
    private final ObjectMapper json = new ObjectMapper();

    public MqController(ModelCatalog catalog, MqService mq, StandingQueries standing, BiService bi, Entitlements entitlements, BiLiveProperties live, ExecutorService executor) {
        this.catalog = catalog;
        this.mq = mq;
        this.standing = standing;
        this.bi = bi;
        this.entitlements = entitlements;
        this.live = live;
        this.executor = executor;
    }

    /** The body is the query itself (JSON), or {@code {"yaml": "<the query as YAML>"}}. */
    private static ModelQuery query(JsonNode body) {
        if (body != null && body.isObject() && body.size() == 1 && body.path("yaml").isTextual()) {
            return MqParser.parse(body.path("yaml").asText());
        }
        return MqParser.parse(body);
    }

    @PostMapping("/mq")
    public ResponseEntity<?> ask(@RequestBody JsonNode body, @RequestParam(required = false) String format, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        return answer(p, query(body), format);
    }

    @PostMapping("/models/{id}/query")
    public ResponseEntity<?> askModel(@PathVariable String id, @RequestBody JsonNode body, @RequestParam(required = false) String format,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        JsonNode b = body;
        if (b != null && b.isObject() && !b.has("mq")) {
            b = ((com.fasterxml.jackson.databind.node.ObjectNode) b.deepCopy()).put("mq", 1);
        }
        ModelQuery q = query(b);
        if (!q.model().isBlank() && !q.model().equals(id)) {
            throw new MqException(MqException.Problem.of("DRS-9150", "model", "the path names the model '" + id + "'; leave `model` out of the body"));
        }
        return answer(p, q.withModel(id), format);
    }

    private ResponseEntity<?> answer(Principal p, ModelQuery q, String format) {
        MqService.Bound b = catalog.bind(p, q.model());
        boolean explain = q.options().explain() && bi.mayAuthor(p);                    // the SQL names server paths: authors only
        MqService.Answer a = mq.ask(b, q, explain);
        String f = format == null ? q.options().format() : format;
        if ("arrow".equalsIgnoreCase(f)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = (Map<String, Object>) a.body().get("meta");
            byte[] bytes = ArrowIpcWriter.write(a.result().columns(), a.result().rows());
            return ResponseEntity.ok().contentType(MediaType.parseMediaType(DatasetController.ARROW))
                    .header("X-Bi-Layer", String.valueOf(meta.get("layer")))
                    .header("X-Bi-Rollup", meta.get("rollup") == null ? "" : String.valueOf(meta.get("rollup")))
                    .header("X-Bi-Rows", Integer.toString(a.result().rows()))
                    .header("X-Bi-Cached", String.valueOf(meta.get("cached")))
                    .header("X-Bi-Masked", String.join(",", a.result().meta() == null ? List.of() : a.result().meta().masked()))
                    .header("X-Bi-Model", String.valueOf(((Map<?, ?>) a.body().get("model")).get("id")))
                    .header("X-Bi-Generation", String.valueOf(meta.get("generation")))
                    .header("X-Bi-No-Access", String.join(",", (List<String>) meta.get("noAccess")))
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(bytes);
        }
        if (!"json".equalsIgnoreCase(f)) {
            throw new MqException(MqException.Problem.of("DRS-9150", "options.format", "format is json or arrow"));
        }
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(a.body());
    }

    /** Steps 1 to 9 without reading data: {@code valid} with the warnings and the request it would run, or the refusal ({@code 422} with code, path and fix). */
    @PostMapping("/mq/validate")
    public Map<String, Object> validate(@RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        ModelQuery q = query(body);
        return mq.validate(catalog.bind(p, q.model()), q);
    }

    @GetMapping("/models")
    public Map<String, Object> models(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        return Map.of("models", catalog.list(p));
    }

    @GetMapping("/models/{id}/describe")
    public Map<String, Object> describe(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        return catalog.describe(p, id);
    }

    /** The roles a model's row rules can name (the security tab's role picker): each with what it is for and the pack kinds it opens ({@code *}: all). */
    @GetMapping("/models/roles")
    public Map<String, Object> roles(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        List<Map<String, Object>> out = new ArrayList<>();
        for (ModelRoles.Role r : catalog.roles().all()) {
            out.add(Map.of("name", r.name(), "description", r.description() == null ? "" : r.description()));
        }
        return Map.of("roles", out);
    }

    /** A {@code rachana: 2} dataset item as stored and with its {@code extends} chain folded in ({@code resolved}), and its Check. */
    @GetMapping("/layer-datasets/{id}")
    public Map<String, Object> layerDataset(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        return catalog.layerDataset(p, id);
    }

    /** Who reads the model (reports, boards, notebooks, datasets on its answer, alerts, live subscriptions), as far as the caller may see them. */
    @GetMapping("/models/{id}/consumers")
    public Map<String, Object> consumers(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        return catalog.usedBy(p, id);
    }

    /** Checks a model's or a {@code rachana: 2} dataset's YAML (body {@code {yaml}}) without storing it: the problems with their codes, and the safeguard's findings. */
    @PostMapping("/models/validate")
    public Map<String, Object> check(@RequestBody Map<String, Object> body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.AUTHOR);
        return catalog.check(String.valueOf(body.getOrDefault("yaml", "")));
    }

    /** The datasets the packs generate for the kinds the caller may open (read-only; narrow one with {@code extends}). */
    @GetMapping("/pack-datasets")
    public Map<String, Object> packDatasets(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        List<Map<String, Object>> out = new ArrayList<>();
        for (DatasetDef d : catalog.packs().all().values()) {
            if (!entitlements.mayOpen(p, d.source().kind())) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", d.name());
            m.put("id", d.id());
            m.put("title", d.title());
            m.put("pack", d.generated().pack());
            m.put("kind", d.source().kind());
            m.put("version", d.version());
            m.put("mode", d.time().mode());
            m.put("endorsement", d.endorsement());
            m.put("columns", d.columns().size());
            m.put("query", "dataset:" + d.name());
            out.add(m);
        }
        out.sort((a, b) -> String.valueOf(a.get("name")).compareTo(String.valueOf(b.get("name"))));
        return Map.of("datasets", out);
    }

    @GetMapping("/pack-datasets/{name}")
    public Map<String, Object> packDataset(@PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        DatasetDef d = catalog.packs().named(name).filter(x -> entitlements.mayOpen(p, x.source().kind()))
                .orElseThrow(() -> new DrishtiException(ErrorCode.BI_NOT_FOUND, "no generated dataset '" + name + "' that you may open"));
        return Map.of("name", d.name(), "version", d.version(), "readOnly", true, "yaml", PackDatasets.yaml(d));
    }

    // ---- standing queries --------------------------------------------------------------------------------------------

    /** Body {@code {query, throttle, deliver}}: the subscription, its first answer and cursor. */
    @PostMapping("/mq/subscribe")
    public Map<String, Object> subscribe(@RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        JsonNode q = body.path("query");
        ModelQuery query = q.isTextual() ? MqParser.parse(q.asText()) : MqParser.parse(q);
        Duration throttle = body.has("throttle") ? com.ash.drishti.server.bi.dataset.DatasetParser.span(body.path("throttle").asText()) : live.frame();
        if (body.has("throttle") && throttle == null) {
            throw new MqException(MqException.Problem.of("DRS-9150", "throttle", "a throttle such as 250ms or 1s"));
        }
        return standing.subscribe(p, query, throttle, body.path("deliver").asText("rows"));
    }

    @DeleteMapping("/mq/subscribe/{id}")
    public ResponseEntity<Void> unsubscribe(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        standing.unsubscribe(p, id);
        return ResponseEntity.noContent().build();
    }

    /**
     * The stream of the caller's subscriptions ({@code ids}, comma-separated; all of theirs when absent), server-sent events: {@code answer} when a subscription's
     * data moved on (at most one per its throttle; the latest state wins), {@code status} every {@code drishti.bi.live.status} (also the heartbeat).
     */
    @GetMapping(path = "/mq/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(required = false) String ids, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        List<String> wanted = standing.mine(p, ids == null || ids.isBlank() ? List.of() : Arrays.stream(ids.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
        SseEmitter emitter = new SseEmitter(0L);
        long poll = mq.properties().poll().toMillis();
        executor.execute(() -> {
            try {
                emitter.send(SseEmitter.event().name("status").data(json.writeValueAsString(Map.of("subscriptions", wanted))));
                long quiet = 0;
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(poll);
                    quiet += poll;
                    for (StandingQueries.Event e : standing.poll(wanted)) {
                        emitter.send(SseEmitter.event().name(e.name()).data(json.writeValueAsString(e.data())));
                        quiet = 0;
                    }
                    if (quiet >= live.status().toMillis()) {
                        quiet = 0;
                        List<StandingQueries.Event> status = standing.status(wanted);
                        if (status.isEmpty()) {
                            emitter.complete();                            // every subscription of the stream was closed
                            return;
                        }
                        for (StandingQueries.Event e : status) {
                            emitter.send(SseEmitter.event().name(e.name()).data(json.writeValueAsString(e.data())));
                        }
                    }
                }
            } catch (IOException | IllegalStateException e) {
                emitter.completeWithError(e);                          // the client went away
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                emitter.complete();
            } catch (RuntimeException e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }
}
