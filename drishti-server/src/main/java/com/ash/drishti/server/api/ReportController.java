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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.PreferenceStore;
import com.ash.drishti.server.reports.ReportService;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
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

/** The caller's scheduled reports, and every report for administrators. */
@RestController
public class ReportController {

    private final ReportService reports;
    private final PreferenceStore store;
    private final Entitlements entitlements;

    public ReportController(ReportService reports, PreferenceStore store, Entitlements entitlements) {
        this.reports = reports;
        this.store = store;
        this.entitlements = entitlements;
    }

    @GetMapping("/api/v1/me/reports")
    public List<JsonNode> mine(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return reports.of(p.user());
    }

    @GetMapping("/api/v1/me/reports/{name}")
    public JsonNode get(@PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return reports.get(p.user(), name).orElseThrow(() -> new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no report '" + name + "'"));
    }

    @PutMapping("/api/v1/me/reports/{name}")
    public JsonNode save(@PathVariable String name, @RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return reports.save(p.user(), name, body);
    }

    @DeleteMapping("/api/v1/me/reports/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        if (!reports.delete(p.user(), name)) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no report '" + name + "'");
        }
    }

    /** Runs it now, as the caller, and answers with the run (status, rows, where it went). */
    @PostMapping("/api/v1/me/reports/{name}/run")
    public JsonNode run(@PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        get(name, p);
        return reports.run(p.user(), name, "by hand");
    }

    /** Every report on the server, with its owner (administrators). */
    @GetMapping("/api/v1/admin/reports")
    public List<JsonNode> all(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        List<JsonNode> out = new ArrayList<>();
        store.users().forEach(u -> out.addAll(reports.of(u)));
        return out;
    }

    /** An administrator removes someone's report. */
    @DeleteMapping("/api/v1/admin/reports/{owner}/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable String owner, @PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        if (!reports.delete(owner, name)) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no report '" + name + "' of " + owner);
        }
    }
}
