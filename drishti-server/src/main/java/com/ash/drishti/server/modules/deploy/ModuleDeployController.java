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
package com.ash.drishti.server.modules.deploy;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.kernel.api.security.Principal;
import com.ash.drishti.module.runtime.ModuleRuntime;
import com.ash.drishti.module.runtime.RouteTable;
import com.ash.drishti.server.net.NetCallFilter;
import com.ash.drishti.server.security.Entitlements;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Module deployment's API (KERNEL_AND_MODULES.md section 7.6): {@code /api/v1/admin/modules/…} for administrators (the store, plans,
 * four-eyes apply, the list), {@code /api/v1/modules/…} for every signed-in person (what a saved artefact needs, a console path's route),
 * and {@code /api/v1/net/modules/…} for the cluster's nodes only (stage, activate, abort, roll back, unload, record).
 */
@RestController
public class ModuleDeployController {

    /** A plan: the action, the module, the version (optional: the newest stored), why. */
    public record PlanIn(String action, String module, String version, String reason) {}

    /** A decision's comment (optional). */
    public record DecideIn(String comment) {}

    /** Items to mark: each an id and its stored text. */
    public record NeedsIn(List<Map<String, String>> items) {}

    private final ModuleDeployService deploy;
    private final ModuleNode node;
    private final Entitlements entitlements;

    public ModuleDeployController(ModuleDeployService deploy, ModuleNode node, Entitlements entitlements) {
        this.deploy = deploy;
        this.node = node;
        this.entitlements = entitlements;
    }

    // ---- administrators ------------------------------------------------------------------------------------------------------------

    @GetMapping("/api/v1/admin/modules/deployments")
    public Map<String, Object> overview(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        Map<String, Object> out = deploy.overview();
        out.put("deployments", deploy.deployments().reversed());
        return out;
    }

    @PostMapping(value = "/api/v1/admin/modules/store", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ModuleDeployService.Stored upload(@RequestBody byte[] bytes, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return deploy.upload(bytes, p.user());
    }

    @GetMapping("/api/v1/admin/modules/store")
    public List<ModuleDeployService.Stored> stored(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return deploy.stored();
    }

    @PostMapping("/api/v1/admin/modules/deployments/plans")
    public ModuleDeployService.Deployment plan(@RequestBody PlanIn in, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return deploy.plan(in.action(), in.module(), in.version(), in.reason(), p.user());
    }

    @GetMapping("/api/v1/admin/modules/deployments/{id}")
    public ModuleDeployService.Deployment get(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return deploy.find(id);
    }

    @PostMapping("/api/v1/admin/modules/deployments/{id}/apply")
    public ModuleDeployService.Deployment apply(@PathVariable String id, @RequestBody(required = false) DecideIn in,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return deploy.apply(id, in == null ? "" : in.comment(), p.user());
    }

    @PostMapping("/api/v1/admin/modules/deployments/{id}/reject")
    public ModuleDeployService.Deployment reject(@PathVariable String id, @RequestBody(required = false) DecideIn in,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return deploy.close(id, false, in == null ? "" : in.comment(), p.user());
    }

    @PostMapping("/api/v1/admin/modules/deployments/{id}/withdraw")
    public ModuleDeployService.Deployment withdraw(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return deploy.close(id, true, "", p.user());
    }

    // ---- every signed-in person ----------------------------------------------------------------------------------------------------

    /** What each saved artefact needs that is off or removed ({@code {id: [{module, title, state, words}]}}; an artefact needing nothing is left out). */
    @PostMapping("/api/v1/modules/needs")
    public Map<String, Object> needs(@RequestBody NeedsIn in) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (in != null && in.items() != null) {
            for (Map<String, String> item : in.items().subList(0, Math.min(500, in.items().size()))) {
                List<Map<String, Object>> n = node.needs(item.get("text"));
                if (!n.isEmpty()) {
                    out.put(String.valueOf(item.get("id")), n);
                }
            }
        }
        return Map.of("needs", out);
    }

    /**
     * A console path's answer from the route table (the console's notice pages): 200 active, 308 moved, 404 off, 410 removed (with who and
     * when for the notice), or 404 unknown.
     */
    @GetMapping("/api/v1/modules/route")
    public Map<String, Object> route(@RequestParam String path) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", path);
        RouteTable.Match m = deploy.routes().lookup(path).orElse(null);
        ModuleNode.Entry e = node.byPath(path).orElse(null);
        if (m != null) {
            out.put("status", m.status());
            out.put("module", m.route().module());
            out.put("route", m.route().id());
        } else if (e != null && e.state() != ModuleNode.State.ACTIVE) {
            out.put("status", e.state() == ModuleNode.State.REMOVED ? 410 : 404);
            out.put("module", e.id());
        } else {
            out.put("status", e == null ? 404 : 200);
            out.put("module", e == null ? null : e.id());
        }
        if (e != null) {
            out.put("title", e.title());
            out.put("state", e.state().name().toLowerCase(java.util.Locale.ROOT));
            out.put("by", e.by());
            out.put("at", e.at());
            out.put("deployment", e.deployment());
        }
        return out;
    }

    // ---- the console part (KERNEL_AND_MODULES.md sections 4.7 and 14.2.1): the console hosts what the kernel verified --------------

    /**
     * Every module of the registry as a console hosts it: id, version, state (active, off, removed), title, whether this server can hand
     * its console part over, and the names it owned (claims: lenses, words, kinds), with a fingerprint that changes with any of them.
     */
    @GetMapping("/public/modules/console")
    public Map<String, Object> consoleParts() {
        List<Map<String, Object>> out = new ArrayList<>();
        StringBuilder print = new StringBuilder();
        for (ModuleNode.Entry e : node.entries()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id());
            m.put("version", e.version());
            m.put("state", e.state().name().toLowerCase(java.util.Locale.ROOT));
            m.put("title", e.title());
            m.put("console", deploy.consoleFolder(e.id(), e.version()) != null);
            m.put("claims", e.claims());
            out.add(m);
            print.append(e.id()).append('@').append(e.version()).append(':').append(e.state()).append('|');
        }
        return Map.of("modules", out, "fingerprint", Integer.toHexString(print.toString().hashCode()));
    }

    /**
     * An active module version's console part as a zip ({@code module.yaml}, {@code console/}, {@code elements/}, {@code docs/}): the
     * console reads it declaratively and runs nothing of it. 410 when the module was removed, 404 when it is off, unknown or not that
     * version.
     */
    @GetMapping("/public/modules/console/{id}/{version}")
    public org.springframework.http.ResponseEntity<byte[]> consolePart(@PathVariable String id, @PathVariable String version) {
        ModuleNode.Entry e = node.entry(id).orElse(null);
        java.nio.file.Path dir = e == null ? null : deploy.consoleFolder(id, version);
        if (dir == null) {
            int status = e != null && e.state() == ModuleNode.State.REMOVED ? 410 : 404;
            return org.springframework.http.ResponseEntity.status(status).build();
        }
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(bytes)) {
            for (String part : List.of("module.yaml", "console", "elements", "docs")) {
                java.nio.file.Path p = dir.resolve(part);
                if (!java.nio.file.Files.exists(p)) {
                    continue;
                }
                try (java.util.stream.Stream<java.nio.file.Path> s = java.nio.file.Files.walk(p)) {
                    for (java.nio.file.Path f : s.filter(java.nio.file.Files::isRegularFile).sorted().toList()) {
                        zip.putNextEntry(new java.util.zip.ZipEntry(dir.relativize(f).toString().replace(java.io.File.separatorChar, '/')));
                        java.nio.file.Files.copy(f, zip);
                        zip.closeEntry();
                    }
                }
            }
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
        return org.springframework.http.ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .header("Cache-Control", "no-store").body(bytes.toByteArray());
    }

    /** Modules no longer active, with the names they owned (the console marks saved work with them). */
    @GetMapping("/api/v1/modules/gone")
    public Map<String, Object> gone() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ModuleNode.Entry e : deploy.gone()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id());
            m.put("title", e.title());
            m.put("state", e.state().name().toLowerCase(java.util.Locale.ROOT));
            m.put("prefix", e.prefix());
            m.put("claims", e.claims());
            m.put("routes", e.routes());
            out.add(m);
        }
        return Map.of("modules", out);
    }

    // ---- the cluster's nodes -------------------------------------------------------------------------------------------------------

    @PostMapping(value = "/api/v1/net/modules/stage/{deployment}/chunk/{index}", consumes = MediaType.ALL_VALUE)
    public Map<String, Object> chunk(@PathVariable String deployment, @PathVariable int index, @RequestBody byte[] bytes,
            @RequestAttribute(value = NetCallFilter.PEER, required = false) String peer) {
        requirePeer(peer);
        node.chunk(deployment, index, bytes);
        return Map.of("chunk", index, "bytes", bytes.length);
    }

    @PostMapping("/api/v1/net/modules/stage/{deployment}")
    public Map<String, Object> stage(@PathVariable String deployment, @RequestBody Map<String, String> in,
            @RequestAttribute(value = NetCallFilter.PEER, required = false) String peer) {
        requirePeer(peer);
        return Map.of("ready", true, "notes", node.stage(deployment, in.get("id"), in.get("version"), in.get("sha")));
    }

    @PostMapping("/api/v1/net/modules/activate/{deployment}")
    public Map<String, Object> activate(@PathVariable String deployment, @RequestBody Map<String, String> in,
            @RequestAttribute(value = NetCallFilter.PEER, required = false) String peer) {
        requirePeer(peer);
        return node.activate(deployment, in.get("id"), in.get("version"));
    }

    @PostMapping("/api/v1/net/modules/abort/{deployment}")
    public Map<String, Object> abort(@PathVariable String deployment, @RequestAttribute(value = NetCallFilter.PEER, required = false) String peer) {
        requirePeer(peer);
        node.abort(deployment);
        return Map.of("aborted", deployment);
    }

    @PostMapping("/api/v1/net/modules/rollback")
    public Map<String, Object> rollback(@RequestBody Map<String, String> in, @RequestAttribute(value = NetCallFilter.PEER, required = false) String peer) {
        requirePeer(peer);
        return node.rollback(in.get("id"), in.get("version"), in.get("previous"));
    }

    @PostMapping("/api/v1/net/modules/unload")
    public Map<String, Object> unload(@RequestBody Map<String, String> in, @RequestAttribute(value = NetCallFilter.PEER, required = false) String peer) {
        requirePeer(peer);
        return node.unload(in.get("id"), ModuleRuntime.Leave.valueOf(in.getOrDefault("leave", "DISABLE")));
    }

    @PostMapping("/api/v1/net/modules/registry")
    public Map<String, Object> record(@RequestBody ModuleNode.Entry in, @RequestAttribute(value = NetCallFilter.PEER, required = false) String peer) {
        requirePeer(peer);
        node.record(in);
        return Map.of("recorded", in.id());
    }

    @GetMapping("/api/v1/net/modules/status")
    public Map<String, Object> status(@RequestAttribute(value = NetCallFilter.PEER, required = false) String peer) {
        requirePeer(peer);
        return node.status();
    }

    private static void requirePeer(String peer) {
        if (peer == null) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "this endpoint is for the cluster's nodes only");
        }
    }
}
