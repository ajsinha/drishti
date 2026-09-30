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
package com.ash.drishti.server.api;

import com.ash.drishti.api.EntityRef;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.ViewPipeline;
import com.ash.drishti.engine.live.Frame;
import com.ash.drishti.engine.live.LiveMetrics;
import com.ash.drishti.engine.live.TopicHub;
import com.ash.drishti.engine.live.ViewStream;
import com.ash.drishti.engine.view.ViewModel;
import com.ash.drishti.identity.PreferenceStore;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Monitors: named watchlists of entities. Reading a monitor returns each entity's title and strip; its stream
 * multiplexes every row's live changes over one connection (a {@code row} event per changed entity).
 */
@RestController
@RequestMapping("/api/v1/me/monitors")
public class MonitorController {

    static final String NS = "monitors";
    private final PreferenceStore store;
    private final Entitlements entitlements;
    private final ViewPipeline pipeline;
    private final TopicHub hub;
    private final LiveMetrics metrics;
    private final ExecutorService executor;
    private final ObjectMapper json = new ObjectMapper();

    public MonitorController(PreferenceStore store, Entitlements entitlements, ViewPipeline pipeline, TopicHub hub, LiveMetrics metrics,
            ExecutorService drishtiVirtualExecutor) {
        this.store = store;
        this.entitlements = entitlements;
        this.pipeline = pipeline;
        this.hub = hub;
        this.metrics = metrics;
        this.executor = drishtiVirtualExecutor;
    }

    @GetMapping
    public List<String> list(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return store.keys(p.user(), NS);
    }

    @PutMapping("/{name}")
    public JsonNode save(@PathVariable String name, @RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        JsonNode entities = body.path("entities");
        if (!entities.isArray() || entities.isEmpty() || entities.size() > 50) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a monitor has 1 to 50 entities");
        }
        ObjectNode out = json.createObjectNode();
        var arr = out.putArray("entities");
        for (JsonNode e : entities) {
            String kind = e.path("kind").asText();
            String id = e.path("id").asText();
            if (kind.isBlank() || id.isBlank()) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "each entity needs kind and id");
            }
            entitlements.requireOpen(p, kind);
            arr.addObject().put("kind", kind).put("id", id);
        }
        store.put(p.user(), NS, name, out);
        return out;
    }

    @DeleteMapping("/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        if (!store.delete(p.user(), NS, name)) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no monitor '" + name + "'");
        }
    }

    private List<EntityRef> refs(String user, String name) {
        JsonNode m = store.get(user, NS, name).orElseThrow(() -> new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no monitor '" + name + "'"));
        List<EntityRef> out = new ArrayList<>();
        m.path("entities").forEach(e -> out.add(EntityRef.of(e.path("kind").asText(), e.path("id").asText())));
        return out;
    }

    /** Each row: the entity, its title and its strip (or the error that stops it rendering). */
    @GetMapping("/{name}")
    public List<Map<String, Object>> rows(@PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (EntityRef r : refs(p.user(), name)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ref", new ViewModel.Ref(r.kind(), r.id()));
            try {
                entitlements.requireOpen(p, r.kind());
                ViewModel v = pipeline.view(r);
                row.put("mnemonic", v.mnemonic());
                row.put("title", v.title());
                row.put("strip", v.strip());
                row.put("live", v.provenance().live());
            } catch (DrishtiException e) {
                row.put("error", e.errorCode().code() + " " + e.getMessage());
            }
            out.add(row);
        }
        return out;
    }

    @GetMapping(path = "/{name}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        List<EntityRef> refs = refs(p.user(), name);
        SseEmitter emitter = new SseEmitter(0L);
        Map<EntityRef, FrameMailbox> boxes = new LinkedHashMap<>();
        java.util.concurrent.Semaphore signal = new java.util.concurrent.Semaphore(0);
        List<ViewStream> streams = new ArrayList<>();
        for (EntityRef r : refs) {
            if (!entitlements.mayOpen(p, r.kind())) {
                continue;
            }
            FrameMailbox box = new FrameMailbox();
            boxes.put(r, box);
            try {
                streams.add(new ViewStream(r, pipeline.view(r), List.of(), hub, pipeline, executor, metrics, f -> {
                    box.offer(f);
                    signal.release();
                }));
            } catch (DrishtiException ignored) {
                // a missing entity simply does not tick
            }
        }
        executor.execute(() -> {
            try {
                emitter.send(SseEmitter.event().name("hello").data(Map.of("rows", boxes.size())));
                while (!Thread.currentThread().isInterrupted()) {
                    if (!signal.tryAcquire(15, java.util.concurrent.TimeUnit.SECONDS)) {
                        emitter.send(SseEmitter.event().comment("hb"));
                        continue;
                    }
                    signal.drainPermits();
                    for (Map.Entry<EntityRef, FrameMailbox> e : boxes.entrySet()) {
                        Frame f = e.getValue().take(0);
                        if (f != null) {
                            emitter.send(SseEmitter.event().name("row").data(Map.of("kind", e.getKey().kind(), "id", e.getKey().id(),
                                    "patches", f.patches().stream().filter(x -> "strip".equals(x.op())).toList(), "p99Ms", f.p99Ms()),
                                    MediaType.APPLICATION_JSON));
                        }
                    }
                }
            } catch (IOException | IllegalStateException e) {
                emitter.completeWithError(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                streams.forEach(ViewStream::close);
            }
        });
        return emitter;
    }
}
