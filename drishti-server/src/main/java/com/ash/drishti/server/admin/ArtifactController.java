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
package com.ash.drishti.server.admin;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin, Artifacts: everything installed (packs, connector files, shipped BI files) in one list, what depends on each, and the safe
 * ways out of one: disable, uninstall to the archive, restore, delete for good. Administrators only; no personal API token may call
 * it (token-never). See docs/admin/ARTIFACTS.md.
 */
@RestController
@RequestMapping("/api/v1/admin/artifacts")
public class ArtifactController {

    /** {@code {"kind": "pack"|"connector"|"bi", "subtype": "dataset"|..., "name": "...", "cascade": false, "confirm": "name"}}. */
    public record Action(String kind, String subtype, String name, Boolean cascade, String confirm) {}

    /** {@code {"replace": false, "confirm": "name"}}. */
    public record Restore(Boolean replace, String confirm) {}

    private final ArtifactService service;
    private final ArtifactCatalog catalog;
    private final Entitlements entitlements;
    private final String uninstalledDir;

    public ArtifactController(ArtifactService service, ArtifactCatalog catalog, Entitlements entitlements) {
        this.service = service;
        this.catalog = catalog;
        this.entitlements = entitlements;
        this.uninstalledDir = service.archive().dir().toString();
    }

    /** A refusal carries the impact, so the page can show what depends on the artifact. */
    @ExceptionHandler(ArtifactRefused.class)
    ProblemDetail refused(ArtifactRefused e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        p.setTitle("artifact in use");
        p.setProperty("code", ErrorCode.ARTIFACT_IN_USE.code());
        p.setProperty("impact", shown(e.impact()));
        return p;
    }

    @GetMapping
    public Map<String, Object> list(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("artifacts", catalog.list());
        out.put("uninstalledDir", uninstalledDir);
        out.put("keepDays", service.keep().toDays());
        return out;
    }

    @GetMapping("/impact")
    public Map<String, Object> impact(@RequestParam String kind, @RequestParam(defaultValue = "") String subtype, @RequestParam String name,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return shown(service.impact(kind, subtype, name));
    }

    @PostMapping("/disable")
    public Map<String, Object> disable(@RequestBody Action a, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return service.disable(need(a.kind(), "kind"), a.subtype(), need(a.name(), "name"), Boolean.TRUE.equals(a.cascade()), p.user());
    }

    @PostMapping("/enable")
    public Map<String, Object> enable(@RequestBody Action a, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return service.enable(need(a.kind(), "kind"), a.subtype(), need(a.name(), "name"), p.user());
    }

    @PostMapping("/uninstall")
    public Map<String, Object> uninstall(@RequestBody Action a, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return service.uninstall(need(a.kind(), "kind"), a.subtype(), need(a.name(), "name"), Boolean.TRUE.equals(a.cascade()), a.confirm(), p.user());
    }

    @GetMapping("/uninstalled")
    public Map<String, Object> uninstalled(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("archives", service.archive().list());
        out.put("directory", uninstalledDir);
        out.put("keepDays", service.keep().toDays());
        return out;
    }

    @PostMapping("/uninstalled/{id}/restore")
    public Map<String, Object> restore(@PathVariable String id, @RequestBody(required = false) Restore r, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return service.restore(id, r != null && Boolean.TRUE.equals(r.replace()), r == null ? null : r.confirm(), p.user());
    }

    @DeleteMapping("/uninstalled/{id}")
    public Map<String, Object> purge(@PathVariable String id, @RequestParam(required = false) String confirm, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return service.purge(id, confirm, p.user());
    }

    /** The dry run of a BI upload: the file is the raw request body, its name {@code file}. Nothing is written. */
    @PostMapping(value = "/bi/check", consumes = {"application/octet-stream", "text/plain", "application/x-yaml", "text/yaml"})
    public Map<String, Object> checkBi(@RequestParam String file, @RequestBody byte[] body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return service.checkBi(file, body);
    }

    @PostMapping(value = "/bi", consumes = {"application/octet-stream", "text/plain", "application/x-yaml", "text/yaml"})
    public Map<String, Object> installBi(@RequestParam String file, @RequestParam(defaultValue = "false") boolean replace, @RequestParam(required = false) String confirm,
            @RequestBody byte[] body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return service.installBi(file, body, replace, confirm, p.user());
    }

    private static String need(String v, String what) {
        if (v == null || v.isBlank()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "send " + what);
        }
        return v;
    }

    /** The impact as the API shows it: counts for everything, the first names of each kind. */
    static Map<String, Object> shown(ArtifactImpact.Impact im) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", im.kind());
        out.put("subtype", im.subtype());
        out.put("name", im.name());
        out.put("total", im.total());
        out.put("blocked", im.blocked());
        out.put("summary", im.summary());
        out.put("templated", im.templated());
        out.put("templatePack", im.templatePack());
        out.put("notes", im.notes());
        List<Map<String, Object>> groups = new ArrayList<>();
        for (ArtifactImpact.Group g : im.groups()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("category", g.category());
            m.put("label", g.label());
            m.put("count", g.count());
            m.put("items", g.items().subList(0, Math.min(ArtifactImpact.NAMED, g.items().size())));
            m.put("more", Math.max(0, g.count() - ArtifactImpact.NAMED));
            groups.add(m);
        }
        out.put("groups", groups);
        return out;
    }
}
