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
package com.ash.drishti.server.alerts.measure;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Alerts on measures, fields and board tiles: the caller's own (create, change, pause, look now, delete) and an administrators' overview of everyone's and of the
 * board tiles that notify. The rules for what an alert reads and how it avoids flapping are in {@link MeasureAlertService}.
 */
@RestController
public class MeasureAlertController {

    private final MeasureAlertService alerts;
    private final Entitlements entitlements;

    public MeasureAlertController(MeasureAlertService alerts, Entitlements entitlements) {
        this.alerts = alerts;
        this.entitlements = entitlements;
    }

    @GetMapping("/api/v1/me/measure-alerts")
    public List<JsonNode> mine(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return alerts.of(p.user());
    }

    @GetMapping("/api/v1/me/measure-alerts/{name}")
    public JsonNode get(@PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return alerts.get(p.user(), name).orElseThrow(() -> missing(name));
    }

    /**
     * Body {@code {title?, severity?: info|warn|critical, enabled?, source: {type: visual|dataset|field, ...}, condition: {type: threshold|change|nodata, ...},
     * trigger: {mode: schedule|after-load|live, ...}, hysteresis?, cooldown?, delivery?: {inbox, email, bridge}}}.
     */
    @PutMapping("/api/v1/me/measure-alerts/{name}")
    public JsonNode save(@PathVariable String name, @RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return alerts.save(p.user(), name, body);
    }

    @DeleteMapping("/api/v1/me/measure-alerts/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        if (!alerts.delete(p.user(), name)) {
            throw missing(name);
        }
    }

    /** Looks now, as the caller, and answers with the alert as it stands (state, history). */
    @PostMapping("/api/v1/me/measure-alerts/{name}/check")
    public JsonNode check(@PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return alerts.check(p.user(), name);
    }

    @PostMapping("/api/v1/me/measure-alerts/{name}/pause")
    public JsonNode pause(@PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return alerts.setEnabled(p.user(), name, false, p.user());
    }

    @PostMapping("/api/v1/me/measure-alerts/{name}/resume")
    public JsonNode resume(@PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return alerts.setEnabled(p.user(), name, true, p.user());
    }

    /** Body as for a save: what the alert would read for the caller right now, and whether it would fire. Saves and sends nothing. */
    @PostMapping("/api/v1/me/measure-alerts/preview")
    public Map<String, Object> preview(@RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return alerts.preview(p.user(), body);
    }

    /** Everyone's alerts (administrators): owner, state, history. */
    @GetMapping("/api/v1/admin/measure-alerts")
    public List<JsonNode> all(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return alerts.all();
    }

    /** The board tiles that notify (administrators): the board, the tile, the colour, how many people are alerted now, the history. */
    @GetMapping("/api/v1/admin/measure-alerts/boards")
    public List<JsonNode> boards(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return alerts.boardAlerts();
    }

    @PostMapping("/api/v1/admin/measure-alerts/{owner}/{name}/pause")
    public JsonNode adminPause(@PathVariable String owner, @PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return alerts.setEnabled(owner, name, false, p.user());
    }

    @PostMapping("/api/v1/admin/measure-alerts/{owner}/{name}/resume")
    public JsonNode adminResume(@PathVariable String owner, @PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return alerts.setEnabled(owner, name, true, p.user());
    }

    @DeleteMapping("/api/v1/admin/measure-alerts/{owner}/{name}")
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
}
