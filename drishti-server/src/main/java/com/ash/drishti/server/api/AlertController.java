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
import com.ash.drishti.api.Subscription;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.server.alerts.AlertEngine;
import com.ash.drishti.server.alerts.AlertEvent;
import com.ash.drishti.server.alerts.AlertRule;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** The caller's alert rules, their fired alerts, a live stream of new ones, and suggestions from the packs. */
@RestController
@RequestMapping("/api/v1/me/alerts")
public class AlertController {

    /**
     * @param kind entity kind
     * @param id entity id
     * @param when Rachana-EL predicate
     * @param severity info, warn or critical
     * @param message Rachana-EL template
     * @param enabled evaluated or not
     */
    public record RuleBody(String kind, String id, String when, String severity, String message, Boolean enabled) {}

    private final AlertEngine engine;
    private final Entitlements entitlements;
    private final PackRegistry packs;
    private final ExecutorService executor;

    public AlertController(AlertEngine engine, Entitlements entitlements, PackRegistry packs, ExecutorService drishtiVirtualExecutor) {
        this.engine = engine;
        this.entitlements = entitlements;
        this.packs = packs;
        this.executor = drishtiVirtualExecutor;
    }

    @GetMapping("/rules")
    public List<AlertRule> rules(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return engine.rules(p.user());
    }

    @PutMapping("/rules/{name}")
    public AlertRule save(@PathVariable String name, @RequestBody RuleBody b, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        if (b.kind() == null || b.id() == null || b.when() == null || b.when().isBlank()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a rule needs kind, id and when");
        }
        entitlements.requireOpen(p, b.kind());
        return engine.save(p.user(), new AlertRule(name, EntityRef.of(b.kind(), b.id()), b.when(),
                b.severity() == null ? "warn" : b.severity(), b.message(), b.enabled() == null || b.enabled()));
    }

    @DeleteMapping("/rules/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        if (!engine.delete(p.user(), name)) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no rule '" + name + "'");
        }
    }

    @GetMapping
    public List<AlertEvent> events(@RequestParam(defaultValue = "50") int limit, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return engine.events(p.user(), limit);
    }

    /** Rules the enabled packs suggest for entities of {@code kind}. */
    @GetMapping("/suggestions/{kind}")
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> suggestions(@PathVariable String kind) {
        List<Map<String, Object>> all = new ArrayList<>();
        packs.packs().forEach(pk -> {
            Object a = pk.manifest().get("alerts");
            if (a instanceof List<?> list) {
                list.forEach(o -> all.add((Map<String, Object>) o));
            }
        });
        return AlertEngine.suggestions(all, kind);
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        SseEmitter emitter = new SseEmitter(0L);
        LinkedBlockingQueue<AlertEvent> q = new LinkedBlockingQueue<>(100);
        Subscription sub = engine.listen(p.user(), e -> {
            if (!q.offer(e)) {
                q.poll();
                q.offer(e);
            }
        });
        executor.execute(() -> {
            try {
                emitter.send(SseEmitter.event().name("hello").data(Map.of("user", p.user())));
                while (!Thread.currentThread().isInterrupted()) {
                    AlertEvent e = q.poll(15, TimeUnit.SECONDS);
                    if (e == null) {
                        emitter.send(SseEmitter.event().comment("hb"));
                    } else {
                        emitter.send(SseEmitter.event().name("alert").id(Long.toString(e.seq())).data(e, MediaType.APPLICATION_JSON));
                    }
                }
            } catch (IOException | IllegalStateException e) {
                emitter.completeWithError(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                sub.close();
            }
        });
        return emitter;
    }
}
