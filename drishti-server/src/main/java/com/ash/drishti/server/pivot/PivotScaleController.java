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
package com.ash.drishti.server.pivot;

import com.ash.drishti.bicompute.cluster.ClusterRoute;
import com.ash.drishti.bicompute.core.PivotCore;
import com.ash.drishti.bicompute.core.PivotView;
import com.ash.drishti.bicompute.core.ScaleOptions;
import com.ash.drishti.bicompute.rollup.RollupDef;
import com.ash.drishti.bicompute.rollup.Rollups;
import com.ash.drishti.bicompute.spi.Viewer;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.server.net.NetCallFilter;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The pivot engine's scale API (PIVOT_GUIDE.md, scale; API_GUIDE.md, the pivot engine):
 *
 * <ul>
 *   <li>{@code POST /api/v1/pivot/query?progressive=true}: server-sent events, {@code approximate} (≈ from a sample, each figure with its
 *       interval; only for a view estimated over {@code progressive.after-ms} with no holistic measure) then {@code exact};</li>
 *   <li>{@code GET /api/v1/pivot/rollups}: the definitions, the rollups built (rows, version, last refresh) and the advice;</li>
 *   <li>{@code POST /api/v1/pivot/rollups}, {@code DELETE /api/v1/pivot/rollups/{id}}, {@code POST /api/v1/pivot/rollups/{id}/refresh}
 *       (administrators; audited);</li>
 *   <li>{@code POST /api/v1/net/pivot/partials}: a node's share of a scattered view, for the cluster's nodes only, as the on-behalf-of
 *       person.</li>
 * </ul>
 */
@RestController
public class PivotScaleController {

    private final PivotCore core;
    private final PivotScaleConfiguration.Scale scale;
    private final PivotViews views;
    private final Entitlements entitlements;
    private final AuditLog audit;
    private final ObjectMapper json;
    private final boolean enabled;

    public PivotScaleController(PivotCore core, PivotScaleConfiguration.Scale scale, PivotViews views, Entitlements entitlements, AuditLog audit,
            ObjectMapper json, @Value("${drishti.pivot.enabled:true}") boolean enabled) {
        this.core = core;
        this.scale = scale;
        this.views = views;
        this.entitlements = entitlements;
        this.audit = audit;
        this.json = json;
        this.enabled = enabled;
        loadDefinitions();
    }

    private Viewer on(Principal p) {
        if (!enabled) {
            throw new DrishtiException(ErrorCode.PIVOT_OFF, "the pivot engine is switched off on this server (drishti.pivot.enabled)");
        }
        return new Viewer(p.user(), p);
    }

    @SuppressWarnings("unchecked")
    private PivotView view(Principal p, Map<String, Object> body) {
        Object v = body == null ? null : body.get("view");
        if (v instanceof String id) {
            return views.open(p, id);
        }
        if (v instanceof Map<?, ?> m) {
            return PivotView.parse((Map<String, Object>) m);
        }
        return PivotView.parse(body);
    }

    @PostMapping(path = "/api/v1/pivot/query", params = "progressive=true", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter progressive(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @RequestBody Map<String, Object> body) {
        Viewer v = on(p);
        PivotView view = view(p, body);
        ScaleOptions o = ScaleOptions.parse(body);
        SseEmitter emitter = new SseEmitter(0L);
        Thread.ofVirtual().name("drishti-pivot-progressive").start(() -> {
            try {
                scale.progressive().run(v, view, o, answer -> {
                    try {
                        Object phase = answer.get("progressive") instanceof Map<?, ?> m ? m.get("phase") : "exact";
                        emitter.send(SseEmitter.event().name(String.valueOf(phase)).data(answer, MediaType.APPLICATION_JSON));
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                });
                audit.record(p.user(), "pivot.query", view.source(), "progressive; rows=" + view.rows() + "; columns=" + view.columns());
                emitter.complete();
            } catch (RuntimeException e) {
                try {
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("error", e.getMessage());
                    if (e instanceof DrishtiException de) {
                        err.put("code", de.errorCode().code());
                    }
                    emitter.send(SseEmitter.event().name("error").data(err, MediaType.APPLICATION_JSON));
                } catch (IOException | RuntimeException ignored) {
                    // the person went away
                }
                emitter.complete();
            }
        });
        return emitter;
    }

    @GetMapping("/api/v1/pivot/rollups")
    public Map<String, Object> rollups(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        on(p);
        Rollups r = scale.rollups();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("definitions", r.definitions().stream().map(RollupDef::toMap).toList());
        m.put("built", r.all().stream().map(Rollups::describe).toList());
        m.put("advice", scale.awareness().advice());
        return m;
    }

    @PostMapping("/api/v1/pivot/rollups")
    public Map<String, Object> define(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @RequestBody Map<String, Object> body) {
        on(p);
        entitlements.requireAdmin(p);
        RollupDef d = scale.rollups().define(RollupDef.parse(body));
        audit.record(p.user(), "pivot.rollup.define", d.source(), d.id() + ": " + d.grain() + " " + d.functions());
        saveDefinitions();
        String date = scale.rollups().sourceOf(d.source()).latestDate(d.source());
        scale.rollups().refreshLater(d.id(), date);
        Map<String, Object> m = new LinkedHashMap<>(d.toMap());
        m.put("refresh", "queued for " + (date == null ? "the source" : date));
        return m;
    }

    @DeleteMapping("/api/v1/pivot/rollups/{id}")
    public Map<String, Object> remove(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @PathVariable String id) {
        on(p);
        entitlements.requireAdmin(p);
        boolean had = scale.rollups().remove(id);
        if (!had) {
            throw new DrishtiException(ErrorCode.PIVOT_NOT_FOUND, "no rollup '" + id + "'");
        }
        audit.record(p.user(), "pivot.rollup.remove", id, "");
        saveDefinitions();
        return Map.of("removed", id);
    }

    @PostMapping("/api/v1/pivot/rollups/{id}/refresh")
    public Map<String, Object> refresh(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @PathVariable String id,
            @RequestParam(required = false) String date) {
        on(p);
        entitlements.requireAdmin(p);
        RollupDef d = scale.rollups().definitions().stream().filter(x -> x.id().equals(id)).findFirst()
                .orElseThrow(() -> new DrishtiException(ErrorCode.PIVOT_NOT_FOUND, "no rollup '" + id + "'"));
        String day = date == null || date.isBlank() ? scale.rollups().sourceOf(d.source()).latestDate(d.source()) : date;
        String words = scale.rollups().refresh(id, day);
        audit.record(p.user(), "pivot.rollup.refresh", id, words);
        return Map.of("rollup", id, "date", day == null ? "" : day, "words", words);
    }

    /** A node's share of a scattered view: the plan evaluated over its parts or shard, as the on-behalf-of person; partial states only. */
    @PostMapping("/api/v1/net/pivot/partials")
    public Map<String, Object> partials(@RequestAttribute(Principal.ATTRIBUTE) Principal p,
            @RequestAttribute(value = NetCallFilter.CLAIMS, required = false) Object claims, @RequestBody Map<String, Object> body) {
        if (!(claims instanceof com.ash.drishti.net.security.OnBehalfOf.Claims)) {
            throw new DrishtiException(ErrorCode.NET_REFUSED, "this endpoint is called by a node for a user: an on-behalf-of token is needed");
        }
        return ClusterRoute.answer(core, scale.parted(), on(p), body);
    }

    // ---- definitions made in the API, kept beside the rollups -------------------------------------------------------------------------

    private Path definitionsFile() {
        return scale.rollups().dir() == null ? null : scale.rollups().dir().resolve("definitions.json");
    }

    private void saveDefinitions() {
        Path f = definitionsFile();
        if (f == null) {
            return;
        }
        try {
            Files.createDirectories(f.getParent());
            List<Map<String, Object>> all = new ArrayList<>();
            scale.rollups().definitions().forEach(d -> all.add(d.toMap()));
            Files.write(f, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(all));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private void loadDefinitions() {
        Path f = definitionsFile();
        if (f == null || !Files.isRegularFile(f)) {
            return;
        }
        try {
            for (Map<String, Object> d : (List<Map<String, Object>>) json.readValue(f.toFile(), List.class)) {
                try {
                    scale.rollups().define(RollupDef.parse(d));
                } catch (RuntimeException e) {
                    org.slf4j.LoggerFactory.getLogger(PivotScaleController.class).warn("pivot rollup {} not defined: {}", d.get("id"), e.getMessage());
                }
            }
        } catch (IOException e) {
            org.slf4j.LoggerFactory.getLogger(PivotScaleController.class).warn("pivot rollup definitions {} not read: {}", f, e.getMessage());
        }
    }
}
