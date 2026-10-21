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
    private final org.springframework.beans.factory.ObjectProvider<com.ash.drishti.server.bi.runs.RunSources> runs;
    private final org.springframework.beans.factory.ObjectProvider<StandingHolders> holders;

    public MqController(ModelCatalog catalog, MqService mq, StandingQueries standing, BiService bi, Entitlements entitlements, BiLiveProperties live, ExecutorService executor) {
        this(catalog, mq, standing, bi, entitlements, live, executor, null);
    }

    /** With BI runs: {@code Prefer: respond-async} answers as {@code POST /api/v1/bi/runs} does (docs/architecture/BI_RUNS.md section 6). */
    public MqController(ModelCatalog catalog, MqService mq, StandingQueries standing, BiService bi, Entitlements entitlements, BiLiveProperties live, ExecutorService executor,
            org.springframework.beans.factory.ObjectProvider<com.ash.drishti.server.bi.runs.RunSources> runs) {
        this(catalog, mq, standing, bi, entitlements, live, executor, runs, null);
    }

    /** On a cluster: a standing query's stream or close asked of a node that does not hold it is relayed from the one that does ({@link StandingHolders}). */
    public MqController(ModelCatalog catalog, MqService mq, StandingQueries standing, BiService bi, Entitlements entitlements, BiLiveProperties live, ExecutorService executor,
            org.springframework.beans.factory.ObjectProvider<com.ash.drishti.server.bi.runs.RunSources> runs,
            org.springframework.beans.factory.ObjectProvider<StandingHolders> holders) {
        this.holders = holders;
        this.runs = runs;
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
    public ResponseEntity<?> ask(@RequestBody JsonNode body, @RequestParam(required = false) String format, @RequestAttribute(Principal.ATTRIBUTE) Principal p,
            @org.springframework.web.bind.annotation.RequestHeader(value = "Prefer", required = false) String prefer) {
        bi.requirePower(p, BiAccess.BI);
        com.ash.drishti.server.bi.runs.RunSources r = runs == null ? null : runs.getIfAvailable();
        if (r != null && r.async(prefer)) {
            return asRun(r, p, body, null, format, prefer);
        }
        return answer(p, query(body), format);
    }

    /** A model query as a BI run: inline within the wait, else a visible run ({@code 202}). */
    private ResponseEntity<?> asRun(com.ash.drishti.server.bi.runs.RunSources r, Principal p, JsonNode body, String model, String format, String prefer) {
        JsonNode b = body != null && body.isObject() ? body.deepCopy() : body;
        boolean interaction = b != null && b.path("interaction").asBoolean(false);
        String supersedes = b != null && b.path("supersedes").isTextual() ? b.path("supersedes").asText() : null;
        boolean confirm = b != null && b.path("confirm").asBoolean(false);
        String token = b != null && b.path("confirmToken").isTextual() ? b.path("confirmToken").asText() : null;
        if (b != null && b.isObject()) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) b).remove(java.util.List.of("interaction", "supersedes", "confirm", "confirmToken"));
        }
        ModelQuery q = query(b);
        if (q.options().explain()) {
            return answer(p, model == null ? q : q.withModel(model), format);   // EXPLAIN answers at once, as before (the explain panel)
        }
        String f = format == null ? q.options().format() : format;
        return r.submit(r.mq(p, q, b != null && b.has("yaml") ? null : b, model, interaction, supersedes, null), prefer, f, p, confirm, token);
    }

    @PostMapping("/models/{id}/query")
    public ResponseEntity<?> askModel(@PathVariable String id, @RequestBody JsonNode body, @RequestParam(required = false) String format,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p, @org.springframework.web.bind.annotation.RequestHeader(value = "Prefer", required = false) String prefer) {
        bi.requirePower(p, BiAccess.BI);
        JsonNode b = body;
        if (b != null && b.isObject() && !b.has("mq")) {
            b = ((com.fasterxml.jackson.databind.node.ObjectNode) b.deepCopy()).put("mq", 1);
        }
        com.ash.drishti.server.bi.runs.RunSources r = runs == null ? null : runs.getIfAvailable();
        if (r != null && r.async(prefer)) {
            return asRun(r, p, b, id, format, prefer);
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
    public Map<String, Object> describe(@PathVariable String id, @RequestParam(required = false) String perspective, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        return catalog.describe(p, id, perspective);
    }

    /** The roles a model's row rules can name (the security tab's role picker): each with what it is for and the pack kinds it opens ({@code *}: all). */
    @GetMapping("/models/roles")
    public Map<String, Object> roles(@org.springframework.web.bind.annotation.RequestParam(required = false) String model,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        java.util.Set<String> readers = model == null || model.isBlank() ? null : catalog.readers(p, model);    // ?model=: those that may read its datasets
        List<Map<String, Object>> out = new ArrayList<>();
        for (ModelRoles.Role r : catalog.roles().all()) {
            if (readers != null && !readers.contains(r.name())) {
                continue;
            }
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

    /** Which consumers of the model use a field ({@code alias.column}) or a measure, from their envelopes. */
    @GetMapping("/models/{id}/impact")
    public Map<String, Object> impact(@PathVariable String id, @RequestParam String target, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        return catalog.impact(p, id, target);
    }

    /** What a report's readers without read on its model may ask: the fields, measures and filters its visuals can produce. */
    @GetMapping("/reports/{id}/envelope")
    public Map<String, Object> envelope(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        return catalog.envelope(p, id);
    }

    /** Report to model to datasets to their roots (pack kind and connector, connector query, Python, model, SQL view), as far as the caller may read. */
    @GetMapping("/lineage/{ref}")
    public Map<String, Object> lineage(@PathVariable String ref, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        return catalog.lineage(p, ref);
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
            m.put("description", d.description());                         // the kind's, not the pack's
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
        StandingHolders h = holders();
        standing.node(h == null ? null : h.self());                          // on a cluster the id names this node: any node relays its stream from here
        return standing.subscribe(p, query, throttle, body.path("deliver").asText("rows"));
    }

    @DeleteMapping("/mq/subscribe/{id}")
    public ResponseEntity<Void> unsubscribe(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p, jakarta.servlet.http.HttpServletRequest req) {
        String node = heldElsewhere(List.of(id), req);
        if (node != null) {
            try {
                holders().delete(node, "/api/v1/bi/mq/subscribe/" + java.net.URLEncoder.encode(id, java.nio.charset.StandardCharsets.UTF_8), p);
            } catch (IOException e) {
                // the holder is gone, and the subscription with it
            }
            return ResponseEntity.noContent().build();
        }
        standing.unsubscribe(p, id);
        return ResponseEntity.noContent().build();
    }

    private StandingHolders holders() {
        StandingHolders h = holders == null ? null : holders.getIfAvailable();
        return h == null || h.self() == null ? null : h;
    }

    /** The node that holds every one of {@code ids} when that is another node (and this request is not itself relayed); null when they are asked here. */
    private String heldElsewhere(List<String> ids, jakarta.servlet.http.HttpServletRequest req) {
        StandingHolders h = holders();
        if (h == null || ids.isEmpty() || h.forwarded(req)) {
            return null;
        }
        java.util.Set<String> nodes = new java.util.HashSet<>();
        for (String id : ids) {
            nodes.add(StandingId.node(id).orElse(""));
        }
        String node = nodes.size() == 1 ? nodes.iterator().next() : "";
        return node.isEmpty() || node.equals(h.self()) ? null : node;
    }

    /**
     * The stream of subscriptions another node holds, relayed from it as the caller (the holder checks they are the subscriber's). A holder that cannot be
     * reached, or no longer holds them (it restarted), answers {@code DRS-9180}: the console subscribes again.
     */
    private SseEmitter relay(String node, List<String> ids, Principal p) {
        StandingHolders h = holders();
        String gone = "the node that holds this standing query (" + node + ") cannot be reached: subscribe again";
        if (!h.alive(node)) {
            throw new MqException(MqException.Problem.of("DRS-9180", "ids", gone));
        }
        java.net.http.HttpResponse<java.io.InputStream> r;
        try {
            r = h.stream(node, "/api/v1/bi/mq/stream?ids=" + java.net.URLEncoder.encode(String.join(",", ids), java.nio.charset.StandardCharsets.UTF_8), p);
        } catch (IOException e) {
            throw new MqException(MqException.Problem.of("DRS-9180", "ids", gone));
        }
        if (r.statusCode() != 200) {
            String code = "DRS-9180";
            String detail = "node " + node + " does not hold this standing query any more (it restarted?): subscribe again";
            try (java.io.InputStream in = r.body()) {
                JsonNode j = json.readTree(in.readNBytes(64 * 1024));
                code = j.path("code").asText(code);
                detail = j.path("detail").asText(detail);
            } catch (IOException | RuntimeException e) {
                // not a problem body: the default words stand
            }
            throw new MqException(MqException.Problem.of(code, "ids", detail));
        }
        SseEmitter emitter = new SseEmitter(0L);
        java.io.InputStream body = r.body();
        emitter.onCompletion(() -> close(body));
        emitter.onTimeout(() -> close(body));
        emitter.onError(x -> close(body));
        executor.execute(() -> {
            try (java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(body, java.nio.charset.StandardCharsets.UTF_8))) {
                String event = null;
                StringBuilder data = new StringBuilder();
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.startsWith("event:")) {
                        event = line.substring(6).strip();
                    } else if (line.startsWith("data:")) {
                        String v = line.substring(5);
                        data.append(data.isEmpty() ? "" : "\n").append(v.startsWith(" ") ? v.substring(1) : v);
                    } else if (line.isEmpty() && event != null) {
                        emitter.send(SseEmitter.event().name(event).data(data.toString()));
                        event = null;
                        data.setLength(0);
                    }
                }
                emitter.complete();                                         // the holder ended the stream (every subscription closed)
            } catch (IOException | IllegalStateException e) {
                emitter.completeWithError(e);                              // the holder or the client went away: the console opens it again
            }
        });
        return emitter;
    }

    private static void close(java.io.InputStream in) {
        try {
            in.close();
        } catch (IOException e) {
            // already closed
        }
    }

    /**
     * The stream of the caller's subscriptions ({@code ids}, comma-separated; all of theirs when absent), server-sent events: {@code answer} when a subscription's
     * data moved on (at most one per its throttle; the latest state wins), {@code status} every {@code drishti.bi.live.status} (also the heartbeat).
     */
    @GetMapping(path = "/mq/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(required = false) String ids, @RequestAttribute(Principal.ATTRIBUTE) Principal p, jakarta.servlet.http.HttpServletRequest req) {
        bi.requirePower(p, BiAccess.BI);
        List<String> asked = ids == null || ids.isBlank() ? List.of() : Arrays.stream(ids.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        String node = heldElsewhere(asked, req);
        if (node != null) {
            return relay(node, asked, p);
        }
        List<String> wanted = standing.mine(p, asked);
        SseEmitter emitter = new SseEmitter(0L);
        long beat = live.status().toMillis();
        executor.execute(() -> {
            try {
                emitter.send(SseEmitter.event().name("status").data(json.writeValueAsString(Map.of("subscriptions", wanted))));
                long quietSince = System.nanoTime();
                while (!Thread.currentThread().isInterrupted()) {
                    long left = Math.max(1, beat - (System.nanoTime() - quietSince) / 1_000_000);
                    for (StandingQueries.Event e : standing.await(wanted, left)) {     // woken by the groups it reads (pushed by the generation bus)
                        emitter.send(SseEmitter.event().name(e.name()).data(json.writeValueAsString(e.data())));
                        quietSince = System.nanoTime();
                    }
                    if ((System.nanoTime() - quietSince) / 1_000_000 >= beat) {
                        quietSince = System.nanoTime();
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
