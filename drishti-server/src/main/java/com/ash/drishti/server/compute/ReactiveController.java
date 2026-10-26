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
package com.ash.drishti.server.compute;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.compute.ComputeEvent;
import com.ash.drishti.api.compute.ComputeRef;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.compute.RequestClass;
import com.ash.drishti.compute.reactive.Answer;
import com.ash.drishti.compute.reactive.ReactiveConnector;
import com.ash.drishti.compute.reactive.ReactiveHub;
import com.ash.drishti.compute.reactive.RequestEvents;
import com.ash.drishti.engine.time.BusinessDates;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Reactive connectors' HTTP face (REACTIVE_CONNECTORS.md section 16.2). Panels read computed kinds through the views as any kind; this is
 * for the live channel and for programs: a request's events as server-sent events (its owner only), cancel, a computation asked
 * directly, events from write-back and feeds (administrators and service tokens), the owner's private what-if sandboxes, and Admin's view
 * of each reactive connector.
 */
@RestController
@RequestMapping("/api/v1")
public class ReactiveController {

    private final Entitlements entitlements;
    private final BusinessDates dates;
    private final ExecutorService executor;
    private final AuditLog audit;
    private final ReactiveHub hub = ReactiveHub.get();

    public ReactiveController(Entitlements entitlements, BusinessDates dates, ExecutorService drishtiVirtualExecutor, AuditLog audit) {
        this.entitlements = entitlements;
        this.dates = dates;
        this.executor = drishtiVirtualExecutor;
        this.audit = audit;
    }

    private ReactiveConnector connector(String name) {
        return hub.connector(name).orElseThrow(() -> new DrishtiException(ErrorCode.COMPUTE_NOT_FOUND, "there is no reactive connector " + name
                + " running on this server"));
    }

    /** A computation asked directly: {@code {id, params, asOf, wait}}; the answer at once, or {@code computing} with the request id to follow. */
    @PostMapping("/compute/{connector}/{kind}")
    @SuppressWarnings("unchecked")
    public Map<String, Object> ask(@PathVariable String connector, @PathVariable String kind, @RequestBody Map<String, Object> body,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireOpen(p, kind);                              // the kind's role, pack and command, as for a view
        ReactiveConnector c = connector(connector);
        Map<String, Object> params = body.get("params") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        String ref = ComputeRef.format(String.valueOf(body.getOrDefault("id", "")), params);
        AsOf asOf = body.get("asOf") == null ? AsOf.LATEST : AsOf.of(LocalDate.parse(String.valueOf(body.get("asOf"))));
        long waitMs = body.get("wait") instanceof Number n ? Math.max(0, Math.min(60_000, n.longValue())) : 2_000;
        Answer a = c.answer(new ReactiveConnector.Ask(kind, ref, asOf, ReactiveHostImpl.viewerOf(p), RequestClass.INTERACTIVE, Duration.ofMillis(waitMs)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", a.state());
        out.put("requestId", a.requestId());
        out.put("connector", a.connector());
        out.put("id", ref);
        if (a.message() != null) {
            out.put("message", a.message());
        }
        if (a.progress() != null) {
            out.put("progress", a.progress());
        }
        out.put("result", a.document());
        return out;
    }

    /** A request's events (progress, state, done, failed, updated) as server-sent events, to its owner only; a heartbeat every 15 s. */
    @GetMapping(path = "/compute/requests/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        LinkedBlockingQueue<RequestEvents.Event> q = new LinkedBlockingQueue<>(256);
        AutoCloseable handle;
        try {
            handle = hub.events().listen(id, p.user(), e -> {
                if (!q.offer(e)) {
                    q.poll();
                    q.offer(e);
                }
            });
        } catch (java.util.NoSuchElementException e) {
            throw new DrishtiException(ErrorCode.COMPUTE_NOT_FOUND, "no compute request " + id);
        }
        SseEmitter emitter = new SseEmitter(0L);
        executor.execute(() -> {
            try {
                emitter.send(SseEmitter.event().name("hello").data(Map.of("requestId", id)));
                while (!Thread.currentThread().isInterrupted()) {
                    RequestEvents.Event e = q.poll(15, TimeUnit.SECONDS);
                    if (e == null) {
                        emitter.send(SseEmitter.event().comment("hb"));
                    } else {
                        emitter.send(SseEmitter.event().name(e.type()).id(Long.toString(e.seq())).data(e.data(), MediaType.APPLICATION_JSON));
                    }
                }
            } catch (IOException | IllegalStateException e) {
                emitter.completeWithError(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                try {
                    handle.close();
                } catch (Exception ignored) {
                    // closing a listener does not fail
                }
            }
        });
        return emitter;
    }

    /** Cancels a request of the caller; the computation stops only if nobody else waits on it. */
    @DeleteMapping("/compute/requests/{id}")
    public Map<String, Object> cancel(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        for (ReactiveConnector c : hub.connectors()) {
            if (c.cancel(id, p.user())) {
                return Map.of("cancelled", id);
            }
        }
        throw new DrishtiException(ErrorCode.COMPUTE_NOT_FOUND, "no compute request " + id);
    }

    /**
     * An event for the triggers of every reactive connector (REACTIVE_CONNECTORS.md section 7): a write-back applied (the edit log's
     * {@code edit.applied}), a feed message, a load. Administrators only (and service tokens with an admin role); each connector ignores
     * an event id it has seen.
     */
    @PostMapping("/compute/events")
    @SuppressWarnings("unchecked")
    public Map<String, Object> event(@RequestBody Map<String, Object> body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        String id = body.get("id") == null ? null : String.valueOf(body.get("id"));
        String type = body.get("type") == null ? null : String.valueOf(body.get("type"));
        if (id == null || id.isBlank() || type == null || !List.of(ComputeEvent.WRITE_BACK, ComputeEvent.LIVE, ComputeEvent.DATA_VERSION, ComputeEvent.SCHEDULE).contains(type)) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "an event has an id and a type (write-back, live, data-version or schedule)");
        }
        Map<String, Long> wm = new LinkedHashMap<>();
        if (body.get("watermark") instanceof Map<?, ?> m) {
            m.forEach((k, v) -> wm.put(String.valueOf(k), v instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(v))));
        }
        List<String> paths = new ArrayList<>();
        if (body.get("paths") instanceof List<?> l) {
            l.forEach(x -> paths.add(String.valueOf(x)));
        }
        ComputeEvent e = new ComputeEvent(id, type, str(body.get("kind")), str(body.get("entity")),
                body.get("businessDate") == null ? null : LocalDate.parse(String.valueOf(body.get("businessDate"))), wm, Instant.now(),
                body.get("by") == null ? p.user() : String.valueOf(body.get("by")), paths,
                body.get("payload") instanceof Map<?, ?> pl ? (Map<String, Object>) pl : Map.of());
        Map<String, Object> out = hub.publish(e);
        audit.record(p.user(), "compute.event", "compute/" + type + "/" + id, "kind " + e.kind() + ", entity " + e.entity() + ": " + out);
        return Map.of("event", id, "connectors", out);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    // ------------------------------------------------------------------------------------------------------------ sandboxes

    @GetMapping("/compute/sandboxes")
    public List<Map<String, Object>> sandboxes(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return hub.sandboxes().list(p.user());
    }

    /** A new private sandbox: {@code {connector, title, items}}. */
    @PostMapping("/compute/sandboxes")
    @SuppressWarnings("unchecked")
    public Map<String, Object> create(@RequestBody Map<String, Object> body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        String connector = str(body.get("connector"));
        connector(connector);
        try {
            var s = hub.sandboxes().create(p.user(), connector, str(body.get("title")), items(body));
            audit.record(p.user(), "compute.sandbox.create", "sandbox/" + s.id(), connector + ", " + s.items().size() + " items");
            return Map.of("id", s.id(), "version", s.version());
        } catch (IllegalArgumentException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, e.getMessage());
        }
    }

    /** Replaces a sandbox's items: {@code {items, basedOn}}; refused for a frozen copy, or when it changed since {@code basedOn}. */
    @PutMapping("/compute/sandboxes/{id}")
    public Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, Object> body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        try {
            Long based = body.get("basedOn") instanceof Number n ? n.longValue() : null;
            var s = hub.sandboxes().update(p.user(), id, items(body), based);
            audit.record(p.user(), "compute.sandbox.update", "sandbox/" + id, "version " + s.version() + ", " + s.items().size() + " items");
            return Map.of("id", s.id(), "version", s.version());
        } catch (IllegalArgumentException e) {
            throw new DrishtiException(ErrorCode.COMPUTE_NOT_FOUND, "no sandbox " + id);
        } catch (IllegalStateException e) {
            throw new DrishtiException(ErrorCode.COMPUTE_REFUSED, e.getMessage());
        }
    }

    /** Gives another user a frozen copy: {@code {to}}. */
    @PostMapping("/compute/sandboxes/{id}/copy")
    public Map<String, Object> copy(@PathVariable String id, @RequestBody Map<String, Object> body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        try {
            var s = hub.sandboxes().copyFor(p.user(), id, str(body.get("to")));
            audit.record(p.user(), "compute.sandbox.copy", "sandbox/" + id, "frozen copy " + s.id() + " for " + s.owner());
            return Map.of("id", s.id(), "owner", s.owner(), "frozen", true);
        } catch (IllegalArgumentException e) {
            throw new DrishtiException(ErrorCode.COMPUTE_NOT_FOUND, "no sandbox " + id);
        }
    }

    @DeleteMapping("/compute/sandboxes/{id}")
    public Map<String, Object> delete(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        if (!hub.sandboxes().delete(p.user(), id)) {
            throw new DrishtiException(ErrorCode.COMPUTE_NOT_FOUND, "no sandbox " + id);
        }
        audit.record(p.user(), "compute.sandbox.delete", "sandbox/" + id, "");
        return Map.of("deleted", id);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> body) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (body.get("items") instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            }
        }
        return out;
    }

    /** Admin → Compute's view of the reactive connectors: kinds and parameters, cache, coalescing, computations, latency. */
    @GetMapping("/admin/compute/reactive")
    public Map<String, Object> admin(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        List<Map<String, Object>> list = new ArrayList<>();
        hub.connectors().forEach(c -> list.add(c.describe()));
        return Map.of("connectors", list, "requests", hub.events().size(), "businessDate", dates.current().toString());
    }
}
