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
package com.ash.drishti.server.enginehost;

import com.ash.drishti.enginehost.EngineHost;
import com.ash.drishti.enginehost.HostedEngine;
import com.ash.drishti.enginehost.InputBinder;
import com.ash.drishti.kernel.api.security.Principal;
import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.server.engines.EngineRegistry;
import com.ash.drishti.server.security.Entitlements;
import com.ash.engine.Answer;
import com.ash.engine.Descriptor;
import com.ash.engine.EngineException;
import com.ash.engine.EngineStats;
import com.ash.engine.Health;
import com.ash.engine.RequestClass;
import com.ash.engine.host.EngineLib;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The hosted engines' API (engine specification 10.4, 7.4 to 7.6; docs/architecture/LIFECYCLE.md section 8). One generic endpoint family
 * reads the descriptor at run time; nothing is generated or written per engine (EN-22).
 *
 * <ul>
 *   <li>{@code GET /api/v1/engines}: the engines the caller may open; {@code GET /api/v1/engines/platform}: what this server provides.</li>
 *   <li>{@code GET /api/v1/engines/{id}}: its descriptor as a caller needs it; {@code /health}, {@code /stats}: from the health board.</li>
 *   <li>{@code POST /api/v1/engines/{id}/output} ({@code {outputs, params, snapshot}}) and {@code GET /api/v1/engines/{id}/outputs/{output}}
 *       (parameters as query parameters, {@code snapshot.<dimension>} for the snapshot): an answer, as the caller.</li>
 *   <li>{@code GET /api/v1/engines/{id}/outputs/{output}/live?since=}: a live output's rows or its changes by key and version.</li>
 *   <li>Administrators: {@code GET /api/v1/admin/engines/host} (the folder, the JARs, the licence, every engine's state, binding and words);
 *       {@code GET|POST /api/v1/admin/engines/{id}/lifecycle[/{init,start,suspend,resume,shutdown}]}; {@code /template}; {@code /asks/{name}}.</li>
 * </ul>
 *
 * Errors answer {@code {code, kind, detail}}: refused 400 (a transition the state does not allow 409), not permitted 403, a limit 429,
 * unavailable 503, cancelled or timed out 504, failed 500.
 */
@RestController
public class EngineHostController {

    private final EngineHost host;
    private final Entitlements entitlements;
    private final PackRegistry packs;
    private final EngineRegistry registry;

    public EngineHostController(EngineHost host, Entitlements entitlements, PackRegistry packs, EngineRegistry registry) {
        this.host = host;
        this.entitlements = entitlements;
        this.packs = packs;
        this.registry = registry;
    }

    /** A request's body: the outputs, the parameters, the snapshot's dimensions. */
    public record OutputBody(List<String> outputs, Map<String, Object> params, Map<String, String> snapshot) {}

    private PrincipalAccess viewer(Principal p) {
        return new PrincipalAccess(p, entitlements, packs);
    }

    @GetMapping("/api/v1/engines")
    public Map<String, Object> list(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("platform", host.platform());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (HostedEngine h : host.engines()) {
            Descriptor d = h.descriptor();
            if (d == null || h.file() == null) {
                continue;
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", h.id());
            r.put("title", d.title());
            r.put("version", d.version());
            r.put("state", h.state());
            r.put("health", h.health().state().name());
            r.put("outputs", new ArrayList<>(d.outputs().keySet()));
            rows.add(r);
        }
        m.put("engines", rows);
        return m;
    }

    @GetMapping("/api/v1/engines/platform")
    public Map<String, Object> platform() {
        Map<String, Object> m = new LinkedHashMap<>();
        var pr = host.settings().provided();
        m.put("provides", host.platform());
        m.put("api", pr.api());
        m.put("tools", pr.tools());
        m.put("lake", pr.lake());
        m.put("rachana", pr.rachana());
        return m;
    }

    @GetMapping("/api/v1/engines/{id}")
    public ResponseEntity<Map<String, Object>> describe(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return answer(() -> {
            HostedEngine h = host.require(id);
            Descriptor d = h.descriptor();
            if (d == null) {
                throw new EngineException("ENG-5030", EngineException.Kind.UNAVAILABLE, h.words());
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.id());
            m.put("version", d.version());
            m.put("title", d.title());
            m.put("about", d.about());
            m.put("publisher", d.publisher() == null ? null : d.publisher().name());
            m.put("snapshot", d.snapshot().stream().map(x -> Map.of("name", x.name(), "type", x.type())).toList());
            Map<String, Object> outputs = new LinkedHashMap<>();
            d.outputs().forEach((name, o) -> {
                Map<String, Object> om = new LinkedHashMap<>();
                om.put("title", o.title());
                om.put("table", "engine." + d.id() + "." + name);
                om.put("params", o.params());
                om.put("key", o.key());
                om.put("columns", o.columns().stream().map(c -> {
                    Map<String, Object> cm = new LinkedHashMap<>();
                    cm.put("name", c.name());
                    cm.put("type", c.type());
                    cm.put("measure", c.measure());
                    cm.put("label", c.label());
                    return cm;
                }).toList());
                outputs.put(name, om);
            });
            m.put("outputs", outputs);
            m.put("state", h.state());
            return m;
        });
    }

    @GetMapping("/api/v1/engines/{id}/health")
    public ResponseEntity<Map<String, Object>> health(@PathVariable String id) {
        return answer(() -> health(host.require(id)));
    }

    @GetMapping("/api/v1/engines/{id}/stats")
    public ResponseEntity<Map<String, Object>> stats(@PathVariable String id) {
        return answer(() -> stats(host.require(id)));
    }

    @PostMapping("/api/v1/engines/{id}/output")
    public ResponseEntity<Map<String, Object>> output(@PathVariable String id, @RequestBody OutputBody body,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return answer(() -> ask(id, new HostedEngine.Ask(body.outputs(), body.params(), body.snapshot(), RequestClass.INTERACTIVE, false), p));
    }

    @GetMapping("/api/v1/engines/{id}/outputs/{output}")
    public ResponseEntity<Map<String, Object>> output(@PathVariable String id, @PathVariable String output, @RequestParam Map<String, String> query,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        Map<String, Object> params = new TreeMap<>();
        Map<String, String> snapshot = new TreeMap<>();
        query.forEach((k, v) -> {
            if (k.startsWith("snapshot.")) {
                snapshot.put(k.substring(9), v);
            } else {
                params.put(k, v);
            }
        });
        return answer(() -> ask(id, new HostedEngine.Ask(List.of(output), params, snapshot, RequestClass.INTERACTIVE, false), p));
    }

    @GetMapping("/api/v1/engines/{id}/outputs/{output}/live")
    public ResponseEntity<Map<String, Object>> live(@PathVariable String id, @PathVariable String output, @RequestParam(defaultValue = "-1") long since,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return answer(() -> {
            HostedEngine h = host.require(id);
            PrincipalAccess v = viewer(p);
            if (!v.admin() && h.binding().inputs().values().stream().flatMap(b -> b.kinds().stream()).anyMatch(k -> !v.mayOpen(k) || !v.rows(k).all())) {
                // a live output is computed as its named ask's identity: a caller with row rules on its inputs asks the output instead
                throw new EngineException("ENG-4003", EngineException.Kind.NOT_PERMITTED, p.user() + " reads " + id + " under row rules; ask "
                        + "/api/v1/engines/" + id + "/outputs/" + output + " for the rows they may see");
            }
            return host.outputs().live(id, output, since).orElseThrow(() -> new EngineException("ENG-5033", EngineException.Kind.UNAVAILABLE,
                    id + "." + output + " is not live here, or no named ask has answered it yet"));
        });
    }

    private Map<String, Object> ask(String id, HostedEngine.Ask ask, Principal p) throws EngineException {
        HostedEngine h = host.require(id);
        try (AutoCloseable counted = registry.known(id) ? registry.enter(id) : () -> { }) {
            Answer a = h.ask(ask, viewer(p));
            return com.ash.drishti.enginehost.AnswerShapes.plain(a);
        } catch (EngineException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new EngineException("ENG-9000", EngineException.Kind.FAILED, id + " failed: " + e.getMessage(), Map.of(), e);
        }
    }

    // ------------------------------------------------------------------------------------------------------------- administrators

    @GetMapping("/api/v1/admin/engines/host")
    public Map<String, Object> hostView(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("platform", host.platform());
        m.put("folder", host.settings().libPath().toString());
        m.put("trust", host.settings().trust().name().toLowerCase(java.util.Locale.ROOT));
        var l = host.licence();
        if (l != null) {
            m.put("licence", Map.of("source", l.source(), "customer", l.customer(), "products", l.products(), "limits", l.limits(),
                    "problem", l.problem() == null ? "" : l.problem()));
        }
        m.put("jars", host.jars().stream().map(j -> Map.of("file", j.file(), "sha384", j.sha384() == null ? "" : j.sha384(), "engines", j.engines(),
                "publishers", j.publishers(), "certificate", j.certificate(), "notes", j.notes())).toList());
        m.put("engines", host.engines().stream().map(EngineHostController::card).toList());
        m.put("packs", host.packNotes());
        return m;
    }

    /** One engine as Admin -> Engines shows it: state and words, version and JAR, binding (each input's tables and rule), sessions, triggers waiting. */
    public static Map<String, Object> card(HostedEngine h) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", h.id());
        Descriptor d = h.descriptor();
        r.put("title", d == null ? h.id() : d.title());
        r.put("version", d == null ? "" : d.version());
        r.put("product", d == null ? "" : d.product());
        EngineLib.Entry e = h.entry();
        r.put("jar", e.jar() == null ? "" : e.jar().path().getFileName().toString());
        r.put("state", h.state());
        r.put("lifecycle", h.lifecycle().name());
        r.put("words", h.words() == null ? "" : h.words());
        r.put("code", h.code() == null ? "" : h.code());
        r.put("since", h.since());
        r.put("isolation", h.isolation());
        r.put("health", health(h));
        List<Map<String, Object>> inputs = new ArrayList<>();
        for (InputBinder.Bound b : h.binding().inputs().values()) {
            Map<String, Object> im = new LinkedHashMap<>();
            im.put("input", b.input());
            im.put("contract", b.contract());
            im.put("tables", b.kinds());
            im.put("rule", b.rule());
            im.put("how", b.how());
            inputs.add(im);
        }
        r.put("inputs", inputs);
        r.put("sessions", h.sessionsOpen());
        r.put("memoryBytes", h.memoryBytes());
        r.put("waiting", h.waiting());
        r.put("problems", h.problems().stream().map(Object::toString).toList());
        return r;
    }

    @GetMapping("/api/v1/admin/engines/{id}/lifecycle")
    public ResponseEntity<Map<String, Object>> lifecycle(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return answer(() -> card(host.require(id)));
    }

    @PostMapping("/api/v1/admin/engines/{id}/lifecycle/{action}")
    public ResponseEntity<Map<String, Object>> transition(@PathVariable String id, @PathVariable String action,
            @RequestParam(required = false) String reason, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return answer(() -> {
            host.transition(id, action, p.user(), reason);
            return card(host.require(id));
        });
    }

    @GetMapping(value = "/api/v1/admin/engines/{id}/template", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> template(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        try {
            return ResponseEntity.ok(host.template(id));
        } catch (EngineException e) {
            return ResponseEntity.status(status(e)).contentType(MediaType.TEXT_PLAIN).body(e.code() + " " + e.getMessage());
        }
    }

    @PostMapping("/api/v1/admin/engines/{id}/asks/{name}")
    public ResponseEntity<Map<String, Object>> runAsk(@PathVariable String id, @PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return answer(() -> com.ash.drishti.enginehost.AnswerShapes.plain(host.require(id).runAsk(name, RequestClass.BATCH)));
    }

    // ------------------------------------------------------------------------------------------------------------- shapes

    static Map<String, Object> health(HostedEngine h) {
        Health x = h.health();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", x.state().name());
        m.put("reasons", x.reasons());
        m.put("checkedAt", x.checkedAt().toString());
        m.put("lifecycle", h.lifecycle().name());
        return m;
    }

    static Map<String, Object> stats(HostedEngine h) {
        EngineStats s = h.stats();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sinceStart", s.sinceStart());
        m.put("recent", s.recent());
        m.put("sessionsOpen", s.sessionsOpen());
        m.put("queueDepth", s.queueDepth());
        m.put("lastActivity", s.lastActivity() == null ? null : s.lastActivity().toString());
        m.put("takenAt", s.takenAt().toString());
        m.put("host", Map.of("refusedBeforeEngine", h.refusedByHost(), "rowsDelivered", h.rowsDelivered(), "sessionsOpen", h.sessionsOpen(),
                "memoryBytes", h.memoryBytes()));
        return m;
    }

    @FunctionalInterface
    private interface Call {
        Map<String, Object> run() throws EngineException;
    }

    private static ResponseEntity<Map<String, Object>> answer(Call call) {
        try {
            return ResponseEntity.ok(call.run());
        } catch (EngineException e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", e.code());
            body.put("kind", e.kind().name());
            body.put("detail", e.getMessage());
            body.put("retry", e.retry());
            return ResponseEntity.status(status(e)).body(body);
        }
    }

    static HttpStatus status(EngineException e) {
        if ("ENG-5020".equals(e.code())) {
            return HttpStatus.CONFLICT;
        }
        return switch (e.kind()) {
            case REFUSED -> HttpStatus.BAD_REQUEST;
            case NOT_PERMITTED -> HttpStatus.FORBIDDEN;
            case LIMIT -> HttpStatus.TOO_MANY_REQUESTS;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case CANCELLED, TIMED_OUT -> HttpStatus.GATEWAY_TIMEOUT;
            case FAILED -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
