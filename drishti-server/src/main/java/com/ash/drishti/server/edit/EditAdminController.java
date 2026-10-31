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
package com.ash.drishti.server.edit;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.server.connectors.ConnectorManager;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin → Edits (WRITE_BACK.md › The design and its reasons, section 11): the store in use and why (the auto rule), every lane's owner, epoch, lease and depth with
 * its oldest wait, the warnings (a cluster without a shared store, an edit primary that is not reachable, a lane whose lease is not
 * renewed, a lane waiting longer than {@code lane-warn}), source commands not confirmed past their timeout, each connector's
 * {@code writes:} with its problems and its switch, and the open overlays. Administrators only; switching a connector's writes on or
 * off and withdrawing an overlay are audited.
 */
@RestController
@RequestMapping("/api/v1/admin/edits")
public class EditAdminController {

    private final EditRuntime runtime;
    private final Entitlements entitlements;
    private final AuditLog audit;
    private final ObjectProvider<ConnectorManager> connectors;

    public EditAdminController(EditRuntime runtime, Entitlements entitlements, AuditLog audit, ObjectProvider<ConnectorManager> connectors) {
        this.runtime = runtime;
        this.entitlements = entitlements;
        this.audit = audit;
        this.connectors = connectors;
    }

    @GetMapping
    public Map<String, Object> status(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        EditSettings s = runtime.settings();
        EditCluster.Plan plan = runtime.cluster().plan();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", s.enabled());
        m.put("origin", s.origin());
        m.put("mode", plan.mode().name().toLowerCase(java.util.Locale.ROOT));
        m.put("store", plan.store());
        m.put("why", plan.why());
        m.put("primary", plan.primary());
        m.put("node", runtime.node());
        List<String> warnings = new ArrayList<>();
        if (plan.mode() == EditCluster.Mode.PRIMARY || plan.mode() == EditCluster.Mode.FOLLOWER) {
            warnings.add("edit primary " + plan.primary() + ": no failover; while it is down edits pause (PostgreSQL as the server's database gives failover)");
        }
        if (plan.mode() == EditCluster.Mode.REFUSED) {
            warnings.add(plan.why());
        }
        List<Map<String, Object>> lanes = new ArrayList<>();
        if (runtime.cluster().owns()) {
            Map<Integer, Long> mine = runtime.lanes() == null ? Map.of() : runtime.lanes().owned();
            for (EditLog.Lane l : runtime.log().lanes()) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("lane", l.lane());
                one.put("owner", l.owner());
                one.put("epoch", l.epoch());
                one.put("leased", l.leased());
                one.put("leaseMs", l.leaseUntil() == null ? null : l.leaseUntil() - l.now());
                one.put("depth", l.depth());
                one.put("mine", mine.containsKey(l.lane()));
                if (l.depth() > 0) {
                    List<EditLog.Row> next = runtime.log().unapplied(l.lane(), 1);
                    if (!next.isEmpty() && next.get(0).submittedAt() != null) {
                        long waitMs = Duration.between(next.get(0).submittedAt(), Instant.now()).toMillis();
                        one.put("oldestWaitMs", waitMs);
                        if (waitMs > s.laneWarn().toMillis()) {
                            warnings.add("lane " + l.lane() + ": its oldest change has waited " + waitMs + " ms (lane-warn " + s.laneWarn().toMillis() + " ms)");
                        }
                    }
                    if (!l.leased()) {
                        warnings.add("lane " + l.lane() + " has " + l.depth() + " changes waiting and no live owner: its lease is not being renewed");
                    }
                }
                lanes.add(one);
            }
        }
        m.put("lanes", lanes);
        List<Map<String, Object>> unconfirmed = new ArrayList<>();
        if (runtime.outbox() != null) {
            for (EditLog.Row r : runtime.outbox().unconfirmed(s.commandTimeout())) {
                unconfirmed.add(Map.of("id", r.changeId(), "connector", String.valueOf(r.change().connector()), "command", String.valueOf(r.change().command()),
                        "sentAt", String.valueOf(r.commandSentAt()), "attempts", r.attempts()));
            }
        }
        m.put("unconfirmed", unconfirmed);
        if (!unconfirmed.isEmpty()) {
            warnings.add(unconfirmed.size() + " source command" + (unconfirmed.size() == 1 ? " is" : "s are") + " sent and not confirmed past "
                    + s.commandTimeout().toSeconds() + " s");
        }
        ConnectorManager cm = connectors.getIfAvailable();
        m.put("connectors", cm == null || runtime.writes() == null ? List.of() : runtime.writes().describe(new ArrayList<>(cm.names())));
        m.put("editableKinds", runtime.service().fields().kinds());
        m.put("watchedViews", runtime.layer().watched());
        m.put("warnings", warnings);
        return m;
    }

    /** The open overlays: every active overlay, newest first (decision 16.1: the Open overlays report). */
    @GetMapping("/overlays")
    public Map<String, Object> overlays(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        List<Map<String, Object>> out = new ArrayList<>();
        for (EditRecords.Overlay o : runtime.records().open(1_000)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("change", o.changeId());
            m.put("kind", o.kind());
            m.put("entity", o.entity());
            m.put("path", o.path());
            boolean hidden = EditHandler.fieldsOf(o.path()).stream().anyMatch(f -> entitlements.masked(p, f));
            m.put("value", hidden ? com.ash.drishti.api.DataNode.MASK : o.value());
            m.put("sourceValue", hidden ? com.ash.drishti.api.DataNode.MASK : o.sourceValue());
            m.put("businessDate", o.businessDate().toString());
            m.put("by", o.by());
            m.put("appliedAt", String.valueOf(o.appliedAt()));
            out.add(m);
        }
        return Map.of("overlays", out);
    }

    /** Switches a connector's {@code writes:} on or off: {@code {on, reason}} (administrators, audited; off by default). */
    @PostMapping("/connectors/{name}")
    public Map<String, Object> connector(@PathVariable String name, @RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        if (runtime.writes() == null) {
            throw new DrishtiException(ErrorCode.EDIT_OFF, "write-back is not set up on this server");
        }
        boolean on = body.path("on").asBoolean(false);
        String reason = body.path("reason").asText("");
        if (reason.isBlank()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "say why the connector's writes are switched " + (on ? "on" : "off"));
        }
        runtime.writes().set(name, on, reason, p.user());
        audit.record(p.user(), on ? "connector-writes-on" : "connector-writes-off", name, reason);
        return Map.of("connector", name, "on", runtime.writes().on(name));
    }

    /** Withdraws an overlay (its change's values stop showing; the source's are shown again): {@code {reason}}. */
    @PostMapping("/overlays/{change}/withdraw")
    public Map<String, Object> withdraw(@PathVariable String change, @RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        String reason = body == null ? "" : body.path("reason").asText("");
        if (reason.isBlank()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "say why the overlay is withdrawn");
        }
        int n = runtime.withdraw(change);
        if (n == 0) {
            throw new DrishtiException(ErrorCode.EDIT_NOT_FOUND, "change " + change + " has no overlay shown");
        }
        audit.record(p.user(), "edit-overlay-withdrawn", change, reason);
        return Map.of("change", change, "withdrawn", n);
    }
}
