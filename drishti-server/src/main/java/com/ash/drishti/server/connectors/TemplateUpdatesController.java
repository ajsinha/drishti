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
package com.ash.drishti.server.connectors;

import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin &rarr; Connectors &rarr; Template updates: connectors that drifted from their pack's current template (additive,
 * conflicting and obsolete parts) and the buttons that fix them. Administrators only; every change is audited and kept in the
 * connector's history. CONNECTOR_FILES.md, "Template updates".
 */
@RestController
@RequestMapping("/api/v1/admin/connectors/template-updates")
public class TemplateUpdatesController {

    private final TemplateUpdates updates;
    private final Entitlements entitlements;

    public TemplateUpdatesController(TemplateUpdates updates, Entitlements entitlements) {
        this.updates = updates;
        this.entitlements = entitlements;
    }

    /** Every drifted connector, the mode and the recent automatic updates; {@code count} is what the Admin menu badge shows. */
    @GetMapping
    public Map<String, Object> list(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        List<TemplateUpdates.Drift> drifts = updates.drifts();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", updates.mode().name().toLowerCase(java.util.Locale.ROOT));
        out.put("count", drifts.size());
        out.put("additive", drifts.stream().filter(TemplateUpdates.Drift::additive).count());
        out.put("drifts", drifts.stream().map(TemplateUpdates.Drift::toMap).toList());
        out.put("notices", updates.notices().stream().map(n -> Map.of("at", n.at().toString(), "name", n.name(), "what", n.what())).toList());
        return out;
    }

    @PostMapping("/{name}/add-missing")
    public Map<String, Object> addMissing(@PathVariable String name, @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return done(updates.addMissing(name, unquote(ifMatch), p.user()));
    }

    @PostMapping("/{name}/regenerate")
    public Map<String, Object> regenerate(@PathVariable String name, @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return done(updates.regenerate(name, unquote(ifMatch), p.user()));
    }

    @PostMapping("/{name}/remove-obsolete")
    public Map<String, Object> removeObsolete(@PathVariable String name, @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return done(updates.removeObsolete(name, unquote(ifMatch), p.user()));
    }

    /** Use the pack's value: body {@code {where: settings|definition, key}}, one conflicting key set to the template's value. */
    @PostMapping("/{name}/use-template-value")
    public Map<String, Object> useTemplateValue(@PathVariable String name, @org.springframework.web.bind.annotation.RequestBody Map<String, Object> body,
            @RequestHeader(value = "If-Match", required = false) String ifMatch, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        String where = body == null || body.get("where") == null ? "settings" : String.valueOf(body.get("where"));
        String key = body == null || body.get("key") == null ? "" : String.valueOf(body.get("key"));
        return done(updates.useTemplateValue(name, where, key, unquote(ifMatch), p.user()));
    }

    @PostMapping("/fix-all")
    public Map<String, Object> fixAll(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return Map.of("fixed", updates.fixAllAdditive(p.user()));
    }

    private static Map<String, Object> done(ConnectorManager.Change c) {
        Map<String, Object> out = new LinkedHashMap<>(c.detail());
        out.put("changed", c.changed());
        return out;
    }

    private static String unquote(String etag) {
        if (etag == null) {
            return null;
        }
        String t = etag.trim();
        if (t.startsWith("W/")) {
            t = t.substring(2);
        }
        return t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"") ? t.substring(1, t.length() - 1) : t;
    }
}
