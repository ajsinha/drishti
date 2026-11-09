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
package com.ash.drishti.server.alerts;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.api.Subscription;
import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.server.collab.InboxHub;
import com.ash.drishti.server.collab.InboxService;
import com.ash.drishti.server.metrics.PlatformAlerts;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.kernel.api.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The one alert engine's API (API_REFERENCE.md "Alerts"): the alerts raised for the caller (acknowledge, resolve, snooze, assign), the caller's
 * alert rules (create, change, pause, look now, delete, preview), the rules the packs suggest, the caller's notification stream, and an
 * administrators' overview of everyone's rules, the board tiles that notify and the platform alerts.
 *
 * <pre>
 *   GET    /api/v1/alerts?stage=open|all                       the alerts raised for me, newest first
 *   POST   /api/v1/alerts/{acknowledge|resolve|snooze|assign}   {id, note, for, to}
 *   GET    /api/v1/alerts/rules                                my rules; PUT|GET|DELETE /rules/{name}; POST /rules/{name}/check|pause|resume
 *   POST   /api/v1/alerts/rules/preview                        what a rule would read for me now
 *   GET    /api/v1/alerts/suggestions/{kind}                   rules the packs suggest for a kind
 *   GET    /api/v1/alerts/stream                               my notifications: notices, switches, edits (server-sent events)
 *   GET    /api/v1/admin/alerts                                everyone's rules, the board tiles that notify, the platform alerts
 *   POST   /api/v1/admin/alerts/rules/{owner}/{name}/pause|resume; DELETE /api/v1/admin/alerts/rules/{owner}/{name}
 * </pre>
 */
@RestController
public class AlertController {

    private final AlertService alerts;
    private final Entitlements entitlements;
    private final PackRegistry packs;
    private final ExecutorService executor;
    private final InboxHub hub;
    private final InboxService inbox;
    private final ObjectProvider<PlatformAlerts> platform;

    public AlertController(AlertService alerts, Entitlements entitlements, PackRegistry packs, ExecutorService drishtiVirtualExecutor, InboxHub hub,
            InboxService inbox, ObjectProvider<PlatformAlerts> platform) {
        this.alerts = alerts;
        this.entitlements = entitlements;
        this.packs = packs;
        this.executor = drishtiVirtualExecutor;
        this.hub = hub;
        this.inbox = inbox;
        this.platform = platform;
    }

    // ---- the alerts raised ------------------------------------------------------------------------------------------------------------

    @GetMapping("/api/v1/alerts")
    public List<Map<String, Object>> raised(@RequestParam(defaultValue = "open") String stage, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return alerts.raised(p.user(), entitlements.isAdmin(p), !"all".equals(stage));
    }

    @PostMapping("/api/v1/alerts/{action:acknowledge|resolve|snooze|assign}")
    public Map<String, Object> act(@PathVariable String action, @RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        String id = body == null ? "" : body.path("id").asText("");
        if (id.isBlank()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "which alert: give its id");
        }
        return alerts.act(p.user(), entitlements.isAdmin(p), id, action, text(body, "note"), text(body, "for"), text(body, "to"));
    }

    private static String text(JsonNode b, String k) {
        return b.hasNonNull(k) ? b.get(k).asText() : null;
    }

    // ---- the caller's rules -----------------------------------------------------------------------------------------------------------

    @GetMapping("/api/v1/alerts/rules")
    public List<JsonNode> mine(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return alerts.of(p.user());
    }

    @GetMapping("/api/v1/alerts/rules/{name}")
    public JsonNode get(@PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return alerts.get(p.user(), name).orElseThrow(() -> missing(name));
    }

    /**
     * Body {@code {title?, severity?: info|warn|critical, enabled?, source: {type: visual|dataset|model|field, ...}, condition: {type:
     * threshold|change|nodata|rule, ...}, trigger: {mode: schedule|after-load|live, ...}, hysteresis?, cooldown?, delivery?: {inbox, email,
     * bridge, webhooks, sms}}}.
     */
    @PutMapping("/api/v1/alerts/rules/{name}")
    public JsonNode save(@PathVariable String name, @RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return alerts.save(p.user(), name, body);
    }

    @DeleteMapping("/api/v1/alerts/rules/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        if (!alerts.delete(p.user(), name)) {
            throw missing(name);
        }
    }

    /** Looks now, as the caller, and answers with the rule as it stands (state, history). */
    @PostMapping("/api/v1/alerts/rules/{name}/check")
    public JsonNode check(@PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return alerts.check(p.user(), name);
    }

    @PostMapping("/api/v1/alerts/rules/{name}/pause")
    public JsonNode pause(@PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return alerts.setEnabled(p.user(), name, false, p.user());
    }

    @PostMapping("/api/v1/alerts/rules/{name}/resume")
    public JsonNode resume(@PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return alerts.setEnabled(p.user(), name, true, p.user());
    }

    /** Body as for a save: what the rule would read for the caller right now, and whether it would fire. Saves and sends nothing. */
    @PostMapping("/api/v1/alerts/rules/preview")
    public Map<String, Object> preview(@RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return alerts.preview(p.user(), body);
    }

    /** Rules the enabled packs suggest for entities of {@code kind} ({@code alerts:} in pack.yaml). */
    @GetMapping("/api/v1/alerts/suggestions/{kind}")
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> suggestions(@PathVariable String kind) {
        List<Map<String, Object>> all = new ArrayList<>();
        packs.packs().forEach(pk -> {
            Object a = pk.manifest().get("alerts");
            if (a instanceof List<?> list) {
                list.forEach(o -> all.add((Map<String, Object>) o));
            }
        });
        return all.stream().filter(s -> kind.equals(s.get("kind"))).toList();
    }

    // ---- administrators ---------------------------------------------------------------------------------------------------------------

    /** Everyone's rules, the board tiles that notify, and the platform alerts (the rules of the file, the open and the recent closed). */
    @GetMapping("/api/v1/admin/alerts")
    public Map<String, Object> all(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rules", alerts.all());
        m.put("boards", alerts.boardAlerts());
        PlatformAlerts pa = platform.getIfAvailable();
        m.put("platform", pa == null ? Map.of("enabled", false) : pa.describe());
        return m;
    }

    @PostMapping("/api/v1/admin/alerts/rules/{owner}/{name}/pause")
    public JsonNode adminPause(@PathVariable String owner, @PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return alerts.setEnabled(owner, name, false, p.user());
    }

    @PostMapping("/api/v1/admin/alerts/rules/{owner}/{name}/resume")
    public JsonNode adminResume(@PathVariable String owner, @PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return alerts.setEnabled(owner, name, true, p.user());
    }

    @DeleteMapping("/api/v1/admin/alerts/rules/{owner}/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void adminDelete(@PathVariable String owner, @PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        if (!alerts.delete(owner, name)) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no alert '" + name + "' of " + owner);
        }
    }

    private static DrishtiException missing(String name) {
        return new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no alert '" + name + "'");
    }

    // ---- the caller's notification stream ---------------------------------------------------------------------------------------------

    private volatile com.ash.drishti.server.switches.SwitchEvents switches;

    /** Admin → Functions: a change of the switches is told on every stream ({@code switches}), so open pages follow it at once. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void switches(com.ash.drishti.server.switches.SwitchEvents s) {
        this.switches = s;
    }

    private com.ash.drishti.server.edit.EditFeed edits;

    /** Write-back: a change's steps to its submitter and approvers, and to every reader of the kind that it was applied ({@code edit}). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void edits(com.ash.drishti.server.edit.EditFeed e) {
        this.edits = e;
    }

    /**
     * The caller's notifications as server-sent events, one connection for the bell: {@code notice} (an alert raised, a share, a comment, an
     * approval question, rendered for this reader), {@code switches} and {@code edit}.
     */
    @GetMapping(path = "/api/v1/alerts/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        SseEmitter emitter = new SseEmitter(0L);
        LinkedBlockingQueue<Object> q = new LinkedBlockingQueue<>(100);
        java.util.function.Consumer<Object> put = e -> {
            if (!q.offer(e)) {
                q.poll();
                q.offer(e);
            }
        };
        Subscription notices = hub.listen(p.user(), put::accept);
        com.ash.drishti.server.switches.SwitchEvents sw = switches;
        AutoCloseable switched = sw == null ? () -> { } : sw.listen(put::accept);
        com.ash.drishti.server.edit.EditFeed ed = edits;
        AutoCloseable edited = ed == null ? () -> { } : ed.listen(put::accept);
        executor.execute(() -> {
            try {
                emitter.send(SseEmitter.event().name("hello").data(Map.of("user", p.user())));
                while (!Thread.currentThread().isInterrupted()) {
                    Object e = q.poll(15, TimeUnit.SECONDS);
                    if (e == null) {
                        emitter.send(SseEmitter.event().comment("hb"));
                    } else if (e instanceof com.ash.drishti.identity.collab.Notice n) {
                        // rendered now, for this reader: no entity id without access, masked spans scrubbed, no data values
                        InboxService.Row row = inbox.render(n, p);
                        emitter.send(SseEmitter.event().name("notice").id(Long.toString(n.seq())).data(row, MediaType.APPLICATION_JSON));
                    } else if (e instanceof com.ash.drishti.server.switches.SwitchEvents.Changed c) {
                        emitter.send(SseEmitter.event().name("switches").data(c, MediaType.APPLICATION_JSON));
                    } else if (e instanceof com.ash.drishti.server.edit.EditFeed.Event ev && ed != null) {
                        Map<String, Object> mine = ed.forReader(ev, p);    // shaped for this reader: no values, names only for who may see them
                        if (mine != null) {
                            emitter.send(SseEmitter.event().name("edit").data(mine, MediaType.APPLICATION_JSON));
                        }
                    }
                }
            } catch (IOException | IllegalStateException e) {
                emitter.completeWithError(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                notices.close();
                try {
                    switched.close();
                    edited.close();
                } catch (Exception ignored) {
                    // a listener removed from a list: nothing to fail
                }
            }
        });
        return emitter;
    }
}
